package com.sailens.shell.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.sailens.camera.CameraPreview
import com.sailens.camera.rememberCameraPermissionState
import com.sailens.shell.R
import com.sailens.shell.design.components.PermissionRationaleDialog
import com.sailens.shell.design.components.RationaleState

/**
 * The camera preview plus the permission policy around it.
 *
 * sailens-camera owns capture and the permission state primitive; deciding when to explain
 * ourselves, in what words and in which design system is presentation policy, so it lives here.
 * That is what keeps sailens-camera from depending back on the shell.
 */
@Composable
fun CameraViewWithPermission(
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val permissionState = rememberCameraPermissionState()

    when {
        permissionState.isGranted -> {
            CameraPreview(modifier = modifier, contentScale = contentScale)
        }

        permissionState.shouldShowRationale -> {
            PermissionRationaleDialog(
                rationaleState = RationaleState(
                    title = stringResource(R.string.permission_camera_title),
                    rationale = stringResource(R.string.permission_camera_rationale),
                    onRationaleReply = { proceed ->
                        if (proceed) {
                            permissionState.request()
                        }
                    },
                ),
                confirmLabel = stringResource(R.string.permission_confirm),
                dismissLabel = stringResource(R.string.permission_cancel),
            )
        }

        else -> {
            LaunchedEffect(permissionState) {
                permissionState.request()
            }
        }
    }
}
