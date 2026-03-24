package dev.cannoli.scorza.libretro

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.cannoli.scorza.libretro.shader.PresetParser
import dev.cannoli.scorza.libretro.shader.SlangTranspiler
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VulkanBackend(private val runner: LibretroRunner) : GraphicsBackend, SurfaceHolder.Callback {

    companion object {
        init {
            System.loadLibrary("vulkan_renderer")
        }

        fun isAvailable(): Boolean = try {
            System.loadLibrary("vulkan_renderer")
            true
        } catch (_: UnsatisfiedLinkError) { false }
    }

    var debugPath: String? = null
    private var surfaceViewRef: SurfaceView? = null

    @Volatile override var paused = false
    @Volatile override var fastForwardFrames = 0
    @Volatile override var scalingMode = ScalingMode.CORE_REPORTED
        set(value) { field = value; pushScaling() }
    @Volatile override var coreAspectRatio = 0f
        set(value) { field = value; pushScaling() }
    @Volatile override var debugHud = false
    @Volatile override var sharpness = Sharpness.SHARP
        set(value) { field = value; pushScaling() }
    @Volatile override var screenEffect = ScreenEffect.NONE
    @Volatile override var overlayPath: String? = null
        set(value) { field = value; loadOverlayImage(value) }
    @Volatile override var shaderPresetPath: String? = null
        set(value) { field = value; loadShaderPreset(value) }
    override var onFrameRendered: (() -> Unit)? = null

    override val backendName = "Vulkan"
    @Volatile override var fps = 0f; private set
    @Volatile override var frameTimeMs = 0f; private set
    @Volatile override var viewportWidth = 0; private set
    @Volatile override var viewportHeight = 0; private set

    private fun loadShaderPreset(path: String?) {
        if (renderHandler == null) {
            // Render thread not ready yet — will load when surface is created
            return
        }
        renderHandler?.post {
            val dbg = debugPath?.let { java.io.File(it) }
            dbg?.writeText("loadShaderPreset: path=$path effect=$screenEffect\n")

            nativeUnloadPreset()
            if (path.isNullOrEmpty() || screenEffect == ScreenEffect.NONE) {
                dbg?.appendText("SKIP: path empty or effect NONE\n")
                return@post
            }

            val file = java.io.File(path)
            val preset = PresetParser.parse(file)
            if (preset == null) { dbg?.appendText("SKIP: preset parse failed\n"); return@post }
            dbg?.appendText("Parsed ${preset.passes.size} passes\n")

            val spirvData = mutableListOf<ByteArray>()
            val configData = mutableListOf<Int>()
            val scaleData = mutableListOf<Float>()

            for ((i, pass) in preset.passes.withIndex()) {
                val shaderFile = java.io.File(preset.basePath, pass.shaderPath)
                if (!shaderFile.exists()) { dbg?.appendText("SKIP: shader file not found: ${shaderFile.absolutePath}\n"); return@post }
                val source = shaderFile.readText()

                if (!SlangTranspiler.isVulkanGLSL(source)) { dbg?.appendText("SKIP: pass $i not Vulkan GLSL\n"); return@post }

                val (rawVs, rawFs) = SlangTranspiler.splitSlangStages(source)
                val basePath = shaderFile.parent
                val resolved_vs = basePath?.let { SlangTranspiler.resolveIncludesPublic(rawVs, it) } ?: rawVs
                val resolved_fs = basePath?.let { SlangTranspiler.resolveIncludesPublic(rawFs, it) } ?: rawFs

                val vertSpirv = SlangTranspiler.compileToSpirv(resolved_vs, isVertex = true)
                val fragSpirv = SlangTranspiler.compileToSpirv(resolved_fs, isVertex = false)
                if (vertSpirv == null || fragSpirv == null) {
                    dbg?.appendText("SKIP: SPIR-V compile failed pass $i: ${SlangTranspiler.getLastError()}\n")
                    return@post
                }
                dbg?.appendText("Pass $i: vert=${vertSpirv.size}B frag=${fragSpirv.size}B\n")

                spirvData.add(vertSpirv)
                spirvData.add(fragSpirv)
                configData.add(pass.scaleType.ordinal)
                configData.add(if (pass.filterLinear) 1 else 0)
                configData.add(if (source.contains("Original") || source.contains("OrigTexture")) 1 else 0)
                scaleData.add(pass.scaleX)
                scaleData.add(pass.scaleY)
            }

            val ok = nativeLoadPreset(
                spirvData.toTypedArray(),
                configData.toIntArray(),
                scaleData.toFloatArray(),
                preset.passes.size
            )
            dbg?.appendText("nativeLoadPreset result: $ok\n")
        }
    }

    private fun loadOverlayImage(path: String?) {
        renderHandler?.post {
            if (path.isNullOrEmpty()) {
                nativeUnloadOverlay()
                return@post
            }
            val bitmap = try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
            if (bitmap == null) { nativeUnloadOverlay(); return@post }
            val buf = ByteBuffer.allocateDirect(bitmap.width * bitmap.height * 4).order(ByteOrder.nativeOrder())
            bitmap.copyPixelsToBuffer(buf)
            buf.position(0)
            nativeLoadOverlay(buf, bitmap.width, bitmap.height)
            bitmap.recycle()
        }
    }

    private fun pushScaling() {
        renderHandler?.post {
            nativeSetScaling(scalingMode.ordinal, coreAspectRatio, sharpness.ordinal)
        }
    }

    private val shaderParamOverrides = mutableMapOf<String, Float>()
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var initialized = false
    private var running = false

    override fun setShaderParameter(id: String, value: Float) {
        shaderParamOverrides[id] = value
        renderHandler?.post { nativeSetParam(id, value) }
    }

    override fun clearShaderParamOverrides() {
        shaderParamOverrides.clear()
    }

    fun attachToSurface(surfaceView: SurfaceView) {
        surfaceViewRef = surfaceView
        surfaceView.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderThread = HandlerThread("VulkanRender").also { it.start() }
        renderHandler = Handler(renderThread!!.looper)
        renderHandler?.post {
            initialized = nativeInit(holder.surface)
            if (initialized) {
                nativeSetScaling(scalingMode.ordinal, coreAspectRatio, sharpness.ordinal)
                // Load shader preset and overlay now that native is ready
                loadShaderPreset(shaderPresetPath)
                loadOverlayImage(overlayPath)
                running = true
                postRenderFrame()
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        renderHandler?.post {
            if (initialized) nativeSurfaceChanged(width, height)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        renderHandler?.post {
            if (initialized) {
                nativeDestroy()
                initialized = false
            }
        }
        renderThread?.quitSafely()
        renderThread?.join()
        renderThread = null
        renderHandler = null
    }

    private fun postRenderFrame() {
        renderHandler?.post {
            if (!running || !initialized) return@post

            if (!paused) {
                runner.run()
                val extra = fastForwardFrames
                if (extra > 0) for (i in 1 until extra) runner.run()
            }

            nativeRenderFrame()
            fps = nativeGetFps()
            viewportWidth = nativeGetViewportWidth()
            viewportHeight = nativeGetViewportHeight()
            onFrameRendered?.invoke()

            if (running) postRenderFrame()
        }
    }

    private external fun nativeInit(surface: Surface): Boolean
    private external fun nativeRenderFrame()
    private external fun nativeSurfaceChanged(width: Int, height: Int)
    private external fun nativeDestroy()
    private external fun nativeGetFps(): Float
    private external fun nativeSetParam(name: String, value: Float)
    private external fun nativeSetScaling(mode: Int, coreAspect: Float, sharpness: Int)
    private external fun nativeGetViewportWidth(): Int
    private external fun nativeGetViewportHeight(): Int
    private external fun nativeLoadOverlay(buffer: ByteBuffer, width: Int, height: Int)
    private external fun nativeUnloadOverlay()
    private external fun nativeLoadPreset(passData: Array<ByteArray>, configData: IntArray, scales: FloatArray, passCount: Int): Boolean
    private external fun nativeUnloadPreset()
}
