package live.projeto.camera

import android.content.Context
import kotlin.random.Random

/**
 * Manda a imagem e o som para o OBS do PC pelo Wi-Fi, usando SRT (igual ao PCLink.swift).
 *
 * Pareado (leu o QR code de uma fonte "Celular" no OBS): o celular liga para o PC, na porta e com
 * a senha daquela fonte, e reconecta sozinho se cair. Junto abre o canal de controle
 * ([ControlLink]): "no ar", avisos do diretor, intercom e a sincronia.
 *
 * Sem pareamento (plano B): o celular espera na porta 9000, e o OBS liga para ele pelo endereço
 * `srt://IP-DO-CELULAR:9000`.
 */
class PcLink(private val context: Context) {
    sealed class State {
        object Starting : State()
        object NoWifi : State()
        class Waiting(val address: String) : State()
        class Connecting(val source: String) : State()
        class Sending(val text: String) : State()
    }

    @Volatile var onState: ((State) -> Unit)? = null
    /** true enquanto o OBS estiver recebendo a imagem (liga o microfone). */
    @Volatile var onSending: ((Boolean) -> Unit)? = null
    /** Chamado quando o codificador deve mandar um quadro-chave já. */
    @Volatile var onNeedKeyFrame: (() -> Unit)? = null

    val control = ControlLink()
    val cameraName: String = makeCameraName()

    private val lock = Any()
    private val muxer = TsMuxer()
    private val sender = SrtSender()
    @Volatile private var sending = false
    private var pairingForLoop: Pairing? = PairingStore.load(context)
    private var generation = 0
    private var thread: Thread? = null

    var pairing: Pairing? = pairingForLoop
        private set

    fun start() {
        if (thread != null) return
        thread = Thread({ runLoop() }, "projetolive-pc").also { it.start() }
    }

    /** Troca (ou apaga) o pareamento e reinicia a conexão na hora. */
    fun setPairing(newPairing: Pairing?) {
        PairingStore.save(context, newPairing)
        pairing = newPairing
        synchronized(lock) {
            pairingForLoop = newPairing
            generation += 1
        }
        sender.close()
    }

    fun sendVideo(accessUnit: ByteArray, pts: Long, keyFrame: Boolean) {
        if (!sending) return
        sender.send(muxer.muxVideo(accessUnit, pts, keyFrame))
    }

    fun sendAudio(adts: ByteArray, pts: Long) {
        if (!sending) return
        sender.send(muxer.muxAudio(adts, pts))
    }

    private fun runLoop() {
        while (true) {
            val ip = NetworkInfo.wifiIPv4()
            if (ip == null) {
                publish(State.NoWifi)
                sleep(2000)
                continue
            }
            val (current, startGeneration) = synchronized(lock) { pairingForLoop to generation }
            if (current != null) runPaired(current, ip, startGeneration) else runUnpaired(ip)
        }
    }

    /** Pareado: liga para o PC. Tenta cada endereço do QR code, primeiro o da mesma rede. */
    private fun runPaired(pairing: Pairing, phoneIp: String, startGeneration: Int) {
        publish(State.Connecting(pairing.sourceName))
        for (host in pairing.orderedHosts(phoneIp)) {
            if (changed(startGeneration)) return
            val connected = stream(
                host, pairing.port, pairing.key,
                "Enviando para \"${pairing.sourceName}\" no OBS", startGeneration
            )
            if (connected) return
        }
        // Nenhum endereço respondeu: o OBS pode estar fechado. Tenta de novo daqui a pouco.
        sleep(2000)
    }

    /** Sem pareamento: espera o OBS ligar na porta 9000. */
    private fun runUnpaired(phoneIp: String) {
        val address = "$phoneIp:$PORT"
        publish(State.Waiting(address))
        if (!stream(null, PORT, null, "Enviando para o PC · srt://$address", null)) sleep(1000)
    }

    /** Conecta, manda enquanto a conexão durar e devolve true se chegou a conectar. */
    private fun stream(host: String?, port: Int, key: String?, status: String, startGeneration: Int?): Boolean {
        val connected = if (host != null) sender.connect(host, port, key) else sender.listen(port)
        if (!connected) {
            sender.close()
            return false
        }
        muxer.reset()
        sending = true
        onNeedKeyFrame?.invoke()
        onSending?.invoke(true)
        publish(State.Sending(status))
        if (host != null && key != null) control.start(host, port, key, cameraName)

        while (true) {
            if (startGeneration != null && changed(startGeneration)) break
            if (!sender.isConnected) break
            sleep(500)
        }

        sending = false
        onSending?.invoke(false)
        control.stop()
        sender.close()
        return true
    }

    private fun changed(startGeneration: Int) = synchronized(lock) { generation != startGeneration }

    private fun publish(state: State) {
        onState?.invoke(state)
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun makeCameraName(): String {
        val prefs = context.getSharedPreferences("projetolive", Context.MODE_PRIVATE)
        val id = prefs.getString("cameraID", null)?.takeIf { it.isNotEmpty() } ?: run {
            val created = "%04X".format(Random.nextInt(0, 0x10000))
            prefs.edit().putString("cameraID", created).apply()
            created
        }
        return "Câmera Android $id"
    }

    companion object {
        const val PORT = 9000
        const val APP_VERSION = "0.2"
    }
}
