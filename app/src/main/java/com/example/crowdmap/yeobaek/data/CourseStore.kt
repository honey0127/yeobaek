package com.example.crowdmap.yeobaek.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * 내가 만든 코스 보관함(로컬).
 *
 * [PlanCache] 는 "마지막 계획 1개"의 오프라인 폴백이고, 이쪽은 사용자가 이름을 붙여
 * **여러 개를 모아두는** 저장소다. 서버에 계정이 없으므로 EcoStore/StampStore 와 같은
 * SharedPreferences + Gson 패턴을 따른다(신규 의존성 없음).
 *
 * 저장하는 건 계산 결과가 아니라 **재현에 필요한 입력**(stops·시작시각·모드)이다.
 * 혼잡도는 시간이 지나면 달라지므로, 불러올 때 서버로 다시 계산해야 그 시점의
 * 도착시각·혼잡도가 맞다. titles/지표는 목록에 보여줄 요약용 스냅샷이다.
 */
data class SavedCourse(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val savedAt: Long = System.currentTimeMillis(),
    val startTime: String,              // KST ISO "2026-10-11T10:00:00"
    val keepOrder: Boolean = true,
    val stops: List<Long>,
    val titles: List<String> = emptyList(),
    val yeobaekIndex: Int? = null,
    val savedCongestionPct: Int = 0,
) {
    /** 목록 카드 두 번째 줄. */
    fun summary(): String {
        val parts = mutableListOf("${stops.size}곳")
        yeobaekIndex?.let { parts += "여백지수 $it" }
        if (savedCongestionPct > 0) parts += "혼잡 ${savedCongestionPct}%↓"
        return parts.joinToString(" · ")
    }
}

object CourseStore {
    private const val PREF = "yeobaek_courses"
    private const val K_COURSES = "courses"
    private const val MAX = 50          // 오래된 것부터 밀어낸다(로컬 저장소 비대화 방지)

    private val gson = Gson()
    private val TYPE = object : TypeToken<List<SavedCourse>>() {}.type

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 최근 저장 순. */
    fun all(ctx: Context): List<SavedCourse> {
        val json = prefs(ctx).getString(K_COURSES, null) ?: return emptyList()
        val list: List<SavedCourse> = runCatching {
            gson.fromJson<List<SavedCourse>>(json, TYPE)
        }.getOrNull() ?: return emptyList()
        return list.sortedByDescending { it.savedAt }
    }

    fun count(ctx: Context): Int = all(ctx).size

    /** 같은 id 가 있으면 덮어쓴다(이름 변경·재저장). */
    fun save(ctx: Context, course: SavedCourse) {
        val next = (listOf(course) + all(ctx).filterNot { it.id == course.id }).take(MAX)
        write(ctx, next)
    }

    fun delete(ctx: Context, id: String) {
        write(ctx, all(ctx).filterNot { it.id == id })
    }

    private fun write(ctx: Context, list: List<SavedCourse>) {
        prefs(ctx).edit().putString(K_COURSES, gson.toJson(list, TYPE)).apply()
    }

    /** "경복궁 외 2곳" — 이름을 안 정했을 때의 기본 이름. */
    fun defaultName(titles: List<String>): String = when {
        titles.isEmpty() -> "이름 없는 코스"
        titles.size == 1 -> titles[0]
        else -> "${titles[0]} 외 ${titles.size - 1}곳"
    }
}
