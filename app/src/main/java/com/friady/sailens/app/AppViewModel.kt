package com.friady.sailens.app

import androidx.lifecycle.ViewModel
import com.friady.sailens.domain.repository.DeviceSensorRepository

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