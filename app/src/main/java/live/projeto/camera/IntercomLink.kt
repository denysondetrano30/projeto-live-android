package live.projeto.camera

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Intercom: conversa por voz com o diretor e com as outras câmeras ("Segure para falar"),
 * igual ao IntercomLink.swift do iPhone.
 *
 * Voz em 16 kHz, mono, 16 bits, em quadros de 20 ms (320 amostras), sem compressão.
 * Pacote: `PLV1` | ficha (8 bytes) | sequência (2 bytes, little-endian) | marcas (1 byte) | 0 |
 * voz (640 bytes, só quando a marca 1 está ligada).
 *
 * Wi-Fi: UDP para a porta que o OBS mandou pelo canal de controle (`AUDIO <porta> <ficha>`).
 * Um pacote sem voz a cada segundo avisa o PC do endereço do celular.
 *
 * Cabo USB: o OBS conecta na porta 9012 do app (pelo adb); cada pacote vai precedido do tamanho
 * (2 bytes, little-endian). Com o cabo ligado, a voz vai por ele.
 *
 * O som só toca com fone de ouvido, para a voz do intercom não vazar no ambiente.
 */
class IntercomLink(context: Context) {
    class WifiRoute(val host: String, val port: Int, val token: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is WifiRoute && other.host == host && other.port == port && other.token.contentEquals(token)

        override fun hashCode(): Int = host.hashCode() * 31 + port
    }

    data class State(
        val available: Boolean = false,
        val hasHeadset: Boolean = false,
        val talking: Boolean = false,
        val speaking: String? = null
    )

    /** Chamado (em qualquer thread) quando algo muda na tela do intercom. */
    @Volatile var onState: ((State) -> Unit)? = null

    private val lock = Any()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var route: WifiRoute? = null
    private var socket: DatagramSocket? = null
    private var receiver: Thread? = null
    private var sequence = 0
    private var voice = "-"
    private var talking = false
    private var headset = false
    private var track: AudioTrack? = null
    private var lastPlayed = 0L
    private var keepAlive: Thread? = null
    private var cable: Socket? = null
    private var cableVoice = "-"

