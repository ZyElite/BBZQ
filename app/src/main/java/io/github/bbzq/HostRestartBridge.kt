package io.github.bbzq

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Restarts scoped target processes without sending their identity to an external shell. */
object HostRestartBridge {
    private const val ACTION_PREPARE = "io.github.bbzq.action.PREPARE_HOST_RESTART"
    private const val EXTRA_REQUEST = "request_id"
    private const val TOKEN_PREFIX = "process_"
    private const val DESCRIPTOR = "io.github.bbzq.HostRestart"
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun install(context: Context, xposed: XposedInterface, packageName: String, processName: String) {
        if (xposed.apiVersion < XposedInterface.API_102 ||
            (xposed.frameworkProperties and XposedInterface.PROP_CAP_REMOTE) == 0L
        ) return
        try {
            val remotePrefs = xposed.getRemotePreferences(ModuleSettings.PREFS_NAME)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != ACTION_PREPARE) return
                    val requestId = intent.getStringExtra(EXTRA_REQUEST) ?: return
                    val issuedAt = requestId.substringBefore(':').toLongOrNull() ?: return
                    if (System.currentTimeMillis() - issuedAt !in 0L..60_000L ||
                        remotePrefs.getString(ModuleSettings.KEY_HOST_RESTART_REQUEST_ID, null) != requestId
                    ) return
                    // The nonce is published only through the module's scoped preferences.
                    // Preparation does not stop a process until the caller observes its Binder.
                    val token = object : Binder() {
                        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                            if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                            data.enforceInterface(DESCRIPTOR)
                            if (data.readString() != requestId) throw SecurityException("Restart request mismatch")
                            mainHandler.post {
                                xposed.log(Log.INFO, "BBZQ", "Restarting scoped process $packageName/$processName")
                                Process.killProcess(Process.myPid())
                                exitProcess(0)
                            }
                            return true
                        }
                    }
                    val result = getResultExtras(true) ?: Bundle()
                    result.putBinder(TOKEN_PREFIX + Process.myPid(), token)
                    setResultExtras(result)
                    resultCode = Activity.RESULT_OK
                }
            }
            val appContext = context.applicationContext ?: context
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, IntentFilter(ACTION_PREPARE), Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, IntentFilter(ACTION_PREPARE))
            }
        } catch (throwable: Throwable) {
            xposed.log(Log.WARN, "BBZQ", "Host restart receiver unavailable for $packageName/$processName", throwable)
        }
    }

    fun restart(
        context: Context,
        targetPackage: String,
        launchIntent: Intent,
        callback: (Boolean, String?, String?) -> Unit,
    ) {
        val requestId = "${System.currentTimeMillis()}:${UUID.randomUUID()}"
        thread(name = "BBZQ-HostRestart", isDaemon = true) {
            try {
                ModuleRemotePreferences.prepareHostRestart(requestId)
            } catch (throwable: Throwable) {
                Log.w("BBZQ", "Host restart preference commit failed", throwable)
                mainHandler.post { callback(false, throwable.message, "Xposed") }
                return@thread
            }
            mainHandler.post {
                var finished = false
                var preparing = true
                var attempts = 0
                val pending = mutableMapOf<IBinder, IBinder.DeathRecipient>()
                fun finish(error: String? = null) {
                    if (finished) return
                    finished = true
                    pending.forEach { (token, recipient) ->
                        runCatching { token.unlinkToDeath(recipient, 0) }
                            .onFailure { Log.w("BBZQ", "Host restart death listener cleanup failed", it) }
                    }
                    pending.clear()
                    callback(error == null, error, "Xposed")
                }
                fun prepare() {
                    if (finished) return
                    attempts++
                    try {
                        context.sendOrderedBroadcast(
                            Intent(ACTION_PREPARE).setPackage(targetPackage)
                                .putExtra(EXTRA_REQUEST, requestId).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                            null,
                            object : BroadcastReceiver() {
                                override fun onReceive(receiverContext: Context, intent: Intent) {
                                    if (finished) return
                                    try {
                                    val result = getResultExtras(false)
                                    val tokens = result?.keySet().orEmpty()
                                        .filter { it.startsWith(TOKEN_PREFIX) }
                                        .mapNotNull { key -> result?.getBinder(key) }
                                    if (resultCode != Activity.RESULT_OK || tokens.isEmpty()) {
                                        if (attempts >= 40) {
                                            finish(context.getString(R.string.restart_failed_module_unavailable))
                                        } else {
                                            if (attempts == 1) {
                                                context.startActivity(Intent(launchIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                            }
                                            mainHandler.postDelayed(::prepare, 500L)
                                        }
                                        return
                                    }
                                    if (result?.containsKey(TOKEN_PREFIX + Process.myPid()) == true) {
                                        finish(context.getString(R.string.restart_failed_same_process))
                                        return
                                    }
                                    preparing = false
                                    tokens.forEach { token ->
                                        val recipient = IBinder.DeathRecipient {
                                            mainHandler.post {
                                                if (pending.remove(token) != null && pending.isEmpty()) finish()
                                            }
                                        }
                                        try {
                                            token.linkToDeath(recipient, 0)
                                            pending[token] = recipient
                                        } catch (throwable: RemoteException) {
                                            if (token.isBinderAlive) throw throwable
                                            // This prepared process has already exited.
                                        }
                                    }
                                    if (pending.isEmpty()) {
                                        finish()
                                        return
                                    }
                                    pending.keys.toList().forEach { token ->
                                        val data = Parcel.obtain()
                                        try {
                                            data.writeInterfaceToken(DESCRIPTOR)
                                            data.writeString(requestId)
                                            check(token.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY)) {
                                                "Target did not accept the restart transaction"
                                            }
                                        } catch (throwable: RemoteException) {
                                            if (token.isBinderAlive) throw throwable
                                            // Binder death, rather than transaction submission, confirms exit.
                                        } finally {
                                            data.recycle()
                                        }
                                    }
                                    mainHandler.postDelayed({
                                        if (!finished) finish(context.getString(R.string.restart_failed_process_timeout))
                                    }, 10_000L)
                                    } catch (throwable: Throwable) {
                                        Log.w("BBZQ", "Host restart stop request failed", throwable)
                                        finish(throwable.message ?: throwable.javaClass.simpleName)
                                    }
                                }
                            },
                            mainHandler, Activity.RESULT_CANCELED, null, Bundle(),
                        )
                    } catch (throwable: Throwable) {
                        Log.w("BBZQ", "Host restart preparation failed", throwable)
                        finish(throwable.message ?: throwable.javaClass.simpleName)
                    }
                }
                mainHandler.postDelayed({
                    if (!finished && preparing) finish(context.getString(R.string.restart_failed_module_unavailable))
                }, 20_000L)
                prepare()
            }
        }
    }
}
