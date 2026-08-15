package com.example.crowdmap.yeobaek.ui

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 화면 공통 UI 도우미.
 *
 * 이 앱의 테마는 상태바/내비바를 투명으로 두고 콘텐츠를 화면 끝까지 흘린다(edge-to-edge).
 * 대신 **콘텐츠가 시스템 바 밑에 깔리지 않도록** 각 화면이 직접 여백을 넣어야 한다.
 * 예전처럼 `layout_marginTop="86dp"` 같은 상수를 쓰면 노치·제스처바 높이가 기기마다
 * 달라서 어긋난다 — 아래 [applyInsets] 로 실제 시스템 바 높이를 받아 패딩에 더한다.
 */
object YeUi {

    /** 상태바·내비바 뒤로 콘텐츠를 그린다. onCreate 의 setContentView 전후 아무데서나 호출. */
    fun Activity.edgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    /**
     * 시스템 바(+노치) 높이만큼 이 뷰의 패딩을 늘린다.
     *
     * XML 에 적어둔 패딩은 그대로 두고 거기에 **더한다**. 인셋은 회전·멀티윈도우에서
     * 여러 번 다시 오므로, 원래 패딩을 기억해 두고 매번 그 위에 얹어야 값이 누적되지 않는다.
     */
    fun View.applyInsets(
        top: Boolean = false,
        bottom: Boolean = false,
        sides: Boolean = true,
    ) {
        val base = Rect(paddingLeft, paddingTop, paddingRight, paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                base.left + if (sides) bars.left else 0,
                base.top + if (top) bars.top else 0,
                base.right + if (sides) bars.right else 0,
                base.bottom + if (bottom) bars.bottom else 0,
            )
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    /**
     * 담기/삭제처럼 "무언가 일어났다"는 걸 손끝으로도 알리는 짧은 진동.
     * 소리·진동을 끈 사용자를 존중해야 하므로 시스템 햅틱 설정을 그대로 따르는
     * performHapticFeedback 만 쓴다(Vibrator 직접 제어 X — 권한도 필요 없다).
     */
    fun View.tick() {
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun Context.color(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

    /** dp → px. 코드에서 마커·비트맵 크기를 만들 때. */
    fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    /** 여러 뷰의 보임/숨김을 한 줄로. */
    fun View.showIf(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }
}
