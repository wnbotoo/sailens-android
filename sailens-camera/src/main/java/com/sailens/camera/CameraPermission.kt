package com.sailens.camera

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale

object Permission {
    const val CAMERA = Manifest.permission.CAMERA
}

/**
 * The camera permission as capture sees it: what the system currently allows, and how to ask.
 *
 * This is deliberately a state primitive and nothing more. Whether to show a rationale, what it
 * says and how it looks are presentation policy, so they live in the shell -- otherwise capture
 * would depend back on the design system and no application could reuse it without that UI.
 */
@Stable
interface CameraPermissionState {
    val isGranted: Boolean

    /** True when the system says an explanation should precede the next request. */
    val shouldShowRationale: Boolean

    fun request()
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberCameraPermissionState(): CameraPermissionState {
    val permissionState = rememberPermissionState(permission = Permission.CAMERA)
    return remember(permissionState) {
        object : CameraPermissionState {
            override val isGranted: Boolean
                get() = permissionState.status.isGranted

            override val shouldShowRationale: Boolean
                get() = permissionState.status.shouldShowRationale

            override fun request() = permissionState.launchPermissionRequest()
        }
    }
}
