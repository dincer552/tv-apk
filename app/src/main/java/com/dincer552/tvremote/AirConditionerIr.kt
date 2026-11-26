package com.dincer552.tvremote

enum class AcMode(val label: String) {
    COOL("Soğutma"),
    HEAT("Isıtma"),
    DRY("Nem alma"),
    FAN("Fan"),
    AUTO("Otomatik");

    fun next(): AcMode = entries[(ordinal + 1) % entries.size]
}

enum class AcFan(val label: String) {
    AUTO("Otomatik"),
    LOW("Düşük"),
    MEDIUM("Orta"),
    HIGH("Yüksek");

    fun next(): AcFan = entries[(ordinal + 1) % entries.size]
}

data class AcState(
    val power: Boolean = false,
    val temperature: Int = 24,
    val mode: AcMode = AcMode.COOL,
    val fan: AcFan = AcFan.AUTO
)

enum class AcAction(val id: String, val title: String) {
    POWER("power", "Aç / Kapat"),
    TEMP_UP("temp_up", "Sıcaklık artır"),
    TEMP_DOWN("temp_down", "Sıcaklık azalt"),
    MODE("mode", "Mod değiştir"),
    FAN("fan", "Fan hızı"),
    QUICK_COOL("quick_cool", "Hızlı soğutma"),
    SWING("swing", "Dikey hava"),
    SLEEP("sleep", "Uyku")
}

data class AcIrCandidate(
    val profileId: String,
    val profileName: String,
    val command: IrCommand,
    val nextState: AcState
)

object AirConditionerIr {
    private data class Timing(
        val headerMark: Int,
        val headerSpace: Int,
        val bitMark: Int,
        val oneSpace: Int,
        val zeroSpace: Int,
        val gap: Int
    )

    // Altus ALK-1840 protocol is not publicly identified. These are separate
    // candidate families for supervised testing, not an assertion of compatibility.
    private val lgTiming = Timing(8500, 4250, 550, 1600, 550, 39750)
    private val lg2Timing = Timing(3200, 9900, 480, 1600, 550, 39750)
    private val coolixTiming = Timing(4692, 4416, 552, 1656, 552, 5244)

    private val coolixTempCodes = intArrayOf(
        0x0, 0x1, 0x3, 0x2, 0x6, 0x7, 0x5, 0x4, 0xC, 0xD, 0x9, 0x8, 0xA, 0xB
    )

    fun candidates(action: AcAction, state: AcState): List<AcIrCandidate> {
        val next = nextState(action, state)
        val out = mutableListOf<AcIrCandidate>()

        if (action != AcAction.SWING && action != AcAction.SLEEP && action != AcAction.QUICK_COOL) {
            val lgCommand = buildLg(next, lgTiming, "LG 28-bit klima")
            out += AcIrCandidate("LG", "LG 28-bit", lgCommand, next)
            val lg2Command = buildLg(next, lg2Timing, "LG2 28-bit klima")
            out += AcIrCandidate("LG2", "LG2 28-bit", lg2Command, next)
        }
        if (action !in setOf(AcAction.QUICK_COOL, AcAction.SWING, AcAction.SLEEP)) {
            out += AcIrCandidate(
                "COOLIX",
                "Coolix 24-bit (Beko/Midea ailesi)",
                buildCoolix(next),
                next
            )
        }
        if (action == AcAction.POWER && !next.power) {
            // Protocol-specific fixed power-off frames are useful while testing.
            out.clear()
            out += AcIrCandidate("LG", "LG 28-bit · Power Off", buildLgOff(lgTiming), next)
            out += AcIrCandidate("LG2", "LG2 28-bit · Power Off", buildLgOff(lg2Timing), next)
            out += AcIrCandidate("COOLIX", "Coolix 24-bit · Power Off", buildCoolixOff(), next)
        }
        if (action == AcAction.SWING) {
            out += AcIrCandidate("COOLIX", "Coolix · Dikey hava", buildCoolixFixed(0xB26BE0), next)
            out += AcIrCandidate("LG", "LG · Dikey hava", buildLgFixed(0x08810001, lgTiming), next)
            out += AcIrCandidate("LG2", "LG2 · Dikey hava", buildLgFixed(0x08810001, lg2Timing), next)
        }
        if (action == AcAction.SLEEP) {
            out += AcIrCandidate("COOLIX", "Coolix · Uyku", buildCoolixFixed(0xB2E003), next)
        }
        if (action == AcAction.QUICK_COOL) {
            val turboState = next.copy(power = true, mode = AcMode.COOL, temperature = 18, fan = AcFan.HIGH)
            out += AcIrCandidate("LG", "LG 28-bit · Hızlı soğutma", buildLg(turboState, lgTiming, "LG 28-bit hızlı soğutma"), turboState)
            out += AcIrCandidate("LG2", "LG2 28-bit · Hızlı soğutma", buildLg(turboState, lg2Timing, "LG2 28-bit hızlı soğutma"), turboState)
            out += AcIrCandidate("COOLIX", "Coolix · Hızlı soğutma", buildCoolix(turboState), turboState)
        }
        return out.distinctBy { it.profileId + ":" + it.profileName }
    }

