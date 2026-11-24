package com.dincer552.tvremote

import android.content.Context
import android.net.wifi.WifiManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

sealed class SamsungWifiState {
    data object Disconnected : SamsungWifiState()
    data object Connecting : SamsungWifiState()
    data object WaitingForApproval : SamsungWifiState()
    data object Connected : SamsungWifiState()
    data class Failed(val reason: String) : SamsungWifiState()
}

/**
 * Samsung Tizen local-network remote protocol.
 * TLS certificate relaxation is isolated to this TV WebSocket client only.
 * No cloud service or external account is used.
 */
class SamsungWifiClient(
    private val onState: (SamsungWifiState) -> Unit,
    private val onToken: (String) -> Unit,
    private val onTvInfo: (String?) -> Unit
) {
    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    private val http = OkHttpClient.Builder()
        .sslSocketFactory(
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }.socketFactory,
            trustManager
        )
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var host: String? = null
    @Volatile private var token: String? = null
    @Volatile private var approved = false

    fun connect(ip: String, savedToken: String?) {
        val cleanIp = ip.trim()
        if (!cleanIp.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$"))) {
            onState(SamsungWifiState.Failed("Geçerli bir TV IPv4 adresi gir."))
            return
        }
        disconnect(false)
        host = cleanIp
        token = savedToken
        approved = !savedToken.isNullOrBlank()
        onState(SamsungWifiState.Connecting)
        Thread {
            val info = fetchTvInfo(cleanIp)
            if (info == null) {
                onState(SamsungWifiState.Failed("TV bulunamadı. Telefon ve TV aynı yerel ağda mı? IP adresini kontrol et."))
                return@Thread
            }
            onTvInfo(info)
            val name = Base64.getEncoder().encodeToString("TV Kumanda".toByteArray(Charsets.UTF_8))
            val url = "wss://$cleanIp:8002/api/v2/channels/samsung.remote.control?name=" +
                URLEncoder.encode(name, "UTF-8") +
                (savedToken?.takeIf { it.isNotBlank() }?.let { "&token=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val request = Request.Builder().url(url).build()
            socket = http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // A saved token is being validated by the TV. Do not show Connected
                    // until Samsung confirms ms.channel.connect.
                    if (approved) onState(SamsungWifiState.Connecting)
                    else onState(SamsungWifiState.WaitingForApproval)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (json.optString("event")) {
                        "ms.channel.connect" -> {
                            val newToken = json.optJSONObject("data")?.optString("token").orEmpty()
                            if (newToken.isNotBlank()) {
                                token = newToken
                                approved = true
                                onToken(newToken)
                            }
                            onState(SamsungWifiState.Connected)
                        }
                        "ms.channel.unauthorized" -> {
                            approved = false
                            token = null
                            onToken("")
                            onState(SamsungWifiState.Failed("TV kayıtlı bağlantı anahtarını kabul etmedi. TV eşleştirmeyi sıfırladıysa yeniden izin vermen gerekebilir."))
                        }
                        "ms.channel.timeOut" -> onState(SamsungWifiState.Failed("TV eşleştirme isteğinin süresi doldu. Yeniden bağlan."))
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    socket = null
                    onState(SamsungWifiState.Failed(t.message ?: "Wi-Fi bağlantısı kurulamadı."))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    socket = null
                    onState(SamsungWifiState.Disconnected)
                }
            })
        }.start()
    }

    fun sendKey(code: String): Boolean {
        val ws = socket ?: return false
        if (!approved) return false
        val params = JSONObject()
            .put("Cmd", "Click")
            .put("DataOfCmd", code)
            .put("Option", "false")
            .put("TypeOfRemote", "SendRemoteKey")
        val payload = JSONObject().put("method", "ms.remote.control").put("params", params).toString()
        return ws.send(payload)
    }

    fun wakeTv(mac: String, ip: String?) {
        Thread {
            runCatching {
                val parts = mac.trim().split(':', '-')
                require(parts.size == 6)
                val address = ByteArray(6) { parts[it].toInt(16).toByte() }
                val packet = ByteArray(102) { i ->
                    if (i < 6) 0xFF.toByte() else address[(i - 6) % 6]
                }
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val targets = mutableListOf("255.255.255.255")
                    val octets = ip?.split('.')
                    if (octets?.size == 4) targets += "${octets[0]}.${octets[1]}.${octets[2]}.255"
                    repeat(3) {
                        targets.forEach { target ->
                            listOf(9, 7).forEach { port ->
                                runCatching {
                                    val addr = InetAddress.getByName(target)
                                    socket.send(DatagramPacket(packet, packet.size, addr, port))
                                }
                            }
                        }
                        Thread.sleep(100)
                    }
                }
            }
        }.start()
    }

    fun disconnect(resetState: Boolean = true) {
        socket?.close(1000, "disconnect")
        socket = null
        if (resetState) onState(SamsungWifiState.Disconnected)
    }

    fun close() {
        disconnect()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun fetchTvInfo(ip: String): String? = runCatching {
        val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url("http://$ip:8001/api/v2/").build()).execute().use { response ->
            if (!response.isSuccessful) null else {
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val device = json.optJSONObject("device") ?: return@use null
                val name = device.optString("name", "Samsung TV")
                val model = device.optString("modelName", "")
                val mac = device.optString("wifiMac", "")
                "${name} · ${model}" + if (mac.isNotBlank()) " · MAC $mac" else ""
            }
        }
    }.getOrNull()

    companion object {
        fun discover(context: Context, timeoutMs: Int = 2500): List<String> {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val multicastLock = wifi.createMulticastLock("tvremote-ssdp").apply { setReferenceCounted(false); acquire() }
            val targets = listOf(
                "urn:samsung.com:device:RemoteControlReceiver:1",
                "urn:dial-multiscreen-org:service:dial:1",
                "urn:schemas-upnp-org:device:MediaRenderer:1"
            )
            val found = linkedSetOf<String>()
            try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 300
                val group = InetAddress.getByName("239.255.255.250")
                targets.forEach { target ->
                    val message = listOf(
                        "M-SEARCH * HTTP/1.1",
                        "HOST: 239.255.255.250:1900",
                        "MAN: \"ssdp:discover\"",
                        "MX: 2",
                        "ST: $target",
                        "", ""
                    ).joinToString("\r\n").toByteArray()
                    socket.send(DatagramPacket(message, message.size, group, 1900))
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        packet.address.hostAddress?.let { found += it }
                    } catch (_: SocketTimeoutException) { }
                }
            }
            return found.toList()
            } finally {
                if (multicastLock.isHeld) multicastLock.release()
            }
        }
    }
}
