
package com.example.rammonitor.data.advanced

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class ShizukuMemoryClient(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var remoteService: IAdvancedMemoryService? = null

    private var isBound = false

    private var connectionCallback: ((Boolean, String?) -> Unit)? = null

    private val args = Shizuku.UserServiceArgs(
        ComponentName(
            appContext.packageName,
            AdvancedMemoryUserService::class.java.name
        )
    )
        .daemon(false)
        .processNameSuffix("advanced_memory")
        .tag("ram_monitor_memory")
        .version(2)

    private val serviceConnection = object : ServiceConnection {

        override fun onServiceConnected(
            name: ComponentName?,
            binder: IBinder?
        ) {
            remoteService = IAdvancedMemoryService.Stub.asInterface(binder)

            mainHandler.post {
                connectionCallback?.invoke(
                    remoteService != null,
                    if (remoteService != null) null else "Не удалось получить Binder"
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteService = null

            mainHandler.post {
                connectionCallback?.invoke(
                    false,
                    "Shizuku UserService отключён"
                )
            }
        }
    }

    fun connect(callback: (Boolean, String?) -> Unit) {
        connectionCallback = callback

        if (!Shizuku.pingBinder()) {
            callback(false, "Shizuku не запущен")
            return
        }

        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            callback(false, "Нет разрешения Shizuku")
            return
        }

        if (isBound) {
            callback(remoteService != null, null)
            return
        }

        try {
            isBound = true
            Shizuku.bindUserService(args, serviceConnection)
        } catch (e: Exception) {
            isBound = false
            callback(false, e.message ?: "Ошибка подключения UserService")
        }
    }

    fun getMemInfo(
        packageName: String,
        callback: (String?, String?) -> Unit
    ) {
        executor.execute {
            try {
                val service = remoteService
                    ?: throw RemoteException("UserService ещё не подключён")

                val result = service.getMemInfo(packageName)

                mainHandler.post {
                    callback(result, null)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    callback(null, e.message ?: "Ошибка получения памяти")
                }
            }
        }
    }

    fun disconnect() {
        if (isBound) {
            try {
                Shizuku.unbindUserService(
                    args,
                    serviceConnection,
                    false
                )
            } catch (_: Exception) {
            }
        }

        isBound = false
        remoteService = null
        connectionCallback = null
    }
}
