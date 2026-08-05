package dev.cannoli.scorza.libretro

import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import dev.cannoli.scorza.libretro.shader.PresetParser
import dev.cannoli.scorza.libretro.shader.ShaderPipeline
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

enum class ScalingMode(val nativeCode: Int) {
    CORE_REPORTED(0),
    INTEGER(1),
    INTEGER_OVERSCALE(4),
    ASPECT_SCREEN(2),
    FULLSCREEN(3),
}
enum class Sharpness { SHARP, SOFT }
enum class ScreenEffect { NONE, SHADER }

private const val FPS_EMA_ALPHA = 0.05

class LibretroRenderer(private val runner: LibretroRunner) : GLSurfaceView.Renderer {

    @Volatile var paused = false
    @Volatile var fastForwardFrames = 0
    @Volatile var coreTargetFps = 60.0
    @Volatile var lockedToVsync = false
    @Volatile var scalingMode = ScalingMode.CORE_REPORTED
    @Volatile var coreAspectRatio = 0f
    @Volatile var debugHud = false

    @Volatile var sharpness = Sharpness.SHARP
        set(value) { field = value; sharpnessDirty = true }

    @Volatile var screenEffect = ScreenEffect.NONE
        set(value) { field = value; shaderDirty = true }

    @Volatile var overlayPath: String? = null
        set(value) { field = value; overlayDirty = true }

    @Volatile var shaderPresetPath: String? = null
        set(value) { field = value; pipelineDirty = true }

    @Volatile private var sharpnessDirty = false
    @Volatile private var shaderDirty = false
    @Volatile private var overlayDirty = false
    @Volatile private var pipelineDirty = false
    private var pipeline: ShaderPipeline? = null
    private var pipelineWarmedUp = false
    private var overlayTextureId = 0
    private var overlayLoaded = false

    @Volatile var backendName = "GLES"; private set
    @Volatile var viewportWidth = 0; private set
    @Volatile var viewportHeight = 0; private set
    @Volatile var portraitMarginPx: Int = 0
    @Volatile var screenGeometryWidth: Int = 100
    @Volatile var screenGeometryHeight: Int = 100
    @Volatile var screenGeometryX: Int = 0
    @Volatile var screenGeometryY: Int = 0

    private val fpsMeter = FpsMeter(emaAlpha = FPS_EMA_ALPHA)
    val fps: Float get() = fpsMeter.fps
    val frameTimeMs: Float get() = fpsMeter.frameTimeMs

    private var lastDrawNanos = 0L
    private var frameAccumulatorNs = 0L
    private val lockedPacer = LockedFramePacer()

    private val shaderParamOverrides = ConcurrentHashMap<String, Float>()

    fun setShaderParameter(id: String, value: Float) {
        shaderParamOverrides[id] = value
        pipeline?.parameters?.set(id, value)
    }

    fun clearShaderParamOverrides() {
        shaderParamOverrides.clear()
    }

    @Volatile var onFrameRendered: (() -> Unit)? = null

    @Volatile var logger: ((String) -> Unit)? = null
    private var loggedFrameW = -1
    private var loggedFrameH = -1
    private var loggedAspect = Float.NaN
    private var loggedRotation = -1
    private var loggedSurfaceW = -1
    private var loggedSurfaceH = -1
    private var loggedFirstFrame = false

