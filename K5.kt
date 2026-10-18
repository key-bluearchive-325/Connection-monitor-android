package com.example.datausage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val CH_ID = "speed"
const val NOTIF_ID = 1001

fun setSpeedNotif(ctx: Context, on: Boolean) {
    ctx.getSharedPreferences("s", 0).edit().putBoolean("notif", on).apply()
    val i = Intent(ctx, SpeedService::class.java)
    if (on) runCatching { ctx.startForegroundService(i) } else ctx.stopService(i)
}

class SpeedService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ID, "通信速度", NotificationManager.IMPORTANCE_LOW)
        )
        val first = build("計測中…", "")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, first)
        }
        job?.cancel()
        job = scope.launch { loop(nm) }
        return START_STICKY
    }

    private fun build(title: String, text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .build()
    }

    private suspend fun loop(nm: NotificationManager) {
        val pm = getSystemService(PowerManager::class.java)
        var rx = TrafficStats.getTotalRxBytes()
        var tx = TrafficStats.getTotalTxBytes()
        var sw = 0L
        var sm = 0L
        var lastQ = 0L
        while (true) {
            delay(1000)
            val r = TrafficStats.getTotalRxBytes()
            val t = TrafficStats.getTotalTxBytes()
            val dr = (r - rx).coerceAtLeast(0L)
            val dt = (t - tx).coerceAtLeast(0L)
            rx = r
            tx = t
            if (!pm.isInteractive) continue
            val mult = getSharedPreferences("s", 0).getFloat("mult", 1f).toDouble()
            val now = System.currentTimeMillis()
            if (now - lastQ > 15000) {
                runCatching {
                    val m = queryDay(this, startOfDay(now), now, false)
                    var w = 0L
                    var mo = 0L
                    for (a in m.values) { w += a[0]; mo += a[1] }
                    sw = w
                    sm = mo
                }
                lastQ = now
            }
            nm.notify(
                NOTIF_ID,
                build(
                    "↓ ${fmtD(dr * mult)}/s   ↑ ${fmtD(dt * mult)}/s",
                    "今日 ${fmt(sw + sm)}  ・  Wi-Fi ${fmt(sw)} / モバイル ${fmt(sm)}"
                )
            )
        }
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}

/** 端末の再起動後、通知がオンなら自動で再開 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (ctx.getSharedPreferences("s", 0).getBoolean("notif", false)) {
            runCatching { ctx.startForegroundService(Intent(ctx, SpeedService::class.java)) }
        }
    }
}
// END