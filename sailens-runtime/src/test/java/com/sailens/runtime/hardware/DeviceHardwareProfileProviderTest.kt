package com.sailens.runtime.hardware

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The runtime hardware label. It is the key the trace and the backend reporting are read against,
 * so it has to stay stable for a given device rather than drift with Build field formatting.
 */
class DeviceHardwareProfileProviderTest {

    @Test
    fun `hardware profile formatter prefers SoC fields`() {
        val profile = DeviceHardwareProfileProvider.format(
            DeviceHardwareProfileProvider.BuildFields(
                socManufacturer = "Qualcomm",
                socModel = "SM8750-AB",
                hardware = "qcom",
                board = "pineapple",
                model = "Test Phone",
            )
        )

        assertEquals("qualcomm_sm8750-ab", profile)
    }

    @Test
    fun `hardware profile formatter falls back to board fields`() {
        val profile = DeviceHardwareProfileProvider.format(
            DeviceHardwareProfileProvider.BuildFields(
                socManufacturer = "unknown",
                socModel = "",
                hardware = "qcom",
                board = "pineapple",
                model = "Test Phone",
            )
        )

        assertEquals("qcom_pineapple_test_phone", profile)
    }
}
