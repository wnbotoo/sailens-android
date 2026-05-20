package com.friady.sailens.data.di

import com.friady.sailens.data.repository.DefaultDepthRepository
import com.friady.sailens.data.repository.DefaultDeviceSensorRepository
import com.friady.sailens.data.repository.MLPerceptionRepository
import com.friady.sailens.data.service.FileLogService
import com.friady.sailens.data.service.FileTraceService
import com.friady.sailens.data.source.depth.ImagePositionDepthEstimator
import com.friady.sailens.data.source.device.DeviceRotationDataSource
import com.friady.sailens.data.source.mapper.ClassMapperProviderImpl
import com.friady.sailens.data.source.ml.segmentation.DDRNetSegmentationModel
import com.friady.sailens.data.source.ml.segmentation.SegmentationModel
import com.friady.sailens.domain.model.perception.ClassMapper
import com.friady.sailens.domain.model.perception.ClassMapperProvider
import com.friady.sailens.domain.repository.DepthRepository
import com.friady.sailens.domain.repository.DeviceSensorRepository
import com.friady.sailens.domain.repository.PerceptionRepository
import com.friady.sailens.domain.service.LogService
import com.friady.sailens.domain.service.TraceService
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val dataModule = module {
    single<ClassMapperProvider> {
        ClassMapperProviderImpl(
            config = get()
        )
    }

    single<ClassMapper> {
        get<ClassMapperProvider>().getSemanticClassMapper()
    }

    // Data source
    single<SegmentationModel> { DDRNetSegmentationModel(context = androidContext()) }
    single { ImagePositionDepthEstimator() }
    single { DeviceRotationDataSource(context = androidContext()) }
//    single { HardwareDepthSource(androidContext()) }

    // Repository
    single<PerceptionRepository> { MLPerceptionRepository(get()) }
    single<DepthRepository> { DefaultDepthRepository(get(), null) }
    single<DeviceSensorRepository> { DefaultDeviceSensorRepository(get()) }


    // service
    single<LogService> { FileLogService(androidContext()) }
    single<TraceService> { FileTraceService(androidContext()) }
}