package com.example.crowdmap.yeobaek.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.crowdmap.R
import com.example.crowdmap.yeobaek.data.FavoritePlace
import com.example.crowdmap.yeobaek.ui.YeUi.tick

/**
 * 찜한 장소 목록.
 *
 * 항목을 탭하면 '코스로 만들 대상'으로 선택된다 — 찜은 보통 여러 곳을 모아 두었다가
 * 그중 몇 곳만 골라 하루 코스로 엮기 때문에, 목록에서 바로 고를 수 있어야 한다.
 */
class FavoriteAdapter(
    private val isPicked: (Long) -> Boolean,
    private val onToggle: (FavoritePlace) -> Unit,
    private val onDelete: (FavoritePlace) -> Unit,
) : RecyclerView.Adapter<FavoriteAdapter.VH>() {

    private val items = mutableListOf<FavoritePlace>()

    fun submit(list: List<FavoritePlace>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val check: ImageView = v.findViewById(R.id.fav_check)
        val title: TextView = v.findViewById(R.id.fav_title)
        val meta: TextView = v.findViewById(R.id.fav_meta)
        val delete: ImageButton = v.findViewById(R.id.fav_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_favorite, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val f = items[position]
        val ctx = h.itemView.context

        h.title.text = f.title
        h.meta.text = f.catLabel ?: "찜한 장소"

        val picked = isPicked(f.contentId)
        h.check.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                ctx, if (picked) R.color.ye_primary else R.color.ye_surface_variant
            )
        )
        h.check.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                ctx, if (picked) R.color.ye_on_primary else R.color.ye_on_surface_faint
            )
        )

        h.itemView.setOnClickListener {
            it.tick()
            onToggle(f)
        }
        h.delete.setOnClickListener { onDelete(f) }
    }

    override fun getItemCount(): Int = items.size
}
