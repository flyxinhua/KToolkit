package com.sanvar.ktoolkit.permission

import android.Manifest
import android.os.Build


fun bleNeedPermissions() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
} else {
    listOf(Manifest.permission.ACCESS_FINE_LOCATION)
}