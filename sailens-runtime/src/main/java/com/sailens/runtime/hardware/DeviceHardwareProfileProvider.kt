package com.sailens.runtime.hardware

import android.os.Build

object DeviceHardwareProfileProvider {
    fun detect(): String {
        return format(
            BuildFields(
                socManufacturer = Build.SOC_MANUFACTURER,
                socModel = Build.SOC_MODEL,
                hardware = Build.HARDWARE,
                board = Build.BOARD,
                model = Build.MODEL,
            )
        )
    }

    internal fun format(fields: BuildFields): String {
        val soc = listOfNotNull(
            fields.socManufacturer.cleanPart(),
            fields.socModel.cleanPart(),
        ).joinToString("_").takeIf { it.isNotBlank() }
        if (soc != null) return soc

        val fallback = listOfNotNull(
            fields.hardware.cleanPart(),
            fields.board.cleanPart(),
            fields.model.cleanPart(),
        ).joinToString("_").takeIf { it.isNotBlank() }
        return fallback ?: UNKNOWN_HARDWARE
    }

    internal data class BuildFields(
        val socManufacturer: String?,
        val socModel: String?,
        val hardware: String?,
        val board: String?,
        val model: String?,
    )

    private fun String?.cleanPart(): String? {
        val cleaned = this
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9._-]+"), "_")
            ?.trim('_', '.', '-')
        return cleaned?.takeIf { it.isNotBlank() && it != "unknown" }
    }

    private const val UNKNOWN_HARDWARE = "unknown_hardware"
}
