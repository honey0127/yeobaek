package com.example.crowdmap.yeobaek.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.crowdmap.R
import com.example.crowdmap.yeobaek.data.PlaceResult
import com.example.crowdmap.yeobaek.data.RecentSearchStore
import com.example.crowdmap.yeobaek.data.YeobaekClient
import com.example.crowdmap.yeobaek.ui.YeUi.applyInsets
import com.example.crowdmap.yeobaek.ui.YeUi.edgeToEdge
import com.example.crowdmap.yeobaek.ui.YeUi.showIf
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 장소 이름 검색. 300ms 디바운스 → `/places/search`.
 *
 * 검색은 같은 말을 여러 번 치게 되는 화면이라 [RecentSearchStore] 로 최근 검색어를
 * 남겨 두고, 검색어가 비면 칩으로 다시 꺼내 쓸 수 있게 한다.
 *
 * 결과 없음과 서버 오류는 성격이 다르다 — 전자는 "이 이름이 DB에 없다", 후자는
 * "서버에 못 닿았다"이고 사용자가 할 일도 다르다. 그래서 상태 화면을 나눠 보여주고
 * 오류일 때만 '다시 시도' 버튼을 준다.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var clearButton: View
    private lateinit var progress: ProgressBar
    private lateinit var list: RecyclerView
    private lateinit var recentPanel: View
    private lateinit var recentGroup: ChipGroup
    private lateinit var stateView: View
    private lateinit var stateIcon: ImageView
    private lateinit var stateTitle: TextView
    private lateinit var stateBody: TextView
    private lateinit var stateRetry: MaterialButton

    private lateinit var adapter: PlaceAdapter
    private var searchJob: Job? = null

    /** 마지막으로 시도한 검색어 — '다시 시도'가 무엇을 다시 할지 알아야 한다. */
    private var lastQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        setContentView(R.layout.activity_yeobaek_search)

        findViewById<View>(R.id.search_bar_row).applyInsets(top = true)
        findViewById<View>(R.id.search_list).applyInsets(bottom = true)

        input = findViewById(R.id.search_input)
        clearButton = findViewById(R.id.search_clear)
        progress = findViewById(R.id.search_progress)
        list = findViewById(R.id.search_list)
        recentPanel = findViewById(R.id.search_recent_panel)
        recentGroup = findViewById(R.id.search_recent_group)
        stateView = findViewById(R.id.state_view)
        stateIcon = findViewById(R.id.state_icon)
        stateTitle = findViewById(R.id.state_title)
        stateBody = findViewById(R.id.state_body)
        stateRetry = findViewById(R.id.state_retry)

        list.layoutManager = LinearLayoutManager(this)
        adapter = PlaceAdapter { place -> returnPlace(place) }
        list.adapter = adapter

        findViewById<View>(R.id.search_back).setOnClickListener { finish() }
        clearButton.setOnClickListener {
            input.setText("")
            input.requestFocus()
            showKeyboard()
        }
        findViewById<MaterialButton>(R.id.search_recent_clear).setOnClickListener {
            RecentSearchStore.clear(this)
            renderRecent()
        }
        stateRetry.setOnClickListener {
            if (lastQuery.isNotEmpty()) startSearch(lastQuery, immediate = true)
        }

        input.doAfterTextChanged { editable ->
            val q = editable?.toString()?.trim().orEmpty()
            clearButton.showIf(q.isNotEmpty())
            searchJob?.cancel()
            if (q.isEmpty()) {
                adapter.submit(emptyList())
                showIdle()
                return@doAfterTextChanged
            }
            startSearch(q, immediate = false)
        }

        // 키보드의 검색 키 → 디바운스 기다리지 않고 바로 조회.
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) startSearch(q, immediate = true)
                true
            } else {
                false
            }
        }

        showIdle()
        // 검색 화면에 들어온 이유는 검색이다 — 바로 칠 수 있게 키보드를 올린다.
        input.requestFocus()
        input.post { showKeyboard() }
    }

    // ── 검색 ─────────────────────────────────────────────────────────────────

    private fun startSearch(q: String, immediate: Boolean) {
        lastQuery = q
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            if (!immediate) delay(300)   // 디바운스
            doSearch(q)
        }
    }

    private suspend fun doSearch(q: String) {
        setLoading(true)
        try {
            val res = YeobaekClient.api.searchPlaces(q)
            adapter.submit(res.results)
            if (res.results.isEmpty()) {
                // 결과 0건은 "검색어가 없다"기보다 "장소 DB가 비어 있다"인 경우가 많다.
                showState(
                    icon = R.drawable.ic_ye_search,
                    title = "‘$q’ 검색 결과가 없어요",
                    body = "다른 이름으로 찾아보세요.\n" +
                        "장소가 하나도 안 나온다면 서버에 명소 데이터가 아직 없는 상태입니다.",
                    retry = false,
                )
            } else {
                hideState()
                RecentSearchStore.add(this, q)
            }
        } catch (e: Exception) {
            // 네트워크/서버 문제는 조용히 지나가지 않게 화면에도 남긴다.
            adapter.submit(emptyList())
            showState(
                icon = R.drawable.ic_ye_warning,
                title = "서버에 연결하지 못했어요",
                body = "· 여백 서버가 실행 중인지\n" +
                    "· local.properties 의 서버 주소가 맞는지\n" +
                    "· 폰과 PC 가 같은 Wi-Fi 인지 확인해 주세요.\n\n" +
                    "(${e.message ?: "네트워크 오류"})",
                retry = true,
            )
        } finally {
            setLoading(false)
        }
    }

    // ── 상태 화면 ────────────────────────────────────────────────────────────

    /** 검색어가 비었을 때: 최근 검색어가 있으면 그걸, 없으면 안내 문구를 보여준다. */
    private fun showIdle() {
        renderRecent()
        val hasRecent = RecentSearchStore.all(this).isNotEmpty()
        if (hasRecent) {
            hideState()
        } else {
            showState(
                icon = R.drawable.ic_ye_search,
                title = getString(R.string.search_idle),
                body = getString(R.string.search_idle_sub),
                retry = false,
            )
        }
    }

    private fun showState(icon: Int, title: String, body: String, retry: Boolean) {
        stateIcon.setImageResource(icon)
        stateTitle.text = title
        stateBody.text = body
        stateRetry.showIf(retry)
        stateView.visibility = View.VISIBLE
    }

    private fun hideState() {
        stateView.visibility = View.GONE
    }

    private fun renderRecent() {
        val recent = RecentSearchStore.all(this)
        val queryEmpty = input.text.toString().trim().isEmpty()
        recentPanel.showIf(recent.isNotEmpty() && queryEmpty)
        recentGroup.removeAllViews()
        if (!queryEmpty) return
        val inflater = LayoutInflater.from(this)
        for (q in recent) {
            val chip = inflater.inflate(R.layout.item_recent_chip, recentGroup, false) as Chip
            chip.text = q
            chip.setOnClickListener {
                input.setText(q)
                input.setSelection(q.length)
                startSearch(q, immediate = true)
            }
            chip.setOnCloseIconClickListener {
                RecentSearchStore.remove(this, q)
                renderRecent()
            }
            recentGroup.addView(chip)
        }
    }

    // ── 기타 ─────────────────────────────────────────────────────────────────

    private fun returnPlace(place: PlaceResult) {
        setResult(RESULT_OK, Intent().apply {
            putExtra(Extras.PLACE_ID, place.contentId)
            putExtra(Extras.PLACE_TITLE, place.title)
            putExtra(Extras.PLACE_LAT, place.lat ?: Double.NaN)
            putExtra(Extras.PLACE_LNG, place.lng ?: Double.NaN)
        })
        finish()
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.INVISIBLE
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }
}
