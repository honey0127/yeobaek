package com.example.crowdmap.yeobaek.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.crowdmap.R
import com.example.crowdmap.yeobaek.data.Congestion
import com.example.crowdmap.yeobaek.data.PlanStop
import com.example.crowdmap.yeobaek.data.ScheduleResponse
import com.example.crowdmap.yeobaek.data.SurgeAlert
import com.example.crowdmap.yeobaek.data.SurgeEvent
import com.example.crowdmap.yeobaek.data.SurgeStream
import com.example.crowdmap.yeobaek.data.YeobaekClient
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 플래너(절차서 4-2): 최적 코스 타임라인(도착시각·혼잡 배지).
 * 고혼잡 stop 의 "대안 보기" → 대안 목록(/match).
 * 스왑으로 재스케줄된 결과가 CLEAR_TOP 으로 이 액티비티를 새 인텐트로 재생성한다.
 */
class PlannerActivity : AppCompatActivity() {

    private var surgeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yeobaek_planner)

        val stops = intent.getLongArrayExtra(Extras.STOPS) ?: LongArray(0)
        val startTime = intent.getStringExtra(Extras.START_TIME) ?: ""
        val keepOrder = intent.getBooleanExtra(Extras.KEEP_ORDER, false)
        val planJson = intent.getStringExtra(Extras.PLAN_JSON)
        val plan: ScheduleResponse? =
            planJson?.let { YeobaekClient.gson.fromJson(it, ScheduleResponse::class.java) }

        val saved = findViewById<TextView>(R.id.planner_saved)
        val sub = findViewById<TextView>(R.id.planner_sub)
        val recycler = findViewById<RecyclerView>(R.id.planner_list)
        recycler.layoutManager = LinearLayoutManager(this)

        if (plan == null || plan.ordered.isEmpty()) {
            saved.text = "코스를 만들지 못했습니다"
            sub.text = ""
            return
        }

        if (keepOrder) {
            saved.text = "내 순서대로 코스"
            sub.text = "${plan.ordered.size}곳 · 고른 순서 유지 · 도착 시점 혼잡도 표시"
        } else {
            saved.text = "혼잡 ${plan.savedCongestionPct}% 절약"
            sub.text = "${plan.ordered.size}곳 · 혼잡도 예측으로 순서 자동 조정"
        }

        // 역방향/엇갈림 동선 힌트(모듈: counter-flow) — 자동 재배치로 혼잡을 회피했을 때
        val hint = findViewById<TextView>(R.id.planner_hint)
        if (!keepOrder && plan.savedCongestionPct > 0) {
            hint.visibility = View.VISIBLE
            hint.text = "↺ 군중과 엇갈리는 동선으로 재배치했어요 (혼잡 ${plan.savedCongestionPct}%↓)"
        } else {
            hint.visibility = View.GONE
        }

        recycler.adapter = PlanAdapter(plan.ordered) { stop ->
            openAlternatives(stop, stops, startTime)
        }

        startSurgeMonitor(plan)
    }

    override fun onDestroy() {
        surgeJob?.cancel()
        super.onDestroy()
    }

    /**
     * 실시간 급증 감시(모듈4) — 서버 SSE(`/api/v1/monitor/surge`) 구독.
     *
     * 예전에는 stop 마다 `/resolve_now` 를 90초씩 폴링했는데, 주기를 줄이면 배터리·API
     * 호출이 같이 늘고 늘리면 급증을 놓치는 문제가 있었다. 이제 감시 루프는 서버가 돌고
     * 앱은 연결 하나만 유지한다 — 혼잡이 임계 이상으로 바뀌는 순간 surge, 회복하면 clear
     * 이벤트가 온다(변화가 없으면 트래픽도 없다).
     *
     * `repeatOnLifecycle(STARTED)` 안에서 구독하므로 화면이 백그라운드로 가면 연결이
     * 끊기고 돌아오면 자동으로 다시 붙는다(예전 onResume 재시작 TODO 를 대신한다).
     */
    private fun startSurgeMonitor(plan: ScheduleResponse) {
        val alert = findViewById<MaterialCardView>(R.id.planner_alert)
        val alertText = findViewById<TextView>(R.id.planner_alert_text)
        val stopIds = plan.ordered.map { it.contentId }
        if (stopIds.isEmpty()) return

        // 지점별 활성 알림. 배너는 한 줄이라 코스 순서상 먼저 만나는 지점을 보여준다.
        val active = LinkedHashMap<Long, SurgeAlert>()
        val render = {
            val first = plan.ordered.firstNotNullOfOrNull { active[it.contentId] }
            if (first == null) {
                alert.visibility = View.GONE
            } else {
                alertText.text = "⚠ ${first.message}"
                alert.visibility = View.VISIBLE
            }
        }

        surgeJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                active.clear()
                render()
                var unsupported = false
                SurgeStream.subscribe(stopIds, intervalSec = 10, level = SURGE_LEVEL)
                    .collect { ev ->
                        when (ev) {
                            is SurgeEvent.Surge -> {
                                active[ev.alert.contentId] = ev.alert
                                render()
                            }
                            is SurgeEvent.Clear -> {
                                active.remove(ev.alert.contentId)
                                render()
                            }
                            is SurgeEvent.Unsupported -> unsupported = true
                            else -> Unit    // open·heartbeat 는 UI 에 영향 없음
                        }
                    }
                // 스트림이 재시도 없이 끝나는 건 구버전 서버뿐 — 그때만 폴링으로 내려간다.
                if (unsupported) pollSurge(plan, active, render)
            }
        }
    }

    /**
     * 구버전 서버(`/monitor/surge` 없음) 폴백 — 기존 90초 폴링.
     * SSE 를 못 쓰는 배포에서도 급증 배너 자체는 계속 동작하게 남겨둔다.
     */
    private suspend fun pollSurge(
        plan: ScheduleResponse,
        active: MutableMap<Long, SurgeAlert>,
        render: () -> Unit,
    ) = coroutineScope {
        while (isActive) {
            for (stop in plan.ordered) {
                val lat = stop.lat
                val lng = stop.lng
                if (lat == null || lng == null) continue
                val now = try {
                    YeobaekClient.api.resolveNow(lat, lng)
                } catch (_: Exception) { null } ?: continue
                val lvl = now.level
                if (now.valid && lvl != null && lvl >= SURGE_LEVEL) {
                    active[stop.contentId] = SurgeAlert(
                        contentId = stop.contentId,
                        title = stop.title,
                        level = lvl,
                        levelLabel = Congestion.label(lvl),
                        message = "‘${stop.title}’ 지금 ${Congestion.label(lvl)} — " +
                            "도착 시점을 늦추거나 대안을 눌러보세요",
                    )
                } else {
                    active.remove(stop.contentId)
                }
            }
            render()
            delay(90_000L)   // 90초 주기 폴링(폴백 경로에서만)
        }
    }

    private fun openAlternatives(stop: PlanStop, stops: LongArray, startTime: String) {
        startActivity(
            Intent(this, AlternativesActivity::class.java).apply {
                putExtra(Extras.TARGET_ID, stop.contentId)
                putExtra(Extras.STOPS, stops)
                putExtra(Extras.START_TIME, startTime)
            }
        )
    }

    private fun round2(v: Double): String = String.format("%.2f", v)

    companion object {
        /** 알림 임계 혼잡 레벨(3=약간 붐빔). 서버 YEOBAEK_HIGH_LVL 기본값과 맞춘다. */
        private const val SURGE_LEVEL = 3
    }
}
