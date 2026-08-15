package com.example.crowdmap.yeobaek.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.crowdmap.R
import com.example.crowdmap.yeobaek.data.Congestion
import com.example.crowdmap.yeobaek.data.PlaceResult
import com.example.crowdmap.yeobaek.ui.YeUi.tick
import com.google.android.material.button.MaterialButton

/**
 * 기준 위치 주변 명소 라인업. 각 항목의 "담기 / 담김" 토글로 내 코스에 넣고 뺀다.
 * 선택 여부는 액티비티가 소유한 selected 맵으로 판단한다([isSelected]).
 */
class LineupAdapter(
    private val isSelected: (Long) -> Boolean,
    private val onToggle: (PlaceResult) -> Unit,
) : RecyclerView.Adapter<LineupAdapter.VH>() {

    private val items = mutableListOf<PlaceResult>()

    fun submit(list: List<PlaceResult>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val index: TextView = v.findViewById(R.id.lineup_index)
        val title: TextView = v.findViewById(R.id.lineup_title)
        val level: TextView = v.findViewById(R.id.lineup_level)
        val meta: TextView = v.findViewById(R.id.lineup_meta)
        val toggle: MaterialButton = v.findViewById(R.id.lineup_toggle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lineup, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val p = items[position]
        val ctx = h.itemView.context

        h.index.text = (position + 1).toString()
        h.title.text = p.title

        h.level.text = Congestion.labelOrUnknown(p.level)
        h.level.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(ctx, Congestion.containerRes(p.level))
        )
        h.level.setTextColor(ContextCompat.getColor(ctx, Congestion.colorRes(p.level)))

        val dist = p.distKm?.let { "%.1fkm".format(it) }
        h.meta.text = listOfNotNull(p.catLabel, dist).joinToString(" · ")

        // 담긴 항목은 글자만 바꾸지 않고 아이콘까지 바꿔야 한눈에 구분된다.
        val picked = isSelected(p.contentId)
        h.toggle.setText(if (picked) R.string.district_picked else R.string.district_pick)
        h.toggle.setIconResource(if (picked) R.drawable.ic_ye_check else R.drawable.ic_ye_add)
        h.toggle.setOnClickListener {
            it.tick()
            onToggle(p)
        }
    }

    override fun getItemCount(): Int = items.size
}
