package dev.cannoli.scorza.libretro

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay

private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
private const val EGL_CONTEXT_MAJOR_VERSION = 0x3098
private const val EGL_CONTEXT_MINOR_VERSION = 0x30FB
private const val EGL_OPENGL_ES2_BIT = 0x0004
private const val EGL_OPENGL_ES3_BIT = 0x0040

private fun hex(value: Int) = "0x${Integer.toHexString(value)}"

class LoggingEglConfigChooser(
    private val version: Int,
    private val log: (String) -> Unit,
    private val depthBits: Int = 0,
    private val stencilBits: Int = 0,
) : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val renderable = if (version >= 3) EGL_OPENGL_ES3_BIT else EGL_OPENGL_ES2_BIT
        val attribs = intArrayOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 0,
            EGL10.EGL_DEPTH_SIZE, depthBits,
            EGL10.EGL_STENCIL_SIZE, stencilBits,
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
        log("EGL chooseConfig: ${count[0]} candidates, picking first" +
            " (version=$version depth=$depthBits stencil=$stencilBits)")
        return configs[0]!!
    }
}

class LoggingEglContextFactory(
    private val version: Int,
    private val log: (String) -> Unit,
    private val minorVersion: Int = 0,
) : GLSurfaceView.EGLContextFactory {
    override fun createContext(egl: EGL10, display: EGLDisplay, config: EGLConfig): EGLContext {
        // A core requesting OPENGLES_VERSION names a minor version too, and 3.1/3.2 features
        // (compute shaders, SSBOs) are absent from the 3.0 context a major-only request gives.
        if (minorVersion > 0) {
            val attribs = intArrayOf(
                EGL_CONTEXT_MAJOR_VERSION, version,
                EGL_CONTEXT_MINOR_VERSION, minorVersion,
                EGL10.EGL_NONE,
            )
            val ctx = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, attribs)
            if (ctx != null && ctx !== EGL10.EGL_NO_CONTEXT) {
                log("EGL createContext: version=$version.$minorVersion")
                return ctx
            }
            // Falling back rather than throwing, so asking for more than the driver has
            // degrades to a working context instead of killing the session.
            log("EGL createContext(version=$version.$minorVersion) failed:" +
                " err=${hex(egl.eglGetError())}, falling back to $version.0")
        }
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
