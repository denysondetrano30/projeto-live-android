package live.projeto.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import java.io.ByteArrayOutputStream

/**
 * Comprime a imagem da câmera em H.264 (igual ao H264Encoder do iPhone): Full HD, 30 quadros por
 * segundo, 6 Mbps, um quadro-chave por segundo.
 *
 * A câmera desenha direto na [surface] do codificador; cada quadro sai com a hora do sensor da
 * câmera, que vira o PTS (relógio da câmera, veja [PhoneClock]). Cada quadro sai em Annex B com o
 * delimitador (AUD) na frente, e os quadros-chave levam SPS/PPS.
 */
class VideoEncoder(val width: Int, val height: Int) {
    /** Quadro pronto: dados, PTS (90 kHz) e se é quadro-chave. Chamado na thread do codificador. */
    @Volatile var output: ((ByteArray, Long, Boolean) -> Unit)? = null

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val surface: Surface
    private var thread: Thread? = null
    @Volatile private var running = false
    private var parameterSets: ByteArray = ByteArray(0)

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        // Sem quadros B: cada quadro sai assim que é filmado (menor atraso).
        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        format.setInteger(MediaFormat.KEY_LATENCY, 1)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Alguns aparelhos não aceitam o perfil High ou as opções de atraso: vai no básico.
            codec.reset()
            val basic = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            basic.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            basic.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            basic.setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            basic.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            codec.configure(basic, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        surface = codec.createInputSurface()
        codec.start()
        running = true
        thread = Thread({ drain() }, "projetolive-video").also { it.start() }
    }

    /** Pede um quadro-chave já (quando o OBS acaba de conectar). */
    fun requestKeyFrame() {
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(params)
        } catch (_: Exception) {
        }
    }

    fun release() {
        running = false
        thread?.join(500)
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        codec.release()
        surface.release()
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                codec.dequeueOutputBuffer(info, 50_000)
            } catch (e: Exception) {
                return
            }
            if (index < 0) continue
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val data = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(data)
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    parameterSets = data
                } else {
                    val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    val unit = ByteArrayOutputStream(data.size + parameterSets.size + 6)
                    // Delimitador de quadro (AUD), exigido no H.264 dentro de MPEG-TS.
                    unit.write(AUD)
                    if (key && !startsWithSps(data)) unit.write(parameterSets)
                    unit.write(data)
                    output?.invoke(unit.toByteArray(), PhoneClock.pts90k(info.presentationTimeUs * 1000), key)
                }
            }
            try {
                codec.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                return
            }
        }
    }

    private fun startsWithSps(data: ByteArray): Boolean {
        val offset = when {
            data.size > 4 && data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 0 && data[3].toInt() == 1 -> 4
            data.size > 3 && data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 1 -> 3
            else -> return false
        }
        return (data[offset].toInt() and 0x1F) == 7
    }

    companion object {
        const val FPS = 30
        private const val BITRATE = 6_000_000
        private val AUD = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())
    }
}
