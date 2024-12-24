package io.github.takusan23.androidcamera2apihdrvideo.opengl

import android.view.Surface
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class OpenGlRenderer(
    outputSurface: Surface,
    width: Int,
    height: Int,
    isEnableTenBitHdr: Boolean
) {

    /** OpenGL 描画用スレッドの Kotlin Coroutine Dispatcher */
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private val openGlRelatedThreadDispatcher = newSingleThreadContext("openGlRelatedThreadDispatcher")

    private val inputSurface = InputSurface(outputSurface, isEnableTenBitHdr)
    private val textureRenderer = TextureRenderer(width, height, isEnableTenBitHdr)

    /** OpenGL ES の用意をし、フラグメントシェーダー等をコンパイルする */
    suspend fun prepare() {
        withContext(openGlRelatedThreadDispatcher) {
            inputSurface.makeCurrent()
            textureRenderer.prepareShader()
        }
    }

    /** テクスチャ ID を払い出す。SurfaceTexture 作成に使うので */
    suspend fun generateTextureId(): Int {
        return withContext(openGlRelatedThreadDispatcher) {
            textureRenderer.generateTextureId()
        }
    }

    /** 描画する */
    suspend fun drawLoop(drawTexture: suspend TextureRenderer.() -> DrawContinuesData) {
        withContext(openGlRelatedThreadDispatcher) {
            while (true) {
                yield()

                // 描画する
                textureRenderer.prepareDraw()
                val continuesData = drawTexture(textureRenderer)

                // presentationTime が多分必要。swapBuffers して Surface に流す
                inputSurface.setPresentationTime(continuesData.currentTimeNanoSeconds)
                inputSurface.swapBuffers()

                // 続行するか
                if (!continuesData.isAvailableNext) break
            }
        }
    }

    /** 破棄する */
    suspend fun destroy() {
        // try-finally で呼び出されるため NonCancellable 必須
        withContext(openGlRelatedThreadDispatcher + NonCancellable) {
            inputSurface.destroy()
        }
        openGlRelatedThreadDispatcher.close()
    }

    data class DrawContinuesData(
        var isAvailableNext: Boolean,
        var currentTimeNanoSeconds: Long = System.nanoTime()
    )

}