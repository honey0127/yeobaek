package com.example.crowdmap.yeobaek.data

import android.util.Log
import com.example.crowdmap.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 서버가 보내는 급증 감시 이벤트 (`GET /api/v1/monitor/surge`).
 * 서버는 혼잡 레벨이 임계 이상으로 **바뀌는 순간에만** surge 를, 회복하면 clear 를 보낸다.
 */
sealed class SurgeEvent {
    /** 연결 직후 1회 — 서버가 실제로 감시 중인 지점·주기·임계값 */
    data class Open(val watching: Int, val intervalSec: Double, val surgeLevel: Int) : SurgeEvent()
    data class Surge(val alert: SurgeAlert) : SurgeEvent()
    data class Clear(val alert: SurgeAlert) : SurgeEvent()
    /** 변화가 없을 때 오는 생존 신호 — UI 는 무시해도 된다 */
    object Heartbeat : SurgeEvent()
    /** 서버가 이 엔드포인트를 모른다(구버전 배포)·요청이 거부됐다 → 재시도 대신 폴링으로 폴백 */
    object Unsupported : SurgeEvent()
}

/**
 * 실시간 급증 알림 SSE 구독 (모듈4).
 *
 * 기존에는 플래너가 stop 마다 `/resolve_now` 를 90초 주기로 폴링했다. 주기를 줄이면
 * 배터리·API 호출이 같이 늘고 늘리면 급증을 놓치는 트레이드오프가 있어, 감시 루프를
 * 서버로 옮기고(`server/api/monitor.py`) 앱은 연결 하나만 유지한다.
 *
 * 연결 수명은 호출측(코루틴 스코프)이 정한다 — 스코프가 취소되면 `awaitClose` 가
 * EventSource 를 끊는다. 플래너는 `repeatOnLifecycle(STARTED)` 안에서 구독하므로
 * 화면이 백그라운드로 가면 소켓도 함께 정리된다.
 */
object SurgeStream {

    private const val TAG = "SurgeStream"
    private const val PATH = "api/v1/monitor/surge"

    private const val BASE_BACKOFF_MS = 5_000L    // 서버가 내려주는 retry 값과 동일
    private const val MAX_BACKOFF_MS = 60_000L

    // SSE 는 끝나지 않는 응답이라 읽기 타임아웃이 있으면 안 된다
    // (공용 YeobaekClient 는 readTimeout=30s 라 그대로 쓰면 주기적으로 끊긴다).
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val factory by lazy { EventSources.createFactory(client) }

    /**
     * 급증 이벤트 구독. 네트워크가 끊기면 지수 백오프로 조용히 재연결한다
     * (이동 중 셀룰러↔와이파이 전환은 흔한 일이라 UI 로 올리지 않는다).
     */
    fun subscribe(stopIds: List<Long>, intervalSec: Int = 10, level: Int = 3): Flow<SurgeEvent> =
        events(stopIds, intervalSec, level).retryWhen { cause, attempt ->
            val backoff = (BASE_BACKOFF_MS shl attempt.coerceAtMost(4).toInt())
                .coerceAtMost(MAX_BACKOFF_MS)
            Log.w(TAG, "SSE 재연결 (${attempt + 1}번째, ${backoff}ms 후): ${cause.message}")
            delay(backoff)
            true
        }

    private fun events(stopIds: List<Long>, intervalSec: Int, level: Int): Flow<SurgeEvent> =
        callbackFlow {
            val url = buildString {
                append(BuildConfig.BASE_URL.trimEnd('/')).append('/').append(PATH)
                append("?stops=").append(stopIds.joinToString(","))
                append("&interval=").append(intervalSec)
                append("&level=").append(level)
            }
            val request = Request.Builder()
                .url(url)
                .header("Accept", "text/event-stream")
                .build()

            val listener = object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    Log.i(TAG, "급증 감시 연결됨 (${stopIds.size}곳)")
                }

                override fun onEvent(
                    eventSource: EventSource, id: String?, type: String?, data: String,
                ) {
                    parse(type, data)?.let { trySend(it) }
                }

                override fun onFailure(
                    eventSource: EventSource, t: Throwable?, response: Response?,
                ) {
                    val code = response?.code
                    if (code != null && code in 400..499) {
                        // 엔드포인트 없음(404)·요청 거부(400) — 재시도해도 결과가 같다.
                        Log.w(TAG, "급증 감시 미지원 (HTTP $code) → 폴링 폴백")
                        trySend(SurgeEvent.Unsupported)
                        close()
                    } else {
                        // 그 외(연결 끊김·5xx)는 재연결 대상 → 예외로 닫아 retryWhen 이 받게 한다.
                        close(t ?: IOException("SSE 연결 실패 (HTTP $code)"))
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    close(IOException("SSE 스트림이 서버에서 종료됨"))
                }
            }

            val source = factory.newEventSource(request, listener)
            awaitClose { source.cancel() }
        }

    private fun parse(type: String?, data: String): SurgeEvent? = try {
        when (type) {
            "surge" -> SurgeEvent.Surge(YeobaekClient.gson.fromJson(data, SurgeAlert::class.java))
            "clear" -> SurgeEvent.Clear(YeobaekClient.gson.fromJson(data, SurgeAlert::class.java))
            "heartbeat" -> SurgeEvent.Heartbeat
            "open" -> YeobaekClient.gson.fromJson(data, SurgeOpen::class.java).let {
                SurgeEvent.Open(it.watching.size, it.intervalSec, it.surgeLevel)
            }
            else -> null      // 서버가 나중에 이벤트를 추가해도 앱이 죽지 않게 무시
        }
    } catch (e: Exception) {
        Log.w(TAG, "이벤트 파싱 실패 (type=$type): ${e.message}")
        null
    }
}
