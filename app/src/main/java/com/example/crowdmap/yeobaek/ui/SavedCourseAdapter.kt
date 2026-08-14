package com.example.crowdmap.yeobaek.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.crowdmap.R
import com.example.crowdmap.yeobaek.data.SavedCourse
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 보관함 코스 목록. 탭하면 다시 계산해서 열고, 휴지통을 누르면 지운다. */
class SavedCourseAdapter(
    private val items: MutableList<SavedCourse>,
    private val onOpen: (SavedCourse) -> Unit,
    private val onDelete: (SavedCourse) -> Unit,
) : RecyclerView.Adapter<SavedCourseAdapter.VH>() {

    private val dateFormat = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.course_name)
        val summary: TextView = v.findViewById(R.id.course_summary)
        val stops: TextView = v.findViewById(R.id.course_stops)
        val delete: ImageButton = v.findViewById(R.id.course_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_saved_course, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        h.name.text = c.name
        h.summary.text = "${c.summary()} · ${dateFormat.format(Date(c.savedAt))} 저장"
        h.stops.text = c.titles.joinToString(" → ").ifEmpty { "${c.stops.size}곳" }
        h.itemView.setOnClickListener { onOpen(c) }
        h.delete.setOnClickListener { onDelete(c) }
    }

    override fun getItemCount(): Int = items.size

    fun replaceAll(courses: List<SavedCourse>) {
        items.clear()
        items.addAll(courses)
        notifyDataSetChanged()
    }
}
