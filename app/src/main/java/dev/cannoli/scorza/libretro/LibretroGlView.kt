package dev.cannoli.scorza.libretro

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLSurfaceView
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.ArrayDeque

private const val EGL_CONTEXT_MINOR_VERSION = 0x30FB
private const val EGL_OPENGL_ES2_BIT = 0x0004
private const val EGL_OPENGL_ES3_BIT = 0x0040

/**
 * Replacement for GLSurfaceView that hands EGL ownership to us.
 *
 * GLSurfaceView re-binds its own context on its own schedule, which makes it impossible to hold a
 * core's shared context current between frames. Cores that render from a worker thread need that,
 * so the render loop here makes a context current only when the surface or context actually
 * changes, and otherwise leaves whatever the renderer bound in place.
 *
 * Deliberately mirrors the GLSurfaceView API surface it replaces (setRenderer, requestRender,
 * queueEvent, onPause, onResume) so LibretroRenderer needs no changes.
 */
class LibretroGlView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    var contextClientVersion = 3
    var contextMinorVersion = 0
    var depthBits = 0
    var stencilBits = 0
    var logger: ((String) -> Unit)? = null

    private var thread: RenderThread? = null

    init {
        holder.addCallback(this)
    }

    fun setRenderer(renderer: GLSurfaceView.Renderer) {
        check(thread == null) { "setRenderer called twice" }
        thread = RenderThread(renderer).also { it.start() }
    }

    fun requestRender() = thread?.requestRender() ?: Unit

    fun queueEvent(r: Runnable) = thread?.queueEvent(r) ?: Unit

    fun onPause() = thread?.setPaused(true) ?: Unit

    fun onResume() = thread?.setPaused(false) ?: Unit

    override fun surfaceCreated(holder: SurfaceHolder) {
        thread?.onSurfaceAvailable(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        thread?.onSurfaceResized(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        thread?.onSurfaceLost()
    }

    /** Invoked before EGL teardown so a core presenting on its own thread stops touching it. */
    var onBeforeShutdown: (() -> Unit)? = null

    override fun onDetachedFromWindow() {
        onBeforeShutdown?.invoke()
        thread?.shutdown()
        thread = null
        super.onDetachedFromWindow()
    }

    private inner class RenderThread(
        private val renderer: GLSurfaceView.Renderer,
    ) : Thread("LibretroGL") {

        private val lock = Object()
        private val events = ArrayDeque<Runnable>()
        private var holderRef: SurfaceHolder? = null
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var sizeChanged = false
        private var surfaceLost = false
        private var paused = false
        private var renderRequested = false
        private var quit = false

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var config: EGLConfig? = null
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var surfaceCreatedSent = false

        fun requestRender() = synchronized(lock) {
            renderRequested = true
            lock.notifyAll()
        }

        fun queueEvent(r: Runnable) = synchronized(lock) {
            events.add(r)
            lock.notifyAll()
        }

        fun setPaused(value: Boolean) = synchronized(lock) {
            paused = value
            lock.notifyAll()
        }

        fun onSurfaceAvailable(h: SurfaceHolder) = synchronized(lock) {
            holderRef = h
            surfaceLost = false
            lock.notifyAll()
        }

        fun onSurfaceResized(w: Int, h: Int) = synchronized(lock) {
            surfaceWidth = w
            surfaceHeight = h
            sizeChanged = true
            lock.notifyAll()
        }

        fun onSurfaceLost() = synchronized(lock) {
            surfaceLost = true
            lock.notifyAll()
            // The EGL surface must be gone before the Android surface is, or the driver is left
            // holding a dead window.
            while (eglSurface != EGL14.EGL_NO_SURFACE && !quit) {
                try { lock.wait(500L) } catch (_: InterruptedException) { return }
            }
        }

        fun shutdown() {
            synchronized(lock) {
                quit = true
                lock.notifyAll()
            }
            try { join(1000L) } catch (_: InterruptedException) {}
        }

        override fun run() {
            try {
                loop()
            } catch (t: Throwable) {
                logger?.invoke("GL thread died: ${t.javaClass.simpleName}: ${t.message}")
                android.util.Log.e("LibretroGlView", "GL thread died", t)
            } finally {
                releaseSurface()
                releaseContext()
                synchronized(lock) { lock.notifyAll() }
            }
        }

        private fun loop() {
            while (true) {
                var pending: Runnable? = null
                var doRender = false
                var doResize = false

                synchronized(lock) {
                    while (true) {
                        if (quit) return
                        if (surfaceLost && eglSurface != EGL14.EGL_NO_SURFACE) {
                            releaseSurface()
                            lock.notifyAll()
                        }
                        if (events.isNotEmpty()) { pending = events.poll(); break }
                        val ready = holderRef != null && !surfaceLost && !paused
                        if (ready && sizeChanged) { doResize = true; sizeChanged = false; break }
                        if (ready && renderRequested) { renderRequested = false; doRender = true; break }
                        try { lock.wait() } catch (_: InterruptedException) { return }
                    }
                }

                pending?.let { it.run(); continue }

                if (!ensureContextAndSurface()) continue

                if (doResize) {
                    renderer.onSurfaceChanged(null, surfaceWidth, surfaceHeight)
                }
                if (doRender) {
                    renderer.onDrawFrame(null)
                    // Swapped with whatever context the renderer left current. Both contexts in
                    // a shared setup hold this same surface, so the post is valid either way.
                    if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                        val err = EGL14.eglGetError()
                        logger?.invoke("eglSwapBuffers failed err=0x${Integer.toHexString(err)}")
                        if (err == EGL14.EGL_CONTEXT_LOST || err == EGL14.EGL_BAD_SURFACE) {
                            releaseSurface()
                            if (err == EGL14.EGL_CONTEXT_LOST) releaseContext()
                        }
                    }
                }
            }
        }

        private fun ensureContextAndSurface(): Boolean {
            val h = synchronized(lock) { holderRef } ?: return false
            if (display == EGL14.EGL_NO_DISPLAY && !initEgl()) return false
            if (eglContext == EGL14.EGL_NO_CONTEXT && !createContext()) return false

            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                val surf = EGL14.eglCreateWindowSurface(
                    display, config, h.surface, intArrayOf(EGL14.EGL_NONE), 0
                )
                if (surf == null || surf == EGL14.EGL_NO_SURFACE) {
                    logger?.invoke("eglCreateWindowSurface failed err=0x${
                        Integer.toHexString(EGL14.eglGetError())}")
                    return false
                }
                eglSurface = surf
                // The only place a context is forced current. Everything after this leaves
                // binding to the renderer, which is the whole point of owning the loop.
                if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) {
                    logger?.invoke("eglMakeCurrent failed err=0x${
                        Integer.toHexString(EGL14.eglGetError())}")
                    releaseSurface()
                    return false
                }
                if (!surfaceCreatedSent) {
                    surfaceCreatedSent = true
                    renderer.onSurfaceCreated(null, null)
                }
                synchronized(lock) { if (surfaceWidth > 0) sizeChanged = true }
            }
            return true
        }

        private fun initEgl(): Boolean {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return false
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                logger?.invoke("eglInitialize failed err=0x${
                    Integer.toHexString(EGL14.eglGetError())}")
                display = EGL14.EGL_NO_DISPLAY
                return false
            }
            val renderable =
                if (contextClientVersion >= 3) EGL_OPENGL_ES3_BIT else EGL_OPENGL_ES2_BIT
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 0,
                EGL14.EGL_DEPTH_SIZE, depthBits,
                EGL14.EGL_STENCIL_SIZE, stencilBits,
                EGL14.EGL_RENDERABLE_TYPE, renderable,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val found = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, found, 0) ||
                found[0] < 1
            ) {
                logger?.invoke("eglChooseConfig found none (version=$contextClientVersion" +
                    " depth=$depthBits stencil=$stencilBits)")
                return false
            }
            config = configs[0]
            logger?.invoke("EGL init: version=$contextClientVersion.$contextMinorVersion" +
                " depth=$depthBits stencil=$stencilBits")
            return true
        }

        private fun createContext(): Boolean {
            val attribs = if (contextMinorVersion > 0) {
                intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, contextClientVersion,
                    EGL_CONTEXT_MINOR_VERSION, contextMinorVersion,
                    EGL14.EGL_NONE,
                )
            } else {
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, contextClientVersion, EGL14.EGL_NONE)
            }
            var ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attribs, 0)
            if ((ctx == null || ctx == EGL14.EGL_NO_CONTEXT) && contextMinorVersion > 0) {
                logger?.invoke("eglCreateContext($contextClientVersion.$contextMinorVersion)" +
                    " failed err=0x${Integer.toHexString(EGL14.eglGetError())}, retrying major only")
                ctx = EGL14.eglCreateContext(
                    display, config, EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, contextClientVersion, EGL14.EGL_NONE), 0
                )
            }
            if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) {
                logger?.invoke("eglCreateContext failed err=0x${
                    Integer.toHexString(EGL14.eglGetError())}")
                return false
            }
            eglContext = ctx
            // A fresh context means the renderer's GL objects are gone with the old one.
            surfaceCreatedSent = false
            logger?.invoke("EGL context created")
            return true
        }

        private fun releaseSurface() {
            if (eglSurface == EGL14.EGL_NO_SURFACE) return
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(display, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }

        private fun releaseContext() {
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            surfaceCreatedSent = false
        }
    }
}
