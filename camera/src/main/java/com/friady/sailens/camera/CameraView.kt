package com.friady.sailens.camera

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friady.sailens.ux.dialog.PermissionRationaleDialog
import com.friady.sailens.ux.dialog.RationaleState
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraView(
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val permissionState = rememberPermissionState(
        permission = Permission.CAMERA
    )

    when {
        permissionState.status.isGranted -> {
            CameraContent(modifier = modifier, contentScale = contentScale)
        }

        permissionState.status.shouldShowRationale -> {
            PermissionRationaleDialog(
                rationaleState = RationaleState(
                    title = "Please grant camera permission",
                    rationale = "Really need camera permission",
                    onRationaleReply = { proceed ->
                        if (proceed) {
                            permissionState.launchPermissionRequest()
                        }
                    }
                )
            )
        }

        else -> {
            LaunchedEffect(permissionState) {
                permissionState.launchPermissionRequest()
            }
        }
    }
}

@Composable
private fun CameraContent(
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    viewModel: CameraViewModel = koinViewModel(),
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
) {
    val surfaceRequest by viewModel.surfaceRequest.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(lifecycleOwner) {
        viewModel.bindToCamera(context.applicationContext, lifecycleOwner)
    }

    surfaceRequest?.let { request ->
        // coordinateTransformer use to transform the tap coordinates from the layout’s coordinate system to the sensor’s coordinate system
        val coordinateTransformer = remember { MutableCoordinateTransformer() }
        // handle touch events and pass those events to the view model
        // use the pointerInput modifier and detectTapGestures to listen for tap events on the CameraXViewfinder
        CameraXViewfinder(
            surfaceRequest = request,
            modifier = modifier,
            contentScale = contentScale,
            coordinateTransformer = coordinateTransformer,
        )
    }
}
