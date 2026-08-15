package com.example.crowdmap.yeobaek.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.crowdmap.R
import com.example.crowdmap.yeobaek.data.Congestion
import com.example.crowdmap.yeobaek.data.Twin

/** 감성 쌍둥이(대안) 목록 어댑터. 항목 탭 → 설득 카드. */
class TwinAdapter(
    private val items: List<Twin>,
    private val onPick: (Twin) -> Unit,
) : RecyclerView.Adapter<TwinAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.twin_title)
        val meta: TextView = v.findViewById(R.id.twin_meta)
        val badge: TextView = v.findViewById(R.id.twin_badge)
        val bar: View = v.findViewById(R.id.twin_bar)
        val barRest: View = v.findViewById(R.id.twin_bar_rest)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_twin, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = items[position]
        val ctx = h.itemView.context

        h.title.text = t.title
        val sim = (t.similarity * 100).toInt().coerceIn(0, 100)
        h.meta.text = "감성 유사도 ${sim}% · %.1fkm".format(t.distKm)

        h.badge.text = Congestion.label(t.forecastLevel)
        h.badge.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(ctx, Congestion.containerRes(t.forecastLevel))
        )
        h.badge.setTextColor(
            ContextCompat.getColor(ctx, Congestion.colorRes(t.forecastLevel))
        )

        // 유사도 비율만큼 막대를 채운다(weight 합이 1이 되도록 나머지에 1-비율).
        val fraction = sim / 100f
        (h.bar.layoutParams as LinearLayout.LayoutParams).weight = fraction
        (h.barRest.layoutParams as LinearLayout.LayoutParams).weight = 1f - fraction
        h.bar.requestLayout()

        h.itemView.setOnClickListener { onPick(t) }
    }

    override fun getItemCount(): Int = items.size
}