    fun start() {
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = updateHeadset()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = updateHeadset()
        }, Handler(Looper.getMainLooper()))
        updateHeadset()
        keepAlive = Thread({
            while (true) {
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                synchronized(lock) { if (socket != null) sendPacket(null) }
            }
        }, "projetolive-intercom-keepalive").also {
            it.isDaemon = true
            it.start()
        }
    }

    /** O OBS mandou "AUDIO <porta> <ficha>" (null = o canal de controle caiu). */
    fun setWifiRoute(newRoute: WifiRoute?) {
        synchronized(lock) {
            if (route == newRoute) return
            closeSocket()
            route = newRoute
            if (newRoute != null) {
                try {
                    val udp = DatagramSocket()
                    udp.connect(InetSocketAddress(newRoute.host, newRoute.port))
                    socket = udp
                    receiver = Thread({ receive(udp) }, "projetolive-intercom").also { it.start() }
                    sendPacket(null)
                } catch (e: Exception) {
                    socket = null
                }
            } else {
                voice = "-"
                talking = false
                stopTrack()
            }
        }
        publish()
    }

    fun setVoice(text: String) {
        synchronized(lock) { voice = text }
        publish()
    }

    /** Linha "VOICE <quem fala>" que chegou pelo cabo. */
    fun setCableVoice(text: String) {
        synchronized(lock) { cableVoice = text }
        publish()
    }

    /** O OBS conectou na porta 9012 pelo cabo: a voz passa a ir e vir por aqui. Bloqueia até fechar. */
    fun attachCable(socket: Socket) {
        synchronized(lock) {
            try {
                cable?.close()
            } catch (_: Exception) {
            }
            cable = socket
        }
        publish()
        try {
            val input = socket.getInputStream()
            val header = ByteArray(2)
            val packet = ByteArray(PACKET_SIZE)
            while (true) {
                if (!readFully(input, header, 2)) break
                val length = (header[0].toInt() and 0xFF) or ((header[1].toInt() and 0xFF) shl 8)
                if (length < HEADER_SIZE || length > PACKET_SIZE) break
                if (!readFully(input, packet, length)) break
                if (length < PACKET_SIZE) continue
                if (packet[0] != 'P'.code.toByte() || packet[1] != 'L'.code.toByte() ||
                    packet[2] != 'V'.code.toByte() || packet[3] != '1'.code.toByte()
                ) continue
                synchronized(lock) {
                    if (cable === socket && packet[14].toInt() and FLAG_AUDIO.toInt() != 0) play(packet)
                }
            }
        } catch (_: Exception) {
        }
        synchronized(lock) {
            if (cable === socket) {
                cable = null
                cableVoice = "-"
                if (route == null) {
                    talking = false
                    stopTrack()
                }
            }
        }
        try {
            socket.close()
        } catch (_: Exception) {
        }
        publish()
    }

    private fun readFully(input: java.io.InputStream, into: ByteArray, count: Int): Boolean {
        var got = 0
        while (got < count) {
            val n = input.read(into, got, count - got)
            if (n < 0) return false
            got += n
        }
        return true
    }

    /** "Segure para falar": true ao apertar, false ao soltar. */
    fun setTalking(value: Boolean) {
        synchronized(lock) { talking = value && (route != null || cable != null) }
        publish()
    }

    val isTalking: Boolean
        get() = synchronized(lock) { talking }

    /** Voz do microfone (320 amostras em 16 kHz), da thread do microfone. */
    fun captured(frame: ShortArray) {
        synchronized(lock) {
            if (!talking || (socket == null && cable == null)) return
            sendPacket(frame)
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun publish() {
        val state = synchronized(lock) {
            val available = route != null || cable != null
            val who = if (cable != null) cableVoice else voice
            State(
                available = available,
                hasHeadset = headset,
                talking = talking,
                speaking = if (available && who != "-" && who.isNotEmpty()) who else null
            )
        }
        onState?.invoke(state)
    }

    private fun updateHeadset() {
        val types = mutableSetOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )
        if (Build.VERSION.SDK_INT >= 31) types.add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        val value = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
        synchronized(lock) {
            headset = value
            if (!value) stopTrack()
        }
        publish()
    }

    /** Só com [lock]. */
    private fun sendPacket(frame: ShortArray?) {
        val wire = cable
        val udp = socket
        if (wire == null && udp == null) return
        val token = route?.token ?: ByteArray(8)
        val packet = ByteArray(HEADER_SIZE + if (frame != null) FRAME_SAMPLES * 2 else 0)
        packet[0] = 'P'.code.toByte()
        packet[1] = 'L'.code.toByte()
        packet[2] = 'V'.code.toByte()
        packet[3] = '1'.code.toByte()
        System.arraycopy(token, 0, packet, 4, 8)
        packet[12] = (sequence and 0xFF).toByte()
        packet[13] = ((sequence shr 8) and 0xFF).toByte()
        sequence = (sequence + 1) and 0xFFFF
        packet[14] = if (frame != null) FLAG_AUDIO else 0
        packet[15] = 0
        if (frame != null) {
            for (i in 0 until FRAME_SAMPLES) {
                val v = frame[i].toInt()
                packet[HEADER_SIZE + i * 2] = (v and 0xFF).toByte()
                packet[HEADER_SIZE + i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
        }
        if (wire != null && frame != null) {
            // Pelo cabo: tamanho (2 bytes) + pacote. Sem ficha conferida (só o PC do cabo chega).
            val framed = ByteArray(2 + packet.size)
            framed[0] = (packet.size and 0xFF).toByte()
            framed[1] = ((packet.size shr 8) and 0xFF).toByte()
            System.arraycopy(packet, 0, framed, 2, packet.size)
            try {
                wire.getOutputStream().write(framed)
            } catch (_: Exception) {
            }
            return
        }
        try {
            udp?.send(DatagramPacket(packet, packet.size))
        } catch (_: Exception) {
        }
    }

    private fun receive(udp: DatagramSocket) {
        val buffer = ByteArray(2048)
        while (true) {
            val datagram = DatagramPacket(buffer, buffer.size)
            try {
                udp.receive(datagram)
            } catch (e: Exception) {
                return
            }
            if (datagram.length < PACKET_SIZE) continue
            if (buffer[0] != 'P'.code.toByte() || buffer[1] != 'L'.code.toByte() ||
                buffer[2] != 'V'.code.toByte() || buffer[3] != '1'.code.toByte()
            ) continue
            synchronized(lock) {
                if (socket !== udp) return
                val token = route?.token ?: return
                for (i in 0 until 8) if (buffer[4 + i] != token[i]) return@synchronized
                if (buffer[14].toInt() and FLAG_AUDIO.toInt() == 0) return@synchronized
                play(buffer)
            }
        }
    }

    /** Só com [lock]. */
    private fun play(packet: ByteArray) {
        if (!headset) return
        val out = track ?: makeTrack()?.also { track = it } ?: return
        val samples = ShortArray(FRAME_SAMPLES)
        for (i in 0 until FRAME_SAMPLES) {
            samples[i] = ((packet[HEADER_SIZE + i * 2].toInt() and 0xFF) or
                (packet[HEADER_SIZE + i * 2 + 1].toInt() shl 8)).toShort()
        }
        val now = System.currentTimeMillis()
        if (now - lastPlayed > 200) {
            // Começo de uma fala: 40 ms de silêncio na frente seguram as variações da rede.
            out.write(ShortArray(FRAME_SAMPLES * 2), 0, FRAME_SAMPLES * 2, AudioTrack.WRITE_NON_BLOCKING)
        }
        lastPlayed = now
        out.write(samples, 0, FRAME_SAMPLES, AudioTrack.WRITE_NON_BLOCKING)
    }

    private fun makeTrack(): AudioTrack? = try {
        val bytes = maxOf(
            AudioTrack.getMinBufferSize(16_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
            FRAME_SAMPLES * 2 * MAX_QUEUED_FRAMES
        )
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(16_000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    } catch (e: Exception) {
        null
    }

    /** Só com [lock]. */
    private fun stopTrack() {
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        track?.release()
        track = null
    }

    /** Só com [lock]. */
    private fun closeSocket() {
        socket?.close()
        socket = null
        receiver = null
    }

    companion object {
        private const val FRAME_SAMPLES = 320
        private const val HEADER_SIZE = 16
        private const val PACKET_SIZE = HEADER_SIZE + FRAME_SAMPLES * 2
        private const val FLAG_AUDIO: Byte = 1
        /** Mais de 200 ms esperando para tocar: o que chega a mais é descartado. */
        private const val MAX_QUEUED_FRAMES = 10
    }
}