    fun command(action: AcAction, state: AcState, profileId: String): AcIrCandidate? =
        candidates(action, state).firstOrNull { it.profileId == profileId }

    fun nextState(action: AcAction, state: AcState): AcState = when (action) {
        AcAction.POWER -> state.copy(power = !state.power)
        AcAction.TEMP_UP -> state.copy(power = true, temperature = (state.temperature + 1).coerceAtMost(30))
        AcAction.TEMP_DOWN -> state.copy(power = true, temperature = (state.temperature - 1).coerceAtLeast(16))
        AcAction.MODE -> state.copy(power = true, mode = state.mode.next())
        AcAction.FAN -> state.copy(power = true, fan = state.fan.next())
        AcAction.QUICK_COOL -> state.copy(power = true, mode = AcMode.COOL, temperature = 18, fan = AcFan.HIGH)
        AcAction.SWING, AcAction.SLEEP -> state
    }

    private fun buildLg(state: AcState, timing: Timing, description: String): IrCommand {
        val mode = when (state.mode) {
            AcMode.COOL -> 0
            AcMode.DRY -> 1
            AcMode.FAN -> 2
            AcMode.AUTO -> 3
            AcMode.HEAT -> 4
        }
        val fan = when (state.fan) {
            AcFan.AUTO -> 5
            AcFan.LOW -> 1
            AcFan.MEDIUM -> 2
            AcFan.HIGH -> 4
        }
        val temp = if (state.power) (state.temperature.coerceIn(16, 30) - 15) else 0
        val power = if (state.power) 0 else 3
        val base = (0x88L shl 20) or (power.toLong() shl 18) or
            (mode.toLong() shl 12) or (temp.toLong() shl 8) or (fan.toLong() shl 4)
        val checksum = ((base shr 4) and 0xFL) +
            ((base shr 8) and 0xFL) +
            ((base shr 12) and 0xFL) +
            ((base shr 16) and 0xFL)
        val frame = base or (checksum and 0xFL)
        return IrCommand(38000, pulseDistance(frame, 28, timing), 1, description)
    }

    private fun buildLgOff(timing: Timing): IrCommand =
        IrCommand(38000, pulseDistance(0x088C0051, 28, timing), 1, "LG klima Power Off")

    private fun buildLgFixed(code: Long, timing: Timing): IrCommand =
        IrCommand(38000, pulseDistance(code, 28, timing), 1, "LG klima özel komut")

    private fun pulseDistance(data: Long, bits: Int, timing: Timing): String {
        val p = mutableListOf<Int>()
        p += timing.headerMark
        p += timing.headerSpace
        for (bit in bits - 1 downTo 0) {
            p += timing.bitMark
            p += if (((data shr bit) and 1L) != 0L) timing.oneSpace else timing.zeroSpace
        }
        p += timing.bitMark
        p += timing.gap
        return p.joinToString(",")
    }

    private fun buildCoolix(state: AcState): IrCommand {
        val mode = when (state.mode) {
            AcMode.COOL -> 0
            AcMode.DRY -> 1
            AcMode.AUTO -> 2
            AcMode.HEAT -> 3
            AcMode.FAN -> 4
        }
        val tempCode = coolixTempCodes[(state.temperature.coerceIn(17, 30) - 17)]
        val fan = when (state.fan) {
            AcFan.AUTO -> 0
            AcFan.LOW -> 4
            AcFan.MEDIUM -> 2
            AcFan.HIGH -> 1
        }
        val raw = 0xB20000L or (fan.toLong() shl 13) or
            (31L shl 8) or (tempCode.toLong() shl 4) or (mode.toLong() shl 2)
        return IrCommand(38000, coolixFrame(raw), 1, "Coolix 24-bit klima durumu")
    }

    private fun buildCoolixOff(): IrCommand = buildCoolixFixed(0xB27BE0)

    private fun buildCoolixFixed(raw: Long): IrCommand =
        IrCommand(38000, coolixFrame(raw), 1, "Coolix özel klima komutu")

    private fun coolixFrame(data: Long): String {
        val p = mutableListOf<Int>()
        p += coolixTiming.headerMark
        p += coolixTiming.headerSpace
        for (byteIndex in 2 downTo 0) {
            val b = ((data shr (byteIndex * 8)) and 0xFF).toInt()
            appendByte(p, b)
            appendByte(p, b xor 0xFF)
        }
        p += coolixTiming.bitMark
        p += coolixTiming.gap
        p += 100000
        return p.joinToString(",")
    }

    private fun appendByte(p: MutableList<Int>, value: Int) {
        for (bit in 7 downTo 0) {
            p += coolixTiming.bitMark
            p += if (((value shr bit) and 1) != 0) coolixTiming.oneSpace else coolixTiming.zeroSpace
        }
    }
}
