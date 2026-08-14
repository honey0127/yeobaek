"""GET /api/v1/monitor/surge — 코스 실시간 혼잡 급증 SSE 알림 (모듈4).

지금까지 앱 플래너는 코스 stop 마다 `/api/v1/resolve_now` 를 90초 주기로 폴링해
급증 배너를 띄웠다(`PlannerActivity.startSurgeMonitor()`). 폴링은 주기를 줄이면
배터리·외부 API 호출이 같이 늘고, 늘리면 급증을 놓치는 트레이드오프가 있다.

이 엔드포인트는 그 감시 루프를 서버로 옮긴다 — 클라이언트는 SSE 연결 하나만 열어두고,
서버가 주기적으로(기본 10초) `resolve_now` 와 같은 경로로 현재 혼잡을 확인해
**레벨이 임계(기본 3=약간 붐빔) 이상으로 "바뀌는 순간"에만** 이벤트를 흘린다.
변화가 없으면 아무것도 보내지 않으므로(하트비트 제외) 폴링 대비 트래픽이 거의 없다.

이벤트(SSE `event:` 필드)
  open       연결 직후 1회 — 감시 대상·주기·임계값 확인용
  surge      임계 이상으로 진입했을 때(또는 진입 후 레벨이 더 올랐을 때)만
  clear      surge 를 알린 지점이 임계 아래로 회복했을 때(앱 배너 자동 소멸용)
  heartbeat  변화가 없어도 주기적으로 — 프록시 idle 타임아웃 방지 + 감시 생존 확인

혼잡 조회 경로(모듈4 fallback 체인, `/resolve_now` 와 동일한 우선순위)
  1) C++ 엔진 `SeoulCityDataForecastClient.resolve_now(lat,lng)` — 실시간 관측
  2) 엔진 부재 시(배포 초기·아키텍처 불일치) 예보지점 기반 `py_forecast` 폴백
  3) 둘 다 실패하면 그 지점은 이번 주기에 "판정 불가" — 상태를 유지하고 알리지 않는다
     (중립값 2 로 떨어뜨려 오탐 clear 를 내보내지 않기 위함)

사용 예:
    curl -N "http://127.0.0.1:8000/api/v1/monitor/surge?stops=126508,126521&interval=10"
"""
from __future__ import annotations
import asyncio
import json
import logging
import time
from dataclasses import dataclass
from typing import AsyncIterator, Optional

from fastapi import APIRouter, HTTPException, Query, Request
from fastapi.responses import StreamingResponse
from starlette.concurrency import run_in_threadpool
from starlette.responses import Response

from ..config import settings
from ..db import repository
from ..engine import engine_state
from ..services import py_forecast
from ..services.rag import LEVEL_LABELS
from .places import quiet_score

# sse-starlette 가 있으면 그걸 쓰고(자동 keepalive·disconnect 처리), 없으면
# StreamingResponse 로 같은 SSE 프레임을 직접 만든다 — 엔진(.so) 폴백과 같은 원칙으로
# 의존성 하나 때문에 서버 전체가 안 뜨는 상황을 만들지 않는다.
try:
    from sse_starlette import EventSourceResponse
except Exception:  # pragma: no cover - 패키지 미설치 환경
    EventSourceResponse = None  # type: ignore[assignment]

log = logging.getLogger("yeobaek")

router = APIRouter(prefix="/api/v1/monitor", tags=["monitor"])

_SSE_HEADERS = {
    "Cache-Control": "no-cache, no-transform",
    "Connection": "keep-alive",
    "X-Accel-Buffering": "no",   # nginx 등 리버스 프록시의 응답 버퍼링 방지
}
_RECONNECT_MS = 5000             # 끊기면 클라이언트가 5초 뒤 재연결(SSE retry 필드)


@dataclass
class _Watch:
    """감시 대상 1곳 + 마지막으로 판정한 상태(이벤트를 '변화'에만 보내기 위한 기억)."""
    content_id: int
    title: str
    lat: Optional[float]
    lng: Optional[float]
    area_name: Optional[str]
    level: Optional[int] = None    # 직전 주기의 혼잡 레벨(1~4), 판정 불가면 None
    alerted: bool = False          # surge 를 이미 보냈는지(중복 알림 방지)


