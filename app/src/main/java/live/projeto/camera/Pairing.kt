package live.projeto.camera

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dados lidos do QR code de uma fonte "Celular (Projeto Live)" no OBS.
 *
 * Formato do QR code: `projetolive://pair?v=1&p=PORTA&k=SENHA&h=IP1,IP2&n=NOME-DA-FONTE`
 */
data class Pairing(val hosts: List<String>, val port: Int, val key: String, val sourceName: String) {

    /** Tenta primeiro o endereço do PC que está na mesma rede do celular. */
    fun orderedHosts(phoneIp: String?): List<String> {
        if (phoneIp == null) return hosts
        val prefix = phoneIp.split(".").take(3).joinToString(".") + "."
        val same = hosts.filter { it.startsWith(prefix) }
        return same + hosts.filter { !it.startsWith(prefix) }
    }

    companion object {
        fun parse(text: String): Pairing? {
            val uri = try {
                Uri.parse(text)
            } catch (e: Exception) {
                return null
            }
            if (uri.scheme != "projetolive" || uri.host != "pair") return null
            val port = uri.getQueryParameter("p")?.toIntOrNull() ?: return null
            if (port <= 0 || port >= 65536) return null
            val key = uri.getQueryParameter("k") ?: return null
            if (key.length < 10) return null
            val hosts = (uri.getQueryParameter("h") ?: return null).split(",").filter { it.isNotEmpty() }
            if (hosts.isEmpty()) return null
            val name = uri.getQueryParameter("n")?.takeIf { it.isNotEmpty() } ?: "OBS"
            return Pairing(hosts, port, key, name)
        }
    }
}

/** Guarda o pareamento no aparelho, para reconectar sozinho na próxima vez. */
object PairingStore {
    private const val PREFS = "projetolive"
    private const val KEY = "pairing"

    fun load(context: Context): Pairing? {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return try {
            val json = JSONObject(text)
            val hosts = json.getJSONArray("hosts")
            Pairing(
                List(hosts.length()) { hosts.getString(it) },
                json.getInt("port"),
                json.getString("key"),
                json.getString("sourceName")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun save(context: Context, pairing: Pairing?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (pairing == null) {
            editor.remove(KEY)
        } else {
            val json = JSONObject()
            json.put("hosts", JSONArray(pairing.hosts))
            json.put("port", pairing.port)
            json.put("key", pairing.key)
            json.put("sourceName", pairing.sourceName)
            editor.putString(KEY, json.toString())
        }
        editor.apply()
    }
}