    private var textureId = 0
    private var hwRender = false
    private var hwFbo = 0
    private var hwDepthRb = 0
    private var hwMaxWidth = 0
    private var hwMaxHeight = 0
    private var hwBottomLeftOrigin = true
    private var hwContextLive = false
    @Volatile var hwSetupFailed = false; private set
    // Set only when the core asked for a shared context. Cores that did not keep the
    // single-context path they already work on.
    private var hwSharedCtx: android.opengl.EGLContext? = null
    private var hwMainCtx: android.opengl.EGLContext? = null
    // Both contexts share the window surface, as RetroArch does. The core's context is the one
    // current at swap time, so it has to hold the surface being swapped.
    private var hwEglDisplay: android.opengl.EGLDisplay? = null
    private var hwEglSurface: android.opengl.EGLSurface? = null
    private var hwCoreSurface: android.opengl.EGLSurface? = null
    private var hwReadFbo = 0
    private var hwTexCoordW = -1
    private var hwTexCoordH = -1
    private var programNone = 0
    private var frameBuffer: ByteBuffer? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastPixelFormat = 0
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texCoordBuffer: FloatBuffer
    private lateinit var fboTexCoordBuffer: FloatBuffer
    private var lastRotation = -1
    private val rotatedTexCoords = arrayOf(
        floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f),   // 0°
        floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f),   // 90° CCW
        floatArrayOf(1f, 0f, 0f, 0f, 1f, 1f, 0f, 1f),   // 180°
        floatArrayOf(1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f)    // 270° CCW
    )

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = guard("onSurfaceCreated") {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        fpsMeter.reset()
        loggedFirstFrame = false
        val glVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
        val parsedVersion = parseGlesVersion(glVersion)
        backendName = "GLES $parsedVersion"
        val actualEs3 = parsedVersion.startsWith("3") || parsedVersion.startsWith("4")
        if (actualEs3 != ShaderPipeline.es3Supported) {
            ShaderPipeline.es3Supported = actualEs3
            logger?.invoke("es3Supported corrected to $actualEs3 from GL_VERSION (was ${!actualEs3})")
        }
        logger?.invoke(
            "GL surface created: vendor=${GLES20.glGetString(GLES20.GL_VENDOR)}" +
                " renderer=${GLES20.glGetString(GLES20.GL_RENDERER)}" +
                " version=$glVersion" +
                " glsl=${GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION)}"
        )

        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices).also { it.position(0) }

        val texCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(texCoords).also { it.position(0) }

        val fboTexCoords = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        fboTexCoordBuffer = ByteBuffer.allocateDirect(fboTexCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(fboTexCoords).also { it.position(0) }

        programNone = createProgram(Shaders.vertex, Shaders.passthrough)

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        textureId = texIds[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val black = ByteBuffer.allocateDirect(4).put(byteArrayOf(0, 0, 0, -1)).also { it.position(0) }
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, black)

        val ovlIds = IntArray(1)
        GLES20.glGenTextures(1, ovlIds, 0)
        overlayTextureId = ovlIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        overlayLoaded = false

        pipeline?.destroy()
        pipeline = null
        ShaderPipeline.invalidateSharedProgram()

        lastWidth = 0
        lastHeight = 0
        lastPixelFormat = 0
        lastRotation = -1
        shaderDirty = true
        sharpnessDirty = true
        overlayDirty = true
        pipelineDirty = true

        hwContextLive = false
        // A new GL context means the old shared one belongs to a dead share group.
        hwSharedCtx = null
        hwMainCtx = null
        hwEglDisplay = null
        hwEglSurface = null
        hwCoreSurface = null
        hwReadFbo = 0
        hwFbo = 0
        hwDepthRb = 0
        hwRender = runner.isHwRender()
        if (hwRender) setUpHwFramebuffer()
    }

    // The core draws into an FBO we own. Handing its colour attachment back as textureId lets
    // the shader pipeline, overlay and viewport math downstream stay identical to the SW path.
    private fun setUpHwFramebuffer() {
        val info = runner.getHwRenderInfo()
        val (maxW, maxH) = runner.getMaxGeometry()
        hwMaxWidth = maxW.coerceAtLeast(1)
        hwMaxHeight = maxH.coerceAtLeast(1)
        hwBottomLeftOrigin = info.bottomLeftOrigin
        hwTexCoordW = -1
        hwTexCoordH = -1
        logger?.invoke(
            "hw render: ${hwMaxWidth}x${hwMaxHeight} ctx=${info.contextType}" +
                " v${info.versionMajor}.${info.versionMinor} depth=${info.depth}" +
                " stencil=${info.stencil} bottomLeft=${info.bottomLeftOrigin}"
        )

        // The 1x1 placeholder from onSurfaceCreated is about to be orphaned by the reassignment.
        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        textureId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            hwMaxWidth, hwMaxHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        hwFbo = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, hwFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, textureId, 0
        )

        if (info.depth) {
            val rbIds = IntArray(1)
            GLES20.glGenRenderbuffers(1, rbIds, 0)
            hwDepthRb = rbIds[0]
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, hwDepthRb)
            val packed = info.stencil && ShaderPipeline.es3Supported
            val format = when {
                packed -> GLES30.GL_DEPTH24_STENCIL8
                else -> GLES20.GL_DEPTH_COMPONENT16
            }
            GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, format, hwMaxWidth, hwMaxHeight)
            val attachment =
                if (packed) GLES30.GL_DEPTH_STENCIL_ATTACHMENT else GLES20.GL_DEPTH_ATTACHMENT
            GLES20.glFramebufferRenderbuffer(
                GLES20.GL_FRAMEBUFFER, attachment, GLES20.GL_RENDERBUFFER, hwDepthRb
            )
            if (info.stencil && !packed) {
                logger?.invoke("hw render: stencil requested but unavailable without ES3")
            }
        }

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            // There is no usable fallback: a HW core never fills the software frame buffer, so
            // this ends as a permanently blank screen. Say so rather than fail quietly.
            logger?.invoke("hw render: FBO incomplete (status=0x${Integer.toHexString(status)})" +
                " - video will be blank, the core cannot render without it")
            hwRender = false
            hwSetupFailed = true
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return
        }
        // glTexImage2D with no data leaves contents undefined, which reads as static and is
        // indistinguishable from the core not drawing. Start from a known black.
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        if (runner.hwWantsSharedContext() && createSharedContext()) {
            // The core renders in its own context, so it needs an FBO created there. The
            // colour texture and depth buffer are shared, only the container is not.
            hwReadFbo = hwFbo
            var coreFbo = 0
            if (bindCoreContext()) {
                coreFbo = buildFboForSharedTexture()
                if (coreFbo == 0) bindMainContext()
            }
            if (coreFbo == 0) {
                logger?.invoke("hw render: shared-context FBO failed, falling back to single context")
                destroySharedContext()
            } else {
                hwFbo = coreFbo
                logger?.invoke("hw render: shared context ready (core fbo=$coreFbo, read fbo=$hwReadFbo)")
            }
        }

        runner.setHwFramebuffer(hwFbo)
        if (hwSharedCtx != null) {
            // context_reset creates the core's GL objects, so it runs in the core's context.
            bindCoreContext()
            runner.hwContextReset()
            // Then hand the context back: the core presents from its own thread, and that
            // thread claims it on the next video_refresh. It cannot if we are still holding it.
            bindMainContext()
            runner.setCoreEglContext(
                handleOf(hwEglDisplay), handleOf(hwCoreSurface), handleOf(hwSharedCtx)
            )
        } else {
            runner.hwContextReset()
        }
        hwContextLive = true
    }

    // EGL14 objects wrap a native handle that the bridge needs as a plain pointer.
    private fun handleOf(o: Any?): Long = when (o) {
        is android.opengl.EGLDisplay -> o.nativeHandle
        is android.opengl.EGLSurface -> o.nativeHandle
        is android.opengl.EGLContext -> o.nativeHandle
        else -> 0L
    }

    private fun createSharedContext(): Boolean {
        val display = EGL14.eglGetCurrentDisplay()
        val mainCtx = EGL14.eglGetCurrentContext()
        if (display == EGL14.EGL_NO_DISPLAY || mainCtx == EGL14.EGL_NO_CONTEXT) return false

        val cfgId = IntArray(1)
        if (!EGL14.eglQueryContext(display, mainCtx, EGL14.EGL_CONFIG_ID, cfgId, 0)) return false
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val found = IntArray(1)
        val cfgAttribs = intArrayOf(EGL14.EGL_CONFIG_ID, cfgId[0], EGL14.EGL_NONE)
        if (!EGL14.eglChooseConfig(display, cfgAttribs, 0, configs, 0, 1, found, 0) || found[0] < 1) {
            return false
        }
        val version = if (ShaderPipeline.es3Supported) 3 else 2
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, version, EGL14.EGL_NONE)
        val ctx = EGL14.eglCreateContext(display, configs[0], mainCtx, ctxAttribs, 0)
        if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) {
            logger?.invoke("hw render: eglCreateContext(shared) failed err=0x${
                Integer.toHexString(EGL14.eglGetError())}")
            return false
        }
        hwSharedCtx = ctx
        hwMainCtx = mainCtx
        hwEglDisplay = display
        hwEglSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)

        // The core's context lives on the core's presenting thread, so it cannot share our
        // window surface: EGL forbids one surface being current to two contexts at once.
        // RetroArch avoids this only because it never has two threads holding contexts.
        val pbAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        val pbuffer = EGL14.eglCreatePbufferSurface(display, configs[0], pbAttribs, 0)
        if (pbuffer == null || pbuffer == EGL14.EGL_NO_SURFACE) {
            logger?.invoke("hw render: pbuffer for core context failed err=0x${
                Integer.toHexString(EGL14.eglGetError())}")
            EGL14.eglDestroyContext(display, ctx)
            hwSharedCtx = null
            return false
        }
        hwCoreSurface = pbuffer
        return true
    }

    private fun destroySharedContext() {
        val ctx = hwSharedCtx ?: return
        val coreSurface = hwCoreSurface
        // Our context has to be the live one before the core's is destroyed.
        bindMainContext()
        hwSharedCtx = null
        hwCoreSurface = null
        val display = hwEglDisplay
        hwMainCtx = null
        hwEglDisplay = null
        hwEglSurface = null
        runner.setCoreEglContext(0L, 0L, 0L)
        if (display != null) {
            if (coreSurface != null) EGL14.eglDestroySurface(display, coreSurface)
            EGL14.eglDestroyContext(display, ctx)
        }
    }

    private fun bindContext(ctx: android.opengl.EGLContext?, label: String): Boolean {
        if (ctx == null) return false
        val display = hwEglDisplay ?: return false
        // The core's context is paired with its own off-screen surface; ours keeps the window.
        val surface = (if (ctx == hwSharedCtx) hwCoreSurface else hwEglSurface) ?: return false
        if (EGL14.eglMakeCurrent(display, surface, surface, ctx)) return true
        logger?.invoke("hw render: makeCurrent($label) failed err=0x${
            Integer.toHexString(EGL14.eglGetError())}")
        return false
    }

    private fun bindMainContext() = bindContext(hwMainCtx, "main")

    private fun bindCoreContext() = bindContext(hwSharedCtx, "core")

    // Runs block with our own context current regardless of which one is live, so queued GL
    // work outside the draw phase cannot read through an FBO belonging to the other context.
    private inline fun <T> withMainContext(block: () -> T): T? {
        if (hwSharedCtx == null) return block()
        val restore = EGL14.eglGetCurrentContext() == hwSharedCtx
        if (restore && !bindMainContext()) return null
        try {
            return block()
        } finally {
            if (restore) bindCoreContext()
        }
    }

    private fun buildFboForSharedTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, textureId, 0
        )
        if (hwDepthRb != 0) {
            val info = runner.getHwRenderInfo()
            val packed = info.stencil && ShaderPipeline.es3Supported
            val attachment =
                if (packed) GLES30.GL_DEPTH_STENCIL_ATTACHMENT else GLES20.GL_DEPTH_ATTACHMENT
            GLES20.glFramebufferRenderbuffer(
                GLES20.GL_FRAMEBUFFER, attachment, GLES20.GL_RENDERBUFFER, hwDepthRb
            )
        }
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            logger?.invoke("hw render: shared FBO incomplete (status=0x${
                Integer.toHexString(status)})")
            return 0
        }
        return ids[0]
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = guard("onSurfaceChanged") {
        surfaceWidth = width
        surfaceHeight = height
        if (width != loggedSurfaceW || height != loggedSurfaceH) {
            logger?.invoke("geom: surface ${loggedSurfaceW}x${loggedSurfaceH} -> ${width}x${height}")
            loggedSurfaceW = width
            loggedSurfaceH = height
        }
    }

    // The core's context stays current between frames, matching RetroArch: a core may touch GL
    // from a worker thread at any point, not only inside retro_run. We drop to our own context
    // for the draw and hand it straight back.
    override fun onDrawFrame(gl: GL10?) = guard("onDrawFrame") {
        if (!loggedFirstFrame) {
            loggedFirstFrame = true
            logger?.invoke("GL first frame: surface=${surfaceWidth}x${surfaceHeight}")
        }
        // Our context stays on this thread throughout. The core's belongs to whichever thread
        // presents, which claims it in video_refresh; taking it back here would strand that
        // thread without a context again.
        runEmulationPhase()
        drawPhase()
    }

    private fun runEmulationPhase() {
        if (!paused && !(hwRender && !hwContextLive)) {
            val now = System.nanoTime()
            val delta = if (lastDrawNanos == 0L) 0L else now - lastDrawNanos
            lastDrawNanos = now

            val extra = fastForwardFrames
            if (extra > 0) {
                runEmulatedFrame()
                repeat(extra - 1) { runEmulatedFrame() }
            } else if (lockedToVsync) {
                val frameDurationNs = (1_000_000_000.0 / coreTargetFps).toLong()
                if (lockedPacer.shouldRunFrame(delta, frameDurationNs)) runEmulatedFrame()
            } else {
                val frameDurationNs = (1_000_000_000.0 / coreTargetFps).toLong()
                frameAccumulatorNs += delta
                if (frameAccumulatorNs > frameDurationNs * 2) frameAccumulatorNs = frameDurationNs * 2
                while (frameAccumulatorNs >= frameDurationNs) {
                    runEmulatedFrame()
                    frameAccumulatorNs -= frameDurationNs
                }
            }
        }
    }

    private fun drawPhase() {
        val w = runner.getFrameWidth()
        val h = runner.getFrameHeight()
        if (w != loggedFrameW || h != loggedFrameH) {
            logger?.invoke("geom: core frame ${loggedFrameW}x${loggedFrameH} -> ${w}x${h}")
            loggedFrameW = w
            loggedFrameH = h
        }
        if (coreAspectRatio != loggedAspect) {
            logger?.invoke("geom: coreAspectRatio $loggedAspect -> $coreAspectRatio")
            loggedAspect = coreAspectRatio
        }
        val rot = runner.getRotation()
        if (rot != loggedRotation) {
            logger?.invoke("geom: rotation $loggedRotation -> $rot")
            loggedRotation = rot
        }
        if (w == 0 || h == 0) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            onFrameRendered?.invoke()
            return
        }

        if (hwRender) {
            runner.consumeFrame()
        } else if (runner.hasNewFrame()) {
            val pixelFormat = runner.getPixelFormat()
            val bpp = if (pixelFormat == LibretroRunner.PIXEL_FORMAT_XRGB8888) 4 else 2
            val needed = w * h * bpp
            val textureChanged = lastWidth != w || lastHeight != h || lastPixelFormat != pixelFormat

            if (frameBuffer == null || frameBuffer!!.capacity() < needed) {
                frameBuffer = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
            }

            frameBuffer!!.clear()
            runner.copyFrame(frameBuffer!!)
            frameBuffer!!.position(0)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            if (pixelFormat == LibretroRunner.PIXEL_FORMAT_XRGB8888) {
                if (textureChanged) {
                    GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                        w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, frameBuffer
                    )
                } else {
                    GLES20.glTexSubImage2D(
                        GLES20.GL_TEXTURE_2D, 0, 0, 0,
                        w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, frameBuffer
                    )
                }
            } else {
                if (textureChanged) {
                    GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB,
                        w, h, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5, frameBuffer
                    )
                } else {
                    GLES20.glTexSubImage2D(
                        GLES20.GL_TEXTURE_2D, 0, 0, 0,
                        w, h, GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5, frameBuffer
                    )
                }
            }
            lastWidth = w
            lastHeight = h
            lastPixelFormat = pixelFormat
        }

        if (sharpnessDirty) {
            sharpnessDirty = false
            val filter = when (sharpness) {
                Sharpness.SHARP -> GLES20.GL_NEAREST
                Sharpness.SOFT -> GLES20.GL_LINEAR
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        }

        if (shaderDirty) {
            shaderDirty = false
            if (screenEffect != ScreenEffect.NONE) pipelineDirty = true
        }

        if (pipelineDirty) {
            pipelineDirty = false
            loadPipeline()
            pipelineWarmedUp = false
        }

        if (overlayDirty) {
            overlayDirty = false
            loadOverlayTexture()
        }

        if (surfaceWidth == 0 || surfaceHeight == 0) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            onFrameRendered?.invoke()
            return
        }

        val rotation = runner.getRotation()
        if (hwRender) {
            updateHwTexCoords(w, h)
        } else if (rotation != lastRotation) {
            lastRotation = rotation
            val coords = rotatedTexCoords[rotation and 3]
            texCoordBuffer.clear()
            texCoordBuffer.put(coords)
            texCoordBuffer.position(0)
        }
        val vp = computeViewport(
            surfaceWidth = surfaceWidth,
            surfaceHeight = surfaceHeight,
            frameWidth = w,
            frameHeight = h,
            coreAspectRatio = coreAspectRatio,
            rotation = rotation,
            scalingMode = scalingMode,
            portraitMarginPx = portraitMarginPx,
            geometryWidthPct = screenGeometryWidth,
            geometryHeightPct = screenGeometryHeight,
            geometryXPct = screenGeometryX,
            geometryYPct = screenGeometryY,
        )
        val vpX = vp.x
        val vpY = vp.y
        val vpW = vp.w
        val vpH = vp.h
        viewportWidth = vpW
        viewportHeight = vpH

        if (screenEffect != ScreenEffect.NONE && pipeline != null && pipelineWarmedUp) {
            pipeline!!.render(textureId, w, h, vpX, vpY, vpW, vpH,
                texCoordBuffer, fboTexCoordBuffer, vertexBuffer, paused = paused)
        } else {
            if (pipeline != null && screenEffect != ScreenEffect.NONE) {
                pipeline!!.prewarmFbos(w, h, vpW, vpH)
                pipelineWarmedUp = true
            }
            drawSimple(w, h, vpX, vpY, vpW, vpH)
        }
        if (overlayLoaded) drawOverlay()

        onFrameRendered?.invoke()
    }

    private inline fun guard(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger?.invoke("$name threw: ${t.javaClass.simpleName}: ${t.message}")
            Log.e("LibretroRenderer", "$name threw", t)
            throw t
        }
    }

    private fun runEmulatedFrame() {
        if (hwSharedCtx != null && runner.corePresentsOffThread()) {
            // The core owns its context on its own thread; touching GL state here would fight
            // it, and our context stays current for the draw that follows.
            runner.run()
        } else if (hwSharedCtx != null) {
            // Presents on this thread, so drive it exactly as the non-shared path does.
            bindCoreContext()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, hwFbo)
            GLES20.glViewport(0, 0, hwMaxWidth, hwMaxHeight)
            runner.run()
            GLES20.glFinish()
            bindMainContext()
        } else {
            if (hwRender) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, hwFbo)
                GLES20.glViewport(0, 0, hwMaxWidth, hwMaxHeight)
            }
            runner.run()
            if (hwRender) resetGlStateAfterCore()
        }
        fpsMeter.tick(System.nanoTime())
    }

    // Under HW render the frame only ever exists on the GPU, so a save state thumbnail has to
    // read it back off the FBO. Must run on the GL thread. glReadPixels returns rows bottom-up.
    fun readHwFrame(w: Int, h: Int): android.graphics.Bitmap? = withMainContext {
        readHwFrameLocked(w, h)
    }

    // GL_RGBA byte order matches Bitmap.ARGB_8888's in-memory layout, so the buffer goes
    // straight in. The old per-pixel loop was ~338k bounds-checked reads at native res and
    // scaled with internal resolution, which now that upscaling is selectable would blow the
    // caller's timeout.
    private fun readHwFrameLocked(w: Int, h: Int): android.graphics.Bitmap? {
        if (!hwRender || w <= 0 || h <= 0) return null
        // A core can report a frame larger than the FBO after an internal-resolution change,
        // since we do not resize on SET_SYSTEM_AV_INFO. Read what is actually there rather
        // than giving up and letting the caller fall back to a garbage software frame.
        @Suppress("NAME_SHADOWING") val w = w.coerceAtMost(hwMaxWidth)
        @Suppress("NAME_SHADOWING") val h = h.coerceAtMost(hwMaxHeight)
        // Read through our own FBO; hwFbo belongs to the core's context on the shared path.
        val readFbo = if (hwSharedCtx != null) hwReadFbo else hwFbo
        if (readFbo == 0) return null
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readFbo)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return null
        buf.position(0)
        val raw = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buf)
        // Cores routinely leave alpha at 0. copyPixelsFromBuffer takes the bytes verbatim, so
        // without this the PNG is fully transparent and reads as a blank white thumbnail.
        raw.setHasAlpha(false)
        // glReadPixels returns rows bottom-up; the flip runs in native code.
        val matrix = android.graphics.Matrix().apply { setScale(1f, -1f) }
        val flipped = android.graphics.Bitmap.createBitmap(raw, 0, 0, w, h, matrix, false)
        if (flipped !== raw) raw.recycle()
        return flipped
    }

    // A core leaves GL state arbitrary. Everything the blit path assumes has to be put back,
    // or the frame lands garbled rather than missing.
    private fun resetGlStateAfterCore() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (ShaderPipeline.es3Supported) GLES30.glBindVertexArray(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_STENCIL_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glColorMask(true, true, true, true)
        GLES20.glDepthMask(true)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUseProgram(0)
    }

    // The frame occupies a w*h corner of the max-sized FBO, and a GL core's origin is already
    // bottom-left, so neither the 0..1 range nor the SW path's Y-flip applies.
    private fun updateHwTexCoords(w: Int, h: Int) {
        if (w == hwTexCoordW && h == hwTexCoordH) return
        hwTexCoordW = w
        hwTexCoordH = h
        val u = w.toFloat() / hwMaxWidth
        val v = h.toFloat() / hwMaxHeight
        val coords = if (hwBottomLeftOrigin) {
            floatArrayOf(0f, 0f, u, 0f, 0f, v, u, v)
        } else {
            floatArrayOf(0f, v, u, v, 0f, 0f, u, 0f)
        }
        texCoordBuffer.clear()
        texCoordBuffer.put(coords)
        texCoordBuffer.position(0)
    }

    private fun drawSimple(w: Int, h: Int, vpX: Int, vpY: Int, vpW: Int, vpH: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(vpX, vpY, vpW, vpH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(programNone)
        bindQuadAttribs(programNone)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programNone, "uTexture"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        unbindQuadAttribs(programNone)
    }

    private fun loadPipeline() {
        if (pipeline != null) GLES20.glFinish()
        pipeline?.destroy()
        pipeline = null
        val path = shaderPresetPath
        if (path.isNullOrEmpty() || screenEffect == ScreenEffect.NONE) return
        logger?.invoke("loadPipeline: $path")
        val file = File(path)
        val preset = PresetParser.parse(file)
        if (preset == null) {
            val msg = "shader parse failed (file exists=${file.exists()}): $path"
            Log.w("LibretroRenderer", msg)
            logger?.invoke(msg)
            return
        }
        pipeline = ShaderPipeline.compile(preset)
        if (pipeline == null) {
            val msg = "shader compile failed: $path"
            Log.w("LibretroRenderer", msg)
            logger?.invoke(msg)
            return
        }
        logger?.invoke("shader loaded: ${file.name} (${preset.passes.size} pass)")
        for ((key, value) in shaderParamOverrides) {
            pipeline!!.parameters[key] = value
        }
    }

    private fun loadOverlayTexture() {
        val path = overlayPath
        if (path.isNullOrEmpty()) { overlayLoaded = false; return }
        val bitmap = try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
        if (bitmap == null) { overlayLoaded = false; return }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        overlayLoaded = true
    }

    private fun drawOverlay() {
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(programNone)
        bindQuadAttribs(programNone)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programNone, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        unbindQuadAttribs(programNone)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun bindQuadAttribs(program: Int) {
        val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        val texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
    }

    private fun unbindQuadAttribs(program: Int) {
        GLES20.glDisableVertexAttribArray(GLES20.glGetAttribLocation(program, "aPosition"))
        GLES20.glDisableVertexAttribArray(GLES20.glGetAttribLocation(program, "aTexCoord"))
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) { GLES20.glDeleteShader(vertexShader); return 0 }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e("LibretroRenderer", "Program link error: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun parseGlesVersion(versionString: String): String {
        // GL_VERSION format: "OpenGL ES M.m ..." or "OpenGL ES-CM M.m ..."
        val match = Regex("""OpenGL ES(?:-\w+)? (\d+\.\d+)""").find(versionString)
        return match?.groupValues?.get(1) ?: versionString.ifEmpty { "?" }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val typeName = if (type == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"
            Log.e("LibretroRenderer", "Shader compile error ($typeName): ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
