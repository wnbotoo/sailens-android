package com.sailens.shell.device

import android.content.Context
import android.os.PowerManager
import com.sailens.guidance.depth.geometry.ThermalLevel
import com.sailens.guidance.depth.geometry.ThermalStatusSource

/**
 * The device's thermal status for the depth lane, kept current by `PowerManager`'s listener so that a read is a field
 * access. The listener is registered on the first read -- a build whose lane never runs registers none -- and stays for
 * the life of the app (this is a single). Without a `PowerManager` it reports [ThermalLevel.NONE]. Only the depth lane
 * reads it; Guidance's own models never slow down for it.
 */
internal class AndroidThermalStatusSource(private val context: Context) : ThermalStatusSource {
    @Volatile
    private var level: ThermalLevel = ThermalLevel.NONE

    private val listening = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        context.getSystemService(PowerManager::class.java)?.also { power ->
            level = levelOf(power.currentThermalStatus)
            power.addThermalStatusListener(context.mainExecutor) { status -> level = levelOf(status) }
        }
    }

    override fun current(): ThermalLevel {
        listening.value
        return level
    }

    companion object {
        /** SEVERE, CRITICAL, EMERGENCY and SHUTDOWN all pause the lane. */
        fun levelOf(status: Int): ThermalLevel = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
            else -> if (status > PowerManager.THERMAL_STATUS_MODERATE) ThermalLevel.SEVERE else ThermalLevel.NONE
        }
    }
}
