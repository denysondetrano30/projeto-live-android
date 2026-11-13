package live.projeto.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Tela do app: a imagem da câmera, o estado da conexão com o OBS, a borda vermelha de "no ar",
 * os avisos do diretor e o "Segure para falar" do intercom.
 */
class MainActivity : ComponentActivity() {
    private lateinit var camera: CameraController
    private lateinit var encoder: VideoEncoder
    private lateinit var link: PcLink
    private lateinit var audio: AudioCapture
    private lateinit var intercom: IntercomLink

    private lateinit var root: FrameLayout
    private lateinit var preview: TextureView
    private lateinit var tallyBorder: View
    private lateinit var tallyLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var cameraMessage: TextView
    private lateinit var zoomText: TextView
    private lateinit var talkBox: LinearLayout
    private lateinit var talkButton: TextView
    private lateinit var talkLabel: TextView
    private lateinit var talkHint: TextView
    private lateinit var speakingText: TextView
    private lateinit var directorBox: LinearLayout
    private lateinit var directorText: TextView

    private var directorNumber: Long? = null
    private var resumed = false
    private var sendingNow = false
    private var zoom = 1f

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        openCameraIfReady()
        updateMicrophone()
    }

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        val pairing = Pairing.parse(text)
        if (pairing == null) {
            AlertDialog.Builder(this)
                .setMessage("Este QR code não é de uma fonte \"Celular\" do Projeto Live.")
                .setPositiveButton("OK", null)
                .show()
        } else {
            link.setPairing(pairing)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildScreen()

        camera = CameraController(this)
        encoder = VideoEncoder(camera.videoSize.width, camera.videoSize.height)
        link = PcLink(this)
        audio = AudioCapture(this)
        intercom = IntercomLink(this)

        encoder.output = { unit, pts, key -> link.sendVideo(unit, pts, key) }
        audio.onAac = { adts, pts -> link.sendAudio(adts, pts) }
        audio.onIntercom = { frame -> intercom.captured(frame) }
        link.onNeedKeyFrame = { encoder.requestKeyFrame() }
        link.onState = { state -> runOnUiThread { showLink(state) } }
        link.onSending = { value ->
            runOnUiThread {
                sendingNow = value
                updateMicrophone()
            }
        }
        link.control.onTally = { value -> runOnUiThread { showTally(value) } }
        link.control.onAudio = { route -> intercom.setWifiRoute(route) }
        link.control.onVoice = { text -> intercom.setVoice(text) }
        link.control.onCommand = { number, text -> runOnUiThread { showDirector(number, text) } }
        intercom.onState = { state -> runOnUiThread { showIntercom(state) } }
        camera.onStatus = { status -> runOnUiThread { showCamera(status) } }

        intercom.start()
        link.start()
        showLink(PcLink.State.Starting)
        showIntercom(IntercomLink.State())

        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        openCameraIfReady()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        camera.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        audio.stop()
    }

    // ---------------------------------------------------------------------------------------------
    // Câmera e microfone

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openCameraIfReady() {
        if (!resumed) return
        if (!granted(Manifest.permission.CAMERA)) {
            cameraMessage.text = "Sem acesso à câmera. Abra Configurações > Apps > Projeto Live > Permissões e ative a Câmera."
            cameraMessage.visibility = View.VISIBLE
            return
        }
        val texture = preview.surfaceTexture ?: return
        camera.open(texture, encoder.surface)
    }

    /** O microfone fica ligado enquanto o OBS recebe a imagem. */
    private fun updateMicrophone() {
        if (sendingNow && granted(Manifest.permission.RECORD_AUDIO)) audio.start() else audio.stop()
    }

    // ---------------------------------------------------------------------------------------------
    // Tela

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun pill(color: Int = Color.argb(140, 0, 0, 0), radius: Float = 8f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun label(size: Float, bold: Boolean = true): TextView = TextView(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildScreen() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        preview = TextureView(this)
        root.addView(preview, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                fixTransform()
                openCameraIfReady()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = fixTransform()
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreview() }

        // Pinça = zoom.
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                camera.pinch(detector.scaleFactor)
                zoom = (zoom * detector.scaleFactor).coerceIn(1f, 6f)
                zoomText.text = "%.1fx".format(zoom)
                zoomText.visibility = if (zoom > 1.05f) View.VISIBLE else View.GONE
                return true
            }
        })
        root.setOnTouchListener { _, event ->
            pinch.onTouchEvent(event)
            true
        }

        // Borda vermelha: no ar.
        tallyBorder = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(8f), Color.RED)
            }
            visibility = View.GONE
        }
        root.addView(tallyBorder, FrameLayout.LayoutParams(-1, -1))
        tallyLabel = label(16f).apply {
            text = "NO AR"
            background = pill(Color.RED, 6f)
            setPadding(dp(10f), dp(3f), dp(10f), dp(3f))
            visibility = View.GONE
        }
        // No canto de cima, à direita: no meio ficava por cima do texto de estado.
        root.addView(tallyLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(16f), dp(24f), 0)
        })

        // Estado da conexão (em cima, à esquerda) e a versão.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusText = label(13f).apply {
            background = pill()
            setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
        }
        val version = label(12f, bold = false).apply {
            text = "v${PcLink.APP_VERSION}"
            background = pill()
            setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
        }
        top.addView(statusText)
        top.addView(version, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6f) })
        root.addView(top, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(16f), dp(16f), dp(16f), 0)
        })

        zoomText = label(14f).apply {
            background = pill()
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            visibility = View.GONE
        }
        root.addView(zoomText, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(20f)
        })

        cameraMessage = label(16f).apply {
            background = pill()
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            text = "Ligando a câmera…"
        }
        root.addView(cameraMessage, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply {
            setMargins(dp(40f), 0, dp(40f), 0)
        })

        // Botão Parear (embaixo, à direita).
        val pairButton = Button(this).apply {
            text = "Parear"
            isAllCaps = false
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = pill(Color.argb(160, 0, 0, 0), 22f)
            setPadding(dp(18f), 0, dp(18f), 0)
            setOnClickListener { pairTapped() }
        }
        root.addView(pairButton, FrameLayout.LayoutParams(-2, dp(44f), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(16f), dp(16f))
        })

        // Intercom (embaixo, à esquerda).
        talkBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        speakingText = label(13f).apply {
            background = pill(Color.argb(200, 0, 90, 200), 6f)
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            visibility = View.GONE
        }
        talkButton = label(30f).apply {
            text = "🎤"
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(140, 0, 0, 0))
                setStroke(dp(2f), Color.argb(200, 255, 255, 255))
            }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> setTalking(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setTalking(false)
                }
                true
            }
        }
        talkLabel = label(12f).apply {
            text = "Segure para falar"
            background = pill(Color.argb(140, 0, 0, 0), 6f)
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
        }
        talkHint = label(11f).apply {
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            background = pill(Color.YELLOW, 6f)
            setPadding(dp(6f), dp(3f), dp(6f), dp(3f))
            maxWidth = dp(150f)
            visibility = View.GONE
        }
        talkBox.addView(speakingText, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6f) })
        talkBox.addView(talkButton, LinearLayout.LayoutParams(dp(84f), dp(84f)))
        talkBox.addView(talkLabel, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f) })
        talkBox.addView(talkHint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4f) })
        root.addView(talkBox, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(16f), 0, 0, dp(16f))
        })

        // Aviso do diretor (no meio, grande).
        directorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = pill(Color.argb(225, 20, 20, 20), 18f)
            setPadding(dp(28f), dp(18f), dp(28f), dp(18f))
            visibility = View.GONE
            isClickable = true
        }
        val directorTitle = label(13f).apply {
            text = "DIRETOR"
            setTextColor(Color.rgb(255, 200, 0))
        }
        directorText = label(40f).apply { gravity = Gravity.CENTER }
        val ok = Button(this).apply {
            text = "Entendi"
            isAllCaps = false
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            background = pill(Color.rgb(255, 200, 0), 22f)
            setPadding(dp(28f), 0, dp(28f), 0)
            setOnClickListener { acknowledgeDirector() }
        }
        directorBox.addView(directorTitle)
        directorBox.addView(directorText, LinearLayout.LayoutParams(-2, -2).apply {
            setMargins(0, dp(8f), 0, dp(14f))
        })
        directorBox.addView(ok, LinearLayout.LayoutParams(-2, dp(48f)))
        root.addView(directorBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply {
            setMargins(dp(40f), dp(40f), dp(40f), dp(40f))
        })

        setContentView(root)
    }

    /** A imagem ocupa o maior 16:9 que cabe na tela. */
    private fun fitPreview() {
        if (!::camera.isInitialized) return
        val width = root.width
        val height = root.height
        if (width <= 0 || height <= 0) return
        val size = camera.videoSize
        var w = width
        var h = width * size.height / size.width
        if (h > height) {
            h = height
            w = height * size.width / size.height
        }
        val params = preview.layoutParams as FrameLayout.LayoutParams
        if (params.width != w || params.height != h) {
            params.width = w
            params.height = h
            preview.post { preview.layoutParams = params }
        }
    }

    @Suppress("DEPRECATION")
    private fun fixTransform() {
        if (!::camera.isInitialized) return
        CameraController.configureTransform(preview, camera.videoSize, windowManager.defaultDisplay.rotation)
    }

    private fun showLink(state: PcLink.State) {
        val (text, color) = when (state) {
            PcLink.State.Starting -> "Preparando o envio para o PC…" to Color.GRAY
            PcLink.State.NoWifi -> "Sem Wi-Fi: conecte o celular na mesma rede do PC" to Color.RED
            is PcLink.State.Waiting -> "Sem pareamento · toque em Parear (ou no OBS use srt://${state.address})" to Color.YELLOW
            is PcLink.State.Connecting -> "Procurando o OBS (fonte \"${state.source}\")…" to Color.YELLOW
            is PcLink.State.Sending -> state.text to Color.GREEN
        }
        statusText.text = "● ${link.cameraName} · $text"
        statusText.setTextColor(Color.WHITE)
        val dot = android.text.SpannableString(statusText.text)
        dot.setSpan(android.text.style.ForegroundColorSpan(color), 0, 1, 0)
        statusText.text = dot
    }

    private fun showCamera(status: CameraController.Status) {
        when (status) {
            CameraController.Status.Starting -> {
                cameraMessage.text = "Ligando a câmera…"
                cameraMessage.visibility = View.VISIBLE
            }
            CameraController.Status.Running -> {
                cameraMessage.visibility = View.GONE
                fixTransform()
            }
            is CameraController.Status.Failed -> {
                cameraMessage.text = status.message
                cameraMessage.visibility = View.VISIBLE
            }
        }
    }

    private fun showTally(onAir: Boolean) {
        tallyBorder.visibility = if (onAir) View.VISIBLE else View.GONE
        tallyLabel.visibility = if (onAir) View.VISIBLE else View.GONE
    }

    private fun showIntercom(state: IntercomLink.State) {
        talkBox.visibility = if (state.available) View.VISIBLE else View.GONE
        speakingText.text = "🔊 ${state.speaking ?: ""}"
        speakingText.visibility = if (state.speaking != null) View.VISIBLE else View.GONE
        (talkButton.background as GradientDrawable).setColor(
            if (state.talking) Color.RED else Color.argb(140, 0, 0, 0)
        )
        talkLabel.text = if (state.talking) "Falando…" else "Segure para falar"
        when {
            !granted(Manifest.permission.RECORD_AUDIO) -> {
                talkHint.text = "Microfone bloqueado: libere nas permissões do app"
                talkHint.visibility = View.VISIBLE
            }
            !state.hasHeadset -> {
                talkHint.text = "Ligue um fone para ouvir o intercom"
                talkHint.visibility = View.VISIBLE
            }
            else -> talkHint.visibility = View.GONE
        }
    }

    private fun setTalking(value: Boolean) {
        intercom.setTalking(value)
        // Enquanto fala no intercom, a câmera manda silêncio (a conversa não entra na live).
        audio.silent = intercom.isTalking
    }

    private fun showDirector(number: Long, text: String) {
        directorNumber = number
        directorText.text = text
        directorBox.visibility = View.VISIBLE
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 120, 200), -1))
        } catch (_: Exception) {
        }
    }

    private fun acknowledgeDirector() {
        directorNumber?.let { link.control.acknowledge(it) }
        directorNumber = null
        directorBox.visibility = View.GONE
    }

    private fun pairTapped() {
        val current = link.pairing
        if (current == null) {
            scan()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Parear com o OBS")
            .setMessage("Este celular está pareado com a fonte \"${current.sourceName}\".")
            .setPositiveButton("Ler outro QR code") { _, _ -> scan() }
            .setNegativeButton("Desfazer pareamento") { _, _ -> link.setPairing(null) }
            .setNeutralButton("Cancelar", null)
            .show()
    }

    private fun scan() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Aponte para o QR code na imagem da fonte \"Celular\" do OBS")
            .setBeepEnabled(false)
            .setOrientationLocked(true)
        scanner.launch(options)
    }
}
