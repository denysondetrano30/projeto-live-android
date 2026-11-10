package live.projeto.camera

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Canal de controle com a fonte "Celular" do OBS, pelo Wi-Fi (igual ao ControlLink.swift).
 *
 * TCP no mesmo número de porta do vídeo (o vídeo vai por SRT, que é UDP). Conversa em linhas:
 * - OBS → app: `PL1 <desafio>`; app → OBS: `HELLO <HMAC-SHA256(senha, desafio) em hex> <nome>`
 * - OBS → app: `OK`, depois `TALLY on` / `TALLY off`
 * - OBS → app: `CMD <número> <texto>`; app → OBS: `ACK <número>` no "Entendi"
 * - OBS → app: `AUDIO <porta UDP> <ficha>` e `VOICE <quem fala>` (intercom)
 * - OBS → app: `TIME <hora do PC>`; app → OBS: `TIME <hora do PC> <hora do celular>`
 * - os dois lados: `PING` a cada 2 segundos; 6 segundos sem nada = caiu, e o app reconecta
 */
class ControlLink {
    /** O OBS avisa se a câmera está no ar. */
    var onTally: ((Boolean) -> Unit)? = null
    var onCommand: ((Long, String) -> Unit)? = null
    var onAudio: ((IntercomLink.WifiRoute?) -> Unit)? = null
    var onVoice: ((String) -> Unit)? = null

    private data class Target(val host: String, val port: Int, val key: String, val cameraName: String)

    private val lock = Object()
    private var target: Target? = null
    private var thread: Thread? = null
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var authenticated = false
    private var tally = false
    @Volatile private var lastReceived = 0L
    @Volatile private var lastSent = 0L

    fun start(host: String, port: Int, key: String, cameraName: String) {
        val newTarget = Target(host, port, key, cameraName)
        synchronized(lock) {
            if (target == newTarget && thread != null) return
            target = newTarget
            closeSocket()
            if (thread == null) {
                thread = Thread({ run() }, "projetolive-control").also { it.start() }
            }
        }
    }

    /** O operador tocou em "Entendi" no aviso [number]. */
    fun acknowledge(number: Long) {
        Thread {
            synchronized(lock) { if (authenticated) send("ACK $number") }
        }.start()
    }

    fun stop() {
        val old: Thread?
        synchronized(lock) {
            target = null
            old = thread
            thread = null
            closeSocket()
        }
        old?.interrupt()
        lost()
    }

    private fun run() {
        val me = Thread.currentThread()
        while (true) {
            val current = synchronized(lock) { if (thread !== me) null else target } ?: return
            try {
                session(current, me)
            } catch (e: Exception) {
                // caiu: tenta de novo
            }
            synchronized(lock) { closeSocket() }
            lost()
            try {
                Thread.sleep(RETRY_DELAY_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun session(target: Target, me: Thread) {
        val newSocket = Socket()
        newSocket.tcpNoDelay = true
        newSocket.connect(InetSocketAddress(target.host, target.port), 3000)
        newSocket.soTimeout = 1000
        val input: InputStream
        synchronized(lock) {
            if (thread !== me || this.target != target) {
                newSocket.close()
                return
            }
            socket = newSocket
            output = newSocket.getOutputStream()
            authenticated = false
        }
        input = newSocket.getInputStream()
        lastReceived = System.currentTimeMillis()
        val buffer = ByteArray(4096)
        val line = java.io.ByteArrayOutputStream()
        while (true) {
            synchronized(lock) { if (thread !== me || socket !== newSocket) return }
            val count = try {
                input.read(buffer)
            } catch (e: java.net.SocketTimeoutException) {
                0
            }
            if (count < 0) return
            val now = System.currentTimeMillis()
            if (count > 0) {
                lastReceived = now
                for (i in 0 until count) {
                    val b = buffer[i]
                    if (b == '\n'.code.toByte()) {
                        var text = line.toString("UTF-8")
                        if (text.endsWith("\r")) text = text.dropLast(1)
                        line.reset()
                        if (!handle(text, target)) return
                    } else {
                        line.write(b.toInt())
                        if (line.size() > 4096) return
                    }
                }
            }
            if (now - lastReceived > SILENCE_LIMIT_MS) return
            synchronized(lock) {
                if (authenticated && now - lastSent >= PING_INTERVAL_MS) send("PING")
            }
        }
    }

    /** Devolve false para derrubar a conexão. */
    private fun handle(line: String, target: Target): Boolean {
        when {
            line.startsWith("PL1 ") -> {
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(target.key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
                val proof = mac.doFinal(line.substring(4).toByteArray(Charsets.UTF_8)).toHex()
                synchronized(lock) { send("HELLO $proof ${target.cameraName}") }
            }
            line == "OK" -> synchronized(lock) { authenticated = true }
            line == "TALLY on" -> setTally(true)
            line == "TALLY off" -> setTally(false)
            line.startsWith("CMD ") -> parseCommand(line)?.let { onCommand?.invoke(it.first, it.second) }
            line.startsWith("AUDIO ") -> {
                val parts = line.split(" ")
                val port = parts.getOrNull(1)?.toIntOrNull()
                val token = parts.getOrNull(2)?.hexToBytes()
                if (port != null && port in 1..65535 && token != null && token.size == 8) {
                    onAudio?.invoke(IntercomLink.WifiRoute(target.host, port, token))
                }
            }
            line.startsWith("TIME ") -> PhoneClock.timeReply(line)?.let { reply -> synchronized(lock) { send(reply) } }
            line.startsWith("VOICE ") -> onVoice?.invoke(line.substring(6))
            // Senha recusada (o OBS gerou um QR code novo).
            line.startsWith("ERR") -> return false
        }
        return true
    }

    /** Só com [lock]. */
    private fun send(text: String) {
        val out = output ?: return
        lastSent = System.currentTimeMillis()
        try {
            out.write((text + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: Exception) {
            closeSocket()
        }
    }

    /** Só com [lock]. */
    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        output = null
        authenticated = false
    }

    private fun lost() {
        setTally(false)
        onAudio?.invoke(null)
        onVoice?.invoke("-")
    }

    private fun setTally(value: Boolean) {
        val changed = synchronized(lock) {
            if (tally == value) false else {
                tally = value
                true
            }
        }
        if (changed) onTally?.invoke(value)
    }

    companion object {
        private const val PING_INTERVAL_MS = 2000L
        private const val SILENCE_LIMIT_MS = 6000L
        private const val RETRY_DELAY_MS = 2000L

        /** Lê uma linha "CMD <número> <texto>". Devolve null se não for um aviso. */
        fun parseCommand(line: String): Pair<Long, String>? {
            if (!line.startsWith("CMD ")) return null
            val rest = line.substring(4)
            val space = rest.indexOf(' ')
            if (space <= 0) return null
            val number = rest.substring(0, space).toLongOrNull() ?: return null
            val text = rest.substring(space + 1).trim()
            if (text.isEmpty()) return null
            return number to text
        }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val high = Character.digit(this[i * 2], 16)
        val low = Character.digit(this[i * 2 + 1], 16)
        if (high < 0 || low < 0) return null
        out[i] = ((high shl 4) or low).toByte()
    }
    return out
}
