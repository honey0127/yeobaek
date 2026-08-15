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
 * '이 지역 추천' 가로 카드 어댑터. "담기" → 홈의 방문지에 추가(마커+칩).
 * 이미 담긴 장소는 목록에서 걸러 넘어오므로 여기선 단순 표시만 한다.
 *
 * 혼잡 배지는 '연한 배경 + 진한 글자'로 그린다 — 카드가 여러 장 늘어설 때
 * 진한 색 덩어리가 줄지어 있으면 눈이 금방 피로해진다.
 */
class RecoAdapter(
    private val onAdd: (PlaceResult) -> Unit,
) : RecyclerView.Adapter<RecoAdapter.VH>() {

    private val items = mutableListOf<PlaceResult>()

    fun submit(list: List<PlaceResult>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val level: TextView = v.findViewById(R.id.reco_level)
        val title: TextView = v.findViewById(R.id.reco_title)
        val meta: TextView = v.findViewById(R.id.reco_meta)
        val add: MaterialButton = v.findViewById(R.id.reco_add)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reco_place, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val p = items[position]
        val ctx = h.itemView.context

        h.level.text = Congestion.labelOrUnknown(p.level)
        h.level.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(ctx, Congestion.containerRes(p.level))
        )
        h.level.setTextColor(ContextCompat.getColor(ctx, Congestion.colorRes(p.level)))

        h.title.text = p.title
        val dist = p.distKm?.let { "%.1fkm".format(it) }
        val quiet = p.quietScore?.let { "한적함 $it" }
        h.meta.text = listOfNotNull(p.catLabel, quiet, dist).joinToString(" · ")
        h.meta.visibility = if (h.meta.text.isBlank()) View.GONE else View.VISIBLE

        h.add.setOnClickListener {
            it.tick()
            onAdd(p)
        }
    }

    override fun getItemCount(): Int = items.size
}
