package com.example.crowdmap.yeobaek.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.crowdmap.R
import com.example.crowdmap.yeobaek.data.District
import com.example.crowdmap.yeobaek.ui.YeUi.tick
import com.google.android.material.chip.Chip

/**
 * 빠른 선택 지역 칩.
 *
 * 선택 상태는 Material Chip 의 checked 상태로 표현한다 — 예전처럼 코드에서 배경색과
 * 글자색을 직접 칠하면 다크 테마·눌림 상태를 전부 손으로 관리해야 했다.
 */
class DistrictChipAdapter(
    private val onSelect: (District) -> Unit,
) : RecyclerView.Adapter<DistrictChipAdapter.VH>() {

    private val items = mutableListOf<District>()
    private var selectedKey: String? = null

    fun submit(list: List<District>, selected: String?) {
        items.clear(); items.addAll(list); selectedKey = selected; notifyDataSetChanged()
    }

    fun select(key: String) {
        if (selectedKey == key) return
        selectedKey = key; notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val chip: Chip = v.findViewById(R.id.district_chip)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_district_chip, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val d = items[position]
        h.chip.text = d.name
        h.chip.isChecked = d.key == selectedKey
        h.chip.setOnClickListener {
            it.tick()
            onSelect(d)
        }
    }

    override fun getItemCount(): Int = items.size
}
