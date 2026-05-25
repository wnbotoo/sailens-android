package com.friady.sailens.data.di

import com.friady.sailens.data.repository.DefaultDepthRepository
import com.friady.sailens.data.repository.DefaultDeviceSensorRepository
import com.friady.sailens.data.repository.MLPerceptionRepository
import com.friady.sailens.data.service.FileLogService
import com.friady.sailens.data.service.FileTraceReplayService
import com.friady.sailens.data.service.FileTraceService
import com.friady.sailens.data.source.depth.ImagePositionDepthEstimator
import com.friady.sailens.data.source.device.DeviceRotationDataSource
import com.friady.sailens.data.source.mapper.ClassMapperProviderImpl
import com.friady.sailens.data.source.ml.analysis.NativeConnectivityChecker
import com.friady.sailens.data.source.ml.instance.YOLO26SegInstanceProvider
import com.friady.sailens.data.source.ml.semantic.NativeSegmentationAnalyzer
import com.friady.sailens.data.source.ml.semantic.SegmentationModel
import com.friady.sailens.data.source.ml.semantic.YOLO26SemSegmentationModel
import com.friady.sailens.domain.model.perception.ClassMapper
import com.friady.sailens.domain.model.perception.ClassMapperProvider
import com.friady.sailens.domain.processor.analysis.ConnectivityAnalysisProcessor
import com.friady.sailens.domain.processor.perception.SegmentationAnalysisProcessor
import com.friady.sailens.domain.repository.DepthRepository
import com.friady.sailens.domain.repository.DeviceSensorRepository
import com.friady.sailens.domain.repository.InstanceSegmentationProvider
import com.friady.sailens.domain.repository.PerceptionRepository
import com.friady.sailens.domain.service.LogService
import com.friady.sailens.domain.service.TraceReplayService
import com.friady.sailens.domain.service.TraceService
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val dataModule = module {
    single<ClassMapperProvider> {
        ClassMapperProviderImpl()
    }

    single<ClassMapper> {
        get<ClassMapperProvider>().getSemanticClassMapper()
    }

    single {
        NativeSegmentationAnalyzer(
            config = get(),
            classMapper = get(),
        )
    }

    single<SegmentationAnalysisProcessor> { get<NativeSegmentationAnalyzer>() }

    single<ConnectivityAnalysisProcessor> {
        NativeConnectivityChecker(config = get())
    }

    // Data source
    single<SegmentationModel> {
        YOLO26SemSegmentationModel(
            context = androidContext(),
            modelConfig = get(),
            nativeSegmentationAnalyzer = get<NativeSegmentationAnalyzer>(),
        )
    }
    single<InstanceSegmentationProvider> {
        YOLO26SegInstanceProvider(
            context = androidContext(),
            perceptionConfig = get(),
            modelConfig = get(),
        )
    }
    single { ImagePositionDepthEstimator() }
    single { DeviceRotationDataSource(context = androidContext()) }

    // Repository
    single<PerceptionRepository> { MLPerceptionRepository(get()) }
    single<DepthRepository> { DefaultDepthRepository(get(), null) }
    single<DeviceSensorRepository> { DefaultDeviceSensorRepository(get()) }


    // service
    single<LogService> { FileLogService(androidContext()) }
    single<TraceService> { FileTraceService(androidContext()) }
    single<TraceReplayService> { FileTraceReplayService(androidContext()) }
}
