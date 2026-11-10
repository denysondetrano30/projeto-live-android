package live.projeto.camera

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkInfo {
    /**
     * Endereço IPv4 do celular no Wi-Fi (interface wlan), por exemplo "192.168.0.15".
     * Devolve null se o Wi-Fi estiver desligado ou sem rede.
     */
    fun wifiIPv4(): String? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
        } catch (e: Exception) {
            return null
        }
        val candidates = interfaces.filter {
            try {
                it.isUp && !it.isLoopback
            } catch (e: Exception) {
                false
            }
        }
        // Primeiro o Wi-Fi (wlan0, wlan1...), depois qualquer outra rede local (como um cabo de rede).
        val ordered = candidates.filter { it.name.startsWith("wlan") } +
            candidates.filter { it.name.startsWith("eth") || it.name.startsWith("ap") }
        for (net in ordered) {
            for (address in net.inetAddresses.toList()) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress
                }
            }
        }
        return null
    }
}
