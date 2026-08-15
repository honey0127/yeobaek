package com.example.crowdmap.yeobaek.data

import android.content.Context

/**
 * 최근 검색어(로컬).
 *
 * 여행지 검색은 같은 말을 여러 번 친다("경복궁" → 담고 → 다시 "경복궁 근처"). 매번
 * 처음부터 타이핑하게 두지 않으려고 최근 검색어를 남긴다. 서버에 보내지 않고
 * 기기 안에만 둔다(계정이 없고, 검색어는 위치·취향이 드러나는 민감한 기록이라
 * 굳이 밖으로 내보낼 이유가 없다).
 */
object RecentSearchStore {
    private const val PREF = "yeobaek_recent_search"
    private const val K_ITEMS = "items"
    private const val MAX = 8
    private const val SEP = "\u001F"    // 검색어에 안 나오는 구분자(Unit Separator)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 최근 순. */
    fun all(ctx: Context): List<String> =
        prefs(ctx).getString(K_ITEMS, null)
            ?.split(SEP)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    /** 같은 검색어는 위로 올린다(중복 없음). */
    fun add(ctx: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val next = (listOf(q) + all(ctx).filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
        prefs(ctx).edit().putString(K_ITEMS, next.joinToString(SEP)).apply()
    }

    fun remove(ctx: Context, query: String) {
        val next = all(ctx).filterNot { it == query }
        prefs(ctx).edit().putString(K_ITEMS, next.joinToString(SEP)).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(K_ITEMS).apply()
    }
}
