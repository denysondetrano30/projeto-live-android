package live.projeto.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView

/**
 * Cuida da câmera traseira (Camera2): mostra a imagem na tela e desenha cada quadro no
 * codificador de vídeo ([VideoEncoder]). Também tem o zoom (pinça).
 */
class CameraController(private val context: Context) {
    sealed class Status {
        object Starting : Status()
        object Running : Status()
        class Failed(val message: String) : Status()
    }

    @Volatile var onStatus: ((Status) -> Unit)? = null

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("projetolive-camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private var cameraId: String? = null
    private var characteristics: CameraCharacteristics? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private var previewSurface: Surface? = null
    private var encoderSurface: Surface? = null
    private var fpsRange: Range<Int>? = null
    private var zoom = 1f
    private var maxZoom = 1f

    /** Tamanho da imagem enviada (Full HD se a câmera tiver). */
    val videoSize: Size

    init {
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()
        cameraId = id
        characteristics = id?.let { manager.getCameraCharacteristics(it) }
        videoSize = chooseSize()
        characteristics?.let { chars ->
            // A hora dos quadros: tempo real (com tela desligada) ou monotônico. O PTS segue o mesmo.
            PhoneClock.useRealtime = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            maxZoom = minOf(chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f, ZOOM_LIMIT)
            fpsRange = chooseFps(chars)
        }
    }

    /** Liga a câmera com a tela ([texture]) e o codificador ([encoder]). */
    @SuppressLint("MissingPermission")
    fun open(texture: SurfaceTexture, encoder: Surface) {
        handler.post {
            if (device != null) return@post
            val id = cameraId ?: run {
                onStatus?.invoke(Status.Failed("Câmera não encontrada"))
                return@post
            }
            texture.setDefaultBufferSize(videoSize.width, videoSize.height)
            previewSurface = Surface(texture)
            encoderSurface = encoder
            onStatus?.invoke(Status.Starting)
            try {
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        device = camera
                        startSession(camera)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (device === camera) device = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (device === camera) device = null
                        onStatus?.invoke(Status.Failed("Erro da câmera ($error)"))
                    }
                }, handler)
            } catch (e: Exception) {
                onStatus?.invoke(Status.Failed(e.message ?: "Erro ao abrir a câmera"))
            }
        }
    }

    fun close() {
        handler.post {
            try {
                session?.close()
            } catch (_: Exception) {
            }
            session = null
            device?.close()
            device = null
            previewSurface?.release()
            previewSurface = null
        }
    }

    /** O zoom máximo desta câmera (até o limite do app). */
    val maxZoomLevel: Float
        get() = maxZoom

    /** Botões 1x, 2x, 3x: vai direto para [target]. */
    fun setZoom(target: Float) {
        handler.post {
            zoom = target.coerceIn(1f, maxZoom)
            applyZoom()
            repeatRequest()
        }
    }

    /** Pinça: [factor] > 1 aproxima. */
    fun pinch(factor: Float) {
        handler.post {
            zoom = (zoom * factor).coerceIn(1f, maxZoom)
            applyZoom()
            repeatRequest()
        }
    }

    @Suppress("DEPRECATION")
    private fun startSession(camera: CameraDevice) {
        val preview = previewSurface ?: return
        val encoder = encoderSurface ?: return
        try {
            camera.createCaptureSession(listOf(preview, encoder), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(newSession: CameraCaptureSession) {
                    if (device !== camera) {
                        newSession.close()
                        return
                    }
                    session = newSession
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    builder.addTarget(preview)
                    builder.addTarget(encoder)
                    builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    fpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    request = builder
                    applyZoom()
                    repeatRequest()
                    onStatus?.invoke(Status.Running)
                }

                override fun onConfigureFailed(failedSession: CameraCaptureSession) {
                    onStatus?.invoke(Status.Failed("A câmera não aceitou a configuração"))
                }
            }, handler)
        } catch (e: Exception) {
            onStatus?.invoke(Status.Failed(e.message ?: "Erro ao ligar a câmera"))
        }
    }

    private fun repeatRequest() {
        val builder = request ?: return
        try {
            session?.setRepeatingRequest(builder.build(), null, handler)
        } catch (_: Exception) {
        }
    }

    private fun applyZoom() {
        val builder = request ?: return
        val active = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val w = (active.width() / zoom).toInt()
        val h = (active.height() / zoom).toInt()
        val left = (active.width() - w) / 2
        val top = (active.height() - h) / 2
        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + w, top + h))
    }

    private fun chooseSize(): Size {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1280, 720)
        val sizes = map.getOutputSizes(MediaCodec::class.java) ?: return Size(1280, 720)
        sizes.firstOrNull { it.width == 1920 && it.height == 1080 }?.let { return it }
        return sizes.filter { it.width * 9 == it.height * 16 && it.width <= 1920 }
            .maxByOrNull { it.width } ?: Size(1280, 720)
    }

    private fun chooseFps(chars: CameraCharacteristics): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
            ?: ranges.filter { it.contains(30) }.maxByOrNull { it.lower }
    }

    companion object {
        private const val ZOOM_LIMIT = 6f

        /**
         * A imagem da câmera chega "de lado" na tela: gira e ajusta para preencher a [view]
         * (que já tem o formato 16:9 da imagem). Com a tela deitada, é girar 90° ou 270°.
         */
        fun configureTransform(view: TextureView, size: Size, rotation: Int) {
            val viewWidth = view.width.toFloat()
            val viewHeight = view.height.toFloat()
            if (viewWidth <= 0 || viewHeight <= 0) return
            val matrix = Matrix()
            val viewRect = RectF(0f, 0f, viewWidth, viewHeight)
            val bufferRect = RectF(0f, 0f, size.height.toFloat(), size.width.toFloat())
            val centerX = viewRect.centerX()
            val centerY = viewRect.centerY()
            if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                val scale = maxOf(viewHeight / size.height, viewWidth / size.width)
                matrix.postScale(scale, scale, centerX, centerY)
                matrix.postRotate(90f * (rotation - 2), centerX, centerY)
            } else if (rotation == Surface.ROTATION_180) {
                matrix.postRotate(180f, centerX, centerY)
            }
            view.setTransform(matrix)
        }
    }
}
