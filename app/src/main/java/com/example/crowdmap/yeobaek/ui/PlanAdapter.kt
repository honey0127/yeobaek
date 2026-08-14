package com.example.crowdmap.yeobaek.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.crowdmap.R
import com.example.crowdmap.yeobaek.data.Congestion
import com.example.crowdmap.yeobaek.data.PlanStop

/**
 * 플래너 타임라인 어댑터. 고혼잡 stop 에만 "대안 보기" 노출.
 *
 * 코스 편집(드래그 정렬·스와이프 삭제)을 위해 목록을 내부에서 가변으로 들고 있다 —
 * 편집 결과는 [items] 로 읽어 서버에 재계산을 요청한다.
 */
class PlanAdapter(
    stops: List<PlanStop>,
    private val onAlt: (PlanStop) -> Unit,
) : RecyclerView.Adapter<PlanAdapter.VH>() {

    private val items: MutableList<PlanStop> = stops.toMutableList()

    /** 현재 화면에 보이는 순서 그대로의 목록(편집 결과). */
    fun items(): List<PlanStop> = items.toList()

    /** 드래그 정렬 — 한 칸씩 옮겨야 RecyclerView 애니메이션이 자연스럽다. */
    fun move(from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices) return false
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
        return true
    }

    fun removeAt(position: Int): PlanStop? {
        if (position !in items.indices) return null
        val removed = items.removeAt(position)
        notifyItemRemoved(position)
        return removed
    }

    /** 스와이프 삭제 되돌리기용. */
    fun insert(position: Int, stop: PlanStop) {
        val at = position.coerceIn(0, items.size)
        items.add(at, stop)
        notifyItemInserted(at)
    }

    /** 서버 재계산 결과로 통째로 교체(도착시각·혼잡도가 갱신된다). */
    fun replaceAll(stops: List<PlanStop>) {
        items.clear()
        items.addAll(stops)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val arrival: TextView = v.findViewById(R.id.stop_arrival)
        val title: TextView = v.findViewById(R.id.stop_title)
        val badge: TextView = v.findViewById(R.id.stop_badge)
        val sub: TextView = v.findViewById(R.id.stop_sub)
        val altBtn: Button = v.findViewById(R.id.stop_alt)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_plan_stop, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val s = items[position]
        h.arrival.text = s.arrival
        h.title.text = s.title
        h.badge.text = Congestion.label(s.forecastLevel)
        h.badge.backgroundTintList = ColorStateList.valueOf(Congestion.color(s.forecastLevel))

        if (s.substitutedFrom != null) {
            h.sub.visibility = View.VISIBLE
            h.sub.text = "대안 스왑됨"
        } else {
            h.sub.visibility = View.GONE
        }

        if (Congestion.isHigh(s.forecastLevel)) {
            h.altBtn.visibility = View.VISIBLE
            h.altBtn.setOnClickListener { onAlt(s) }
        } else {
            h.altBtn.visibility = View.GONE
            h.altBtn.setOnClickListener(null)
        }
    }

    override fun getItemCount(): Int = items.size
}
