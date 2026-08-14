package com.example.crowdmap.yeobaek.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.crowdmap.R
import com.example.crowdmap.yeobaek.data.SurgeEvent
import com.example.crowdmap.yeobaek.data.SurgeStream
import com.example.crowdmap.yeobaek.ui.PlannerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 화면이 꺼져 있어도 코스 혼잡 급증을 알려주는 포그라운드 서비스 (모듈4 확장).
 *
 * 플래너 화면은 `repeatOnLifecycle(STARTED)` 로 구독하므로 앱을 내리면 감시가 멈춘다.
 * 실제 여행 중에는 폰을 주머니에 넣고 다니니 그때야말로 알림이 필요하다 — 그래서 감시를
 * 서비스로 옮기고, 급증 이벤트가 오면 시스템 알림을 띄운다.
 *
 * 포그라운드 서비스인 이유: 백그라운드 제한(Doze·백그라운드 실행 제한)에서 살아남아
 * SSE 연결을 유지하려면 사용자에게 보이는 상시 알림이 필요하다. 사용자가 플래너에서
 * 직접 켜야 시작되고, 상시 알림의 "감시 끄기"로 언제든 끌 수 있다.
 *
 * ⚠️ Play 정책: `foregroundServiceType="dataSync"` 는 Play Console 에 용도 선언이 필요하다
 * (여행 중 혼잡 급증 알림). Android 15+ 에서는 dataSync 총 실행시간이 하루 6시간으로
 * 제한되는데, 코스 단위(몇 시간) 사용이라 실사용에는 문제가 없다.
 */
class SurgeMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /** content_id → 알림 id. 회복(clear)되면 그 장소의 알림만 정확히 지우려고 기억한다. */
    private val alertIds = HashMap<Long, Int>()
    private var nextAlertId = ALERT_ID_BASE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val stopIds = intent?.getLongArrayExtra(EXTRA_STOP_IDS)?.toList().orEmpty()
        if (stopIds.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannels()
        ServiceCompat.startForeground(
            this, ONGOING_ID, ongoingNotification(stopIds.size),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        running = true

        job?.cancel()
        job = scope.launch {
            // 화면이 꺼진 상태의 감시라 주기를 화면 안(10초)보다 늘려 배터리를 아낀다.
            SurgeStream.subscribe(stopIds, intervalSec = BACKGROUND_INTERVAL_SEC, level = SURGE_LEVEL)
                .collect { ev ->
                    when (ev) {
                        is SurgeEvent.Surge -> notifySurge(
                            ev.alert.contentId, ev.alert.title, ev.alert.message)
                        is SurgeEvent.Clear -> clearSurge(ev.alert.contentId)
                        is SurgeEvent.Unsupported -> {
                            // 서버가 이 기능을 모른다 — 배터리만 쓰게 두지 않는다.
                            Log.w(TAG, "서버가 급증 감시를 지원하지 않아 백그라운드 감시를 종료한다")
                            stopSelf()
                        }
                        else -> Unit
                    }
                }
        }
        // 시스템이 죽였다가 되살릴 때 감시 대상(stopIds)이 필요하므로 인텐트를 함께 복원시킨다.
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        running = false
        job?.cancel()
        scope.cancel()
        // 상시 알림은 서비스와 함께 사라지지만, 개별 급증 알림은 남으므로 정리한다.
        val nm = NotificationManagerCompat.from(this)
        alertIds.values.forEach { nm.cancel(it) }
        super.onDestroy()
    }

    // ── 알림 ─────────────────────────────────────────────────────────────────

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "코스 감시", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "코스를 진행하는 동안 혼잡을 지켜보는 중임을 알립니다" }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "혼잡 급증 알림", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "코스의 장소가 갑자기 붐빌 때 알립니다" }
        )
    }

    private fun plannerIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, PlannerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ongoingNotification(watching: Int) =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_surge_alert)
            .setContentTitle("코스 감시 중")
            .setContentText("${watching}곳의 혼잡을 지켜보고 있어요")
            .setContentIntent(plannerIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0, "감시 끄기",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, SurgeMonitorService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

    private fun notifySurge(contentId: Long, title: String, message: String) {
        val id = alertIds.getOrPut(contentId) { nextAlertId++ }
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_surge_alert)
            .setContentTitle("‘$title’ 지금 붐벼요")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(plannerIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        // POST_NOTIFICATIONS 가 없으면(사용자가 거부) 조용히 무시된다 — 감시 자체는 계속한다.
        runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
    }

    private fun clearSurge(contentId: Long) {
        val id = alertIds.remove(contentId) ?: return
        NotificationManagerCompat.from(this).cancel(id)
    }

    companion object {
        private const val TAG = "SurgeMonitorService"

        private const val CHANNEL_ONGOING = "yeobaek_surge_monitor"
        private const val CHANNEL_ALERT = "yeobaek_surge_alert"
        private const val ONGOING_ID = 1
        private const val ALERT_ID_BASE = 100

        private const val BACKGROUND_INTERVAL_SEC = 30
        private const val SURGE_LEVEL = 3

        const val ACTION_STOP = "com.example.crowdmap.yeobaek.STOP_SURGE_MONITOR"
        const val EXTRA_STOP_IDS = "stop_ids"

        /** 토글 버튼 표시용. 서비스는 프로세스당 하나뿐이라 이 플래그로 충분하다. */
        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context, stopIds: List<Long>) {
            if (stopIds.isEmpty()) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, SurgeMonitorService::class.java)
                    .putExtra(EXTRA_STOP_IDS, stopIds.toLongArray()),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SurgeMonitorService::class.java))
            running = false
        }
    }
}
