package com.example.crowdmap.yeobaek.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.crowdmap.R
import com.example.crowdmap.yeobaek.data.AdhocRequest
import com.example.crowdmap.yeobaek.data.Congestion
import com.example.crowdmap.yeobaek.data.EcoStore
import com.example.crowdmap.yeobaek.data.FavoritePlace
import com.example.crowdmap.yeobaek.data.FavoriteStore
import com.example.crowdmap.yeobaek.data.PlaceResult
import com.example.crowdmap.yeobaek.data.ReportRequest
import com.example.crowdmap.yeobaek.data.ScheduleRequest
import com.example.crowdmap.yeobaek.data.StampStore
import com.example.crowdmap.yeobaek.data.YeobaekClient
import com.example.crowdmap.yeobaek.ui.YeUi.applyInsets
import com.example.crowdmap.yeobaek.ui.YeUi.edgeToEdge
import com.example.crowdmap.yeobaek.ui.YeUi.showIf
import com.example.crowdmap.yeobaek.ui.YeUi.tick
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PointOfInterest
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 홈 — 풀스크린 지도 + 상단 검색/필터 + 하단 코스 시트.
 *
 * 지도에서 장소를 담고(마커 + 시트의 칩), 날짜·시각과 코스 방식을 정해 코스를 만든다.
 *  - "자동 최적화" = 혼잡도 예측으로 방문 순서를 재배치
 *  - "내 순서대로" = 고른 순서를 유지하고 도착 시점 혼잡도만 표시
 *
 * 화면은 edge-to-edge 다. 상단 컨트롤은 [top_bar] 하나에 모여 있고 상태바 높이는
 * [YeUi.applyInsets] 로 넣는다 — 기기별 상태바/노치 높이를 상수로 가정하지 않는다.
 */
class YeobaekHomeActivity : AppCompatActivity(), OnMapReadyCallback {

    private data class Stop(val title: String, val lat: Double, val lng: Double) {
        val hasLatLng: Boolean get() = !lat.isNaN() && !lng.isNaN()
    }

    private val selectedStops = LinkedHashMap<Long, Stop>()  // id → 정보(순서 보존)
    private var date: LocalDate = LocalDate.now()
    private var time: LocalTime = LocalTime.of(14, 0)
    private var keepOrder = false

    private var map: GoogleMap? = null

    private lateinit var root: View
    private lateinit var topBar: View
    private lateinit var dateText: TextView
    private lateinit var timeText: TextView
    private lateinit var chipGroup: ChipGroup
    private lateinit var emptyHint: TextView
    private lateinit var progress: ProgressBar
    private lateinit var planButton: MaterialButton
    private lateinit var modeHint: TextView
    private lateinit var sheetCount: TextView
    private lateinit var sheetSummary: TextView
    private lateinit var sheetSummarySub: TextView
    private lateinit var sheetBehavior: BottomSheetBehavior<View>

    private lateinit var recoPanel: View
    private var recoJob: Job? = null

    private val selectedMarkers = HashMap<Long, Marker>()      // 담긴 장소(브랜드 칩)
    private val nearbyMarkers = HashMap<Marker, PlaceResult>()  // 주변 추천(혼잡 색 칩)
    private lateinit var placeInfo: View
    private lateinit var infoTitle: TextView
    private lateinit var infoMeta: TextView
    private lateinit var infoLevel: TextView
    private lateinit var infoAdd: MaterialButton
    private lateinit var infoFav: MaterialButton
    private lateinit var infoOffpeak: MaterialButton
    private lateinit var infoDisperse: MaterialButton
    private lateinit var recoHeader: TextView
    private lateinit var ecoChip: TextView
    private lateinit var placeInfoBehavior: BottomSheetBehavior<View>
    private val reportMarkers = ArrayList<Marker>()

    private var quietOnly = false           // '한적한 곳만' 필터

    /** 마지막으로 서버에 물어본 지도 상태 — 조금 움직인 정도로는 다시 안 부른다. */
    private var lastFetchCenter: LatLng? = null
    private var lastFetchZoom = Float.NaN
    /** 마지막으로 지도에 그린 결과. 같은 내용이면 마커를 다시 만들지 않는다. */
    private var lastRendered: List<PlaceResult> = emptyList()

    /** 제스처 바/내비바 높이(px). 시트 peek 높이와 지도 하단 패딩에 더해진다. */
    private var navBarInset = 0
    /** 마지막으로 계산한 peek 높이(px) — 지도 패딩·범례 위치가 이 값을 따라간다. */
    private var sheetPeek = 0

