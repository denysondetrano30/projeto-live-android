package live.projeto.camera

import android.os.SystemClock

/**
 * Relógio do celular usado na sincronia com o OBS (igual ao app do iPhone, PhoneClock.swift).
 *
 * Os quadros da câmera vêm com a hora de um destes relógios (depende do aparelho:
 * SENSOR_INFO_TIMESTAMP_SOURCE): o "tempo real" (com o tempo de tela desligada) ou o "monotônico".
 * O app escolhe o mesmo da câmera ([useRealtime]) e responde `TIME` com ele. Cada quadro sai com o
 * PTS do MPEG-TS igual a esse relógio (90 kHz). Com isso o OBS sabe a hora exata, no relógio do PC,
 * em que cada quadro foi filmado.
 */
object PhoneClock {
    @Volatile
    var useRealtime = false

    /** Agora, em nanossegundos, no relógio da câmera. */
    fun nowNanos(): Long = if (useRealtime) SystemClock.elapsedRealtimeNanos() else System.nanoTime()

    /** Converte uma hora do relógio monotônico (System.nanoTime, como o do microfone) para o da câmera. */
    fun fromMonotonic(monotonicNanos: Long): Long =
        if (useRealtime) monotonicNanos + (SystemClock.elapsedRealtimeNanos() - System.nanoTime()) else monotonicNanos

    /** PTS do MPEG-TS (90 kHz, 33 bits). */
    fun pts90k(nanos: Long): Long = (nanos / 100_000L * 9L + (nanos % 100_000L) * 9L / 100_000L) and 0x1_FFFF_FFFFL

    /** Resposta ao `TIME <hora do PC>` do OBS: `TIME <hora do PC> <hora do celular>`. */
    fun timeReply(line: String): String? {
        if (!line.startsWith("TIME ")) return null
        val parts = line.split(" ")
        if (parts.size < 2 || parts[1].toLongOrNull() == null) return null
        return "TIME ${parts[1]} ${nowNanos()}"
    }
}
