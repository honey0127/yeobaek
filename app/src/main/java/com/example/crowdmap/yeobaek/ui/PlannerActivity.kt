package com.example.crowdmap.yeobaek.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.crowdmap.R
import com.example.crowdmap.yeobaek.data.Congestion
import com.example.crowdmap.yeobaek.data.CourseStore
import com.example.crowdmap.yeobaek.data.PlanCache
import com.example.crowdmap.yeobaek.data.PlanStop
import com.example.crowdmap.yeobaek.data.SavedCourse
import com.example.crowdmap.yeobaek.data.ScheduleRequest
import com.example.crowdmap.yeobaek.data.ScheduleResponse
import com.example.crowdmap.yeobaek.data.SurgeAlert
import com.example.crowdmap.yeobaek.data.SurgeEvent
import com.example.crowdmap.yeobaek.data.SurgeStream
import com.example.crowdmap.yeobaek.data.YeobaekClient
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 플래너(절차서 4-2): 최적 코스 타임라인(도착시각·혼잡 배지).
 * 고혼잡 stop 의 "대안 보기" → 대안 목록(/match).
 * 스왑으로 재스케줄된 결과가 CLEAR_TOP 으로 이 액티비티를 새 인텐트로 재생성한다.
 *
 * 코스 편집: 길게 눌러 드래그하면 순서를 바꾸고, 옆으로 밀면 지운다. 편집한 순서는
 * "이 순서로 다시 계산"으로 서버에 보내야 도착시각·혼잡도가 맞춰진다. 마음에 드는
 * 코스는 "코스 저장"으로 보관함([CourseStore])에 넣어 두고 나중에 다시 꺼낸다.
 */
class PlannerActivity : AppCompatActivity() {

    private var surgeJob: Job? = null
    private lateinit var adapter: PlanAdapter

    private lateinit var savedText: TextView
    private lateinit var subText: TextView
    private lateinit var hintText: TextView
    private lateinit var editHint: TextView
    private lateinit var alert: MaterialCardView
    private lateinit var alertText: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var recalcBtn: MaterialButton
    private lateinit var saveBtn: MaterialButton

