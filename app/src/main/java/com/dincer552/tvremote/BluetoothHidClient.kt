package com.dincer552.tvremote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

data class BluetoothHidState(
    val code: Code = Code.IDLE,
    val message: String = "Bluetooth bağlantısı henüz hazırlanmadı.",
    val hostName: String = ""
) {
    enum class Code {
        IDLE, UNSUPPORTED, BLUETOOTH_OFF, WAITING_FOR_PROFILE,
        REGISTERING, READY_TO_PAIR, WAITING_FOR_HOST, CONNECTING, CONNECTED, FAILED
    }
}

class BluetoothHidClient(
    private val context: Context,
    private val onState: (BluetoothHidState) -> Unit,
    private val onReadyForPairing: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var connectedDevice: BluetoothDevice? = null
    @Volatile private var registered = false
    @Volatile private var profileRequested = false
    @Volatile private var registrationPending = false
    @Volatile private var closed = false

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val service = proxy as? BluetoothHidDevice
            if (service == null) {
                publish(BluetoothHidState(BluetoothHidState.Code.FAILED, "Android Bluetooth HID servisi açılamadı."))
                return
            }
            hid = service
            profileRequested = false
            registerHidApp(service)
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            registered = false
            profileRequested = false
            connectedDevice = null
            publish(BluetoothHidState(BluetoothHidState.Code.WAITING_FOR_PROFILE, "Bluetooth HID servisi kesildi. Yeniden başlat düğmesine bas."))
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            registrationPending = false
            if (isRegistered) {
                publish(BluetoothHidState(
                    BluetoothHidState.Code.READY_TO_PAIR,
                    "Telefon Bluetooth klavye olarak yayınlanıyor. TV'de Bluetooth cihazlarını yenile."
                ))
                onReadyForPairing()
            } else {
                connectedDevice = null
                profileRequested = false
                publish(BluetoothHidState(
                    BluetoothHidState.Code.FAILED,
                    "Android HID kaydını kapattı. Uygulamayı ön planda tutup yeniden başlat."
                ))
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    val name = runCatching { device.name.orEmpty() }.getOrDefault("")
                    publish(BluetoothHidState(
                        BluetoothHidState.Code.CONNECTED,
                        if (name.isBlank()) "TV Bluetooth HID bağlantısı kuruldu." else "Bluetooth HID bağlantısı kuruldu: $name",
                        name
                    ))
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    publish(BluetoothHidState(BluetoothHidState.Code.CONNECTING, "TV ile Bluetooth eşleşmesi kuruluyor."))
                }
                BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING -> {
                    if (connectedDevice?.address == runCatching { device.address }.getOrNull()) connectedDevice = null
                    publish(BluetoothHidState(
                        if (registered) BluetoothHidState.Code.WAITING_FOR_HOST else BluetoothHidState.Code.REGISTERING,
                        "TV bağlantısı kesildi. Telefon görünür durumdaysa TV'den yeniden bağlanabilirsin."
                    ))
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val expectedSize = when (id.toInt()) {
                REPORT_KEYBOARD -> 8
                REPORT_CONSUMER -> 2
                else -> 0
            }
            val size = maxOf(expectedSize, bufferSize).coerceIn(0, 64)
            val service = hid ?: return
            if (hasConnectPermission() && size > 0) {
                runCatching { service.replyReport(device, type, id, ByteArray(size)) }
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            val service = hid ?: return
            if (hasConnectPermission()) {
                runCatching { service.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ) }
            }
        }
    }

    fun start() {
        closed = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            publish(BluetoothHidState(BluetoothHidState.Code.UNSUPPORTED, "Bluetooth HID Device için Android 9 veya üzeri gerekir."))
            return
        }
        if (adapter == null) {
            publish(BluetoothHidState(BluetoothHidState.Code.UNSUPPORTED, "Bu telefonda Bluetooth donanımı bulunamadı."))
            return
        }
        if (!hasConnectPermission()) {
            publish(BluetoothHidState(BluetoothHidState.Code.FAILED, "Yakındaki Cihazlar izni verilmedi."))
            return
        }
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            publish(BluetoothHidState(BluetoothHidState.Code.BLUETOOTH_OFF, "Telefonun Bluetooth özelliğini aç."))
            return
        }
        if (registered) {
            publish(BluetoothHidState(BluetoothHidState.Code.READY_TO_PAIR, "Bluetooth klavye hazır. TV'de cihazları yenile."))
            onReadyForPairing()
            return
        }
        val existingService = hid
        if (existingService != null) {
            if (registrationPending) {
                publish(BluetoothHidState(BluetoothHidState.Code.REGISTERING, "Bluetooth klavye kaydı bekleniyor…"))
            } else {
                registerHidApp(existingService)
            }
            return
        }
        if (profileRequested) {
            publish(BluetoothHidState(BluetoothHidState.Code.WAITING_FOR_PROFILE, "Android Bluetooth HID profili hazırlanıyor…"))
            return
        }
        profileRequested = true
        publish(BluetoothHidState(BluetoothHidState.Code.WAITING_FOR_PROFILE, "Android Bluetooth HID profili başlatılıyor…"))
        val accepted = runCatching {
            adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        }.getOrDefault(false)
        if (!accepted) {
            registrationPending = false
            profileRequested = false
            publish(BluetoothHidState(
                BluetoothHidState.Code.FAILED,
                "Telefonun Bluetooth yığını HID Device profilini açmadı. Bu, bazı üretici yazılımlarında desteklenmeyebilir."
            ))
        }
    }

    private fun registerHidApp(service: BluetoothHidDevice) {
        if (!hasConnectPermission()) {
            publish(BluetoothHidState(BluetoothHidState.Code.FAILED, "Bluetooth bağlantı izni eksik."))
            return
        }
        if (registrationPending) return
        registrationPending = true
        publish(BluetoothHidState(BluetoothHidState.Code.REGISTERING, "TV Kumanda HID klavye profili kaydediliyor…"))
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "TV Kumanda",
            "Samsung TV Bluetooth Remote",
            "TV Kumanda",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            HID_REPORT_DESCRIPTOR
        )
        val accepted = runCatching {
            service.registerApp(sdp, null, null, context.mainExecutor, callback)
        }.getOrDefault(false)
        if (!accepted) {
            profileRequested = false
            publish(BluetoothHidState(
                BluetoothHidState.Code.FAILED,
                "Android HID profili kayıt isteğini kabul etmedi. Bluetooth'u kapatıp açarak tekrar dene."
            ))
        }
    }

    fun sendKey(key: String): Boolean {
        if (!hasConnectPermission() || !registered) return false
        val service = hid ?: return false
        val device = connectedDevice ?: runCatching {
            service.connectedDevices.firstOrNull()
        }.getOrNull() ?: return false

        val keyboardUsage = when (key) {
            "Up" -> 0x52
            "Down" -> 0x51
            "Left" -> 0x50
            "Right" -> 0x4F
            "OK" -> 0x28
            "Back" -> 0x29
            "Number 1" -> 0x1E
            "Menu", "More" -> 0x65
            else -> null
        }
        val consumerUsage = when (key) {
            "Power" -> 0x0030
            "Source" -> 0x0089
            "Home" -> 0x0223
            "CH+" -> 0x009C
            "CH-" -> 0x009D
            "Mute" -> 0x00E2
            "VOL+" -> 0x00E9
            "VOL-" -> 0x00EA
            else -> null
        }

        val reportId: Int
        val pressed: ByteArray
        val released: ByteArray
        if (keyboardUsage != null) {
            reportId = REPORT_KEYBOARD
            pressed = byteArrayOf(0, 0, keyboardUsage.toByte(), 0, 0, 0, 0, 0)
            released = ByteArray(8)
        } else if (consumerUsage != null) {
            reportId = REPORT_CONSUMER
            pressed = byteArrayOf((consumerUsage and 0xFF).toByte(), ((consumerUsage shr 8) and 0xFF).toByte())
            released = ByteArray(2)
        } else {
            return false
        }

        val sent = runCatching { service.sendReport(device, reportId, pressed) }.getOrDefault(false)
        if (sent) {
            mainHandler.postDelayed({
                if (hasConnectPermission()) {
                    runCatching { hid?.sendReport(device, reportId, released) }
                }
            }, 75L)
        }
        return sent
    }

    fun close() {
        closed = true
        val service = hid
        hid = null
        registered = false
        profileRequested = false
        registrationPending = false
        connectedDevice = null
        if (service != null && hasConnectPermission()) {
            runCatching { service.unregisterApp() }
            runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, service) }
        }
    }

    private fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun publish(state: BluetoothHidState) {
        if (!closed) mainHandler.post { if (!closed) onState(state) }
    }

    companion object {
        private const val REPORT_KEYBOARD = 1
        private const val REPORT_CONSUMER = 2

        // Report 1: standard 8-byte keyboard; Report 2: 16-bit Consumer Control usage.
        private val HID_REPORT_DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01,
            0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00,
            0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
            0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
            0xC0.toByte(),
            0x05, 0x0C, 0x09, 0x01, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x02,
            0x15, 0x00, 0x26, 0xFF.toByte(), 0x03, 0x19, 0x00, 0x2A, 0xFF.toByte(), 0x03,
            0x75, 0x10, 0x95.toByte(), 0x01, 0x81.toByte(), 0x00, 0xC0.toByte()
        )
    }
}
