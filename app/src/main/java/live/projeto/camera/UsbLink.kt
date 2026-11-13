package live.projeto.camera

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque

/**
 * Manda a imagem pelo cabo USB (Depuração USB), igual ao USBLink.swift do iPhone.
 *
 * No PC, o OBS usa o `adb` (o programa do Google que fala com o Android pelo cabo) para abrir
 * conexões TCP até portas do celular. Então o app só escuta, aqui dentro do celular:
 * - 9010: a imagem e o som, em MPEG-TS (o mesmo formato do Wi-Fi). Pela mesma conexão o OBS pode
 *   mandar `TALLY on` / `TALLY off`.
 * - 9011: controle, em linhas (sem senha: só o próprio PC do cabo chega aqui). O app se apresenta
 *   com `HELLO usb <nome da câmera>`; o OBS manda `TALLY`, `CMD <número> <texto>`, `TIME`, `VOICE`
 *   e `PING`; o app responde `ACK <número>` no "Entendi" e `TIME …` na sincronia.
 * - 9012: a voz do intercom (veja [IntercomLink.attachCable]).
 *
 * Funciona junto com o Wi-Fi: o que chegar primeiro manda; os dois podem estar ligados.
 */
class UsbLink(private val cameraName: String, private val intercom: IntercomLink) {
    @Volatile var onSending: ((Boolean) -> Unit)? = null
    @Volatile var onNeedKeyFrame: (() -> Unit)? = null
    @Volatile var onTally: ((Boolean) -> Unit)? = null
    @Volatile var onCommand: ((Long, String) -> Unit)? = null

    private val lock = Object()
    private val muxer = TsMuxer()
    private var videoSocket: Socket? = null
    private var controlSocket: Socket? = null
    private var controlOut: OutputStream? = null
    private val queue = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    @Volatile private var sending = false
    private var tallyVideo = false
    private var tallyControl = false

    val isSending: Boolean
        get() = sending

