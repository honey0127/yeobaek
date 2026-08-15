package com.example.crowdmap.yeobaek.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 찜한 장소(로컬).
 *
 * [StampStore] 는 "담아본 적 있다"는 **기록**이고(지울 수 없는 수집물), 이쪽은
 * "다음에 갈래요"라는 **의도**다. 지도에서 좋은 곳을 발견했지만 지금 코스에는 안
 * 넣을 때 눌러 두고, 나중에 내 여백에서 한 번에 코스로 만든다.
 *
 * 좌표까지 같이 저장해 둬야 서버 검색을 다시 하지 않고도 지도로 이동할 수 있다.
 */
data class FavoritePlace(
    val contentId: Long,
    val title: String,
    val catLabel: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val savedAt: Long = System.currentTimeMillis(),
)

object FavoriteStore {
    private const val PREF = "yeobaek_favorites"
    private const val K_ITEMS = "items"
    private const val MAX = 100

    private val gson = Gson()
    private val TYPE = object : TypeToken<List<FavoritePlace>>() {}.type

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 최근 찜한 순. */
    fun all(ctx: Context): List<FavoritePlace> {
        val json = prefs(ctx).getString(K_ITEMS, null) ?: return emptyList()
        val list: List<FavoritePlace> = runCatching {
            gson.fromJson<List<FavoritePlace>>(json, TYPE)
        }.getOrNull() ?: return emptyList()
        return list.sortedByDescending { it.savedAt }
    }

    fun count(ctx: Context): Int = all(ctx).size

    fun isFavorite(ctx: Context, contentId: Long): Boolean =
        contentId > 0 && all(ctx).any { it.contentId == contentId }

    /** 찜 상태를 뒤집고, 뒤집은 뒤 상태(true=찜함)를 돌려준다. */
    fun toggle(ctx: Context, place: FavoritePlace): Boolean {
        val current = all(ctx)
        val exists = current.any { it.contentId == place.contentId }
        val next = if (exists) {
            current.filterNot { it.contentId == place.contentId }
        } else {
            (listOf(place) + current).take(MAX)
        }
        write(ctx, next)
        return !exists
    }

    fun remove(ctx: Context, contentId: Long) {
        write(ctx, all(ctx).filterNot { it.contentId == contentId })
    }

    private fun write(ctx: Context, list: List<FavoritePlace>) {
        prefs(ctx).edit().putString(K_ITEMS, gson.toJson(list, TYPE)).apply()
    }
}
