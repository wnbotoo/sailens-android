package com.sailens.app

import androidx.lifecycle.ViewModel
import com.sailens.guidance.repository.DeviceSensorRepository

class AppViewModel(
    private val deviceSensorRepository: DeviceSensorRepository,
): ViewModel() {
    init {
        deviceSensorRepository.startObserving()
    }

    override fun onCleared() {
        super.onCleared()
        deviceSensorRepository.stopObserving()
    }
}