    private var startTime = ""
    private var keepOrder = false
    private var courseId: String? = null      // 보관함에서 열었으면 그 코스를 덮어쓴다
    private var currentPlan: ScheduleResponse? = null   // 마지막으로 서버가 계산해 준 결과

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yeobaek_planner)

        startTime = intent.getStringExtra(Extras.START_TIME) ?: ""
        keepOrder = intent.getBooleanExtra(Extras.KEEP_ORDER, false)
        courseId = intent.getStringExtra(Extras.COURSE_ID)

        savedText = findViewById(R.id.planner_saved)
        subText = findViewById(R.id.planner_sub)
        hintText = findViewById(R.id.planner_hint)
        editHint = findViewById(R.id.planner_edit_hint)
        alert = findViewById(R.id.planner_alert)
        alertText = findViewById(R.id.planner_alert_text)
        recycler = findViewById(R.id.planner_list)
        recalcBtn = findViewById(R.id.planner_recalc)
        saveBtn = findViewById(R.id.planner_save)
        recycler.layoutManager = LinearLayoutManager(this)

        val planJson = intent.getStringExtra(Extras.PLAN_JSON)
        // 서버가 안 뜬 상태(시연 중 사고 등)에서도 빈 화면을 보이지 않도록 마지막 계획으로 폴백.
        val plan: ScheduleResponse? = planJson
            ?.let { runCatching { YeobaekClient.gson.fromJson(it, ScheduleResponse::class.java) }.getOrNull() }
            ?: PlanCache.load(this)

        if (plan == null || plan.ordered.isEmpty()) {
            savedText.text = "코스를 만들지 못했습니다"
            subText.text = ""
            recalcBtn.visibility = View.GONE
            saveBtn.visibility = View.GONE
            return
        }
        PlanCache.save(this, plan)

        adapter = PlanAdapter(plan.ordered) { stop -> openAlternatives(stop) }
        recycler.adapter = adapter
        attachEditGestures()

        recalcBtn.setOnClickListener { recalculate() }
        saveBtn.setOnClickListener { saveCourse() }

        render(plan)
    }

    override fun onDestroy() {
        surgeJob?.cancel()
        super.onDestroy()
    }

    // ── 화면 갱신 ──────────────────────────────────────────────────────────────

    private fun render(plan: ScheduleResponse) {
        currentPlan = plan
        if (keepOrder) {
            savedText.text = "내 순서대로 코스"
            subText.text = "${plan.ordered.size}곳 · 고른 순서 유지 · 도착 시점 혼잡도 표시"
        } else {
            savedText.text = "혼잡 ${plan.savedCongestionPct}% 절약"
            subText.text = "${plan.ordered.size}곳 · 혼잡도 예측으로 순서 자동 조정"
        }

        // 역방향/엇갈림 동선 힌트(모듈: counter-flow) — 자동 재배치로 혼잡을 회피했을 때
        if (!keepOrder && plan.savedCongestionPct > 0) {
            hintText.visibility = View.VISIBLE
            hintText.text = "↺ 군중과 엇갈리는 동선으로 재배치했어요 (혼잡 ${plan.savedCongestionPct}%↓)"
        } else {
            hintText.visibility = View.GONE
        }

        setDirty(false)
        startSurgeMonitor(adapter.items())
    }

    /** 편집됨 = 화면의 도착시각·혼잡도가 서버 계산과 어긋난 상태. */
    private fun setDirty(dirty: Boolean) {
        editHint.visibility = if (dirty) View.VISIBLE else View.GONE
        recalcBtn.isEnabled = dirty
    }

    // ── 코스 편집(드래그 정렬 · 스와이프 삭제) ─────────────────────────────────

    private fun attachEditGestures() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.START or ItemTouchHelper.END,
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val moved = adapter.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                if (moved) setDirty(true)
                return moved
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val position = vh.bindingAdapterPosition
                if (adapter.itemCount <= 1) {
                    // 마지막 한 곳은 지울 수 없다 — 원위치로 되돌린다.
                    adapter.notifyItemChanged(position)
                    Toast.makeText(this@PlannerActivity,
                        "코스에는 장소가 하나 이상 있어야 해요", Toast.LENGTH_SHORT).show()
                    return
                }
                val removed = adapter.removeAt(position) ?: return
                setDirty(true)
                Snackbar.make(recycler, "‘${removed.title}’ 삭제", Snackbar.LENGTH_LONG)
                    .setAction("되돌리기") {
                        adapter.insert(position, removed)
                        setDirty(true)
                    }
                    .show()
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recycler)
    }

    /** 편집한 순서 그대로 서버에 다시 계산시킨다(사용자가 정한 순서니 keep_order=true). */
    private fun recalculate() {
        val ids = adapter.items().map { it.contentId }
        if (ids.isEmpty()) return
        setBusy(true)
        lifecycleScope.launch {
            try {
                val fresh = YeobaekClient.api.schedule(
                    ScheduleRequest(startTime = startTime, stops = ids, keepOrder = true)
                )
                keepOrder = true
                adapter.replaceAll(fresh.ordered)
                PlanCache.save(this@PlannerActivity, fresh)
                render(fresh)
            } catch (e: Exception) {
                Toast.makeText(this@PlannerActivity,
                    "다시 계산 실패: ${e.message ?: "네트워크 오류"}", Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        recalcBtn.isEnabled = !busy
        saveBtn.isEnabled = !busy
        recalcBtn.text = if (busy) "계산 중…" else "이 순서로 다시 계산"
    }

    // ── 보관함 저장 ───────────────────────────────────────────────────────────

    private fun saveCourse() {
        val items = adapter.items()
        if (items.isEmpty()) return
        val titles = items.map { it.title }

        val input = EditText(this).apply {
            setText(CourseStore.defaultName(titles))
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("코스 저장")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { CourseStore.defaultName(titles) }
                val course = SavedCourse(
                    id = courseId ?: UUID.randomUUID().toString(),
                    name = name,
                    startTime = startTime,
                    keepOrder = keepOrder,
                    stops = items.map { it.contentId },
                    titles = titles,
                    // 지표는 마지막 서버 계산 결과의 스냅샷(목록 카드 표시용).
                    yeobaekIndex = currentPlan?.yeobaekIndex,
                    savedCongestionPct = currentPlan?.savedCongestionPct ?: 0,
                )
                CourseStore.save(this, course)
                courseId = course.id
                Snackbar.make(recycler, "‘${course.name}’ 저장됨 · 내 여백에서 볼 수 있어요",
                    Snackbar.LENGTH_LONG).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── 실시간 급증 감시(모듈4) ───────────────────────────────────────────────

    /**
     * 서버 SSE(`/api/v1/monitor/surge`) 구독.
     *
     * 예전에는 stop 마다 `/resolve_now` 를 90초씩 폴링했는데, 주기를 줄이면 배터리·API
     * 호출이 같이 늘고 늘리면 급증을 놓치는 문제가 있었다. 이제 감시 루프는 서버가 돌고
     * 앱은 연결 하나만 유지한다 — 혼잡이 임계 이상으로 바뀌는 순간 surge, 회복하면 clear
     * 이벤트가 온다(변화가 없으면 트래픽도 없다).
     *
     * `repeatOnLifecycle(STARTED)` 안에서 구독하므로 화면이 백그라운드로 가면 연결이
     * 끊기고 돌아오면 자동으로 다시 붙는다. 코스를 편집·재계산하면 감시 대상이 달라지므로
     * 이전 구독을 취소하고 새 목록으로 다시 붙인다.
     */
    private fun startSurgeMonitor(stops: List<PlanStop>) {
        surgeJob?.cancel()
        alert.visibility = View.GONE
        val stopIds = stops.map { it.contentId }
        if (stopIds.isEmpty()) return

        // 지점별 활성 알림. 배너는 한 줄이라 코스 순서상 먼저 만나는 지점을 보여준다.
        val active = LinkedHashMap<Long, SurgeAlert>()
        val render = {
            val first = stops.firstNotNullOfOrNull { active[it.contentId] }
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
                if (unsupported) pollSurge(stops, active, render)
            }
        }
    }

    /**
     * 구버전 서버(`/monitor/surge` 없음) 폴백 — 기존 90초 폴링.
     * SSE 를 못 쓰는 배포에서도 급증 배너 자체는 계속 동작하게 남겨둔다.
     */
    private suspend fun pollSurge(
        stops: List<PlanStop>,
        active: MutableMap<Long, SurgeAlert>,
        render: () -> Unit,
    ) = coroutineScope {
        while (isActive) {
            for (stop in stops) {
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

    private fun openAlternatives(stop: PlanStop) {
        startActivity(
            Intent(this, AlternativesActivity::class.java).apply {
                putExtra(Extras.TARGET_ID, stop.contentId)
                // 편집 중이면 지금 화면의 순서가 기준이다(원래 인텐트가 아니라).
                putExtra(Extras.STOPS, adapter.items().map { it.contentId }.toLongArray())
                putExtra(Extras.START_TIME, startTime)
            }
        )
    }

    companion object {
        /** 알림 임계 혼잡 레벨(3=약간 붐빔). 서버 YEOBAEK_HIGH_LVL 기본값과 맞춘다. */
        private const val SURGE_LEVEL = 3
    }
}
