package com.watchparty

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchparty.ui.navigation.AppNavHost
import com.watchparty.ui.theme.WatchPartyTheme
import com.watchparty.viewmodel.RoomViewModel

data class RuntimePermissionState(
    val cameraGranted: Boolean,
    val micGranted: Boolean,
    val notificationsGranted: Boolean,
    val deniedOnce: Boolean,
    val shouldShowRationale: Boolean,
    val permanentlyDenied: Boolean
)

class MainActivity : ComponentActivity() {

    private var permissionState by mutableStateOf<RuntimePermissionState?>(null)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            permissionState = readRuntimePermissionState(
                deniedOnce = grants.any { (_, granted) -> !granted }
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionState = readRuntimePermissionState(deniedOnce = false)
        requestRuntimePermissionsIfNeeded()

        setContent {
            WatchPartyTheme {
                val permissions = permissionState ?: readRuntimePermissionState(deniedOnce = false)
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        val roomViewModel: RoomViewModel = viewModel()
                        AppNavHost(
                            roomViewModel = roomViewModel,
                            cameraGranted = permissions.cameraGranted,
                            micGranted = permissions.micGranted
                        )
                        if ((!permissions.cameraGranted || !permissions.micGranted) && permissions.deniedOnce) {
                            PermissionRationaleBanner(
                                permanentlyDenied = permissions.permanentlyDenied,
                                onRetry = { requestRuntimePermissionsIfNeeded(force = true) }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestRuntimePermissionsIfNeeded(force: Boolean = false) {
        val missing = permissionsToRequest().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty() && (force || permissionState?.deniedOnce != true)) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun permissionsToRequest(): List<String> {
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        return needed
    }

    private fun readRuntimePermissionState(deniedOnce: Boolean): RuntimePermissionState {
        val cameraGranted = isGranted(Manifest.permission.CAMERA)
        val micGranted = isGranted(Manifest.permission.RECORD_AUDIO)
        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isGranted(Manifest.permission.POST_NOTIFICATIONS)
        val mediaDenied = !cameraGranted || !micGranted
        val shouldShowRationale =
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ||
                shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)

        return RuntimePermissionState(
            cameraGranted = cameraGranted,
            micGranted = micGranted,
            notificationsGranted = notificationsGranted,
            deniedOnce = deniedOnce,
            shouldShowRationale = shouldShowRationale,
            permanentlyDenied = deniedOnce && mediaDenied && !shouldShowRationale
        )
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun PermissionRationaleBanner(permanentlyDenied: Boolean, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (permanentlyDenied) {
                "Camera is off. Enable camera/mic in system settings to join with video."
            } else {
                "Camera and mic are optional, but needed for live video chat."
            },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry) {
            Text(if (permanentlyDenied) "Try again" else "Allow")
        }
    }
}
