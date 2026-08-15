package com.example.crowdmap.yeobaek.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.crowdmap.R
import com.example.crowdmap.yeobaek.data.CourseStore
import com.example.crowdmap.yeobaek.data.EcoStore
import com.example.crowdmap.yeobaek.data.FavoritePlace
import com.example.crowdmap.yeobaek.data.FavoriteStore
import com.example.crowdmap.yeobaek.data.SavedCourse
import com.example.crowdmap.yeobaek.data.ScheduleRequest
import com.example.crowdmap.yeobaek.data.StampStore
import com.example.crowdmap.yeobaek.data.YeobaekClient
import com.example.crowdmap.yeobaek.ui.YeUi.applyInsets
import com.example.crowdmap.yeobaek.ui.YeUi.edgeToEdge
import com.example.crowdmap.yeobaek.ui.YeUi.showIf
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 내 여백 — 지금까지 쌓인 것들을 한 화면에 모은다.
 *
 * 에코 포인트/배지([EcoStore]) · 스탬프([StampStore]) · 저장한 코스([CourseStore]) 에
 * 찜한 장소([FavoriteStore])까지 합쳐 "내가 모은 것"을 한곳에서 본다.
 *
 * 저장한 코스를 탭하면 **그 자리에서 다시 계산해서** 연다 — 혼잡도는 시간이 지나면
 * 달라지므로 저장 당시의 도착시각을 그대로 보여주면 틀린 정보가 된다.
 *
 * 찜은 여러 곳을 모아 두는 곳이라, 목록에서 몇 곳을 고른 뒤 바로 하루 코스로 엮을 수 있게 했다.
 */
class MyPageActivity : AppCompatActivity() {

    private lateinit var badge: TextView
    private lateinit var points: TextView
    private lateinit var progress: ProgressBar
    private lateinit var nextBadge: TextView
    private lateinit var statCourses: TextView
    private lateinit var statFavs: TextView
    private lateinit var statStamps: TextView
    private lateinit var courses: RecyclerView
    private lateinit var coursesEmpty: TextView
    private lateinit var favorites: RecyclerView
    private lateinit var favoritesEmpty: TextView
    private lateinit var favMake: MaterialButton
    private lateinit var stampsTitle: TextView
    private lateinit var stamps: ChipGroup
    private lateinit var stampsEmpty: TextView

    private lateinit var adapter: SavedCourseAdapter
    private lateinit var favAdapter: FavoriteAdapter

    /** 찜 목록에서 코스로 엮을 대상(선택 순서 유지). */
    private val pickedFavorites = LinkedHashSet<Long>()

    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        setContentView(R.layout.activity_yeobaek_mypage)

        findViewById<View>(R.id.my_bar).applyInsets(top = true)
        findViewById<View>(R.id.my_scroll).applyInsets(bottom = true)
        findViewById<View>(R.id.my_back).setOnClickListener { finish() }

        badge = findViewById(R.id.my_badge)
        points = findViewById(R.id.my_points)
        progress = findViewById(R.id.my_progress)
        nextBadge = findViewById(R.id.my_next_badge)
        statCourses = findViewById(R.id.my_stat_courses)
        statFavs = findViewById(R.id.my_stat_favs)
        statStamps = findViewById(R.id.my_stat_stamps)
        courses = findViewById(R.id.my_courses)
        coursesEmpty = findViewById(R.id.my_courses_empty)
        favorites = findViewById(R.id.my_favorites)
        favoritesEmpty = findViewById(R.id.my_favorites_empty)
        favMake = findViewById(R.id.my_fav_make)
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

        favorites.layoutManager = LinearLayoutManager(this)
        favAdapter = FavoriteAdapter(
            isPicked = { id -> pickedFavorites.contains(id) },
            onToggle = { fav -> toggleFavoritePick(fav) },
            onDelete = { fav -> confirmUnfavorite(fav) },
        )
        favorites.adapter = favAdapter