    fun start() {
        listen(VIDEO_PORT, "projetolive-usb-video") { socket -> serveVideo(socket) }
        listen(CONTROL_PORT, "projetolive-usb-control") { socket -> serveControl(socket) }
        listen(VOICE_PORT, "projetolive-usb-voice") { socket -> intercom.attachCable(socket) }
        Thread({ writer() }, "projetolive-usb-writer").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun sendVideo(accessUnit: ByteArray, pts: Long, keyFrame: Boolean) {
        if (!sending) return
        enqueue(muxer.muxVideo(accessUnit, pts, keyFrame))
    }

    fun sendAudio(adts: ByteArray, pts: Long) {
        if (!sending) return
        enqueue(muxer.muxAudio(adts, pts))
    }

    /** O operador tocou em "Entendi" num aviso que veio pelo cabo. */
    fun acknowledge(number: Long) {
        Thread { sendControl("ACK $number") }.start()
    }

    // ---------------------------------------------------------------------------------------------

    /** Escuta só dentro do celular (o adb chega por 127.0.0.1). Se a porta falhar, tenta de novo. */
    private fun listen(port: Int, name: String, serve: (Socket) -> Unit) {
        Thread({
            while (true) {
                try {
                    ServerSocket(port, 2, InetAddress.getLoopbackAddress()).use { server ->
                        while (true) {
                            val socket = server.accept()
                            socket.tcpNoDelay = true
                            Thread({ serve(socket) }, "$name-client").also {
                                it.isDaemon = true
                                it.start()
                            }
                        }
                    }
                } catch (_: Exception) {
                }
                try {
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, name).also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun serveVideo(socket: Socket) {
        // Um PC por vez: a conexão nova substitui a antiga.
        synchronized(lock) {
            closeQuietly(videoSocket)
            videoSocket = socket
            queue.clear()
            queuedBytes = 0
            muxer.reset()
            sending = true
            lock.notifyAll()
        }
        onNeedKeyFrame?.invoke()
        onSending?.invoke(true)
        try {
            readLines(socket.getInputStream()) { line ->
                when (line) {
                    "TALLY on" -> setTally(video = true, value = true)
                    "TALLY off" -> setTally(video = true, value = false)
                }
            }
        } catch (_: Exception) {
        }
        val wasCurrent = synchronized(lock) {
            if (videoSocket === socket) {
                videoSocket = null
                sending = false
                queue.clear()
                queuedBytes = 0
                true
            } else false
        }
        closeQuietly(socket)
        if (wasCurrent) {
            setTally(video = true, value = false)
            onSending?.invoke(false)
        }
    }

    private fun serveControl(socket: Socket) {
        synchronized(lock) {
            closeQuietly(controlSocket)
            controlSocket = socket
            controlOut = socket.getOutputStream()
        }
        sendControl("HELLO usb $cameraName")
        try {
            readLines(socket.getInputStream()) { line ->
                when {
                    line == "TALLY on" -> setTally(video = false, value = true)
                    line == "TALLY off" -> setTally(video = false, value = false)
                    line.startsWith("TIME ") -> PhoneClock.timeReply(line)?.let { sendControl(it) }
                    line.startsWith("VOICE ") -> intercom.setCableVoice(line.substring(6))
                    line.startsWith("CMD ") ->
                        ControlLink.parseCommand(line)?.let { onCommand?.invoke(it.first, it.second) }
                    // "PING": nada a fazer.
                }
            }
        } catch (_: Exception) {
        }
        val wasCurrent = synchronized(lock) {
            if (controlSocket === socket) {
                controlSocket = null
                controlOut = null
                true
            } else false
        }
        closeQuietly(socket)
        if (wasCurrent) {
            setTally(video = false, value = false)
            intercom.setCableVoice("-")
        }
    }

    private fun sendControl(text: String) {
        synchronized(lock) {
            val out = controlOut ?: return
            try {
                out.write((text + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (_: Exception) {
                closeQuietly(controlSocket)
            }
        }
    }

    /** "No ar" pode chegar pelos dois canais; vale o último de cada um. */
    private fun setTally(video: Boolean, value: Boolean) {
        val (before, after) = synchronized(lock) {
            val old = tallyVideo || tallyControl
            if (video) tallyVideo = value else tallyControl = value
            old to (tallyVideo || tallyControl)
        }
        if (before != after) onTally?.invoke(after)
    }

    /** Fila para o cabo: o codificador nunca espera a rede. Mais de 4 MB parados = descarta. */
    private fun enqueue(data: ByteArray) {
        synchronized(lock) {
            if (!sending || queuedBytes > MAX_QUEUED_BYTES) return
            queue.addLast(data)
            queuedBytes += data.size
            lock.notifyAll()
        }
    }

    private fun writer() {
        while (true) {
            val (socket, data) = synchronized(lock) {
                while (queue.isEmpty() || videoSocket == null) {
                    try {
                        lock.wait()
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                val item = queue.removeFirst()
                queuedBytes -= item.size
                videoSocket to item
            }
            try {
                socket?.getOutputStream()?.write(data)
            } catch (_: Exception) {
                closeQuietly(socket)
            }
        }
    }

    private fun readLines(input: InputStream, handle: (String) -> Unit) {
        val buffer = ByteArray(4096)
        val line = java.io.ByteArrayOutputStream()
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            for (i in 0 until count) {
                val b = buffer[i]
                if (b == '\n'.code.toByte()) {
                    var text = line.toString("UTF-8")
                    if (text.endsWith("\r")) text = text.dropLast(1)
                    line.reset()
                    handle(text)
                } else {
                    line.write(b.toInt())
                    if (line.size() > 4096) return
                }
            }
        }
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val VIDEO_PORT = 9010
        const val CONTROL_PORT = 9011
        const val VOICE_PORT = 9012
        private const val MAX_QUEUED_BYTES = 4_000_000
    }
}
