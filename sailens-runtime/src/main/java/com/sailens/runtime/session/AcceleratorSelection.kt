package com.sailens.runtime.session

import com.sailens.runtime.ModelAcceleratorBackend
import com.sailens.runtime.ModelAcceleratorSelectionMode
import com.sailens.runtime.toLiteRtAccelerator
import com.google.ai.edge.litert.Accelerator

/**
 * Accelerator selection policy, lifted out of the individual model providers so every LiteRT
 * model — and a future VLM engine — shares one place for "which backend, and how to fall back".
 *
 * @param mode EXPLICIT (only [preferredBackend]), PREFER_BACKEND (preferred then [fallbackOrder]),
 *   or FIRST_AVAILABLE ([fallbackOrder] in order).
 * @param preferredBackend the backend the caller asked for.
 * @param fallbackOrder the accelerators tried for PREFER_BACKEND (after preferred) and for
 *   FIRST_AVAILABLE. Per-model on purpose — e.g. the int8 obstacle models exclude GPU.
 */
data class AcceleratorSelection(
    val mode: ModelAcceleratorSelectionMode,
    val preferredBackend: ModelAcceleratorBackend,
    val fallbackOrder: List<Accelerator>,
) {
    val preferredAccelerator: Accelerator get() = preferredBackend.toLiteRtAccelerator()

    /** The accelerators to attempt, in order, for the configured [mode]. */
    fun attemptOrder(): List<Accelerator> = when (mode) {
        ModelAcceleratorSelectionMode.EXPLICIT -> listOf(preferredAccelerator)
        ModelAcceleratorSelectionMode.PREFER_BACKEND ->
            listOf(preferredAccelerator) + fallbackOrder.filterNot { it == preferredAccelerator }
        ModelAcceleratorSelectionMode.FIRST_AVAILABLE -> fallbackOrder
    }
}
