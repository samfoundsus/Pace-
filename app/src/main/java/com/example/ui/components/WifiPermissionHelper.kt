package com.example.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat

enum class WifiNameAccessIssue {
    NONE,
    PERMISSION_DENIED,
    PERMISSION_PERMANENTLY_DENIED,
    LOCATION_OFF,
}

fun resolveWifiNameAccessIssue(
    hasPermission: Boolean,
    isLocationEnabled: Boolean,
    permissionDenied: Boolean,
    permissionPermanentlyDenied: Boolean,
): WifiNameAccessIssue = when {
    hasPermission && !isLocationEnabled -> WifiNameAccessIssue.LOCATION_OFF
    !hasPermission && permissionPermanentlyDenied -> WifiNameAccessIssue.PERMISSION_PERMANENTLY_DENIED
    !hasPermission && permissionDenied -> WifiNameAccessIssue.PERMISSION_DENIED
    else -> WifiNameAccessIssue.NONE
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun openAppDetailsSettings(context: Context) {
    runCatching {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

fun openLocationSettings(context: Context) {
    runCatching {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/**
 * Returns a lambda that requests ACCESS_FINE_LOCATION ONLY when invoked on user tap.
 */
@Composable
fun rememberWifiLocationPermissionLauncher(
    onResult: (granted: Boolean, permanentlyDenied: Boolean) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { fineGranted ->
        val activity = context.findActivity()
        val shouldShowRationale = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val permanentlyDenied = !fineGranted && !shouldShowRationale
        onResult(fineGranted, permanentlyDenied)
    }

    return {
        launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

/**
 * Returns a lambda that requests READ_PHONE_STATE on Android 11+ ONLY when the user explicitly
 * taps "Show network type". Never called at app launch.
 */
@Composable
fun rememberPhoneStatePermissionLauncher(
    onResult: (granted: Boolean) -> Unit,
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onResult(granted)
    }

    return {
        launcher.launch(Manifest.permission.READ_PHONE_STATE)
    }
}