    private val searchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val id = result.data?.getLongExtra(Extras.PLACE_ID, -1L) ?: -1L
            val title = result.data?.getStringExtra(Extras.PLACE_TITLE) ?: return@registerForActivityResult
            val lat = result.data?.getDoubleExtra(Extras.PLACE_LAT, Double.NaN) ?: Double.NaN
            val lng = result.data?.getDoubleExtra(Extras.PLACE_LNG, Double.NaN) ?: Double.NaN
            if (id <= 0) return@registerForActivityResult
            // 검색 결과는 바로 담지 않고, 그 위치로 이동해 장소 시트를 띄운다.
            // 사용자가 시트의 '코스에 추가'를 눌러야 코스에 들어간다.
            if (!lat.isNaN() && !lng.isNaN()) {
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 16f))
            }
            showPlaceInfo(
                PlaceResult(
                    contentId = id, title = title,
                    lat = lat.takeIf { !it.isNaN() }, lng = lng.takeIf { !it.isNaN() })
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        setContentView(R.layout.activity_yeobaek_home)

        root = findViewById(R.id.home_root)
        topBar = findViewById(R.id.top_bar)
        dateText = findViewById(R.id.input_date)
        timeText = findViewById(R.id.input_time)
        chipGroup = findViewById(R.id.chip_group)
        emptyHint = findViewById(R.id.stops_empty)
        progress = findViewById(R.id.home_progress)
        planButton = findViewById(R.id.btn_plan)
        modeHint = findViewById(R.id.mode_hint)
        sheetCount = findViewById(R.id.sheet_count)
        sheetSummary = findViewById(R.id.sheet_summary)
        sheetSummarySub = findViewById(R.id.sheet_summary_sub)

        // 상태바는 상단 컨트롤이, 제스처 바는 두 시트가 각각 피한다.
        topBar.applyInsets(top = true)
        findViewById<View>(R.id.sheet_scroll).applyInsets(bottom = true)
        findViewById<View>(R.id.place_info).applyInsets(bottom = true)

        // 하단 시트: 드래그로 접고 펼침. 손잡이를 탭해도 토글된다.
        sheetBehavior = BottomSheetBehavior.from(findViewById<View>(R.id.bottom_sheet))
        sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        // 접힌 시트의 노출 높이(peek)를 실제 콘텐츠 높이로 잡는다.
        //
        // dimen 상수로 두면 두 가지가 어긋난다 — 글꼴 크기를 키운 사용자에게는 CTA 버튼이
        // 잘리고, 제스처 내비게이션 기기에서는 버튼 아래쪽이 내비바에 깔린다.
        // 그래서 '손잡이 + 요약 줄'의 측정된 높이에 내비바 높이를 더해 계산한다.
        val sheetView = findViewById<View>(R.id.bottom_sheet)
        val handleArea = findViewById<View>(R.id.sheet_handle_area)
        val collapsedRow = findViewById<View>(R.id.sheet_collapsed_row)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            navBarInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            sheetView.post { updateSheetPeek(handleArea, collapsedRow) }
            insets
        }
        sheetView.post { updateSheetPeek(handleArea, collapsedRow) }
        findViewById<View>(R.id.sheet_handle_area).setOnClickListener {
            sheetBehavior.state =
                if (sheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED)
                    BottomSheetBehavior.STATE_COLLAPSED
                else
                    BottomSheetBehavior.STATE_EXPANDED
        }

        findViewById<View>(R.id.field_date).setOnClickListener { pickDate() }
        findViewById<View>(R.id.field_time).setOnClickListener { pickTime() }
        val openSearch = View.OnClickListener {
            searchLauncher.launch(Intent(this, SearchActivity::class.java))
        }
        findViewById<View>(R.id.search_bar).setOnClickListener(openSearch)
        findViewById<MaterialButton>(R.id.btn_add_place).setOnClickListener(openSearch)
        planButton.setOnClickListener { requestSchedule() }
        // 지금 보고 있는 지도 중심을 기준으로 코스 플래너를 연다.
        findViewById<MaterialButton>(R.id.btn_district).setOnClickListener { openAreaPlanner() }

        // 지도 상태 배너 — 평소엔 숨어 있고, 알려줄 일이 있을 때만 한 줄로 뜬다.
        recoPanel = findViewById(R.id.reco_panel)
        findViewById<View>(R.id.reco_close).setOnClickListener { recoPanel.visibility = View.GONE }

        // 장소 정보 시트 — 탭/검색했을 때만 아래에서 올라온다.
        placeInfo = findViewById(R.id.place_info)
        infoTitle = findViewById(R.id.info_title)
        infoMeta = findViewById(R.id.info_meta)
        infoLevel = findViewById(R.id.info_level)
        infoAdd = findViewById(R.id.info_add)
        infoFav = findViewById(R.id.info_fav)
        infoOffpeak = findViewById(R.id.info_offpeak)
        infoDisperse = findViewById(R.id.info_disperse)
        recoHeader = findViewById(R.id.reco_header)
        placeInfoBehavior = BottomSheetBehavior.from(placeInfo)
        placeInfoBehavior.state = BottomSheetBehavior.STATE_HIDDEN   // 기본은 숨김
        findViewById<View>(R.id.info_close).setOnClickListener { hidePlaceInfo() }

        // 에코 포인트 알약 = '내 여백' 입구(스탬프·포인트·저장한 코스·찜).
        ecoChip = findViewById(R.id.eco_chip)
        updateEcoChip()
        findViewById<View>(R.id.eco_pill).setOnClickListener { openMyPage() }

        findViewById<Chip>(R.id.chip_report).setOnClickListener { showReportDialog() }

        // 한적한 곳만 보기 — 지도·추천 목록에 함께 적용된다.
        findViewById<Chip>(R.id.chip_quiet_only).setOnCheckedChangeListener { view, checked ->
            view.tick()
            quietOnly = checked
            refreshReco(force = true)
        }

        val modeGroup = findViewById<MaterialButtonToggleGroup>(R.id.mode_group)
        modeGroup.check(R.id.btn_mode_auto)
        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            keepOrder = checkedId == R.id.btn_mode_keep
            modeHint.setText(
                if (keepOrder) R.string.home_mode_hint_keep else R.string.home_mode_hint_auto
            )
        }

        (supportFragmentManager.findFragmentById(R.id.map_fragment) as SupportMapFragment)
            .getMapAsync(this)

        updateDateText()
        updateTimeText()
        refreshChips()
    }

    override fun onResume() {
        super.onResume()
        // 내 여백에서 포인트가 바뀌었을 수 있다.
        updateEcoChip()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        // 다크 모드에서는 지도도 야간 스타일로 — 밝은 지도 위 어두운 UI 는 눈이 부시다.
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        runCatching {
            googleMap.setMapStyle(
                MapStyleOptions.loadRawResourceStyle(
                    this,
                    if (night) R.raw.ye_map_style_night else R.raw.ye_map_style
                )
            )
        }
        googleMap.uiSettings.isMapToolbarEnabled = false
        // 구글 로고·컨트롤이 상단 바/접힌 시트에 가리지 않도록 여백을 준다.
        // 아래쪽은 updateSheetPeek 이 계산한 실제 peek 높이를 따른다(아직 0이면 기본값).
        val gap = resources.getDimensionPixelSize(R.dimen.ye_space_sm)
        val bottomPad = if (sheetPeek > 0) sheetPeek + gap
        else resources.getDimensionPixelSize(R.dimen.ye_map_overlay_bottom)
        topBar.post { googleMap.setPadding(0, topBar.height, 0, bottomPad) }
        // 기본 시점: 서울 중심
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(37.5665, 126.9780), 13f))
        // 지도를 옮길 때마다 그 지역 명소를 추천
        googleMap.setOnCameraIdleListener { refreshReco() }
        // 핀 탭 → 정보 카드. 지도의 명소 라벨 탭도 여백 DB에서 찾아 카드로.
        googleMap.setOnMarkerClickListener { marker ->
            val p = nearbyMarkers[marker]
            if (p != null) { showPlaceInfo(p); true } else false
        }
        googleMap.setOnPoiClickListener { poi -> handlePoiTap(poi) }
        googleMap.setOnMapClickListener { hidePlaceInfo() }
        // 아무 지점이나 길게 누르면 그 위치를 '선택한 위치'로 담을 수 있게(어드혹)
        googleMap.setOnMapLongClickListener { ll ->
            showPlaceInfo(
                PlaceResult(
                    contentId = 0, title = "선택한 위치",
                    lat = ll.latitude, lng = ll.longitude
                )
            )
        }
        // 구성 변경 등으로 이미 담긴 장소가 있으면 마커 복원
        selectedStops.forEach { (id, stop) -> addSelectedMarker(id, stop) }
        fitCameraToSelected()
        refreshReco()
    }

    /**
     * 접힌 시트 높이를 다시 계산하고, 거기에 맞춰 지도 하단 패딩과 범례 위치를 옮긴다.
     *
     * 세 값이 같은 기준(peek)을 쓰지 않으면 구글 로고나 범례가 시트에 가린다.
     */
    private fun updateSheetPeek(handleArea: View, collapsedRow: View) {
        val content = handleArea.height + collapsedRow.height
        if (content <= 0) return
        val peek = content + navBarInset
        if (peek == sheetPeek) return
        sheetPeek = peek
        sheetBehavior.peekHeight = peek

        val gap = resources.getDimensionPixelSize(R.dimen.ye_space_sm)
        map?.setPadding(0, topBar.height, 0, peek + gap)
        findViewById<View>(R.id.heat_legend).apply {
            val lp = layoutParams as? ViewGroup.MarginLayoutParams ?: return@apply
            lp.bottomMargin = peek + gap
            layoutParams = lp
        }
    }

    /**
     * 현재 지도 중심 주변 명소를 지도 라벨로 표시한다(디바운스).
     *
     * 카메라가 멈출 때마다 호출되므로 아낄 수 있는 건 최대한 아낀다:
     * 조금 움직인 정도면 서버를 다시 부르지 않고([hasMovedEnough]), 결과가 지난번과
     * 같으면 마커도 다시 만들지 않는다. 라벨 비트맵은 [MapLabel] 이 캐시한다.
     */
    private fun refreshReco(force: Boolean = false) {
        val gmap = map ?: return
        val center = gmap.cameraPosition.target
        val zoom = gmap.cameraPosition.zoom
        val radius = radiusKmFromMap(gmap)
        if (!force && !hasMovedEnough(center, zoom, radius)) return
        recoJob?.cancel()
        recoJob = lifecycleScope.launch {
            delay(450)   // 카메라가 멈춘 뒤에만 요청(스팸 방지)
            try {
                // 히트맵: 주변 명소 + 현재 혼잡 레벨(라벨 색) + 한적함 지수
                val res = YeobaekClient.api.heatmap(center.latitude, center.longitude, radius)
                lastFetchCenter = center
                lastFetchZoom = zoom
                val all = res.results.filter { !selectedStops.containsKey(it.contentId) }
                // '한적한 곳만' — 레벨을 모르는 곳은 한적하다고 단정할 수 없으니 함께 감춘다.
                val fresh = if (quietOnly) all.filter { Congestion.isQuiet(it.level) } else all
                // 평소엔 배너를 띄우지 않는다. 지도에 라벨이 떠 있으면 그게 곧 결과다.
                // 비어 있을 때만 왜 비었는지 한 줄로 알려준다(고장으로 오해하지 않게).
                if (fresh.isEmpty()) {
                    showBanner(
                        if (quietOnly && all.isNotEmpty()) "이 지역엔 지금 한적한 곳이 없어요"
                        // 수집 데이터에 없는 지역이어도 길게 눌러 직접 담을 수 있다(어드혹).
                        // 길게 누르기는 발견하기 어려운 제스처라 비었을 때 알려준다.
                        else "이 지역엔 등록된 명소가 없어요 · 지도를 길게 눌러 추가"
                    )
                } else {
                    recoPanel.visibility = View.GONE
                }
                renderNearbyMarkers(fresh)
                loadReports()
            } catch (e: CancellationException) {
                // 다음 요청이 이 요청을 취소한 것 — 실패가 아니다.
                //
                // 취소는 CancellationException 으로 오는데 이것도 Exception 이라,
                // 아래 catch 가 함께 삼키면 '서버에 연결할 수 없어요' 가 떠 버린다.
                // 확대/축소처럼 카메라가 연달아 멈추는 동안 매번 앞 요청이 취소되므로
                // 배너가 켜졌다 꺼졌다 하며 접속이 끊긴 것처럼 보였다. 그대로 흘려보낸다.
                throw e
            } catch (e: Exception) {
                // 서버 요청 자체가 실패한 것 — 데이터가 없는 것과 구분해 재시도를 안내한다.
                showBanner(getString(R.string.home_reco_error)) { refreshReco(force = true) }
            }
        }
    }

    /** 상태 배너 한 줄. [onTap] 을 주면 눌러서 다시 시도할 수 있다. */
    private fun showBanner(text: String, onTap: (() -> Unit)? = null) {
        recoHeader.text = text
        if (onTap == null) {
            recoHeader.setOnClickListener(null)
            recoHeader.isClickable = false
        } else {
            recoHeader.setOnClickListener { onTap() }
        }
        if (!isPlaceInfoShown()) recoPanel.visibility = View.VISIBLE
    }

    /**
     * 다시 불러올 만큼 지도가 움직였는지.
     *
     * 손가락을 뗄 때마다 카메라가 미세하게 흔들려도 onCameraIdle 이 오는데, 그때마다
     * 요청하고 마커를 다시 그리면 그게 곧 버벅임이다. 보이는 반경의 25% 이상
     * 이동했거나 줌이 반 단계 이상 바뀐 경우에만 새로 부른다.
     */
    private fun hasMovedEnough(center: LatLng, zoom: Float, radiusKm: Double): Boolean {
        val prev = lastFetchCenter ?: return true
        if (lastFetchZoom.isNaN() || abs(zoom - lastFetchZoom) >= 0.5f) return true
        val moved = haversineKm(prev.latitude, prev.longitude, center.latitude, center.longitude)
        return moved >= radiusKm * 0.25
    }

    /**
     * 주변 추천 장소를 '점 + 이름' 라벨로 표시(탭하면 정보 시트).
     *
     * 핀만 잔뜩 찍히면 구분이 안 되므로 이름을 직접 그린다. 라벨끼리 겹치면 읽을 수 없으니,
     * 화면 좌표로 겹침을 검사해 겹치는 것은 생략한다. 한적한 곳(quiet_score 높은 순)을 먼저
     * 배치해 추천 대상이 우선 살아남게 한다. 확대하면 간격이 벌어져 가려졌던 이름이 나타난다.
     */
    private fun renderNearbyMarkers(list: List<PlaceResult>) {
        val gmap = map ?: return
        // 내용이 지난번과 같으면 그대로 둔다 — 마커를 지웠다 다시 만드는 게 제일 비싸다.
        // (겹침 검사는 화면 좌표 기준이라 줌이 바뀌면 다시 해야 하는데, 그때는
        //  hasMovedEnough 가 이미 통과시켜 새 목록이 들어온다.)
        // 빈 목록은 항상 처리한다 — 지워야 할 마커가 남아 있을 수 있다.
        if (list.isNotEmpty() && nearbyMarkers.isNotEmpty() && sameAsRendered(list)) return
        lastRendered = list
        nearbyMarkers.keys.forEach { it.remove() }
        nearbyMarkers.clear()

        val proj = gmap.projection
        val dm = resources.displayMetrics
        val placed = ArrayList<Rect>()
        val margin = (2 * dm.density).toInt()

        for (p in list.sortedByDescending { it.quietScore ?: 0 }) {
            val lat = p.lat ?: continue
            val lng = p.lng ?: continue
            val pos = LatLng(lat, lng)
            // 레벨이 없으면(예보권 밖·키 미설정) 중립 회색 — 모르는 걸 '보통'으로 속이지 않는다.
            val label = MapLabel.render(dm, p.title, Congestion.color(p.level ?: 0))

            // 화면상 차지할 영역을 구해 이미 놓인 라벨과 겹치는지 확인
            val pt = proj.toScreenLocation(pos)
            val left = pt.x - label.bitmap.width / 2
            val top = pt.y - (label.anchorY * label.bitmap.height).toInt()
            val rect = Rect(
                left - margin, top - margin,
                left + label.bitmap.width + margin, top + label.bitmap.height + margin
            )
            if (placed.any { Rect.intersects(it, rect) }) continue
            placed.add(rect)

            val m = gmap.addMarker(
                MarkerOptions().position(pos)
                    .icon(BitmapDescriptorFactory.fromBitmap(label.bitmap))
                    .anchor(label.anchorX, label.anchorY)
            ) ?: continue
            nearbyMarkers[m] = p
        }
    }

    /** 같은 장소가 같은 혼잡 레벨로 들어왔는지 — 마커를 다시 만들지 판단하는 기준. */
    private fun sameAsRendered(list: List<PlaceResult>): Boolean {
        if (list.size != lastRendered.size) return false
        return list.zip(lastRendered).all { (a, b) ->
            a.contentId == b.contentId && a.level == b.level
        }
    }

    private fun addPlaceToCourse(place: PlaceResult) {
        // DB 명소(content_id>0)는 바로 담고, 어드혹(0)은 서버에 즉석 등록 후 담는다.
        if (place.contentId > 0) {
            addStop(
                place.contentId,
                Stop(place.title, place.lat ?: Double.NaN, place.lng ?: Double.NaN)
            )
            // 에코 리워드: 한적한 곳(여유/보통)을 담으면 포인트
            if ((place.level ?: 9) <= Congestion.QUIET_MAX_LEVEL) awardEco(EcoStore.P_QUIET_ADD)
            return
        }
        val lat = place.lat
        val lng = place.lng
        if (lat == null || lng == null) return
        lifecycleScope.launch {
            try {
                val res = YeobaekClient.api.addAdhoc(AdhocRequest(place.title, lat, lng))
                addStop(res.contentId, Stop(res.title, lat, lng))
            } catch (e: Exception) {
                Snackbar.make(
                    root, "담기 실패: ${e.message ?: "서버 확인"}", Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** 지도 명소 라벨(구글 POI) 탭 →
     *  ① 라벨의 한글 이름으로 검색(구글 라벨은 영문+한글 혼합이라 한글만 추출),
     *  ② 실패하면 탭한 좌표에서 가장 가까운 여백 명소(반경 250m)로 매칭,
     *  ③ 둘 다 없으면 그 위치 자체를 담을 수 있게 제시(어드혹). */
    private fun handlePoiTap(poi: PointOfInterest) {
        val ll = poi.latLng
        val korean = poi.name.split("\n").map { it.trim() }
            .firstOrNull { seg -> seg.any { it in '가'..'힣' } } ?: poi.name.trim()
        lifecycleScope.launch {
            try {
                val byName = korean.takeIf { it.isNotBlank() }
                    ?.let { YeobaekClient.api.searchPlaces(it, 1).results.firstOrNull() }
                if (byName != null) { showPlaceInfo(byName); return@launch }
                val byLoc = YeobaekClient.api
                    .nearbyPlaces(ll.latitude, ll.longitude, 0.4, 1).results.firstOrNull()
                if (byLoc != null && (byLoc.distKm ?: 9.9) <= 0.25) {
                    showPlaceInfo(byLoc)
                } else {
                    // DB 에 없는 명소 — 이 위치 자체를 담을 수 있게 제시(어드혹).
                    showPlaceInfo(
                        PlaceResult(
                            contentId = 0,
                            title = korean.ifBlank { "선택한 위치" },
                            lat = ll.latitude, lng = ll.longitude
                        )
                    )
                }
            } catch (e: Exception) { /* 부가 기능 — 조용히 무시 */ }
        }
    }

    private fun showPlaceInfo(place: PlaceResult) {
        infoTitle.text = place.title

        // 혼잡 배지는 '연한 배경 + 진한 글자'. 목록을 훑을 때 색 덩어리가 덜 피로하다.
        val level = place.level
        infoLevel.showIf(level != null)
        if (level != null) {
            infoLevel.text = Congestion.label(level)
            infoLevel.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, Congestion.containerRes(level))
            )
            infoLevel.setTextColor(ContextCompat.getColor(this, Congestion.colorRes(level)))
        }

        val dist = place.distKm?.let { "%.1fkm".format(it) }
        infoMeta.text = if (place.contentId <= 0) {
            "지도에서 선택한 위치 · 담아서 코스에 추가"
        } else {
            val quiet = place.quietScore?.let { "한적함 $it" }
            listOfNotNull(place.catLabel, quiet, dist)
                .joinToString(" · ")
                .ifBlank { "코스에 추가해 하루 동선을 만들어 보세요" }
        }

        val already = selectedStops.containsKey(place.contentId)
        infoAdd.setText(if (already) R.string.place_added else R.string.place_add)
        infoAdd.isEnabled = !already
        infoAdd.setOnClickListener { addPlaceToCourse(place); hidePlaceInfo() }

        // 찜·오프피크·미시분산은 DB 명소(content_id>0)에서만 의미가 있다.
        val isDb = place.contentId > 0
        infoFav.showIf(isDb)
        infoOffpeak.showIf(isDb)
        infoDisperse.showIf(isDb)
        if (isDb) {
            renderFavorite(place)
            infoFav.setOnClickListener { toggleFavorite(place) }
            infoOffpeak.setOnClickListener { showOffpeak(place) }
            infoDisperse.setOnClickListener { showDisperse(place) }
        }

        // 지역 추천 패널과 코스 시트는 접어 두고, 장소 시트만 올린다.
        recoPanel.visibility = View.GONE
        sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        placeInfoBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    // ── 찜 ────────────────────────────────────────────────────────────────────

    private fun renderFavorite(place: PlaceResult) {
        val fav = FavoriteStore.isFavorite(this, place.contentId)
        infoFav.setIconResource(if (fav) R.drawable.ic_ye_heart_filled else R.drawable.ic_ye_heart)
        infoFav.iconTint = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (fav) R.color.ye_danger else R.color.ye_on_surface)
        )
    }

    private fun toggleFavorite(place: PlaceResult) {
        infoFav.tick()
        val now = FavoriteStore.toggle(
            this,
            FavoritePlace(
                contentId = place.contentId,
                title = place.title,
                catLabel = place.catLabel,
                lat = place.lat,
                lng = place.lng,
            )
        )
        renderFavorite(place)
        Snackbar.make(
            root,
            if (now) "‘${place.title}’ 찜했어요 · 내 여백에서 볼 수 있어요" else "찜을 해제했어요",
            Snackbar.LENGTH_SHORT
        ).show()
    }

    /** 오프피크(덜 붐비는 시간) — 12시간 타임라인 시트. 고른 시각은 출발 시각이 된다. */
    private fun showOffpeak(place: PlaceResult) {
        lifecycleScope.launch {
            try {
                val res = YeobaekClient.api.offpeak(place.contentId)
                if (res.best.isEmpty() && res.timeline.isEmpty()) {
                    Snackbar.make(root, R.string.offpeak_none, Snackbar.LENGTH_LONG).show()
                    return@launch
                }
                OffpeakSheet.show(this@YeobaekHomeActivity, place.title, res) { picked ->
                    date = picked.toLocalDate()
                    time = picked.toLocalTime()
                    updateDateText()
                    updateTimeText()
                    sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                    Snackbar.make(
                        root,
                        "출발 시각을 ${picked.format(DateTimeFormatter.ofPattern("M월 d일 HH:mm"))} 로 맞췄어요",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
                awardEco(EcoStore.P_OFFPEAK)
            } catch (e: Exception) {
                Snackbar.make(root, "오프피크 조회 실패", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 미시적 분산 — 도보권의 더 한적한 대안을 **지도에 직접** 표시한다.
     *
     * 예전엔 카루셀에 채웠지만, 지도에 라벨이 이미 떠 있는데 아래에 같은 목록을 또
     * 깔면 화면만 복잡해진다. 대신 대안만 남겨 그리고 배너로 무엇을 보고 있는지 알린다.
     */
    private fun showDisperse(place: PlaceResult) {
        lifecycleScope.launch {
            try {
                val res = YeobaekClient.api.disperse(place.contentId)
                val fresh = res.results.filter { !selectedStops.containsKey(it.contentId) }
                if (fresh.isEmpty()) {
                    Snackbar.make(root, "근처에 더 한적한 대안이 없어요", Snackbar.LENGTH_SHORT).show()
                    return@launch
                }
                hidePlaceInfo()
                // 대안만 그린 상태를 다음 카메라 이동이 덮어쓰게 둔다(임시 표시).
                lastRendered = emptyList()
                renderNearbyMarkers(fresh)
                showBanner("‘${MapLabel.shorten(place.title)}’ 근처 더 한적한 곳 ${fresh.size}곳")
            } catch (e: Exception) {
                Snackbar.make(root, "대안 조회 실패", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateEcoChip() {
        ecoChip.text = EcoStore.points(this).toString()
    }

    /** 내 여백 — 스탬프·에코 포인트·찜·저장한 코스 모아보기. */
    private fun openMyPage() {
        startActivity(Intent(this, MyPageActivity::class.java))
    }

    private fun awardEco(pts: Int) {
        val total = EcoStore.award(this, pts)
        updateEcoChip()
        Snackbar.make(
            root, "🌱 +$pts 에코 포인트 · ${EcoStore.badge(total)}", Snackbar.LENGTH_SHORT
        ).show()
    }

    /** 이 지점 실시간 제보(붐빔/한적/팁). */
    private fun showReportDialog() {
        val gmap = map ?: return
        val c = gmap.cameraPosition.target
        val labels = arrayOf("지금 붐벼요", "여기 한적해요", "팁 남기기")
        val kinds = arrayOf("busy", "quiet", "tip")
        MaterialAlertDialogBuilder(this)
            .setTitle("이 지점(지도 중심) 실시간 제보")
            .setItems(labels) { _, which -> postReport(kinds[which], c.latitude, c.longitude) }
            .show()
    }

    private fun postReport(kind: String, lat: Double, lng: Double) {
        lifecycleScope.launch {
            try {
                YeobaekClient.api.postReport(ReportRequest(kind, lat, lng))
                awardEco(EcoStore.P_REPORT)
                loadReports()
            } catch (e: Exception) {
                Snackbar.make(
                    root, "제보 실패: ${e.message ?: "서버 확인"}", Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** 반경 내 최근 제보를 지도에 마커로. */
    private fun loadReports() {
        val gmap = map ?: return
        val c = gmap.cameraPosition.target
        lifecycleScope.launch {
            try {
                val reports = YeobaekClient.api
                    .getReports(c.latitude, c.longitude, radiusKmFromMap(gmap)).reports
                reportMarkers.forEach { it.remove() }
                reportMarkers.clear()
                for (r in reports) {
                    val hue = when (r.kind) {
                        "busy" -> BitmapDescriptorFactory.HUE_RED
                        "quiet" -> BitmapDescriptorFactory.HUE_AZURE
                        else -> BitmapDescriptorFactory.HUE_VIOLET
                    }
                    val label = when (r.kind) {
                        "busy" -> "붐빔 제보"
                        "quiet" -> "한적 제보"
                        else -> "여행 팁"
                    }
                    val m = gmap.addMarker(
                        MarkerOptions().position(LatLng(r.lat, r.lng))
                            .title(label).snippet(r.text)
                            .icon(BitmapDescriptorFactory.defaultMarker(hue))
                    )
                    if (m != null) reportMarkers.add(m)
                }
            } catch (e: Exception) { /* 부가 기능 */ }
        }
    }

    private fun hidePlaceInfo() {
        placeInfoBehavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    private fun isPlaceInfoShown() =
        placeInfoBehavior.state != BottomSheetBehavior.STATE_HIDDEN

    /** 보이는 지도 범위(중심→모서리)로 추천 반경(km)을 정한다. */
    private fun radiusKmFromMap(gmap: GoogleMap): Double {
        val c = gmap.cameraPosition.target
        val ne = gmap.projection.visibleRegion.latLngBounds.northeast
        val d = haversineKm(c.latitude, c.longitude, ne.latitude, ne.longitude)
        return d.coerceIn(0.6, 12.0)
    }

    private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0088
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    private fun pickDate() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("날짜 선택")
            .setSelection(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
            updateDateText()
        }
        picker.show(supportFragmentManager, "date")
    }

    private fun pickTime() {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(time.hour).setMinute(time.minute)
            .setTitleText("출발 시각")
            .build()
        picker.addOnPositiveButtonClickListener {
            time = LocalTime.of(picker.hour, picker.minute)
            updateTimeText()
        }
        picker.show(supportFragmentManager, "time")
    }

    private fun updateDateText() {
        dateText.text = date.format(DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN))
    }

    private fun updateTimeText() {
        timeText.text = time.format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    private fun addStop(id: Long, stop: Stop) {
        if (selectedStops.containsKey(id)) {
            Snackbar.make(root, "이미 담은 장소예요", Snackbar.LENGTH_SHORT).show()
            return
        }
        selectedStops[id] = stop
        addSelectedMarker(id, stop)
        fitCameraToSelected()
        refreshChips()
        refreshReco(force = true)   // 담은 장소는 지도 라벨에서 제외
        root.tick()
        val stamped = id > 0 && StampStore.stamp(this, id, stop.title)
        Snackbar.make(
            root,
            if (stamped) "‘${stop.title}’ 담았어요 · 스탬프를 모았어요"
            else "‘${stop.title}’ 담았어요",
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun removeStop(id: Long) {
        val removed = selectedStops.remove(id) ?: return
        selectedMarkers.remove(id)?.remove()
        refreshChips()
        refreshReco(force = true)
        // 칩의 ✕ 는 작아서 잘못 누르기 쉽다 — 되돌릴 방법을 반드시 준다.
        Snackbar.make(root, "‘${removed.title}’ 뺐어요", Snackbar.LENGTH_LONG)
            .setAction("되돌리기") {
                selectedStops[id] = removed
                addSelectedMarker(id, removed)
                refreshChips()
                refreshReco(force = true)
            }
            .show()
    }

    private fun refreshChips() {
        chipGroup.removeAllViews()
        emptyHint.showIf(selectedStops.isEmpty())
        val inflater = LayoutInflater.from(this)
        for ((id, stop) in selectedStops) {
            val chip = inflater.inflate(R.layout.item_stop_chip, chipGroup, false) as Chip
            chip.text = stop.title
            chip.isCloseIconVisible = true
            chip.setOnCloseIconClickListener { removeStop(id) }
            chipGroup.addView(chip)
        }
        updateSheetSummary()
    }

    /** 접힌 시트에 보이는 요약 줄. */
    private fun updateSheetSummary() {
        val n = selectedStops.size
        sheetCount.text = n.toString()
        if (n == 0) {
            sheetSummary.setText(R.string.home_sheet_empty_title)
            sheetSummarySub.setText(R.string.home_sheet_empty_sub)
        } else {
            sheetSummary.text = "담은 장소 ${n}곳"
            sheetSummarySub.text = selectedStops.values.joinToString(" · ") { it.title }
        }
        planButton.isEnabled = n > 0
    }

    /** 지금 보고 있는 지도 중심을 기준으로 '이 위치 코스 플래너'를 연다. */
    private fun openAreaPlanner() {
        val c = map?.cameraPosition?.target
        startActivity(Intent(this, DistrictActivity::class.java).apply {
            if (c != null) {
                putExtra(Extras.CENTER_LAT, c.latitude)
                putExtra(Extras.CENTER_LNG, c.longitude)
                putExtra(Extras.CENTER_NAME, "지도에서 고른 위치")
            }
        })
    }

    /** 담은 장소 라벨 — 브랜드 색 칩으로 주변 추천과 구분. 이미 있으면 스킵. */
    private fun addSelectedMarker(id: Long, stop: Stop) {
        val gmap = map ?: return
        if (!stop.hasLatLng || selectedMarkers.containsKey(id)) return
        val brand = ContextCompat.getColor(this, R.color.ye_primary)
        val label = MapLabel.render(resources.displayMetrics, stop.title, brand, emphasize = true)
        val m = gmap.addMarker(
            MarkerOptions().position(LatLng(stop.lat, stop.lng))
                .icon(BitmapDescriptorFactory.fromBitmap(label.bitmap))
                .anchor(label.anchorX, label.anchorY)
                .zIndex(1f)   // 담은 곳은 항상 위에
        )
        if (m != null) selectedMarkers[id] = m
    }

    /** 담은 장소들이 다 보이도록 카메라를 맞춘다. */
    private fun fitCameraToSelected() {
        val gmap = map ?: return
        val pts = selectedStops.values.filter { it.hasLatLng }.map { LatLng(it.lat, it.lng) }
        when (pts.size) {
            0 -> Unit
            1 -> gmap.animateCamera(CameraUpdateFactory.newLatLngZoom(pts[0], 15f))
            else -> {
                val b = LatLngBounds.Builder().apply { pts.forEach { include(it) } }.build()
                gmap.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 160))
            }
        }
    }

    private fun requestSchedule() {
        if (selectedStops.isEmpty()) {
            Snackbar.make(root, "장소를 하나 이상 담아주세요", Snackbar.LENGTH_SHORT).show()
            return
        }
        val startTime = LocalDateTime.of(date, time)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:00"))
        val stops = selectedStops.keys.toList()

        setLoading(true)
        lifecycleScope.launch {
            try {
                val plan = YeobaekClient.api.schedule(
                    ScheduleRequest(startTime = startTime, stops = stops, keepOrder = keepOrder)
                )
                val json = YeobaekClient.gson.toJson(plan)
                startActivity(
                    Intent(this@YeobaekHomeActivity, PlannerActivity::class.java).apply {
                        putExtra(Extras.STOPS, stops.toLongArray())
                        putExtra(Extras.START_TIME, startTime)
                        putExtra(Extras.PLAN_JSON, json)
                        putExtra(Extras.KEEP_ORDER, keepOrder)
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(
                    this@YeobaekHomeActivity,
                    "코스 생성 실패: ${e.message ?: "네트워크 오류"} (서버 주소·실행 확인)",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progress.showIf(loading)
        planButton.isEnabled = !loading && selectedStops.isNotEmpty()
        // 시트가 접혀 있어도 상태가 보이도록 버튼 자체에 표시한다.
        planButton.setText(if (loading) R.string.home_planning else R.string.home_plan)
    }
}
