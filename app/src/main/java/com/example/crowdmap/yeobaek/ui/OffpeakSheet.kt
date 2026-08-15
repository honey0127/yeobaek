package com.example.crowdmap.yeobaek.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.crowdmap.R
import com.example.crowdmap.yeobaek.data.Congestion
import com.example.crowdmap.yeobaek.data.OffpeakHour
import com.example.crowdmap.yeobaek.data.OffpeakResponse
import com.example.crowdmap.yeobaek.ui.YeUi.tick
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * '덜 붐비는 시간' 시트.
 *
 * 서버 `/places/offpeak/{id}` 는 추천 시각 3개(best)와 **앞으로 12시간 전체 타임라인**을
 * 준다. 예전 화면은 best 세 줄만 다이얼로그로 읽어주고 timeline 은 통째로 버렸는데,
 * 여행자가 진짜 알고 싶은 건 "몇 시부터 몇 시까지 한적한가"라는 흐름이다.
 * 그래서 타임라인을 막대로 그리고, 고른 시각을 그대로 출발 시각에 반영한다.
 *
 * 시트는 데이터를 직접 불러오지 않는다 — 호출한 화면이 이미 로딩/실패 처리를 하고 있고,
 * 여기서 또 네트워크를 잡으면 실패 안내가 두 군데로 갈라지기 때문이다.
 */
object OffpeakSheet {

    private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** 막대 트랙 높이(dp)와 레벨별 채움 높이 — 레벨 1이 가장 낮고 4가 거의 꽉 찬다. */
    private const val FILL_BASE_DP = 22
    private const val FILL_STEP_DP = 16

    /**
     * @param onPick 사용자가 시각을 확정했을 때. 호출한 화면은 이 시각을 출발 시각으로 쓴다.
     */
    fun show(
        ctx: Context,
        placeTitle: String,
        res: OffpeakResponse,
        onPick: (LocalDateTime) -> Unit,
    ) {
        val view = LayoutInflater.from(ctx).inflate(R.layout.sheet_offpeak, null)
        val dialog = BottomSheetDialog(ctx)
        dialog.setContentView(view)

        val title = view.findViewById<TextView>(R.id.offpeak_title)
        val source = view.findViewById<TextView>(R.id.offpeak_source)
        val bestLabel = view.findViewById<TextView>(R.id.offpeak_best_label)
        val bestGroup = view.findViewById<ChipGroup>(R.id.offpeak_best)
        val timelineLabel = view.findViewById<TextView>(R.id.offpeak_timeline_label)
        val scroll = view.findViewById<View>(R.id.offpeak_scroll)
        val bars = view.findViewById<LinearLayout>(R.id.offpeak_bars)
        val detail = view.findViewById<TextView>(R.id.offpeak_detail)
        val empty = view.findViewById<TextView>(R.id.offpeak_empty)
        val apply = view.findViewById<MaterialButton>(R.id.offpeak_apply)

        title.text = "‘$placeTitle’ 덜 붐비는 시간"
        source.text = listOfNotNull(res.source, res.area).joinToString(" · ")
        source.visibility = if (source.text.isBlank()) View.GONE else View.VISIBLE

        // 예보권 밖(서울 121개 지점 밖)이면 추천할 근거 자체가 없다.
        val timeline = res.timeline.ifEmpty { res.best }
        if (timeline.isEmpty() && res.best.isEmpty()) {
            bestLabel.visibility = View.GONE
            bestGroup.visibility = View.GONE
            timelineLabel.visibility = View.GONE
            scroll.visibility = View.GONE
            detail.visibility = View.GONE
            empty.visibility = View.VISIBLE
            apply.visibility = View.GONE
            dialog.show()
            return
        }

        // 선택 상태는 시각(unix)으로 들고 있는다. 막대와 칩 어느 쪽을 눌러도 같은 값이 잡힌다.
        var selected: OffpeakHour? = null
        val barViews = mutableMapOf<Long, View>()

        fun render() {
            val picked = selected
            barViews.forEach { (unix, bar) ->
                bar.findViewById<View>(R.id.bar_selected).visibility =
                    if (picked != null && picked.unix == unix) View.VISIBLE else View.INVISIBLE
            }
            for (i in 0 until bestGroup.childCount) {
                val chip = bestGroup.getChildAt(i) as? Chip ?: continue
                chip.isChecked = picked != null && (chip.tag as? Long) == picked.unix
            }
            detail.text = picked?.let { describe(it) } ?: "막대를 눌러 시각을 골라보세요"
            apply.isEnabled = picked != null
        }

        // ── 추천 시각 칩 ──
        if (res.best.isEmpty()) {
            bestLabel.visibility = View.GONE
            bestGroup.visibility = View.GONE
        } else {
            for (hour in res.best) {
                val chip = Chip(ctx).apply {
                    text = "${time(hour)} ${Congestion.label(hour.level)}"
                    tag = hour.unix
                    isCheckable = true
                    setOnClickListener {
                        it.tick()
                        selected = hour
                        render()
                    }
                }
                bestGroup.addView(chip)
            }
        }

        // ── 12시간 막대 ──
        if (timeline.isEmpty()) {
            timelineLabel.visibility = View.GONE
            scroll.visibility = View.GONE
        } else {
            val inflater = LayoutInflater.from(ctx)
            val density = ctx.resources.displayMetrics.density
            for (hour in timeline) {
                val bar = inflater.inflate(R.layout.item_offpeak_bar, bars, false)
                val fill = bar.findViewById<View>(R.id.bar_fill)
                val level = hour.level.coerceIn(1, 4)
                fill.layoutParams = fill.layoutParams.apply {
                    height = ((FILL_BASE_DP + FILL_STEP_DP * level) * density).toInt()
                }
                fill.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, Congestion.colorRes(level))
                )
                // 과거 평균으로 추정한 구간은 실측 예보와 구분되게 흐리게 둔다.
                fill.alpha = if (hour.historical) 0.45f else 1f
                bar.findViewById<TextView>(R.id.bar_hour).text = hourLabel(hour)
                bar.setOnClickListener {
                    it.tick()
                    selected = hour
                    render()
                }
                barViews[hour.unix] = bar
                bars.addView(bar)
            }
        }

        apply.setOnClickListener {
            val picked = selected ?: return@setOnClickListener
            onPick(LocalDateTime.ofInstant(Instant.ofEpochSecond(picked.unix), KST))
            dialog.dismiss()
        }

        render()
        dialog.show()
    }

    private fun localTime(hour: OffpeakHour) =
        Instant.ofEpochSecond(hour.unix).atZone(KST).toLocalTime()

    private fun time(hour: OffpeakHour): String = localTime(hour).format(HHMM)

    private fun hourLabel(hour: OffpeakHour): String =
        localTime(hour).hour.toString()

    /** 선택한 시각의 근거 한 줄 — 서울시 원본 예보 값이 있으면 같이 보여준다. */
    private fun describe(hour: OffpeakHour): String {
        val parts = mutableListOf(time(hour), Congestion.label(hour.level))
        hour.quietScore?.let { parts += "한적함 $it" }
        val min = hour.ppltnMin
        val max = hour.ppltnMax
        if (min != null && max != null) parts += "예상 인구 %,d~%,d명".format(min, max)
        if (hour.historical) parts += "과거 평균 추정"
        return parts.joinToString(" · ")
    }
}
