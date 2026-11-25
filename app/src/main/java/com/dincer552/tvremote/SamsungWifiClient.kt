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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

sealed class SamsungWifiState {
    data object Disconnected : SamsungWifiState()
    data object Connecting : SamsungWifiState()
    data object WaitingForApproval : SamsungWifiState()
    data object Connected : SamsungWifiState()
    data class Reconnecting(val attempt: Int, val delaySeconds: Int, val reason: String) : SamsungWifiState()
    data class Failed(val reason: String) : SamsungWifiState()
}

/**
 * Samsung Tizen local-network remote protocol.
 * Reconnection is limited to previously approved sessions. First-time pairing
 * failures are left for the user to retry so the TV is not spammed with prompts.
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
        // Samsung TVs can silently drop idle WebSockets on busy home networks.
        // Periodic protocol ping detects dead links before the user next presses a key.
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val infoHttp = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val sessionId = AtomicLong(0)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var host: String? = null
    @Volatile private var token: String? = null
    @Volatile private var approved = false
    @Volatile private var retryCount = 0
    @Volatile private var retryTask: ScheduledFuture<*>? = null
    @Volatile private var handshakeTask: ScheduledFuture<*>? = null
    @Volatile private var closed = false

    fun connect(ip: String, savedToken: String?) {
        val cleanIp = ip.trim()
        val octets = cleanIp.split('.')
        val validIp = octets.size == 4 && octets.all { part ->
            part.toIntOrNull()?.let { it in 0..255 } == true
        }
        if (!validIp) {
            onState(SamsungWifiState.Failed("Geçerli bir TV IPv4 adresi gir."))
            return
        }
        closed = false
        val generation = sessionId.incrementAndGet()
        cancelPendingTasks()
        socket?.cancel()
        socket = null
        host = cleanIp
        token = savedToken?.takeIf { it.isNotBlank() }
        approved = !token.isNullOrBlank()
        retryCount = 0
        onState(SamsungWifiState.Connecting)
        attemptConnect(generation)
    }

    private fun attemptConnect(generation: Long) {
        if (!isCurrent(generation)) return
        scheduler.execute {
            if (!isCurrent(generation)) return@execute

            // TV metadata is useful for diagnostics, but it is not a prerequisite
            // for the remote WebSocket service. Some firmwares omit or delay port 8001.
            val info = fetchTvInfo(host ?: return@execute)
            if (!isCurrent(generation)) return@execute
            onTvInfo(info)

            val cleanIp = host ?: return@execute
            val displayName = Base64.getEncoder().encodeToString("TV Kumanda".toByteArray(Charsets.UTF_8))
            val url = "wss://$cleanIp:8002/api/v2/channels/samsung.remote.control?name=" +
                URLEncoder.encode(displayName, "UTF-8") +
                (token?.let { "&token=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val request = Request.Builder().url(url).build()
            socket = http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!isCurrent(generation)) {
                        webSocket.close(1000, "stale session")
                        return
                    }
                    if (approved) {
                        onState(SamsungWifiState.Connecting)
                        handshakeTask?.cancel(false)
                        handshakeTask = scheduler.schedule({
                            if (isCurrent(generation) && approved) {
                                invalidateSession(generation)
                                token = null
                                approved = false
                                onToken("")
                                onState(SamsungWifiState.Failed(
                                    "Kayıtlı bağlantı anahtarı doğrulanamadı. TV eşleşmesini kaldırıp yeniden izin ver."
                                ))
                                webSocket.close(4000, "Samsung token handshake timeout")
                            }
                        }, 12, TimeUnit.SECONDS)
                    } else {
                        onState(SamsungWifiState.WaitingForApproval)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!isCurrent(generation)) return
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (json.optString("event")) {
                        "ms.channel.connect" -> {
                            handshakeTask?.cancel(false)
                            retryCount = 0
                            val newToken = json.optJSONObject("data")?.optString("token").orEmpty()
                            if (newToken.isNotBlank()) {
                                token = newToken
                                approved = true
                                onToken(newToken)
                            }
                            approved = true
                            onState(SamsungWifiState.Connected)
                        }
                        "ms.channel.unauthorized" -> {
                            invalidateSession(generation)
                            approved = false
                            token = null
                            onToken("")
                            onState(SamsungWifiState.Failed(
                                "TV kayıtlı bağlantı anahtarını kabul etmedi. TV'de eşleşmeyi kaldırıp yeniden bağlan."
                            ))
                        }
                        "ms.channel.timeOut" -> {
                            invalidateSession(generation)
                            onState(SamsungWifiState.Failed("TV eşleştirme isteğinin süresi doldu. Yeniden bağlan."))
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!isCurrent(generation)) return
                    socket = null
                    handshakeTask?.cancel(false)
                    if (response?.code == 401 || response?.code == 403) {
                        invalidateSession(generation)
                        token = null
                        approved = false
                        onToken("")
                        onState(SamsungWifiState.Failed("TV bağlantıyı reddetti (${response.code}). Eşleştirmeyi sıfırlayıp yeniden bağlan."))
                    } else {
                        scheduleReconnect(generation, t.message ?: "Wi-Fi bağlantısı kesildi.")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isCurrent(generation)) return
                    socket = null
                    handshakeTask?.cancel(false)
                    if (approved) scheduleReconnect(generation, "TV bağlantıyı kapattı ($code).")
                    else onState(SamsungWifiState.Failed("İlk eşleştirme tamamlanmadan TV bağlantıyı kapattı. Yeniden bağlanıp TV'de İzin Ver seç."))
                }
            })
        }
    }

    private fun scheduleReconnect(generation: Long, reason: String) {
        if (!isCurrent(generation) || !approved) {
            if (isCurrent(generation)) onState(SamsungWifiState.Failed(reason))
            return
        }
        retryCount += 1
        val delay = when (retryCount) {
            1 -> 2
            2 -> 4
            3 -> 8
            4 -> 15
            else -> 30
        }
        onState(SamsungWifiState.Reconnecting(retryCount, delay, reason))
        retryTask?.cancel(false)
        retryTask = scheduler.schedule({
            if (isCurrent(generation)) attemptConnect(generation)
        }, delay.toLong(), TimeUnit.SECONDS)
    }

    private fun invalidateSession(generation: Long) {
        if (sessionId.compareAndSet(generation, generation + 1)) {
            cancelPendingTasks()
            socket?.cancel()
            socket = null
        }
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
        val sent = ws.send(payload)
        if (!sent) {
            val generation = sessionId.get()
            scheduleReconnect(generation, "Komut gönderilemedi; WebSocket hazır değil.")
        }
        return sent
    }

    fun wakeTv(mac: String, ip: String?) {
        Thread {
            runCatching {
                val parts = mac.trim().split(':', '-')
                require(parts.size == 6)
                val address = ByteArray(6) { i -> parts[i].toInt(16).toByte() }
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
        sessionId.incrementAndGet()
        cancelPendingTasks()
        socket?.close(1000, "disconnect")
        socket = null
        approved = false
        if (resetState) onState(SamsungWifiState.Disconnected)
    }

    fun close() {
        closed = true
        disconnect()
        scheduler.shutdownNow()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        infoHttp.dispatcher.executorService.shutdown()
        infoHttp.connectionPool.evictAll()
    }

    private fun cancelPendingTasks() {
        retryTask?.cancel(false)
        retryTask = null
        handshakeTask?.cancel(false)
        handshakeTask = null
    }

    private fun isCurrent(generation: Long): Boolean = !closed && sessionId.get() == generation

    private fun fetchTvInfo(ip: String): String? = runCatching {
        infoHttp.newCall(Request.Builder().url("http://$ip:8001/api/v2/").build()).execute().use { response ->
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
            val multicastLock = wifi.createMulticastLock("tvremote-ssdp").apply {
                setReferenceCounted(false)
                acquire()
            }
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
