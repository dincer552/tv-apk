@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.dincer552.tvremote

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private val RemoteBg = Color(0xFFEEEEEE)
private val RemoteInk = Color(0xFF252525)
private val RemoteMuted = Color(0xFFD4D4D4)
private val RemoteGreen = Color(0xFF20A879)

data class IrCommand(
    val frequency: Int,
    val pattern: String,
    val repeats: Int = 1,
    val description: String
)

data class RemoteKey(
    val id: String,
    val title: String,
    val symbol: String,
    val samsungLegacyCode: Long?,
    val samsung32Command: Int?
)

private val remoteKeys = listOf(
    RemoteKey("Power", "Güç", "⏻", 0xE0E040BF, 0x02),
    RemoteKey("Source", "Kaynak", "⇥", 0xE0E0807F, 0x01),
    RemoteKey("Menu", "Menü", "☰", 0xE0E058A7, null),
    RemoteKey("Home", "Ana ekran", "⌂", 0xE0E09E61, 0x79),
    RemoteKey("Number 1", "1 tuşu", "①", 0xE0E020DF, null),
    RemoteKey("Back", "Geri", "↶", 0xE0E01AE5, 0x58),
    RemoteKey("Up", "Yukarı", "•", 0xE0E006F9, 0x60),
    RemoteKey("Down", "Aşağı", "•", 0xE0E08679, 0x61),
    RemoteKey("Left", "Sol", "•", 0xE0E0A659, 0x65),
    RemoteKey("OK", "OK", "OK", 0xE0E016E9, 0x68),
    RemoteKey("Right", "Sağ", "•", 0xE0E046B9, 0x62),
    RemoteKey("CH+", "Kanal artır", "⌃", 0xE0E048B7, null),
    RemoteKey("CH-", "Kanal azalt", "⌄", 0xE0E008F7, null),
    RemoteKey("VOL+", "Ses artır", "+", 0xE0E0E01F, 0x07),
    RemoteKey("VOL-", "Ses azalt", "−", 0xE0E0D02F, 0x0B),
    RemoteKey("More", "Diğer seçenekler", "•••", 0xE0E0D22D, null),
    RemoteKey("Mute", "Sessiz", "⌁", 0xE0E0F00F, 0x0F)
)

private data class SamsungTiming(
    val headerMark: Int,
    val headerSpace: Int,
    val oneMark: Int,
    val oneSpace: Int,
    val zeroMark: Int,
    val zeroSpace: Int,
    val trailMark: Int,
    val gap: Int,
    val title: String
)

object SamsungIr {
    private val standard = SamsungTiming(4500, 4500, 560, 1690, 560, 560, 560, 45000, "Samsung 32-bit / 38 kHz")
    private val alternate = SamsungTiming(4611, 4433, 634, 1621, 634, 480, 632, 45000, "Samsung alternatif zamanlama / 38 kHz")
    private val samsung32Timing = SamsungTiming(4500, 4500, 550, 1650, 550, 550, 550, 46000, "Samsung32 adres 0x07 / 38 kHz")

    fun candidates(keyId: String): List<IrCommand> {
        val key = remoteKeys.firstOrNull { it.id == keyId } ?: return emptyList()
        val result = mutableListOf<IrCommand>()
        key.samsungLegacyCode?.let {
            result += buildFrame(it, standard, standard.title)
            result += buildFrame(it, alternate, alternate.title)
        }
        key.samsung32Command?.let {
            result += buildSamsung32(0x07, it)
        }
        return result
    }

    private fun buildFrame(code: Long, timing: SamsungTiming, description: String): IrCommand {
        val values = mutableListOf<Int>()
        values += timing.headerMark
        values += timing.headerSpace
        for (bit in 0 until 32) {
            values += if (((code shr bit) and 1L) == 1L) timing.oneMark else timing.zeroMark
            values += if (((code shr bit) and 1L) == 1L) timing.oneSpace else timing.zeroSpace
        }
        values += timing.trailMark
        values += timing.gap
        return IrCommand(38000, values.joinToString(","), 1, description)
    }

    private fun buildSamsung32(address: Int, command: Int): IrCommand {
        val addressByte = address and 0xFF
        val commandByte = command and 0xFF
        val bytes = intArrayOf(addressByte, addressByte, commandByte, commandByte.inv() and 0xFF)
        val values = mutableListOf<Int>()
        values += samsung32Timing.headerMark
        values += samsung32Timing.headerSpace
        bytes.forEach { byte ->
            for (bit in 0 until 8) {
                val one = ((byte shr bit) and 1) == 1
                values += if (one) samsung32Timing.oneMark else samsung32Timing.zeroMark
                values += if (one) samsung32Timing.oneSpace else samsung32Timing.zeroSpace
            }
        }
        values += samsung32Timing.trailMark
        values += samsung32Timing.gap
        return IrCommand(38000, values.joinToString(","), 1, samsung32Timing.title)
    }

    fun validateCustom(frequency: String, raw: String, repeats: String): IrCommand? {
        val hz = frequency.toIntOrNull() ?: return null
        val values = raw.split(Regex("[,;\\s]+")).filter { it.isNotBlank() }.map {
            it.toIntOrNull() ?: return null
        }
        if (hz !in 20000..60000 || values.size < 4 || values.size % 2 != 0) return null
        if (values.any { it !in 1..100000 }) return null
        if (values.sum() > 1_900_000) return null
        return IrCommand(hz, values.joinToString(","), (repeats.toIntOrNull() ?: 1).coerceIn(1, 5), "Özel kullanıcı sinyali")
    }
}

