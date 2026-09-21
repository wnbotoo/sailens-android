package com.sailens.guidance.processor.perception

import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.model.common.ObstacleProviderType
import com.sailens.guidance.model.common.PerceptionProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class PerceptionProfileManagerTest {

    private val initialConfig = PerceptionConfig.forProfile(
        profile = PerceptionProfile.DEFAULT,
        runtimeProfileName = "standard",
        targetHardwareProfile = "qualcomm_sm8750",
    )

    @Test
    fun `selecting a profile does not change active config until activated`() {
        val manager = PerceptionProfileManager(initialConfig)

        manager.selectProfile(PerceptionProfile.BASIC)

        assertEquals(PerceptionProfile.BASIC, manager.selectedProfile)
        assertEquals(PerceptionProfile.DEFAULT, manager.activeConfig.profile)
    }

    @Test
    fun `activateSelected rebuilds config for the selected profile keeping template identity`() {
        val manager = PerceptionProfileManager(initialConfig)
        manager.selectProfile(PerceptionProfile.BASIC)

        val active = manager.activateSelected()

        assertEquals(PerceptionProfile.BASIC, active.profile)
        assertEquals(false, active.detectionEnabled)
        assertEquals("standard", active.runtimeProfileName)
        assertEquals("qualcomm_sm8750", active.targetHardwareProfile)
        assertEquals(ObstacleProviderType.DETECTION_MODEL, active.realtimeObstacleProviderType)
        assertEquals(active, manager.activeConfig)
    }

    @Test
    fun `activateSelected preserves non-default template fields across profile switch`() {
        val tuned = initialConfig.copy(detectionTargetFps = 8, minObstacleConfidence = 0.6f)
        val manager = PerceptionProfileManager(tuned)
        manager.selectProfile(PerceptionProfile.BASIC)

        val active = manager.activateSelected()

        assertEquals(PerceptionProfile.BASIC, active.profile)
        assertEquals(8, active.detectionTargetFps)
        assertEquals(0.6f, active.minObstacleConfidence, 0.0f)
    }

    @Test
    fun `activateSelected keeps the same config when profile is unchanged`() {
        val manager = PerceptionProfileManager(initialConfig)

        val active = manager.activateSelected()

        assertEquals(initialConfig, active)
    }
}