        favMake.setOnClickListener { makeCourseFromFavorites() }
    }

    override fun onResume() {
        super.onResume()
        // 코스를 저장하거나 지도에서 포인트를 쌓고 돌아왔을 수 있다.
        refresh()
    }

    private fun refresh() {
        val pts = EcoStore.points(this)
        badge.text = EcoStore.badge(pts)
        points.text = "$pts 포인트"
        progress.progress = EcoStore.progressPct(pts)
        val next = EcoStore.nextThreshold(pts)
        nextBadge.text = if (next == null) {
            "최고 배지 달성 — 한적한 여행을 계속 이어가세요"
        } else {
            "다음 배지까지 ${next - pts}포인트"
        }

        val saved = CourseStore.all(this)
        adapter.replaceAll(saved)
        coursesEmpty.showIf(saved.isEmpty())
        statCourses.text = saved.size.toString()

        renderFavorites()
        renderStamps()
    }

    // ── 찜 ────────────────────────────────────────────────────────────────────

    private fun renderFavorites() {
        val all = FavoriteStore.all(this)
        // 목록에서 사라진 찜은 선택에서도 빼야 '코스 만들기'가 유령 id 를 보내지 않는다.
        pickedFavorites.retainAll(all.map { it.contentId }.toSet())

        favAdapter.submit(all)
        favoritesEmpty.showIf(all.isEmpty())
        statFavs.text = all.size.toString()
        updateFavMakeButton()
    }

    private fun toggleFavoritePick(fav: FavoritePlace) {
        if (!pickedFavorites.remove(fav.contentId)) pickedFavorites.add(fav.contentId)
        favAdapter.notifyDataSetChanged()
        updateFavMakeButton()
    }

    private fun updateFavMakeButton() {
        val n = pickedFavorites.size
        favMake.showIf(n > 0)
        favMake.text = getString(R.string.my_favorites_make) + " ($n)"
    }

    private fun confirmUnfavorite(fav: FavoritePlace) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.my_fav_delete_cd)
            .setMessage("‘${fav.title}’ 을(를) 찜에서 뺄까요?")
            .setPositiveButton("빼기") { _, _ ->
                FavoriteStore.remove(this, fav.contentId)
                pickedFavorites.remove(fav.contentId)
                renderFavorites()
            }
            .setNegativeButton(R.string.ye_cancel, null)
            .show()
    }

    /** 고른 찜들을 지금 시각 출발의 하루 코스로 엮는다(고른 순서 유지). */
    private fun makeCourseFromFavorites() {
        if (busy || pickedFavorites.isEmpty()) return
        busy = true
        val stops = pickedFavorites.toList()
        val startTime = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:00"))
        lifecycleScope.launch {
            try {
                val plan = YeobaekClient.api.schedule(
                    ScheduleRequest(startTime = startTime, stops = stops, keepOrder = true)
                )
                startActivity(
                    Intent(this@MyPageActivity, PlannerActivity::class.java).apply {
                        putExtra(Extras.STOPS, stops.toLongArray())
                        putExtra(Extras.START_TIME, startTime)
                        putExtra(Extras.PLAN_JSON, YeobaekClient.gson.toJson(plan))
                        putExtra(Extras.KEEP_ORDER, true)
                    }
                )
            } catch (e: Exception) {
                Snackbar.make(
                    favMake, "코스 생성 실패: ${e.message ?: "네트워크 오류"}", Snackbar.LENGTH_LONG
                ).show()
            } finally {
                busy = false
            }
        }
    }

    // ── 스탬프 ────────────────────────────────────────────────────────────────

    private fun renderStamps() {
        val all = StampStore.all(this)
        statStamps.text = all.size.toString()
        stampsTitle.text = getString(R.string.my_stamps) + " (${all.size})"
        stampsEmpty.showIf(all.isEmpty())
        stamps.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (s in all) {
            val chip = inflater.inflate(R.layout.item_stamp_chip, stamps, false) as Chip
            chip.text = s.title
            stamps.addView(chip)
        }
    }

    // ── 저장한 코스 ───────────────────────────────────────────────────────────

    /** 저장 당시 입력(장소·시작시각·모드)으로 다시 계산해서 코스 화면을 연다. */
    private fun openCourse(course: SavedCourse) {
        if (busy) return
        busy = true
        Snackbar.make(courses, "‘${course.name}’ 다시 계산 중…", Snackbar.LENGTH_SHORT).show()
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
                Snackbar.make(
                    courses,
                    "코스를 여는 데 실패했어요: ${e.message ?: "네트워크 오류"}",
                    Snackbar.LENGTH_LONG
                ).show()
            } finally {
                busy = false
            }
        }
    }

    private fun confirmDelete(course: SavedCourse) {
        MaterialAlertDialogBuilder(this)
            .setTitle("코스 삭제")
            .setMessage("‘${course.name}’ 을(를) 보관함에서 지울까요?")
            .setPositiveButton("삭제") { _, _ ->
                CourseStore.delete(this, course.id)
                refresh()
            }
            .setNegativeButton(R.string.ye_cancel, null)
            .show()
    }
}
