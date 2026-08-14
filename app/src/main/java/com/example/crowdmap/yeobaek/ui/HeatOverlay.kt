package com.example.crowdmap.yeobaek.ui

import com.example.crowdmap.yeobaek.data.Congestion
import com.example.crowdmap.yeobaek.data.PlaceResult
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng

/**
 * 혼잡 농도 오버레이 — 명소마다 반투명 원을 깔아 "어느 쪽이 붐비는지"를 한눈에 보이게 한다.
 *
 * 지금까지 지도는 라벨 칩 색으로만 혼잡을 표현했는데, 칩은 겹침 검사에서 생략되는 것이 많아
 * (`YeobaekHomeActivity.renderNearbyMarkers`) 전체 분포가 잘 안 보였다. 원은 겹쳐도 정보가
 * 되므로(겹칠수록 진해진다) 밀집 구역이 자연스럽게 드러난다.
 *
 * 레벨을 모르는 곳은 **그리지 않는다** — 예보권 밖이나 API 키 미설정을 '보통'처럼 칠하면
 * 없는 정보를 지어내는 셈이다(라벨 칩이 회색으로 남아 위치는 여전히 보인다).
 */
class HeatOverlay(private val map: GoogleMap) {

    private val circles = ArrayList<Circle>()

    fun render(places: List<PlaceResult>) {
        clear()
        for (p in places) {
            val lat = p.lat ?: continue
            val lng = p.lng ?: continue
            val level = p.level ?: continue
            val fill = Congestion.heatFill(level) ?: continue
            val circle = map.addCircle(
                CircleOptions()
                    .center(LatLng(lat, lng))
                    .radius(Congestion.heatRadiusM(level))
                    .fillColor(fill)
                    .strokeWidth(0f)
                    // 라벨 칩·핀보다 아래에 깔린다(원이 이름을 가리지 않게).
                    .zIndex(0f)
            ) ?: continue
            circles.add(circle)
        }
    }

    fun clear() {
        circles.forEach { it.remove() }
        circles.clear()
    }
}