def _parse_stops(raw: str) -> list[int]:
    ids: list[int] = []
    for tok in raw.split(","):
        tok = tok.strip()
        if not tok:
            continue
        try:
            cid = int(tok)
        except ValueError:
            raise HTTPException(
                status_code=400,
                detail=f"stops must be comma-separated content_id(s), got: {tok!r}")
        if cid not in ids:      # 같은 장소를 두 번 감시하지 않는다
            ids.append(cid)
    if not ids:
        raise HTTPException(status_code=400, detail="stops must contain at least one content_id")
    if len(ids) > settings.SURGE_MAX_STOPS:
        raise HTTPException(
            status_code=400,
            detail=f"too many stops: {len(ids)} (max {settings.SURGE_MAX_STOPS})")
    return ids


def _build_watches(ids: list[int]) -> list[_Watch]:
    places = repository.get_places(ids)
    missing = [i for i in ids if i not in places]
    if missing:
        raise HTTPException(status_code=404, detail=f"unknown content_id(s): {missing}")
    area_map = repository.get_area_map(ids)
    return [
        _Watch(
            content_id=cid,
            title=places[cid]["title"],
            lat=places[cid].get("lat"),
            lng=places[cid].get("lng"),
            area_name=area_map.get(cid, (None, None))[0],
        )
        for cid in ids
    ]


async def _current_level(w: _Watch) -> Optional[tuple[int, Optional[float], str]]:
    """(level, ratio, source) 또는 판정 불가 시 None.

    외부 API 호출(libcurl/requests)은 동기 blocking 이라 그대로 await 하면 이벤트 루프가
    멈춘다 — 다른 SSE 연결까지 같이 멎으므로 반드시 스레드풀로 내보낸다.
    """
    try:
        if w.lat is not None and w.lng is not None:
            r = await run_in_threadpool(engine_state.resolve_now, w.lat, w.lng)
            if r:
                level, ratio, _valid = r
                return int(level), float(ratio), "cpp"
        if w.area_name:
            # 엔진이 없을 때의 폴백. py_forecast 는 (지점, 시간버킷) 단위로 캐시하므로
            # interval 을 짧게 잡아도 외부 API 호출이 그만큼 늘지는 않는다.
            fc = await run_in_threadpool(py_forecast.forecast, w.area_name, int(time.time()))
            # 과거 평균(is_historical)은 "지금 급증"의 근거가 못 되므로 알림에 쓰지 않는다.
            if fc.valid and not fc.is_historical:
                return int(fc.level), None, "python-fallback"
    except Exception as e:  # pragma: no cover - 외부 API/DB 일시 오류
        log.warning("surge check failed for content_id=%s: %s", w.content_id, e)
    return None


def _ro_particle(word: str) -> str:
    """‘여유로 / 보통으로’ — 받침 유무로 조사를 고른다(ㄹ 받침은 '로'). 알림 문구용."""
    ch = word.strip()[-1] if word.strip() else ""
    if not ("가" <= ch <= "힣"):
        return "로"
    jongseong = (ord(ch) - 0xAC00) % 28
    return "로" if jongseong in (0, 8) else "으로"   # 0=받침 없음, 8=ㄹ


def _sse(event: str, data: dict, retry: Optional[int] = None) -> dict:
    """sse-starlette 가 이해하는 dict 프레임(폴백 포맷터도 같은 키를 읽는다)."""
    frame = {"event": event, "data": json.dumps(data, ensure_ascii=False)}
    if retry:
        frame["retry"] = retry
    return frame


def _payload(w: _Watch, level: int, ratio: Optional[float], source: str,
             prev: Optional[int], message: str) -> dict:
    return {
        "content_id": w.content_id,
        "title": w.title,
        "level": level,
        "level_label": LEVEL_LABELS.get(level),
        "quiet_score": quiet_score(level),
        "prev_level": prev,
        "ratio": round(ratio, 4) if ratio is not None else None,
        "area_name": w.area_name,
        "lat": w.lat,
        "lng": w.lng,
        "source": source,
        "at": int(time.time()),
        "message": message,
    }


