@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.dincer552.tvremote

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

private enum class AppScreen { REMOTE, BUTTON_LIST, BUTTON_SETTINGS, IR_INFO }

class MainActivity : ComponentActivity() {
    private lateinit var ir: ConsumerIrManager
    private val preferences by lazy { getSharedPreferences("tv_remote_profile_v2", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ir = getSystemService(CONSUMER_IR_SERVICE) as ConsumerIrManager
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
                    onTransmit = ::transmit,
                    onPersist = ::persistCommands
                )
            }
        }
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
    onTransmit: (IrCommand) -> String?,
    onPersist: (Map<String, IrCommand>) -> Unit
) {
    var screen by remember { mutableStateOf(AppScreen.REMOTE) }
    var selectedKey by remember { mutableStateOf("Power") }
    var commands by remember { mutableStateOf(initialCommands) }
    var status by remember { mutableStateOf(if (hasEmitter) "Kızılötesi verici hazır" else "Bu telefonda IR vericisi bulunamadı") }

    fun openSettings(key: String) {
        selectedKey = key
        screen = AppScreen.BUTTON_SETTINGS
    }

    fun pressKey(key: String) {
        val command = commands[key]
        if (command == null) {
            openSettings(key)
        } else {
            val error = onTransmit(command)
            status = error ?: (remoteKeys.first { it.id == key }.title + " komutu gönderildi")
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = RemoteBg) {
        when (screen) {
            AppScreen.REMOTE -> RemoteScreen(
                savedKeys = commands.keys,
                onPress = ::pressKey,
                onConfigure = ::openSettings,
                onOpenButtonList = { screen = AppScreen.BUTTON_LIST },
                onOpenInfo = { screen = AppScreen.IR_INFO }
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
        }
    }
}

@Composable
private fun RemoteScreen(
    savedKeys: Set<String>,
    onPress: (String) -> Unit,
    onConfigure: (String) -> Unit,
    onOpenButtonList: () -> Unit,
    onOpenInfo: () -> Unit
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
                        text = { Text("IR verici bilgisi") },
                        onClick = { menuExpanded = false; onOpenInfo() }
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RemoteRoundButton("⏻", accent = RemoteGreen, configured = "Power" in savedKeys, onClick = { onPress("Power") }, onLongClick = { onConfigure("Power") })
            RemoteRoundButton("⇥", configured = "Source" in savedKeys, onClick = { onPress("Source") }, onLongClick = { onConfigure("Source") })
            RemoteRoundButton("☰", configured = "Menu" in savedKeys, onClick = { onPress("Menu") }, onLongClick = { onConfigure("Menu") })
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RemoteRoundButton("⌂", configured = "Home" in savedKeys, onClick = { onPress("Home") }, onLongClick = { onConfigure("Home") })
            RemoteRoundButton("①", configured = "Number 1" in savedKeys, onClick = { onPress("Number 1") }, onLongClick = { onConfigure("Number 1") })
            RemoteRoundButton("↶", configured = "Back" in savedKeys, onClick = { onPress("Back") }, onLongClick = { onConfigure("Back") })
        }
        Spacer(Modifier.weight(1f))
        DirectionPad(
            size = 264.dp,
            savedKeys = savedKeys,
            onPress = onPress,
            onConfigure = onConfigure
        )
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            RemoteVerticalControl(
                top = "⌃", middle = "CH", bottom = "⌄",
                topConfigured = "CH+" in savedKeys, bottomConfigured = "CH-" in savedKeys,
                onTop = { onPress("CH+") }, onBottom = { onPress("CH-") },
                onTopLong = { onConfigure("CH+") }, onBottomLong = { onConfigure("CH-") }
            )
            RemoteVerticalControl(
                top = "+", middle = "VOL", bottom = "−",
                topConfigured = "VOL+" in savedKeys, bottomConfigured = "VOL-" in savedKeys,
                onTop = { onPress("VOL+") }, onBottom = { onPress("VOL-") },
                onTopLong = { onConfigure("VOL+") }, onBottomLong = { onConfigure("VOL-") }
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RemoteRoundButton("•••", buttonSize = 56.dp, configured = "More" in savedKeys, onClick = { onPress("More") }, onLongClick = { onConfigure("More") })
            RemoteRoundButton("◁×", buttonSize = 56.dp, configured = "Mute" in savedKeys, onClick = { onPress("Mute") }, onLongClick = { onConfigure("Mute") })
        }
    }
}

@Composable
private fun RemoteRoundButton(
    symbol: String,
    buttonSize: Dp = 66.dp,
    accent: Color = RemoteInk,
    configured: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier.size(buttonSize).clip(CircleShape).background(Color.White)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, fontSize = if (buttonSize < 60.dp) 19.sp else 25.sp, color = accent)
    }
}

@Composable
private fun DirectionPad(
    size: Dp,
    savedKeys: Set<String>,
    onPress: (String) -> Unit,
    onConfigure: (String) -> Unit
) {
    Column(
        modifier = Modifier.size(size).clip(CircleShape).background(Color.White).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .combinedClickable(onClick = { onPress("Up") }, onLongClick = { onConfigure("Up") }),
            contentAlignment = Alignment.Center
        ) { PadDot("Up" in savedKeys) }
        Row(Modifier.weight(1.25f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .combinedClickable(onClick = { onPress("Left") }, onLongClick = { onConfigure("Left") }),
                contentAlignment = Alignment.Center
            ) { PadDot("Left" in savedKeys) }
            Box(
                Modifier.size(92.dp).clip(CircleShape).background(RemoteBg)
                    .combinedClickable(onClick = { onPress("OK") }, onLongClick = { onConfigure("OK") }),
                contentAlignment = Alignment.Center
            ) { Text("OK", fontSize = 20.sp, color = Color.Black) }
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .combinedClickable(onClick = { onPress("Right") }, onLongClick = { onConfigure("Right") }),
                contentAlignment = Alignment.Center
            ) { PadDot("Right" in savedKeys) }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .combinedClickable(onClick = { onPress("Down") }, onLongClick = { onConfigure("Down") }),
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
    onBottom: () -> Unit,
    onTopLong: () -> Unit,
    onBottomLong: () -> Unit
) {
    Column(
        modifier = Modifier.width(82.dp).height(148.dp).clip(RoundedCornerShape(42.dp))
            .background(Color.White).padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .combinedClickable(onClick = onTop, onLongClick = onTopLong),
            contentAlignment = Alignment.Center
        ) {
            Text(top, fontSize = 25.sp, color = RemoteInk)
            if (topConfigured) Box(Modifier.align(Alignment.CenterEnd).padding(end = 9.dp).size(5.dp).background(RemoteGreen, CircleShape))
        }
        Text(middle, fontSize = 15.sp, color = RemoteInk)
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .combinedClickable(onClick = onBottom, onLongClick = onBottomLong),
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