private enum class AppScreen {
    REMOTE, BUTTON_LIST, BUTTON_SETTINGS, IR_INFO, WIFI_SETUP, BLUETOOTH_SETUP,
    AC_BUTTON_LIST, AC_BUTTON_SETTINGS
}
private enum class ConnectionMode { WIFI, BLUETOOTH, IR }
private enum class DeviceMode { TV, AIR_CONDITIONER }

private fun wifiKeyCode(key: String): String? = when (key) {
    "Power" -> "KEY_POWER"
    "Source" -> "KEY_SOURCE"
    "Menu" -> "KEY_MENU"
    "Home" -> "KEY_HOME"
    "Number 1" -> "KEY_1"
    "Back" -> "KEY_RETURN"
    "Up" -> "KEY_UP"
    "Down" -> "KEY_DOWN"
    "Left" -> "KEY_LEFT"
    "OK" -> "KEY_ENTER"
    "Right" -> "KEY_RIGHT"
    "CH+" -> "KEY_CHUP"
    "CH-" -> "KEY_CHDOWN"
    "VOL+" -> "KEY_VOLUP"
    "VOL-" -> "KEY_VOLDOWN"
    "More" -> "KEY_TOOLS"
    "Mute" -> "KEY_MUTE"
    else -> null
}

class MainActivity : ComponentActivity() {
    private lateinit var ir: ConsumerIrManager
    private lateinit var wifiClient: SamsungWifiClient
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wifiPreferences by lazy { getSharedPreferences("tv_wifi_profile", Context.MODE_PRIVATE) }
    private val wifiState = mutableStateOf<SamsungWifiState>(SamsungWifiState.Disconnected)
    private val bluetoothState = mutableStateOf(BluetoothHidState())
    private val tvInfo = mutableStateOf("")
    private val discoveredIps = mutableStateOf<List<String>>(emptyList())
    private val preferences by lazy { getSharedPreferences("tv_remote_profile_v2", Context.MODE_PRIVATE) }
    private val acPreferences by lazy { getSharedPreferences("altus_ac_profile_v1", Context.MODE_PRIVATE) }
    private lateinit var bluetoothClient: BluetoothHidClient

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) beginBluetoothHid()
        else bluetoothState.value = BluetoothHidState(
            BluetoothHidState.Code.FAILED,
            "Bluetooth için Yakındaki Cihazlar izni gerekli. Android ayarlarından izin ver."
        )
    }
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startBluetoothHidRegistration()
        else bluetoothState.value = BluetoothHidState(BluetoothHidState.Code.BLUETOOTH_OFF, "Bluetooth açılmadı.")
    }
    private val discoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode > Activity.RESULT_CANCELED) {
            bluetoothState.value = BluetoothHidState(
                BluetoothHidState.Code.READY_TO_PAIR,
                "Telefon 5 dakika görünür. TV'de Bluetooth cihaz listesini yenile."
            )
        } else {
            bluetoothState.value = BluetoothHidState(
                BluetoothHidState.Code.FAILED,
                "Telefon görünür yapılmadı. TV'nin telefonu bulabilmesi için görünürlük izni gerekli."
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ir = getSystemService(CONSUMER_IR_SERVICE) as ConsumerIrManager
        wifiClient = SamsungWifiClient(
            onState = { state -> mainHandler.post { wifiState.value = state } },
            onToken = { token ->
                if (token.isBlank()) wifiPreferences.edit().remove("token").commit()
                else wifiPreferences.edit().putString("token", token).commit()
            },
            onTvInfo = { info -> mainHandler.post { tvInfo.value = info.orEmpty() } }
        )
        bluetoothClient = BluetoothHidClient(
            context = this,
            onState = { state ->
                bluetoothState.value = state
                if (state.code == BluetoothHidState.Code.READY_TO_PAIR ||
                    state.code == BluetoothHidState.Code.WAITING_FOR_HOST ||
                    state.code == BluetoothHidState.Code.CONNECTED ||
                    state.code == BluetoothHidState.Code.CONNECTING
                ) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            },
            onReadyForPairing = {
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        discoverableLauncher.launch(
                            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                        )
                    }
                }
            }
        )
        val initialCommands = loadCommands()
        val hasEmitter = runCatching { ir.hasIrEmitter() }.getOrDefault(false)
        val carrierRanges = runCatching {
            ir.carrierFrequencies?.map { it.minFrequency to it.maxFrequency }.orEmpty()
        }.getOrDefault(emptyList())

        setContent {
            MaterialTheme {
                TvRemoteApp(
                    hasEmitter = hasEmitter,
                    carrierRanges = carrierRanges,
                    initialCommands = initialCommands,
                    initialAcProfiles = loadAcProfiles(),
                    onPersistAcProfiles = ::persistAcProfiles,
                    onTransmit = ::transmit,
                    onPersist = ::persistCommands,
                    wifiState = wifiState,
                    initialWifiHost = wifiPreferences.getString("host", "") ?: "",
                    initialWifiToken = wifiPreferences.getString("token", null),
                    tvInfo = tvInfo,
                    discoveredIps = discoveredIps,
                    onWifiConnect = { ip ->
                        wifiPreferences.edit().putString("host", ip.trim()).commit()
                        wifiClient.connect(ip, wifiPreferences.getString("token", null))
                    },
                    onWifiDisconnect = { wifiClient.disconnect() },
                    onWifiSend = { key -> wifiKeyCode(key)?.let(wifiClient::sendKey) ?: false },
                    onWifiDiscover = {
                        Thread {
                            val found = SamsungWifiClient.discover(applicationContext)
                            mainHandler.post { discoveredIps.value = found }
                        }.start()
                    },
                    bluetoothState = bluetoothState,
                    onBluetoothStart = ::requestBluetoothHid,
                    onBluetoothSend = { key -> bluetoothClient.sendKey(key) }
                )
            }
        }

        // Restore the previously paired Samsung TV automatically.
        // A saved token is reused, so normal app restarts do not ask for TV approval again.
        val savedHost = wifiPreferences.getString("host", null)
        val savedToken = wifiPreferences.getString("token", null)
        if (!savedHost.isNullOrBlank() && !savedToken.isNullOrBlank()) {
            mainHandler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    wifiClient.connect(savedHost, savedToken)
                }
            }, 500)
        }
    }

    private fun requestBluetoothHid() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
            val missing = required.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                bluetoothPermissionLauncher.launch(missing.toTypedArray())
                return
            }
        }
        beginBluetoothHid()
    }

    private fun beginBluetoothHid() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            bluetoothState.value = BluetoothHidState(
                BluetoothHidState.Code.UNSUPPORTED,
                "Bu telefonda Bluetooth donanımı bulunamadı."
            )
            return
        }
        val enabled = runCatching { adapter.isEnabled }.getOrDefault(false)
        if (!enabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        startBluetoothHidRegistration()
    }

    private fun startBluetoothHidRegistration() {
        if (::bluetoothClient.isInitialized) bluetoothClient.start()
    }

    private fun transmit(command: IrCommand): String? {
        if (!runCatching { ir.hasIrEmitter() }.getOrDefault(false)) return "Bu telefonda kızılötesi verici bulunamadı."
        val pattern = command.pattern.split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()
        if (pattern.size < 4 || pattern.size % 2 != 0) return "IR sinyal deseni geçersiz."
        return try {
            repeat(command.repeats.coerceIn(1, 5)) {
                ir.transmit(command.frequency, pattern)
            }
            null
        } catch (error: Exception) {
            "IR gönderilemedi: " + (error.message ?: error.javaClass.simpleName)
        }
    }

    override fun onDestroy() {
        if (::wifiClient.isInitialized) wifiClient.close()
        if (::bluetoothClient.isInitialized) bluetoothClient.close()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private fun loadAcProfiles(): Map<String, String> {
        return runCatching {
            val root = JSONObject(acPreferences.getString("profiles", "{}") ?: "{}")
            AcAction.entries.mapNotNull { action ->
                root.optString(action.id).takeIf { it.isNotBlank() }?.let { action.id to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun persistAcProfiles(profiles: Map<String, String>) {
        val root = JSONObject()
        profiles.forEach { (action, profile) -> root.put(action, profile) }
        acPreferences.edit().putString("profiles", root.toString()).apply()
    }

    private fun loadCommands(): Map<String, IrCommand> {
        return runCatching {
            val root = JSONObject(preferences.getString("commands", "{}") ?: "{}")
            remoteKeys.mapNotNull { key ->
                if (!root.has(key.id)) return@mapNotNull null
                val item = root.getJSONObject(key.id)
                key.id to IrCommand(
                    item.optInt("frequency", 38000),
                    item.optString("pattern"),
                    item.optInt("repeats", 1),
                    item.optString("description", "Kabul edilen tuş komutu")
                )
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun persistCommands(commands: Map<String, IrCommand>) {
        val root = JSONObject()
        commands.forEach { (key, command) ->
            root.put(
                key,
                JSONObject()
                    .put("frequency", command.frequency)
                    .put("pattern", command.pattern)
                    .put("repeats", command.repeats)
                    .put("description", command.description)
            )
        }
        preferences.edit().putString("commands", root.toString()).apply()
    }
}

@Composable
private fun TvRemoteApp(
    hasEmitter: Boolean,
    carrierRanges: List<Pair<Int, Int>>,
    initialCommands: Map<String, IrCommand>,
    initialAcProfiles: Map<String, String>,
    onPersistAcProfiles: (Map<String, String>) -> Unit,
    onTransmit: (IrCommand) -> String?,
    onPersist: (Map<String, IrCommand>) -> Unit,
    wifiState: androidx.compose.runtime.MutableState<SamsungWifiState>,
    initialWifiHost: String,
    initialWifiToken: String?,
    tvInfo: androidx.compose.runtime.MutableState<String>,
    discoveredIps: androidx.compose.runtime.MutableState<List<String>>,
    onWifiConnect: (String) -> Unit,
    onWifiDisconnect: () -> Unit,
    onWifiSend: (String) -> Boolean,
    onWifiDiscover: () -> Unit,
    bluetoothState: androidx.compose.runtime.MutableState<BluetoothHidState>,
    onBluetoothStart: () -> Unit,
    onBluetoothSend: (String) -> Boolean
) {
    var screen by remember { mutableStateOf(AppScreen.REMOTE) }
    var wifiHost by remember { mutableStateOf(initialWifiHost) }
    var selectedMode by remember { mutableStateOf(ConnectionMode.WIFI) }
    LaunchedEffect(wifiState.value) {
        if (wifiState.value is SamsungWifiState.Connected) screen = AppScreen.REMOTE
    }
    LaunchedEffect(bluetoothState.value.code) {
        if (bluetoothState.value.code == BluetoothHidState.Code.CONNECTED) screen = AppScreen.REMOTE
    }
    var selectedKey by remember { mutableStateOf("Power") }
    var commands by remember { mutableStateOf(initialCommands) }
    var status by remember { mutableStateOf(if (hasEmitter) "Kızılötesi verici hazır" else "Bu telefonda IR vericisi bulunamadı") }

    fun openSettings(key: String) {
        selectedKey = key
        screen = AppScreen.BUTTON_SETTINGS
    }

    fun pressKey(key: String) {
        val title = remoteKeys.first { it.id == key }.title
        when (selectedMode) {
            ConnectionMode.WIFI -> {
                status = if (wifiState.value is SamsungWifiState.Connected && onWifiSend(key)) "$title · Wi-Fi"
                    else "Wi-Fi bağlı değil. Bağlantı ekranını kontrol et."
            }
            ConnectionMode.BLUETOOTH -> {
                status = if (bluetoothState.value.code == BluetoothHidState.Code.CONNECTED && onBluetoothSend(key)) "$title · Bluetooth"
                    else "Bluetooth TV'ye bağlı değil. Bluetooth bağlantı ekranını kontrol et."
            }
            ConnectionMode.IR -> {
                val command = commands[key]
                status = if (command == null) "$title tuşu için IR ayarı yok. Üç nokta menüsünden Tuş ayarları ve test bölümünü aç."
                    else onTransmit(command) ?: "$title · IR"
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = RemoteBg) {
        when (screen) {
            AppScreen.REMOTE -> RemoteScreen(
                savedKeys = commands.keys,
                onPress = ::pressKey,
                onOpenButtonList = { screen = AppScreen.BUTTON_LIST },
                onOpenInfo = { screen = AppScreen.IR_INFO },
                onOpenWifi = { screen = AppScreen.WIFI_SETUP },
                onOpenBluetooth = { screen = AppScreen.BLUETOOTH_SETUP; onBluetoothStart() },
                wifiConnected = wifiState.value is SamsungWifiState.Connected,
                mode = selectedMode,
                onModeChange = { mode ->
                    selectedMode = mode
                    if (mode == ConnectionMode.BLUETOOTH) {
                        screen = AppScreen.BLUETOOTH_SETUP
                        onBluetoothStart()
                    }
                }
            )
            AppScreen.BUTTON_LIST -> ButtonListScreen(
                commands = commands,
                onBack = { screen = AppScreen.REMOTE },
                onSelect = ::openSettings
            )
            AppScreen.BUTTON_SETTINGS -> ButtonSettingsScreen(
                keyId = selectedKey,
                savedCommand = commands[selectedKey],
                carrierRanges = carrierRanges,
                hasEmitter = hasEmitter,
                onBack = { screen = AppScreen.BUTTON_LIST },
                onTransmit = onTransmit,
                onAccepted = { command ->
                    commands = commands + (selectedKey to command)
                    onPersist(commands)
                    status = remoteKeys.first { it.id == selectedKey }.title + " tuşunun ayarı kaydedildi."
                },
                onClear = {
                    commands = commands - selectedKey
                    onPersist(commands)
                    status = remoteKeys.first { it.id == selectedKey }.title + " tuş eşlemesi silindi."
                }
            )
            AppScreen.IR_INFO -> IrInfoScreen(
                hasEmitter = hasEmitter,
                carrierRanges = carrierRanges,
                onBack = { screen = AppScreen.REMOTE }
            )
            AppScreen.WIFI_SETUP -> WifiSetupScreen(
                host = wifiHost,
                onHostChange = { wifiHost = it },
                state = wifiState.value,
                tvInfo = tvInfo.value,
                discoveredIps = discoveredIps.value,
                onDiscover = onWifiDiscover,
                onConnect = { onWifiConnect(wifiHost) },
                onSelectIp = { wifiHost = it; onWifiConnect(it) },
                onDisconnect = onWifiDisconnect,
                onBack = { screen = AppScreen.REMOTE }
            )
            AppScreen.BLUETOOTH_SETUP -> BluetoothSetupScreen(
                state = bluetoothState.value,
                onStart = onBluetoothStart,
                onBack = { screen = AppScreen.REMOTE }
            )
        }
    }
}

@Composable
private fun ModeSlider(mode: ConnectionMode, onModeChange: (ConnectionMode) -> Unit) {
    val thumbOffset by androidx.compose.animation.core.animateDpAsState(
        targetValue = when (mode) {
            ConnectionMode.WIFI -> 3.dp
            ConnectionMode.BLUETOOTH -> 57.dp
            ConnectionMode.IR -> 111.dp
        },
        label = "connection-mode-thumb"
    )
    Box(
        modifier = Modifier.width(168.dp).height(34.dp).clip(RoundedCornerShape(20.dp))
            .background(Color(0xFFDCE5F1)).padding(2.dp)
    ) {
        Box(
            modifier = Modifier.align(Alignment.CenterStart).offset(x = thumbOffset)
                .width(54.dp).height(30.dp).clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF087CF0))
        )
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            listOf(
                ConnectionMode.WIFI to "Wi-Fi",
                ConnectionMode.BLUETOOTH to "BT",
                ConnectionMode.IR to "IR"
            ).forEach { (option, label) ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable { onModeChange(option) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = if (mode == option) Color.White else Color(0xFF334155))
                }
            }
        }
    }
}

@Composable
private fun BluetoothSetupScreen(
    state: BluetoothHidState,
    onStart: () -> Unit,
    onBack: () -> Unit
) {
    val connected = state.code == BluetoothHidState.Code.CONNECTED
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        ScreenHeader("Bluetooth kumanda", onBack)
        Text(
            "Telefonu TV'ye standart Bluetooth klavye/HID kumanda olarak tanıtıyoruz. Bu yöntem Wi-Fi ağından bağımsızdır.",
            fontSize = 12.sp,
            color = Color.DarkGray
        )
        Spacer(Modifier.height(12.dp))
        Surface(color = Color.White, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (connected) "TV bağlantısı kuruldu" else state.message,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (connected) RemoteGreen else RemoteInk
                )
                if (state.hostName.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Bağlanan cihaz: " + state.hostName, fontSize = 11.sp, color = Color.Gray)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text(if (connected) "BLUETOOTH'U YENİDEN HAZIRLA" else "BLUETOOTH'U HAZIRLA")
        }
        Spacer(Modifier.height(16.dp))
        Text("TV üzerinde yapılacak işlem", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Home > Ayarlar > Tüm Ayarlar > Bağlantı > Bluetooth Cihaz Listesi",
            fontSize = 12.sp,
            color = Color.DarkGray
        )
        Text(
            "Telefon görünür olduktan sonra TV'de Yenile seç. Listede “TV Kumanda” veya telefonun adı klavye olarak görünürse seçip eşleştir.",
            fontSize = 12.sp,
            color = Color.DarkGray
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Önemli: Android, HID kaydını uygulama ön planda değilken otomatik kapatabilir. Eşleştirme ve kullanım sırasında uygulamayı kapatma; ekranı açık tut. Yön tuşları ve OK ilk test edilecek komutlardır. Güç, kaynak ve ses komutları TV'nin HID eşlemesine göre değişebilir.",
            fontSize = 11.sp,
            color = Color.Gray
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.End)) { Text("Kumandaya dön") }
    }
}

@Composable
private fun RemoteScreen(
    savedKeys: Set<String>,
    onPress: (String) -> Unit,
    onOpenButtonList: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenWifi: () -> Unit,
    onOpenBluetooth: () -> Unit,
    wifiConnected: Boolean,
    mode: ConnectionMode,
    onModeChange: (ConnectionMode) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("‹", fontSize = 34.sp, color = Color.Black)
            Text("Samsung Televizyon", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)
            Box {
                Text("⋮", fontSize = 28.sp, color = Color.Black, modifier = Modifier.clickable { menuExpanded = true })
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Tuş ayarları ve test") },
                        onClick = { menuExpanded = false; onOpenButtonList() }
                    )
                    DropdownMenuItem(
                        text = { Text("Wi-Fi bağlantısı" + if (wifiConnected) " · Bağlı" else "") },
                        onClick = { menuExpanded = false; onOpenWifi() }
                    )
                    DropdownMenuItem(
                        text = { Text("Bluetooth bağlantısı") },
                        onClick = { menuExpanded = false; onOpenBluetooth() }
                    )
                    DropdownMenuItem(
                        text = { Text("IR verici bilgisi") },
                        onClick = { menuExpanded = false; onOpenInfo() }
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        ModeSlider(mode = mode, onModeChange = onModeChange)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RemoteRoundButton("⏻", accent = RemoteGreen, configured = "Power" in savedKeys, onClick = { onPress("Power") })
            RemoteRoundButton("⇥", configured = "Source" in savedKeys, onClick = { onPress("Source") })
            RemoteRoundButton("☰", configured = "Menu" in savedKeys, onClick = { onPress("Menu") })
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RemoteRoundButton("⌂", configured = "Home" in savedKeys, onClick = { onPress("Home") })
            RemoteRoundButton("①", configured = "Number 1" in savedKeys, onClick = { onPress("Number 1") })
            RemoteRoundButton("↶", configured = "Back" in savedKeys, onClick = { onPress("Back") })
        }
        Spacer(Modifier.weight(1f))
        DirectionPad(
            size = 264.dp,
            savedKeys = savedKeys,
            onPress = onPress
        )
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            RemoteVerticalControl(
                top = "⌃", middle = "CH", bottom = "⌄",
                topConfigured = "CH+" in savedKeys, bottomConfigured = "CH-" in savedKeys,
                onTop = { onPress("CH+") }, onBottom = { onPress("CH-") }
            )
            RemoteVerticalControl(
                top = "+", middle = "VOL", bottom = "−",
                topConfigured = "VOL+" in savedKeys, bottomConfigured = "VOL-" in savedKeys,
                onTop = { onPress("VOL+") }, onBottom = { onPress("VOL-") }
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RemoteRoundButton("•••", buttonSize = 56.dp, configured = "More" in savedKeys, onClick = { onPress("More") })
            RemoteRoundButton("◁×", buttonSize = 56.dp, configured = "Mute" in savedKeys, onClick = { onPress("Mute") })
        }
    }
}

@Composable
private fun RemoteRoundButton(
    symbol: String,
    buttonSize: Dp = 66.dp,
    accent: Color = RemoteInk,
    configured: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.size(buttonSize).clip(CircleShape).background(Color.White)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (symbol == "⏻") {
            // Draw the power glyph ourselves so it renders consistently on devices
            // whose system font does not contain the Unicode power character.
            Canvas(Modifier.size(if (buttonSize < 60.dp) 25.dp else 30.dp)) {
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2.4.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                val diameter = size.minDimension
                val radius = diameter * 0.36f
                val left = (size.width - radius * 2f) / 2f
                val top = (size.height - radius * 2f) / 2f + diameter * 0.08f
                drawArc(
                    color = accent,
                    startAngle = 45f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                    style = stroke
                )
                val centerX = size.width / 2f
                drawLine(
                    color = accent,
                    start = androidx.compose.ui.geometry.Offset(centerX, diameter * 0.08f),
                    end = androidx.compose.ui.geometry.Offset(centerX, diameter * 0.52f),
                    strokeWidth = 2.6.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        } else {
            Text(symbol, fontSize = if (buttonSize < 60.dp) 19.sp else 25.sp, color = accent)
        }
    }
}

@Composable
private fun DirectionPad(
    size: Dp,
    savedKeys: Set<String>,
    onPress: (String) -> Unit
) {
    Column(
        modifier = Modifier.size(size).clip(CircleShape).background(Color.White).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .clickable { onPress("Up") },
            contentAlignment = Alignment.Center
        ) { PadDot("Up" in savedKeys) }
        Row(Modifier.weight(1.25f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable { onPress("Left") },
                contentAlignment = Alignment.Center
            ) { PadDot("Left" in savedKeys) }
            Box(
                Modifier.size(92.dp).clip(CircleShape).background(RemoteBg)
                    .clickable { onPress("OK") },
                contentAlignment = Alignment.Center
            ) { Text("OK", fontSize = 20.sp, color = Color.Black) }
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable { onPress("Right") },
                contentAlignment = Alignment.Center
            ) { PadDot("Right" in savedKeys) }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .clickable { onPress("Down") },
            contentAlignment = Alignment.Center
        ) { PadDot("Down" in savedKeys) }
    }
}

@Composable
private fun PadDot(configured: Boolean) {
    Box(Modifier.size(8.dp).background(Color(0xFFE8E8E8), CircleShape))
}

@Composable
private fun RemoteVerticalControl(
    top: String,
    middle: String,
    bottom: String,
    topConfigured: Boolean,
    bottomConfigured: Boolean,
    onTop: () -> Unit,
    onBottom: () -> Unit
) {
    Column(
        modifier = Modifier.width(82.dp).height(148.dp).clip(RoundedCornerShape(42.dp))
            .background(Color.White).padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .clickable(onClick = onTop),
            contentAlignment = Alignment.Center
        ) {
            Text(top, fontSize = 25.sp, color = RemoteInk)
            if (topConfigured) Box(Modifier.align(Alignment.CenterEnd).padding(end = 9.dp).size(5.dp).background(RemoteGreen, CircleShape))
        }
        Text(middle, fontSize = 15.sp, color = RemoteInk)
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .clickable(onClick = onBottom),
            contentAlignment = Alignment.Center
        ) {
            Text(bottom, fontSize = 25.sp, color = RemoteInk)
            if (bottomConfigured) Box(Modifier.align(Alignment.CenterEnd).padding(end = 9.dp).size(5.dp).background(RemoteGreen, CircleShape))
        }
    }
}

