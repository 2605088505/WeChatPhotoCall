package com.family.photocall.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.family.photocall.R

/**
 * 与无障碍同进程（:a11y）的前台服务：
 * 1) 保活，降低 ColorOS 冻结概率
 * 2) 作为主界面触发自动拨号的入口（跨进程可靠）
 */
class CallKeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_CALL -> {
                val contactId = intent.getStringExtra(EXTRA_CONTACT_ID)
                val forceDry = if (intent.hasExtra(EXTRA_FORCE_DRY_RUN)) {
                    intent.getBooleanExtra(EXTRA_FORCE_DRY_RUN, true)
                } else null
                createChannel()
                startAsForeground(intent.getStringExtra(EXTRA_TEXT) ?: "正在自动操作微信…")
                if (contactId.isNullOrBlank()) {
                    toast("联系人无效")
                } else {
                    triggerCall(contactId, forceDry, attempt = 1)
                }
            }
            else -> {
                createChannel()
                startAsForeground(intent?.getStringExtra(EXTRA_TEXT) ?: "自动点击保活中…")
            }
        }
        // This service only exists for one user-triggered flow. A sticky restart
        // can replay a stale call after the flow has already finished.
        return START_NOT_STICKY
    }

    private fun triggerCall(contactId: String, forceDry: Boolean?, attempt: Int) {
        val svc = PhotoCallAccessibilityService.instance
        if (svc != null) {
            Log.i(TAG, "triggerCall via a11y instance attempt=$attempt")
            svc.startCall(contactId, forceDry)
            return
        }
        if (attempt >= 8) {
            Log.e(TAG, "a11y instance still null")
            toast("无障碍服务未就绪，请到设置里重新开关一次“亲情照片通话”")
            stopSelf()
            return
        }
        // 服务可能刚绑定，稍等再试
        handler.postDelayed({ triggerCall(contactId, forceDry, attempt + 1) }, 300)
    }

    private fun startAsForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("亲情照片通话")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "自动拨号保活", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val TAG = "PhotoCallKeepAlive"
        private const val CHANNEL_ID = "call_keep_alive"
        private const val NOTI_ID = 10086
        const val ACTION_STOP = "com.family.photocall.action.STOP_KEEP_ALIVE"
        const val ACTION_START_CALL = "com.family.photocall.action.START_CALL_SERVICE"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_FORCE_DRY_RUN = "force_dry_run"

        fun start(context: Context, text: String) {
            val i = Intent(context, CallKeepAliveService::class.java)
                .putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun startCall(context: Context, contactId: String, forceDryRun: Boolean? = null) {
            val i = Intent(context, CallKeepAliveService::class.java).apply {
                action = ACTION_START_CALL
                putExtra(EXTRA_CONTACT_ID, contactId)
                putExtra(EXTRA_TEXT, "正在自动操作微信…")
                if (forceDryRun != null) putExtra(EXTRA_FORCE_DRY_RUN, forceDryRun)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, CallKeepAliveService::class.java).setAction(ACTION_STOP)
                )
            } catch (_: Exception) {
            }
        }
    }
}
