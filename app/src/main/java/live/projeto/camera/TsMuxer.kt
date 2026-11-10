package live.projeto.camera

import java.io.ByteArrayOutputStream

/**
 * Empacota vídeo H.264 e som AAC em MPEG-TS (pacotes de 188 bytes), igual ao app do iPhone
 * (TSMuxer em USBLink.swift): vídeo no PID 0x100 (com o relógio PCR), som no PID 0x101, PAT/PMT a
 * cada quadro-chave. O PTS vem no relógio do celular ([PhoneClock]).
 */
class TsMuxer {
    private val continuity = HashMap<Int, Int>()
    private val lock = Any()

    fun reset() = synchronized(lock) { continuity.clear() }

    /** Um quadro de vídeo (Annex B, com SPS/PPS na frente dos quadros-chave). */
    fun muxVideo(accessUnit: ByteArray, pts: Long, keyFrame: Boolean): ByteArray = synchronized(lock) {
        val out = ByteArrayOutputStream(accessUnit.size + accessUnit.size / 180 * 4 + 1024)
        if (keyFrame) {
            out.write(psiPacket(PAT_PID, PAT_SECTION))
            out.write(psiPacket(PMT_PID, PMT_SECTION))
        }
        val pes = ByteArrayOutputStream(accessUnit.size + 14)
        pes.write(byteArrayOf(0x00, 0x00, 0x01, 0xE0.toByte(), 0x00, 0x00, 0x84.toByte(), 0x80.toByte(), 0x05))
        pes.write(timestamp(pts))
        pes.write(accessUnit)
        // O relógio de referência (PCR) vai um pouco antes do quadro.
        val pcr = if (pts > 3_000) pts - 3_000 else 0
        out.write(packetize(VIDEO_PID, pes.toByteArray(), pcr, keyFrame))
        out.toByteArray()
    }

    /** Um quadro de som AAC (com cabeçalho ADTS). */
    fun muxAudio(adts: ByteArray, pts: Long): ByteArray = synchronized(lock) {
        val length = 3 + 5 + adts.size
        val pes = ByteArrayOutputStream(adts.size + 14)
        pes.write(
            byteArrayOf(
                0x00, 0x00, 0x01, 0xC0.toByte(), ((length shr 8) and 0xFF).toByte(), (length and 0xFF).toByte(),
                0x80.toByte(), 0x80.toByte(), 0x05
            )
        )
        pes.write(timestamp(pts))
        pes.write(adts)
        packetize(AUDIO_PID, pes.toByteArray(), null, false)
    }

    private fun nextContinuity(pid: Int): Int {
        val value = continuity[pid] ?: 0
        continuity[pid] = (value + 1) and 0x0F
        return value
    }

    private fun psiPacket(pid: Int, section: ByteArray): ByteArray {
        val packet = ByteArray(PACKET_SIZE) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = (0x40 or ((pid shr 8) and 0x1F)).toByte()
        packet[2] = (pid and 0xFF).toByte()
        packet[3] = (0x10 or nextContinuity(pid)).toByte()
        packet[4] = 0x00 // pointer_field
        System.arraycopy(section, 0, packet, 5, section.size)
        return packet
    }

    private fun packetize(pid: Int, payload: ByteArray, pcr: Long?, randomAccess: Boolean): ByteArray {
        val out = ByteArrayOutputStream(payload.size / 184 * 188 + 376)
        var offset = 0
        var first = true
        while (offset < payload.size) {
            val adaptation = ByteArrayOutputStream()
            var hasAdaptation = false
            if (first && pcr != null) {
                hasAdaptation = true
                adaptation.write(0x10 or (if (randomAccess) 0x40 else 0x00)) // PCR (+ acesso aleatório)
                adaptation.write(pcrBytes(pcr))
            }
            val headerSpace = if (hasAdaptation) 1 + adaptation.size() else 0
            var space = 184 - headerSpace
            val remaining = payload.size - offset
            if (remaining < space) {
                val stuffing = space - remaining
                if (hasAdaptation) {
                    repeat(stuffing) { adaptation.write(0xFF) }
                } else {
                    hasAdaptation = true
                    if (stuffing >= 2) {
                        adaptation.write(0x00)
                        repeat(stuffing - 2) { adaptation.write(0xFF) }
                    }
                }
                space = remaining
            }
            out.write(0x47)
            out.write((if (first) 0x40 else 0x00) or ((pid shr 8) and 0x1F))
            out.write(pid and 0xFF)
            out.write((if (hasAdaptation) 0x30 else 0x10) or nextContinuity(pid))
            if (hasAdaptation) {
                out.write(adaptation.size())
                out.write(adaptation.toByteArray())
            }
            out.write(payload, offset, space)
            offset += space
            first = false
        }
        return out.toByteArray()
    }

    companion object {
        private const val PACKET_SIZE = 188
        private const val PAT_PID = 0x0000
        private const val PMT_PID = 0x1000
        private const val VIDEO_PID = 0x0100
        private const val AUDIO_PID = 0x0101

        private fun timestamp(pts: Long): ByteArray {
            val value = pts and 0x1_FFFF_FFFFL
            return byteArrayOf(
                (0x21L or ((value shr 29) and 0x0E)).toByte(),
                ((value shr 22) and 0xFF).toByte(),
                (((value shr 14) and 0xFE) or 0x01).toByte(),
                ((value shr 7) and 0xFF).toByte(),
                (((value shl 1) and 0xFE) or 0x01).toByte()
            )
        }

        private fun pcrBytes(pcr: Long): ByteArray {
            val base = pcr and 0x1_FFFF_FFFFL
            return byteArrayOf(
                ((base shr 25) and 0xFF).toByte(),
                ((base shr 17) and 0xFF).toByte(),
                ((base shr 9) and 0xFF).toByte(),
                ((base shr 1) and 0xFF).toByte(),
                (((base and 0x01) shl 7) or 0x7E).toByte(),
                0x00
            )
        }

        private val PAT_SECTION: ByteArray = withCrc(
            intArrayOf(0x00, 0xB0, 0x0D, 0x00, 0x01, 0xC1, 0x00, 0x00, 0x00, 0x01, 0xF0, 0x00)
        )

        private val PMT_SECTION: ByteArray = withCrc(
            intArrayOf(
                0x02, 0xB0, 0x17, 0x00, 0x01, 0xC1, 0x00, 0x00,
                0xE1, 0x00, // PCR no PID do vídeo (0x100)
                0xF0, 0x00, // sem descritores do programa
                0x1B, 0xE1, 0x00, 0xF0, 0x00, // H.264 no PID 0x100
                0x0F, 0xE1, 0x01, 0xF0, 0x00 // som AAC (ADTS) no PID 0x101
            )
        )

        /** CRC-32 do MPEG-2 (polinômio 0x04C11DB7, sem inversão). */
        private fun withCrc(section: IntArray): ByteArray {
            var crc = 0xFFFF_FFFFL
            for (byte in section) {
                crc = crc xor (byte.toLong() shl 24)
                repeat(8) {
                    crc = if (crc and 0x8000_0000L != 0L) ((crc shl 1) xor 0x04C1_1DB7L) and 0xFFFF_FFFFL
                    else (crc shl 1) and 0xFFFF_FFFFL
                }
            }
            val out = ByteArray(section.size + 4)
            section.forEachIndexed { i, v -> out[i] = v.toByte() }
            out[section.size] = (crc shr 24).toByte()
            out[section.size + 1] = (crc shr 16).toByte()
            out[section.size + 2] = (crc shr 8).toByte()
            out[section.size + 3] = crc.toByte()
            return out
        }
    }
}
