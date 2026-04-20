package dev.cannoli.scorza.libretro

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay

private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
private const val EGL_OPENGL_ES2_BIT = 0x0004
private const val EGL_OPENGL_ES3_BIT = 0x0040

private fun hex(value: Int) = "0x${Integer.toHexString(value)}"

class LoggingEglConfigChooser(
    private val version: Int,
    private val log: (String) -> Unit,
) : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val renderable = if (version >= 3) EGL_OPENGL_ES3_BIT else EGL_OPENGL_ES2_BIT
        val attribs = intArrayOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 0,
            EGL10.EGL_DEPTH_SIZE, 0,
            EGL10.EGL_STENCIL_SIZE, 0,
            EGL10.EGL_RENDERABLE_TYPE, renderable,
            EGL10.EGL_NONE,
        )
        val count = IntArray(1)
        if (!egl.eglChooseConfig(display, attribs, null, 0, count)) {
            val err = egl.eglGetError()
            log("EGL chooseConfig(count) failed: err=${hex(err)} version=$version")
            throw RuntimeException("eglChooseConfig count failed: err=${hex(err)}")
        }
        if (count[0] == 0) {
            log("EGL chooseConfig returned 0 configs for version=$version")
            throw RuntimeException("eglChooseConfig returned 0 configs for version=$version")
        }
        val configs = arrayOfNulls<EGLConfig>(count[0])
        if (!egl.eglChooseConfig(display, attribs, configs, count[0], count)) {
            val err = egl.eglGetError()
            log("EGL chooseConfig(list) failed: err=${hex(err)} version=$version")
            throw RuntimeException("eglChooseConfig list failed: err=${hex(err)}")
        }
        log("EGL chooseConfig: ${count[0]} candidates, picking first (version=$version)")
        return configs[0]!!
    }
}

class LoggingEglContextFactory(
    private val version: Int,
    private val log: (String) -> Unit,
) : GLSurfaceView.EGLContextFactory {
    override fun createContext(egl: EGL10, display: EGLDisplay, config: EGLConfig): EGLContext {
        val attribs = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, version, EGL10.EGL_NONE)
        val ctx = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, attribs)
        if (ctx == null || ctx === EGL10.EGL_NO_CONTEXT) {
            val err = egl.eglGetError()
            log("EGL createContext(version=$version) failed: err=${hex(err)}")
            throw RuntimeException("eglCreateContext failed: err=${hex(err)}")
        }
        log("EGL createContext: version=$version")
        return ctx
    }

    override fun destroyContext(egl: EGL10, display: EGLDisplay, context: EGLContext) {
        if (!egl.eglDestroyContext(display, context)) {
            log("EGL destroyContext failed: err=${hex(egl.eglGetError())}")
        }
    }
}