@Composable
private fun ButtonListScreen(
    commands: Map<String, IrCommand>,
    onBack: () -> Unit,
    onSelect: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        ScreenHeader("Tuş ayarları", onBack)
        Text("Her tuşu ayrı ayrı açıp test edebilirsin. Çalışan sinyal yalnızca onayından sonra kaydedilir.", fontSize = 13.sp, color = Color.DarkGray)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            remoteKeys.forEach { key ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Color.White)
                        .clickable { onSelect(key.id) }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(key.symbol, modifier = Modifier.width(42.dp), fontSize = 22.sp, textAlign = TextAlign.Center)
                    Column(Modifier.weight(1f)) {
                        Text(key.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            if (commands.containsKey(key.id)) "Kaydedildi · " + commands.getValue(key.id).description else "Henüz eşlenmedi",
                            fontSize = 11.sp,
                            color = if (commands.containsKey(key.id)) RemoteGreen else Color.Gray
                        )
                    }
                    Text("Ayarla  ›", fontSize = 12.sp, color = Color.DarkGray)
                }
            }
        }
    }
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("‹", fontSize = 32.sp, modifier = Modifier.clickable(onClick = onBack))
        Spacer(Modifier.width(14.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ButtonSettingsScreen(
    keyId: String,
    savedCommand: IrCommand?,
    carrierRanges: List<Pair<Int, Int>>,
    hasEmitter: Boolean,
    onBack: () -> Unit,
    onTransmit: (IrCommand) -> String?,
    onAccepted: (IrCommand) -> Unit,
    onClear: () -> Unit
) {
    val key = remoteKeys.first { it.id == keyId }
    val candidates = remember(keyId) { SamsungIr.candidates(keyId) }
    var candidateIndex by remember(keyId) { mutableIntStateOf(0) }
    var pendingCommand by remember { mutableStateOf<IrCommand?>(null) }
    var pendingMode by remember { mutableStateOf("automatic") }
    var feedbackOpen by remember { mutableStateOf(false) }
    var frequency by remember(savedCommand) { mutableStateOf((savedCommand?.frequency ?: 38000).toString()) }
    var rawPattern by remember(savedCommand) { mutableStateOf(savedCommand?.pattern.orEmpty()) }
    var repeatCount by remember(savedCommand) { mutableStateOf((savedCommand?.repeats ?: 1).toString()) }
    var status by remember { mutableStateOf(if (hasEmitter) "Bu tuş için hazır kodları sırayla dene." else "Bu telefonda IR vericisi algılanmadı.") }

    fun runTest(command: IrCommand, mode: String) {
        val error = onTransmit(command)
        if (error != null) {
            status = error
            return
        }
        pendingCommand = command
        pendingMode = mode
        feedbackOpen = true
        status = "Sinyal gönderildi. Televizyondaki tepkiyi kontrol et."
    }

    fun runNextAutomaticCandidate() {
        if (candidateIndex >= candidates.size) {
            status = "Hazır kodların tamamı denendi. Özel sinyal alanından farklı bir desen deneyebilirsin."
            return
        }
        runTest(candidates[candidateIndex], "automatic")
    }

    Column(Modifier.fillMaxSize().padding(18.dp)) {
        ScreenHeader(key.title + " tuşu", onBack)
        Text("Bu tuş için bağımsız sinyal ayarı", fontSize = 13.sp, color = Color.DarkGray)
        Spacer(Modifier.height(10.dp))
        if (savedCommand != null) {
            Surface(color = Color.White, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Şu anda kayıtlı", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(savedCommand.description, fontSize = 12.sp, color = Color.DarkGray)
                    Text(savedCommand.frequency.toString() + " Hz · " + savedCommand.repeats + " tekrar", fontSize = 11.sp, color = RemoteGreen)
                    TextButton(onClick = onClear) { Text("Bu tuşun kaydını sil") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("1. Otomatik Samsung kodlarını dene", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "Deneme " + (minOf(candidateIndex + 1, candidates.size.coerceAtLeast(1))) + " / " + candidates.size +
                    " · Başarılı olanı sen onaylayınca kaydedeceğiz.",
                fontSize = 11.sp,
                color = Color.Gray
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { runNextAutomaticCandidate() },
                enabled = hasEmitter && candidates.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (candidateIndex == 0) "İLK SİNYALİ GÖNDER" else "SONRAKİ HAZIR SİNYALİ GÖNDER") }
            candidates.forEachIndexed { index, command ->
                Text(
                    (if (index < candidateIndex) "✓ " else if (index == candidateIndex) "● " else "○ ") +
                        (index + 1).toString() + ". " + command.description,
                    fontSize = 11.sp,
                    color = if (index == candidateIndex) RemoteInk else Color.Gray,
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            Text("2. Özel sinyal ayarı", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("Frekans Hz, ham desen ise µs cinsinden mark,space sıralı olmalı.", fontSize = 11.sp, color = Color.Gray)
            OutlinedTextField(
                value = frequency,
                onValueChange = { frequency = it.filter(Char::isDigit).take(6) },
                label = { Text("Taşıyıcı frekansı (Hz)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rawPattern,
                onValueChange = { rawPattern = it },
                label = { Text("Ham IR süreleri (µs)") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = repeatCount,
                onValueChange = { repeatCount = it.filter(Char::isDigit).take(1) },
                label = { Text("Tekrar sayısı (1–5)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val custom = SamsungIr.validateCustom(frequency, rawPattern, repeatCount)
                    if (custom == null) status = "Özel sinyal geçersiz. Frekans, süreleri ve toplam süreyi kontrol et."
                    else runTest(custom, "manual")
                },
                enabled = hasEmitter,
                modifier = Modifier.fillMaxWidth()
            ) { Text("ÖZEL SİNYALİ TEST ET") }
            Spacer(Modifier.height(12.dp))
            Text("Durum: " + status, fontSize = 12.sp, color = Color.DarkGray)
            if (carrierRanges.isNotEmpty()) {
                Text(
                    "Telefonun desteklediği frekans: " + carrierRanges.joinToString(" · ") { (low, high) ->
                        (low / 1000).toString() + "–" + (high / 1000).toString() + " kHz"
                    },
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.End)) { Text("Tuş listesine dön") }
    }

    if (feedbackOpen && pendingCommand != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Tuşun işlevi çalıştı mı?") },
            text = {
                Text(
                    key.title + " tuşu için '" + pendingCommand!!.description +
                        "' sinyali gönderildi.\n\nTelevizyonda beklediğin işlem gerçekleşti mi?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val accepted = pendingCommand
                    feedbackOpen = false
                    if (accepted != null) {
                        onAccepted(accepted)
                        status = "Çalışan sinyal kabul edildi ve bu tuşa kaydedildi."
                    }
                }) { Text("Evet, çalıştı · Kaydet") }
            },
            dismissButton = {
                TextButton(onClick = {
                    feedbackOpen = false
                    if (pendingMode == "automatic") {
                        candidateIndex += 1
                        if (candidateIndex < candidates.size) {
                            runNextAutomaticCandidate()
                        } else {
                            status = "Hazır Samsung kodları bitti. Özel sinyal ayarını deneyebilirsin."
                        }
                    } else {
                        status = "Bu özel sinyal çalışmadı. Parametreleri değiştirip tekrar deneyebilirsin."
                    }
                }) { Text("Hayır, sonraki deneme") }
            }
        )
    }
}

@Composable
private fun IrInfoScreen(
    hasEmitter: Boolean,
    carrierRanges: List<Pair<Int, Int>>,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        ScreenHeader("IR verici bilgisi", onBack)
        Spacer(Modifier.height(12.dp))
        Text(if (hasEmitter) "Kızılötesi verici algılandı." else "Kızılötesi verici algılanmadı.", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Android ConsumerIrManager yalnızca IR sinyali göndermeyi sağlar. Telefonun normal IR vericisi TV'den gelen sinyali dinleyemez; bu nedenle uygulamada gerçek bir otomatik öğrenme/geri okuma bulunmaz.", fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Text("Desteklenen taşıyıcı frekansları", fontWeight = FontWeight.Bold)
        if (carrierRanges.isEmpty()) {
            Text("Telefon bu bilgiyi raporlamadı.", fontSize = 12.sp, color = Color.Gray)
        } else {
            carrierRanges.forEach { range ->
                Text((range.first / 1000).toString() + "–" + (range.second / 1000).toString() + " kHz", fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Samsung kodları farklı kumanda serilerinde değişebilir. Bu yüzden hiçbir kod otomatik olarak doğru kabul edilmez; yalnızca TV'de denediğinde çalıştığını onayladığın komut kaydedilir.", fontSize = 13.sp)
    }
}


@Composable
private fun WifiSetupScreen(
    host: String,
    onHostChange: (String) -> Unit,
    state: SamsungWifiState,
    tvInfo: String,
    discoveredIps: List<String>,
    onDiscover: () -> Unit,
    onConnect: () -> Unit,
    onSelectIp: (String) -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        ScreenHeader("Samsung TV · Wi-Fi", onBack)
        Text("Telefon ve televizyon aynı yerel Wi-Fi/ağ üzerinde olmalı. İlk bağlantıda TV ekranında izin penceresi açılır; kumandayla Allow/İzin Ver seç.", fontSize = 12.sp, color = Color.DarkGray)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = host,
            onValueChange = onHostChange,
            label = { Text("Televizyon IP adresi") },
            placeholder = { Text("Örn. 192.168.1.50") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDiscover, modifier = Modifier.weight(1f)) { Text("AĞDA TV ARA") }
            Button(onClick = onConnect, modifier = Modifier.weight(1f), enabled = host.isNotBlank()) { Text("BAĞLAN") }
        }
        if (discoveredIps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Bulunan ağ cihazları · Samsung TV olanı seç", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            discoveredIps.forEach { ip ->
                TextButton(onClick = { onSelectIp(ip) }) { Text("TV adayı: $ip") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(color = Color.White, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    when (state) {
                        SamsungWifiState.Disconnected -> "Bağlı değil"
                        SamsungWifiState.Connecting -> "TV aranıyor ve bağlantı kuruluyor…"
                        SamsungWifiState.WaitingForApproval -> "TV ekranında bağlantı izni bekleniyor. Kumandayla Allow seç."
                        SamsungWifiState.Connected -> "Wi-Fi bağlantısı kuruldu"
                        is SamsungWifiState.Reconnecting -> "Bağlantı kesildi · " + state.delaySeconds + " sn sonra yeniden denenecek (deneme " + state.attempt + ")"
                        is SamsungWifiState.Failed -> "Bağlantı hatası: " + state.reason
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = if (state is SamsungWifiState.Connected) RemoteGreen else if (state is SamsungWifiState.Reconnecting) Color(0xFFB7791F) else RemoteInk
                )
                if (tvInfo.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(tvInfo, fontSize = 11.sp, color = Color.Gray)
                }
                if (state is SamsungWifiState.Connected || state is SamsungWifiState.WaitingForApproval) {
                    TextButton(onClick = onDisconnect) { Text("Bağlantıyı kes") }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Wi-Fi destek bilgisi", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("Bu bağlantı Samsung Tizen TV'lerin yerel WebSocket kumanda servisini kullanır (genellikle 2016 ve sonrası). Eski Orsay modelleri bu yöntemle uyumlu olmayabilir. Wi-Fi bağlantısı kurulamazsa kızılötesi tuş eşlemesini kullanmaya devam edebilirsin.", fontSize = 11.sp, color = Color.Gray)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.End)) { Text("Kumandaya dön") }
    }
}
