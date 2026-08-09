package com.sailens.app

import com.sailens.data.source.ml.ModelAcceleratorBackend
import com.sailens.domain.model.common.ObstacleProviderType
import com.sailens.domain.model.common.PerceptionProfile
import com.sailens.presentation.scene.SceneOverlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SailensRuntimeProfileTest {
    @Test
    fun `standard profile keeps all vision models on GPU`() {
        val profile = SailensRuntimeProfile.standard(targetHardwareProfile = "qualcomm_sm8750")

        assertEquals("standard", profile.name)
        assertEquals(SailensPerformanceTier.STANDARD, profile.tier)
        assertEquals("qualcomm_sm8750", profile.targetHardwareProfile)
        assertEquals(960, profile.camera.analysisWidth)
        assertEquals(540, profile.camera.analysisHeight)
        assertEquals("standard", profile.perception.runtimeProfileName)
        assertEquals(profile.targetHardwareProfile, profile.perception.targetHardwareProfile)
        assertEquals(ModelAcceleratorBackend.GPU, profile.semanticModel.acceleratorBackend)
        assertEquals(ModelAcceleratorBackend.GPU, profile.realtimeObstacleModel.acceleratorBackend)
        assertEquals(ModelAcceleratorBackend.GPU, profile.vlmModelBackend)
        assertEquals(ObstacleProviderType.DETECTION_MODEL, profile.perception.realtimeObstacleProviderType)
        // 出厂默认标准挡（sem+det）
        assertEquals(PerceptionProfile.DEFAULT, profile.perception.profile)
        // 默认不限频：设备尽力而为，正值上限留给动态降级
        assertEquals(0, profile.perception.semanticTargetFps)
        assertEquals(0, profile.perception.detectionTargetFps)
        assertTrue(profile.perception.detectionEnabled)
        assertEquals(false, profile.analysis.enableIntersectionFallback)
        assertTrue(profile.analysis.enableIntersectionEvents)
        assertEquals(false, profile.analysis.enableRoadWarningEvents)
        assertEquals(false, profile.analysis.enableGroundChangeEvents)
        assertTrue(profile.analysis.enableProximityPrefix)
        // 镜头遮挡/过暗是用户无法自行察觉的失效，必须默认开。
        assertTrue(profile.analysis.enableSensorQualityEvents)
        assertTrue(profile.trace.enabled)
        assertTrue(profile.sceneOverlay.enablePassableAreaMaskOverlay)
        assertTrue(profile.sceneOverlay.enableSemanticClassMaskOverlay)
        assertTrue(profile.sceneOverlay.enableDetectionOverlay)
        assertTrue(profile.sceneOverlay.enableDebugPanel)
        assertEquals(SceneOverlayMode.PASSABLE_AREA_MASK, profile.sceneOverlay.initialMode)
    }

    @Test
    fun `ultra profile reserves NPU for VLM and keeps vision models on GPU`() {
        val profile = SailensRuntimeProfile.ultra(targetHardwareProfile = "qualcomm_sm8750")

        assertEquals("ultra", profile.name)
        assertEquals(SailensPerformanceTier.ULTRA, profile.tier)
        assertEquals(ModelAcceleratorBackend.GPU, profile.semanticModel.acceleratorBackend)
        assertEquals(ModelAcceleratorBackend.GPU, profile.realtimeObstacleModel.acceleratorBackend)
        assertEquals(ModelAcceleratorBackend.NPU, profile.vlmModelBackend)
        assertEquals("ultra", profile.perception.runtimeProfileName)
    }

    @Test
    fun `basic perception profile runs semantic only`() {
        val profile = SailensRuntimeProfile.standard(
            targetHardwareProfile = "qualcomm_sm8750",
            perceptionProfile = PerceptionProfile.BASIC,
        )

        assertEquals(PerceptionProfile.BASIC, profile.perception.profile)
        assertFalse(profile.perception.detectionEnabled)
    }

    @Test
    fun `profile selector keeps hardware on standard without VLM NPU`() {
        assertEquals(
            SailensPerformanceTier.STANDARD,
            SailensRuntimeProfile.selectTier("qualcomm_sm8750-ab"),
        )
        assertEquals(
            SailensPerformanceTier.STANDARD,
            SailensRuntimeProfile.selectTier("qti_sm8850"),
        )
        assertEquals(
            SailensPerformanceTier.STANDARD,
            SailensRuntimeProfile.selectTier("google_tensor_g5"),
        )
    }

    @Test
    fun `profile selector uses ultra only when VLM NPU is available`() {
        assertEquals(
            SailensPerformanceTier.ULTRA,
            SailensRuntimeProfile.selectTier(
                targetHardwareProfile = "qualcomm_sm8850",
                vlmNpuAvailable = true,
            ),
        )
    }

    @Test
    fun `diagnostics disabled profile disables trace and bitmap overlays`() {
        val profile = SailensRuntimeProfile.standard(
            targetHardwareProfile = "qualcomm_sm8750",
            enableDiagnostics = false,
        )

        assertFalse(profile.trace.enabled)
        assertEquals(emptySet<SceneOverlayMode>(), profile.sceneOverlay.enabledOverlayModes)
        assertEquals(SceneOverlayMode.OFF, profile.sceneOverlay.effectiveInitialMode)
        assertEquals(500L, profile.sceneOverlay.bitmapRenderIntervalMs)
    }

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
