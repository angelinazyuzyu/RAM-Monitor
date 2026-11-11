
package com.example.rammonitor.data.advanced

import android.os.Process
import android.os.RemoteException
import android.util.Log
import androidx.annotation.Keep

@Keep
class AdvancedMemoryUserService : IAdvancedMemoryService.Stub() {

    init {
        Log.i(
            "RAM_MONITOR_SERVICE",
            "UserService created. UID=${Process.myUid()}, PID=${Process.myPid()}"
        )
    }

    override fun getMemInfo(packageName: String): String {
        Log.i("RAM_MONITOR_SERVICE", "getMemInfo: $packageName")

        if (!packageName.matches(
                Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
            )
        ) {
            throw RemoteException("Invalid package name")
        }

        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("dumpsys", "meminfo", packageName)
            )

            val output = process.inputStream.bufferedReader().use {
                it.readText()
            }

            val exitCode = process.waitFor()

            if (exitCode != 0) {
                throw RemoteException("dumpsys exit code: $exitCode")
            }

            output
        } catch (e: Exception) {
            Log.e("RAM_MONITOR_SERVICE", "Command failed", e)
            throw RemoteException(e.message ?: "Unknown error")
        }
    }
}