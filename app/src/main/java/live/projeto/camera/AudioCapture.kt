package live.projeto.camera

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import kotlin.math.abs

/**
 * Microfone do celular (igual ao AACEncoder.swift + o microfone do IntercomLink.swift).
 *
 * Um microfone ligado no USB-C ou no fone (como o receptor de um microfone sem fio) é preferido.
 * O som vai para dois lugares:
 * - a câmera: AAC com cabeçalho ADTS e o PTS no relógio da câmera ([PhoneClock]), para o OBS tocar
 *   junto com a imagem. Enquanto a pessoa fala no intercom ([silent]), vai silêncio.
 * - o intercom: quadros de 20 ms em 16 kHz ([onIntercom]).
 */
class AudioCapture(private val context: Context) {
    /** Quadro AAC (com ADTS) e o PTS (90 kHz). Chamado na thread do microfone. */
    @Volatile var onAac: ((ByteArray, Long) -> Unit)? = null
    /** 320 amostras (20 ms) em 16 kHz. Chamado na thread do microfone. */
    @Volatile var onIntercom: ((ShortArray) -> Unit)? = null
    /** true = a câmera manda silêncio (alguém está falando no intercom). */
    @Volatile var silent = false

    private var thread: Thread? = null
    @Volatile private var running = false

    @Synchronized
    fun start() {
        if (thread != null) return
        running = true
        thread = Thread({ loop() }, "projetolive-mic").also { it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, CHUNK * 2 * 4)
            )
        } catch (e: Exception) {
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        preferExternal(record)
        val encoder = makeEncoder()
        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            encoder?.release()
            return
        }

        val pcm = ShortArray(CHUNK)
        val bytes = ByteArray(CHUNK * 2)
        val stamp = AudioTimestamp()
        var framesRead = 0L
        var anchorNanos = Long.MIN_VALUE
        var samplesIn = 0L
        val down = ShortArray(CHUNK / 3)
        val intercom = ShortArray(INTERCOM_FRAME)
        var intercomFill = 0
        val info = MediaCodec.BufferInfo()

        while (running) {
            val count = record.read(pcm, 0, CHUNK)
            if (count <= 0) {
                if (count < 0) break
                continue
            }

            // Hora (relógio monotônico) da primeira amostra deste pedaço.
            val first = if (record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                stamp.nanoTime + (framesRead - stamp.framePosition) * 1_000_000_000L / RATE
            } else {
                System.nanoTime() - count * 1_000_000_000L / RATE
            }
            framesRead += count
            if (anchorNanos == Long.MIN_VALUE ||
                abs(anchorNanos + samplesIn * 1_000_000_000L / RATE - first) > MAX_DRIFT_NANOS
            ) {
                anchorNanos = first - samplesIn * 1_000_000_000L / RATE
            }
            val chunkNanos = anchorNanos + samplesIn * 1_000_000_000L / RATE
            samplesIn += count

            // Intercom: 48 kHz → 16 kHz (média de 3 amostras), em quadros de 320.
            val sink = onIntercom
            if (sink != null) {
                val n = count / 3
                for (i in 0 until n) {
                    down[i] = ((pcm[i * 3] + pcm[i * 3 + 1] + pcm[i * 3 + 2]) / 3).toShort()
                }
                var i = 0
                while (i < n) {
                    val take = minOf(INTERCOM_FRAME - intercomFill, n - i)
                    System.arraycopy(down, i, intercom, intercomFill, take)
                    intercomFill += take
                    i += take
                    if (intercomFill == INTERCOM_FRAME) {
                        sink(intercom.copyOf())
                        intercomFill = 0
                    }
                }
            } else {
                intercomFill = 0
            }

            // Câmera: AAC.
            if (encoder != null && onAac != null) {
                val mute = silent
                for (i in 0 until count) {
                    val v = if (mute) 0 else pcm[i].toInt()
                    bytes[i * 2] = (v and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                val ptsUs = PhoneClock.fromMonotonic(chunkNanos) / 1000
                try {
                    val index = encoder.dequeueInputBuffer(20_000)
                    if (index >= 0) {
                        val buffer = encoder.getInputBuffer(index)!!
                        buffer.clear()
                        val size = minOf(buffer.remaining(), count * 2)
                        buffer.put(bytes, 0, size)
                        encoder.queueInputBuffer(index, 0, size, ptsUs, 0)
                    }
                    drain(encoder, info)
                } catch (e: Exception) {
                    // codificador com problema: segue só com o intercom
                }
            }
        }

        try {
            record.stop()
        } catch (_: Exception) {
        }
        record.release()
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        encoder?.release()
    }

    private fun drain(encoder: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            val buffer = encoder.getOutputBuffer(index)
            if (buffer != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                val frame = ByteArray(info.size + 7)
                writeAdts(frame, info.size + 7)
                buffer.position(info.offset)
                buffer.get(frame, 7, info.size)
                onAac?.invoke(frame, PhoneClock.pts90k(info.presentationTimeUs * 1000))
            }
            encoder.releaseOutputBuffer(index, false)
        }
    }

    private fun makeEncoder(): MediaCodec? = try {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * 2 * 2)
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
            it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }
    } catch (e: Exception) {
        null
    }

    /** Microfone ligado no USB-C ou no fone tem preferência. */
    private fun preferExternal(record: AudioRecord) {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val preferred = listOf(
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET
        )
        val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        for (type in preferred) {
            val device = inputs.firstOrNull { it.type == type } ?: continue
            record.preferredDevice = device
            return
        }
    }

    companion object {
        const val RATE = 48_000
        private const val CHUNK = 960 // 20 ms
        private const val INTERCOM_FRAME = 320
        private const val MAX_DRIFT_NANOS = 50_000_000L

        /** Cabeçalho ADTS (7 bytes) de um quadro AAC-LC, 48 kHz, mono. */
        private fun writeAdts(out: ByteArray, length: Int) {
            val profile = 1 // AAC-LC
            val frequency = 3 // 48 kHz
            val channels = 1
            out[0] = 0xFF.toByte()
            out[1] = 0xF1.toByte()
            out[2] = ((profile shl 6) or (frequency shl 2) or (channels shr 2)).toByte()
            out[3] = (((channels and 3) shl 6) or ((length shr 11) and 3)).toByte()
            out[4] = ((length shr 3) and 0xFF).toByte()
            out[5] = (((length and 7) shl 5) or 0x1F).toByte()
            out[6] = 0xFC.toByte()
        }
    }
}
