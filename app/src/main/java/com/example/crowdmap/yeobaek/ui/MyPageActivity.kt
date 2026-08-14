package com.example.crowdmap.yeobaek.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.crowdmap.R
import com.example.crowdmap.yeobaek.data.CourseStore
import com.example.crowdmap.yeobaek.data.EcoStore
import com.example.crowdmap.yeobaek.data.SavedCourse
import com.example.crowdmap.yeobaek.data.ScheduleRequest
import com.example.crowdmap.yeobaek.data.StampStore
import com.example.crowdmap.yeobaek.data.YeobaekClient
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch

/**
 * 내 여백 — 지금까지 쌓인 것들을 한 화면에 모은다.
 *
 * 에코 포인트/배지([EcoStore])와 스탬프([StampStore]) 는 원래 홈의 작은 칩과 다이얼로그로만
 * 보였다. 저장한 코스([CourseStore])까지 합쳐 "내가 모은 것"을 한곳에서 보게 한다.
 *
 * 저장한 코스를 탭하면 **그 자리에서 다시 계산해서** 연다 — 혼잡도는 시간이 지나면
 * 달라지므로 저장 당시의 도착시각을 그대로 보여주면 틀린 정보가 된다.
 */
class MyPageActivity : AppCompatActivity() {

    private lateinit var badge: TextView
    private lateinit var points: TextView
    private lateinit var progress: ProgressBar
    private lateinit var nextBadge: TextView
    private lateinit var courses: RecyclerView
    private lateinit var coursesEmpty: TextView
    private lateinit var stampsTitle: TextView
    private lateinit var stamps: ChipGroup
    private lateinit var stampsEmpty: TextView
    private lateinit var adapter: SavedCourseAdapter

    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yeobaek_mypage)

        badge = findViewById(R.id.my_badge)
        points = findViewById(R.id.my_points)
        progress = findViewById(R.id.my_progress)
        nextBadge = findViewById(R.id.my_next_badge)
        courses = findViewById(R.id.my_courses)
        coursesEmpty = findViewById(R.id.my_courses_empty)
        stampsTitle = findViewById(R.id.my_stamps_title)
        stamps = findViewById(R.id.my_stamps)
        stampsEmpty = findViewById(R.id.my_stamps_empty)

        courses.layoutManager = LinearLayoutManager(this)
        adapter = SavedCourseAdapter(
            mutableListOf(),
            onOpen = { openCourse(it) },
            onDelete = { confirmDelete(it) },
        )
        courses.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        // 플래너에서 코스를 저장하거나 지도에서 포인트를 쌓고 돌아왔을 수 있다.
        refresh()
    }

    private fun refresh() {
        val pts = EcoStore.points(this)
        badge.text = EcoStore.badge(pts)
        points.text = "🌱 $pts 포인트"
        progress.progress = EcoStore.progressPct(pts)
        val next = EcoStore.nextThreshold(pts)
        nextBadge.text = if (next == null) {
            "최고 배지 달성 — 한적한 여행을 계속 이어가세요"
        } else {
            "다음 배지까지 ${next - pts}포인트"
        }

        val saved = CourseStore.all(this)
        adapter.replaceAll(saved)
        coursesEmpty.visibility = if (saved.isEmpty()) View.VISIBLE else View.GONE

        renderStamps()
    }

    private fun renderStamps() {
        val all = StampStore.all(this)
        stampsTitle.text = "여백 스탬프 (${all.size})"
        stampsEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        stamps.removeAllViews()
        for (s in all) {
            stamps.addView(Chip(this).apply {
                text = "旅 ${s.title}"
                isClickable = false
                isCheckable = false
            })
        }
    }

    /** 저장 당시 입력(장소·시작시각·모드)으로 다시 계산해서 플래너를 연다. */
    private fun openCourse(course: SavedCourse) {
        if (busy) return
        busy = true
        Toast.makeText(this, "‘${course.name}’ 다시 계산 중…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val plan = YeobaekClient.api.schedule(
                    ScheduleRequest(
                        startTime = course.startTime,
                        stops = course.stops,
                        keepOrder = course.keepOrder,
                    )
                )
                startActivity(
                    Intent(this@MyPageActivity, PlannerActivity::class.java).apply {
                        putExtra(Extras.STOPS, course.stops.toLongArray())
                        putExtra(Extras.START_TIME, course.startTime)
                        putExtra(Extras.PLAN_JSON, YeobaekClient.gson.toJson(plan))
                        putExtra(Extras.KEEP_ORDER, course.keepOrder)
                        putExtra(Extras.COURSE_ID, course.id)
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(this@MyPageActivity,
                    "코스를 여는 데 실패했어요: ${e.message ?: "네트워크 오류"}",
                    Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    private fun confirmDelete(course: SavedCourse) {
        AlertDialog.Builder(this)
            .setTitle("코스 삭제")
            .setMessage("‘${course.name}’ 을(를) 보관함에서 지울까요?")
            .setPositiveButton("삭제") { _, _ ->
                CourseStore.delete(this, course.id)
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
