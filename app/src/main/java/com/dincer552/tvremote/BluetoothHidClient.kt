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
            registrationPending = false
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
            val expectedSize = if (id.toInt() == REPORT_KEYBOARD) 8 else 0
            val service = hid ?: return
            if (!hasConnectPermission()) return
            if (expectedSize == 0 || bufferSize < expectedSize) {
                runCatching { service.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ) }
            } else {
                // Respond with exactly the report length advertised in the HID descriptor.
                runCatching { service.replyReport(device, type, id, ByteArray(expectedSize)) }
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
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            publish(BluetoothHidState(BluetoothHidState.Code.UNSUPPORTED, "Bu telefonda Bluetooth donanımı bulunamadı."))
            return
        }
        if (!hasConnectPermission()) {
            publish(BluetoothHidState(BluetoothHidState.Code.FAILED, "Yakındaki Cihazlar izni verilmedi."))
            return
        }
        if (!runCatching { bluetoothAdapter.isEnabled }.getOrDefault(false)) {
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
            bluetoothAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
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
            registrationPending = false
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

        // QN85C e-Manual maps these standard keyboard usages to TV functions.
        val keyboardUsage = when (key) {
            "Power" -> 0x3A       // F1: Power
            "Menu" -> 0x3D        // F4: Settings screen
            "More" -> 0x3B        // F2: Settings / numeric / option pad
            "Home" -> 0x3E        // F5: Home screen
            "Source" -> 0x3F      // F6: Sources
            "Mute" -> 0x41        // F8: Mute
            "VOL-" -> 0x42        // F9: Volume down
            "VOL+" -> 0x43        // F10: Volume up
            "CH+" -> 0x44         // F11: Channel up
            "CH-" -> 0x45         // F12: Channel down
            "Up" -> 0x52
            "Down" -> 0x51
            "Left" -> 0x50
            "Right" -> 0x4F
            "OK" -> 0x28         // Enter
            "Back" -> 0x29       // Escape
            "Number 1" -> 0x1E
            else -> null
        }

        if (keyboardUsage == null) return false
        val reportId = REPORT_KEYBOARD
        val pressed = byteArrayOf(0, 0, keyboardUsage.toByte(), 0, 0, 0, 0, 0)
        val released = ByteArray(8)

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

        // Standard 8-byte keyboard report. The TV manual documents the F-key mapping
        // used below, so the app deliberately emulates a keyboard rather than a
        // proprietary SolarCell remote or an unrelated Consumer Control device.
        private val HID_REPORT_DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01,
            0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00,
            0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
            0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
            0xC0.toByte()
        )
    }
}
