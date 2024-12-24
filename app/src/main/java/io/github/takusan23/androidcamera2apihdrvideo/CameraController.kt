package io.github.takusan23.androidcamera2apihdrvideo

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraDevice.StateCallback
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.opengl.Matrix
import android.os.Environment
import android.provider.MediaStore
import android.util.Range
import android.view.Surface
import androidx.core.content.contentValuesOf
import io.github.takusan23.androidcamera2apihdrvideo.opengl.OpenGlRenderer
import io.github.takusan23.androidcamera2apihdrvideo.opengl.TextureRenderer
import io.github.takusan23.androidcamera2apihdrvideo.opengl.TextureRendererSurfaceTexture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@SuppressLint("MissingPermission")
class CameraController(private val context: Context) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private val _isRecording = MutableStateFlow(false)
    private var mediaRecorder: MediaRecorder? = null
    private var saveVideoFile: File? = null

    /** 今の処理キャンセル用 */
    private var currentJob: Job? = null

    /** Surface の生成を通知する Flow */
    private val _surfaceFlow = MutableStateFlow<Surface?>(null)

    /** カメラを開いて、状態を通知する Flow */
    private val backCameraDeviceFlow = callbackFlow {
        var _device: CameraDevice? = null
        // openCamera は複数回コールバック関数を呼び出すので flow にする必要がある
        cameraManager.openCamera(getBackCameraId(), cameraExecutor, object : StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                _device = camera
                trySend(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                // TODO エラー処理
                _device?.close()
                _device = null
                trySend(null)
            }

            override fun onError(camera: CameraDevice, error: Int) {
                // TODO エラー処理
                _device?.close()
                _device = null
                trySend(null)
            }
        })
        // キャンセル時
        awaitClose { _device?.close() }
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    private var previewOpenGlRenderer: OpenGlRenderer? = null
    private var previewSurfaceTexture: TextureRendererSurfaceTexture? = null
    private var recordOpenGlRenderer: OpenGlRenderer? = null
    private var recordSurfaceTexture: TextureRendererSurfaceTexture? = null

    /** 録画中か */
    val isRecording = _isRecording.asStateFlow()

    /** 初回時、録画終了後に呼び出す */
    fun prepare() {
        scope.launch {
            currentJob?.cancelAndJoin()

            // MediaRecorder を作る
            initMediaRecorder()
            // OpenGL ES 周りを作る
            createOpenGlRendererAndSurfaceTexture(mediaRecorder!!.surface).also { (newOpenGlRenderer, newSurfaceTexture) ->
                recordOpenGlRenderer = newOpenGlRenderer
                recordSurfaceTexture = newSurfaceTexture
            }

            currentJob = launch {
                // SurfaceView の生存に合わせる
                // アプリ切り替えで SurfaceView は再生成されるので、常に監視する必要がある
                _surfaceFlow.collectLatest { previewSurface ->
                    previewSurface ?: return@collectLatest

                    // プレビュー OpenGL ES を作る
                    createOpenGlRendererAndSurfaceTexture(previewSurface).also { (newOpenGlRenderer, newSurfaceTexture) ->
                        previewSurfaceTexture = newSurfaceTexture
                        previewOpenGlRenderer = newOpenGlRenderer
                    }

                    // 外カメラが開かれるのを待つ
                    val cameraDevice = backCameraDeviceFlow.filterNotNull().first()

                    // カメラ出力先。それぞれの SurfaceTexture
                    val outputSurfaceList = listOfNotNull(previewSurfaceTexture?.surface, recordSurfaceTexture?.surface)

                    // CaptureRequest をつくる
                    val captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        // FPS とかズームとか設定するなら
                        // HDR はここじゃない
                        outputSurfaceList.forEach { surface -> addTarget(surface) }
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(VIDEO_FPS, VIDEO_FPS))
                    }

                    // CaptureSession をつくる
                    val captureSession = cameraDevice.awaitCameraSessionConfiguration(outputSurfaceList = outputSurfaceList) ?: return@collectLatest

                    // カメラ映像を流し始める
                    captureSession.setRepeatingRequest(captureRequest.build(), null, null)

                    coroutineScope {
                        // プレビューを描画する
                        launch {
                            try {
                                val drawContinuesData = OpenGlRenderer.DrawContinuesData(true, 0)
                                previewOpenGlRenderer?.drawLoop {
                                    drawFrame(previewSurfaceTexture!!)
                                    drawContinuesData
                                }
                            } finally {
                                previewSurfaceTexture?.destroy()
                                previewOpenGlRenderer?.destroy()
                            }
                        }
                        // MediaRecorder のを描画する
                        launch {
                            try {
                                val drawContinuesData = OpenGlRenderer.DrawContinuesData(true, 0)
                                recordOpenGlRenderer?.drawLoop {
                                    drawFrame(recordSurfaceTexture!!)
                                    // MediaRecorder は setPresentationTime の指定が必要（そう）
                                    drawContinuesData.currentTimeNanoSeconds = System.nanoTime()
                                    drawContinuesData
                                }
                            } finally {
                                previewSurfaceTexture?.destroy()
                                previewOpenGlRenderer?.destroy()
                            }
                        }
                    }
                }
            }
        }
    }

    /** Surface の生成コールバックで呼び出す */
    fun createSurface(surface: Surface) {
        _surfaceFlow.value = surface
    }

    /** Surface の破棄コールバックで呼び出す */
    fun destroySurface() {
        _surfaceFlow.value = null
    }

    /** 撮影開始 */
    fun startRecord() {
        mediaRecorder?.start()
        _isRecording.value = true
    }

    /** 撮影終了 */
    fun stopRecord() {
        scope.launch {
            // 処理を止める
            currentJob?.cancelAndJoin()
            // 録画停止
            mediaRecorder?.stop()
            mediaRecorder?.release()
            _isRecording.value = false

            // 動画データを動画フォルダへ移動
            val contentResolver = context.contentResolver
            val contentValues = contentValuesOf(
                MediaStore.Images.Media.DISPLAY_NAME to saveVideoFile!!.name,
                MediaStore.Images.Media.RELATIVE_PATH to "${Environment.DIRECTORY_MOVIES}/AndroidCamera2HdrVideo"
            )
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)!!
            saveVideoFile!!.inputStream().use { inputStream ->
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            saveVideoFile?.delete()

            // MediaRecorder は使い捨てなので、準備からやり直す
            prepare()
        }
    }

    /** 終了時に呼ぶ */
    fun destroy() {
        scope.cancel()
        // cameraExecutor.shutdown()
    }

    /** プレビューと録画の描画を共通化するための関数 */
    private fun TextureRenderer.drawFrame(surfaceTexture: TextureRendererSurfaceTexture) {
        drawSurfaceTexture(surfaceTexture) { mvpMatrix ->
            // 回転する
            // TODO 常に横画面で使う想定のため、条件分岐がありません。縦持ちでも使いたい場合は if (isLandscape) { } をやってください
            Matrix.rotateM(mvpMatrix, 0, 90f, 0f, 0f, 1f)
        }
    }

    /** [OpenGlRenderer]と[TextureRendererSurfaceTexture]を作る */
    @SuppressLint("Recycle")
    private suspend fun createOpenGlRendererAndSurfaceTexture(surface: Surface): Pair<OpenGlRenderer, TextureRendererSurfaceTexture> {
        // OpenGL ES で描画する OpenGlRenderer を作る
        val openGlRenderer = OpenGlRenderer(surface, VIDEO_WIDTH, VIDEO_HEIGHT, isSupportedTenBitHdr())
        openGlRenderer.prepare()
        // カメラ映像を OpenGL ES へ渡す SurfaceTexture を作る
        val surfaceTexture = TextureRendererSurfaceTexture(openGlRenderer.generateTextureId())
        // カメラ映像の解像度を設定
        surfaceTexture.setTextureSize(VIDEO_WIDTH, VIDEO_HEIGHT)
        return openGlRenderer to surfaceTexture
    }

    /** MediaRecorder と仮のファイルを作る */
    private fun initMediaRecorder() {
        // 一時的に getExternalFilesDir に保存する
        saveVideoFile = context.getExternalFilesDir(null)!!.resolve("${System.currentTimeMillis()}.mp4")
        mediaRecorder = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.HEVC) // 10 ビット HDR 動画の場合は HEVC にする
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(2)
            setVideoEncodingBitRate(20_000_000)
            setVideoFrameRate(60)
            setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
            setAudioEncodingBitRate(192_000)
            setAudioSamplingRate(48_000)
            setOutputFile(saveVideoFile)
            prepare()
        }
    }

    /** CameraDevice#createCaptureSession をサスペンド関数にしたもの */
    private suspend fun CameraDevice.awaitCameraSessionConfiguration(outputSurfaceList: List<Surface>): CameraCaptureSession? = suspendCoroutine { continuation ->
        // OutputConfiguration を作る
        val outputConfigurationList = outputSurfaceList
            .map { surface -> OutputConfiguration(surface) }
            .onEach { outputConfig ->
                // 10 ビット HDR 動画撮影を有効にする
                // HDR 動画撮影に対応している場合、少なくとも HLG 形式の HDR に対応していることが保証されているので HLG
                if (isSupportedTenBitHdr()) {
                    outputConfig.dynamicRangeProfile = DynamicRangeProfiles.HLG10
                }
            }
        val sessionConfiguration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputConfigurationList, cameraExecutor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                continuation.resume(captureSession)
            }

            override fun onConfigureFailed(p0: CameraCaptureSession) {
                // TODO エラー処理
                continuation.resume(null)
            }
        })
        createCaptureSession(sessionConfiguration)
    }

    /** 10 ビット HDR 動画撮影に対応している場合は true */
    private fun isSupportedTenBitHdr(): Boolean {
        val characteristic = cameraManager.getCameraCharacteristics(getBackCameraId())
        val capabilities = characteristic[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
        return capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT) == true
    }

    /** バックカメラの ID を返す */
    private fun getBackCameraId(): String = cameraManager
        .cameraIdList
        .first { cameraId -> cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }

    companion object {

        // フル HD
        const val VIDEO_WIDTH = 1920
        const val VIDEO_HEIGHT = 1080
        const val VIDEO_FPS = 60

    }
}