def _transitions(w: _Watch, cur: Optional[tuple[int, Optional[float], str]],
                 threshold: int) -> list[dict]:
    """이번 주기의 판정 결과를 직전 상태와 비교해 보낼 이벤트만 만든다(없으면 빈 리스트)."""
    if cur is None:
        return []                       # 판정 불가 — 직전 상태를 그대로 유지
    level, ratio, source = cur
    prev = w.level
    events: list[dict] = []

    if level >= threshold and (not w.alerted or (prev is not None and level > prev)):
        # 임계 진입(또는 이미 알린 뒤 레벨이 더 올라간 경우)에만 알린다.
        label = LEVEL_LABELS.get(level, "붐빔")
        events.append(_sse("surge", _payload(
            w, level, ratio, source, prev,
            f"‘{w.title}’ 지금 {label} — 도착 시점을 늦추거나 대안을 확인하세요")))
        w.alerted = True
    elif w.alerted and level < threshold:
        # 회복 — 앱이 배너를 자동으로 내릴 수 있게 알린다.
        label = LEVEL_LABELS.get(level, "보통")
        events.append(_sse("clear", _payload(
            w, level, ratio, source, prev,
            f"‘{w.title}’ 혼잡이 {label}{_ro_particle(label)} 내려갔어요")))
        w.alerted = False

    w.level = level
    return events


async def _surge_stream(request: Request, watches: list[_Watch],
                        interval: float, threshold: int) -> AsyncIterator[dict]:
    """감시 루프 — 주기마다 전 지점을 동시에 확인하고 '변화'만 yield 하는 비동기 제너레이터."""
    yield _sse("open", {
        "interval_sec": interval,
        "surge_level": threshold,
        "surge_level_label": LEVEL_LABELS.get(threshold),
        "engine_available": engine_state.available,
        "watching": [{"content_id": w.content_id, "title": w.title,
                      "area_name": w.area_name} for w in watches],
        "at": int(time.time()),
    }, retry=_RECONNECT_MS)

    last_sent = time.monotonic()
    try:
        # 클라이언트가 끊으면 Starlette 이 이 태스크를 취소하지만, 프록시 뒤에서 취소가
        # 늦게 오는 경우를 대비해 매 주기 연결 상태도 직접 확인한다.
        while not await request.is_disconnected():
            started = time.monotonic()
            # 지점별 확인은 서로 독립 — 순차로 돌면 지점 수만큼 주기가 밀리므로 동시에.
            results = await asyncio.gather(*(_current_level(w) for w in watches))

            sent = 0
            for w, cur in zip(watches, results):
                for frame in _transitions(w, cur, threshold):
                    sent += 1
                    yield frame

            now = time.monotonic()
            if sent:
                last_sent = now
            elif now - last_sent >= settings.SURGE_PING_SEC:
                yield _sse("heartbeat", {
                    "checked": len(watches),
                    "unknown": sum(1 for r in results if r is None),
                    "levels": {str(w.content_id): w.level for w in watches},
                    "at": int(time.time()),
                })
                last_sent = now

            # 확인에 쓴 시간을 빼서 주기가 밀리지 않게 한다.
            await asyncio.sleep(max(0.0, interval - (time.monotonic() - started)))
    except asyncio.CancelledError:      # 정상 종료 경로(클라이언트 연결 종료·서버 셧다운)
        raise
    finally:
        log.info("surge monitor closed (stops=%s)", [w.content_id for w in watches])


async def _raw_sse(frames: AsyncIterator[dict]) -> AsyncIterator[str]:
    """sse-starlette 가 없을 때 dict 프레임을 SSE 와이어 포맷으로 직렬화."""
    async for f in frames:
        chunk = ""
        if f.get("retry"):
            chunk += f"retry: {f['retry']}\n"
        chunk += f"event: {f['event']}\ndata: {f['data']}\n\n"
        yield chunk


@router.get("/surge")
async def surge(
    request: Request,
    stops: str = Query(..., description="감시할 content_id 목록(콤마 구분)",
                       examples=["126508,126521"]),
    interval: float = Query(settings.SURGE_INTERVAL_SEC, ge=5.0, le=300.0,
                            description="혼잡 확인 주기(초)"),
    level: int = Query(settings.HIGH_CONGESTION_LEVEL, ge=2, le=4,
                       description="알림 임계 혼잡 레벨(3=약간 붐빔, 4=붐빔)"),
) -> Response:
    """코스 진행 중 실시간 혼잡 급증 알림 스트림(Server-Sent Events).

    폴링 없이 연결 하나로 감시한다. 응답은 `text/event-stream` 이며
    레벨이 임계 이상으로 바뀔 때만 `surge`, 회복 시 `clear` 이벤트가 온다.
    """
    watches = _build_watches(_parse_stops(stops))
    stream = _surge_stream(request, watches, interval, level)

    if EventSourceResponse is not None:
        return EventSourceResponse(stream, ping=int(settings.SURGE_PING_SEC),
                                   headers=_SSE_HEADERS)
    return StreamingResponse(_raw_sse(stream), media_type="text/event-stream",
                             headers=_SSE_HEADERS)
