package com.sailens.guidance.geometry

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneGeometrySettingsTest {

    @Test
    fun `nothing said gives the seed, and a body height alone says nothing about the phone`() {
        assertEquals(PhoneGeometrySettings.DEFAULT, PhoneGeometrySettings.of(null, null))
        assertEquals(PhoneGeometrySettings.DEFAULT, PhoneGeometrySettings.of(null, 175))
    }

    @Test
    fun `a placement gives its preset height`() {
        for (placement in Placement.entries) {
            assertEquals(
                PhoneGeometrySettings(placement.presetHeightMeters, HeightSource.PRESET, placement),
                PhoneGeometrySettings.of(placement, null),
            )
        }
    }

    @Test
    fun `a placement and a body height give the ratio of it, to the centimetre`() {
        assertEquals(PhoneGeometrySettings(1.23f, HeightSource.CALIBRATED, Placement.HANDHELD_CHEST), PhoneGeometrySettings.of(Placement.HANDHELD_CHEST, 175))
        assertEquals(PhoneGeometrySettings(1.17f, HeightSource.CALIBRATED, Placement.CHEST_LANYARD), PhoneGeometrySettings.of(Placement.CHEST_LANYARD, 160))
        assertEquals(PhoneGeometrySettings(1.04f, HeightSource.CALIBRATED, Placement.WAIST), PhoneGeometrySettings.of(Placement.WAIST, 180))
    }

    @Test
    fun `a body height out of range is ignored`() {
        for (cm in listOf(0, 99, 221, 1750)) {
            assertEquals(HeightSource.PRESET, PhoneGeometrySettings.of(Placement.WAIST, cm).source)
        }
        assertEquals(HeightSource.CALIBRATED, PhoneGeometrySettings.of(Placement.WAIST, 100).source)
        assertEquals(HeightSource.CALIBRATED, PhoneGeometrySettings.of(Placement.WAIST, 220).source)
    }
}
