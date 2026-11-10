package live.projeto.camera

import io.github.thibaultbee.srtdroid.core.Srt
import io.github.thibaultbee.srtdroid.core.enums.SockOpt
import io.github.thibaultbee.srtdroid.core.enums.Transtype
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import java.io.ByteArrayOutputStream

/**
 * Envio SRT (igual ao SRTSender.swift do iPhone): entrega os pacotes MPEG-TS ao OBS em mensagens
 * de 1316 bytes (7 pacotes de 188).
 *
 * - Pareado: liga para o OBS (caller), com a senha do QR code.
 * - Plano B: espera o OBS ligar (listener) na porta 9000, sem senha.
 */
class SrtSender {
    private val lock = Any()
    private var socket: SrtSocket? = null
    private var listenSocket: SrtSocket? = null
    private val pending = ByteArrayOutputStream()

    init {
        startUp
    }

    /** Liga para o OBS. Bloqueia até conectar ou desistir (uns 3 segundos). */
    fun connect(host: String, port: Int, passphrase: String?): Boolean {
        close()
        return try {
            val newSocket = SrtSocket()
            configure(newSocket, passphrase)
            newSocket.setSockFlag(SockOpt.CONNTIMEO, 3000)
            try {
                newSocket.connect(host, port)
            } catch (e: Exception) {
                newSocket.close()
                return false
            }
            synchronized(lock) {
                socket = newSocket
                pending.reset()
            }
            true
        } catch (e: Throwable) {
            false
        }
    }

    /** Espera o OBS ligar na [port] (plano B). Bloqueia até alguém conectar ou [close]. */
    fun listen(port: Int): Boolean {
        close()
        val server = try {
            SrtSocket().also {
                configure(it, null)
                it.setSockFlag(SockOpt.REUSEADDR, true)
                it.bind("0.0.0.0", port)
                it.listen(1)
            }
        } catch (e: Throwable) {
            return false
        }
        synchronized(lock) { listenSocket = server }
        val client = try {
            server.accept().first
        } catch (e: Throwable) {
            null
        }
        synchronized(lock) { if (listenSocket === server) listenSocket = null }
        try {
            server.close()
        } catch (_: Throwable) {
        }
        if (client == null) return false
        synchronized(lock) {
            socket = client
            pending.reset()
        }
        return true
    }

    val isConnected: Boolean
        get() = synchronized(lock) {
            try {
                socket?.isConnected == true
            } catch (e: Throwable) {
                false
            }
        }

    /** Manda pacotes MPEG-TS. Devolve false se a conexão caiu. */
    fun send(packets: ByteArray): Boolean = synchronized(lock) {
        val current = socket ?: return false
        pending.write(packets)
        if (pending.size() < MESSAGE_SIZE) return true
        val data = pending.toByteArray()
        pending.reset()
        var offset = 0
        while (data.size - offset >= MESSAGE_SIZE) {
            val sent = try {
                current.send(data, offset, MESSAGE_SIZE)
            } catch (e: Throwable) {
                -1
            }
            offset += MESSAGE_SIZE
            if (sent < 0) {
                try {
                    current.close()
                } catch (_: Throwable) {
                }
                socket = null
                return false
            }
        }
        pending.write(data, offset, data.size - offset)
        true
    }

    fun close() {
        val old: SrtSocket?
        val oldListener: SrtSocket?
        synchronized(lock) {
            old = socket
            oldListener = listenSocket
            socket = null
            listenSocket = null
            pending.reset()
        }
        try {
            old?.close()
        } catch (_: Throwable) {
        }
        try {
            oldListener?.close()
        } catch (_: Throwable) {
        }
    }

    private fun configure(target: SrtSocket, passphrase: String?) {
        target.setSockFlag(SockOpt.TRANSTYPE, Transtype.LIVE)
        target.setSockFlag(SockOpt.LATENCY, LATENCY_MS)
        target.setSockFlag(SockOpt.SENDER, true)
        if (!passphrase.isNullOrEmpty()) {
            target.setSockFlag(SockOpt.PBKEYLEN, 16)
            target.setSockFlag(SockOpt.PASSPHRASE, passphrase)
        }
    }

    companion object {
        private const val MESSAGE_SIZE = 1316

        /** Folga para reenviar pacotes perdidos: 60 ms basta numa rede local (o padrão do SRT é 120). */
        private const val LATENCY_MS = 60
        private val startUp: Unit by lazy { Srt.startUp(); Unit }
    }
}
