
package com.example.rammonitor.data.advanced

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

enum class ShizukuStatus {
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    READY,
    UNSUPPORTED
}

class ShizukuManager(
    private val onStatusChanged: (ShizukuStatus) -> Unit
) {

    companion object {
        const val REQUEST_CODE = 1001
    }

    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            updateStatus()
        }

    private val binderDeadListener =
        Shizuku.OnBinderDeadListener {
            onStatusChanged(ShizukuStatus.NOT_RUNNING)
        }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                updateStatus()
            }
        }

    private var registered = false

    fun register() {
        if (registered) return

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        registered = true
        updateStatus()
    }

    fun unregister() {
        if (!registered) return

        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)

        registered = false
    }

    fun getStatus(): ShizukuStatus {
        if (!Shizuku.pingBinder()) {
            return ShizukuStatus.NOT_RUNNING
        }

        if (Shizuku.isPreV11()) {
            return ShizukuStatus.UNSUPPORTED
        }

        return if (
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        ) {
            ShizukuStatus.READY
        } else {
            ShizukuStatus.PERMISSION_REQUIRED
        }
    }

    fun requestPermission() {
        if (!Shizuku.pingBinder()) {
            updateStatus()
            return
        }

        if (getStatus() == ShizukuStatus.PERMISSION_REQUIRED) {
            Shizuku.requestPermission(REQUEST_CODE)
        } else {
            updateStatus()
        }
    }

    fun updateStatus() {
        onStatusChanged(getStatus())
    }
}
