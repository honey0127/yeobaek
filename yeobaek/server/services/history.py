"""D+1 역사 평균 폴백 (부록 A `forecast_cache` 활용).

서울 실시간 도시데이터의 FCST_PPLTN 은 약 12시간 앞까지만 예보한다. 그보다 먼
시각(예: "내일 오전 방문")을 물으면 엔진은 예보도 실시간도 못 찾아 중립값(레벨2)
으로 떨어지는데, 이건 "내일 여행" 시나리오에서 가장 자주 발생하는 경로라 매번
중립만 보여주면 신뢰를 잃는다.

대신 지금까지 실제로 관측된(라이브) 예보값을 `forecast_cache` 에 누적해두고,
같은 지점·같은 시간대(hour-of-day)의 과거 평균으로 대체한다. 표본이 쌓일수록
정확해지는 구조이며, 표본이 아직 없으면(신규 배포 초기) None 을 반환해
호출측이 기존 중립 폴백을 쓰게 한다 — 즉 이 모듈은 있으면 쓰고 없으면 조용히
비켜주는 순수 개선(strict improvement)이다.
"""
from __future__ import annotations
import sqlite3
import time

from ..config import settings
from ..util import KST_OFFSET

MIN_SAMPLES = 3  # 이보다 표본이 적으면 평균을 신뢰하지 않고 None(=기존 중립 폴백)


def _hour_of_day_kst(unix_ts: int) -> int:
    return time.gmtime(int(unix_ts) + KST_OFFSET).tm_hour


def _connect() -> sqlite3.Connection:
    return sqlite3.connect(settings.DB_PATH)


def record(area_name: str, level: int, ppltn_min: int | None, ppltn_max: int | None,
           fcst_unix: int) -> None:
    """실측(라이브) 예보 1건을 forecast_cache 에 적재 — 미래 D+1 폴백의 표본이 된다."""
    if not area_name or not level:
        return
    con = _connect()
    try:
        con.execute(
            "INSERT OR REPLACE INTO forecast_cache"
            "(seoul_area_name, fcst_time, congest_lvl, ppltn_min, ppltn_max, fetched_at)"
            " VALUES (?, ?, ?, ?, ?, ?)",
            (area_name, str(int(fcst_unix)), int(level),
             int(ppltn_min) if ppltn_min else None, int(ppltn_max) if ppltn_max else None,
             str(int(time.time()))),
        )
        con.commit()
    finally:
        con.close()


def historical_level(area_name: str, arrival_unix: int) -> int | None:
    """area_name 의 같은 시간대(hour-of-day, KST) 과거 관측 평균 레벨(반올림).
    표본이 MIN_SAMPLES 미만이면 None(호출측이 중립 폴백을 쓰게 둔다)."""
    target_hour = _hour_of_day_kst(arrival_unix)
    con = _connect()
    try:
        rows = con.execute(
            "SELECT fcst_time, congest_lvl FROM forecast_cache WHERE seoul_area_name = ?",
            (area_name,),
        ).fetchall()
    finally:
        con.close()

    levels = [lvl for t, lvl in rows if _hour_of_day_kst(int(t)) == target_hour]
    if len(levels) < MIN_SAMPLES:
        return None
    return round(sum(levels) / len(levels))


# 예보 슬롯은 1시간 단위(C++ ForecastProvider 의 bucket_seconds=3600 과 같다).
# 도착 시각에서 이 절반 이내의 슬롯을 "그 도착을 덮는 예보"로 본다.
_BUCKET_SEC = 3600


def cached_forecast(area_name: str, arrival_unix: int, max_age_sec: int):
    """최근에 받아 둔 **실측** 예보가 있으면 돌려준다(외부 API 호출을 건너뛰기 위해).

    forecast_cache 는 원래 D+1 역사 평균의 표본 창고였는데, 같은 테이블이 사실
    "언제 받아서(fetched_at) 어느 슬롯(fcst_time)의 값이 무엇이었는지"를 다 갖고
    있으므로 짧은 TTL 의 **영속 캐시**로도 그대로 쓸 수 있다.

    엔진의 인메모리 LRU 캐시는 프로세스가 죽으면 사라진다. Fly 는
    min_machines_running=0 이라 트래픽이 없으면 머신이 통째로 꺼지고, 그때마다
    121개 예보지점을 처음부터 다시 받아야 했다. 이 캐시는 볼륨에 남으므로
    콜드스타트를 넘어 살아남는다 — 공공 API 일일 한도를 지키는 핵심 장치다.

    반환: (level, ppltn_min, ppltn_max, fcst_unix) 또는 None(캐시 미스).
    """
    if not area_name or max_age_sec <= 0:
        return None
    arrival = int(arrival_unix)
    half = _BUCKET_SEC // 2
    fresh_since = int(time.time()) - int(max_age_sec)
    con = _connect()
    try:
        row = con.execute(
            "SELECT fcst_time, congest_lvl, ppltn_min, ppltn_max FROM forecast_cache"
            " WHERE seoul_area_name = ?"
            "   AND CAST(fetched_at AS INTEGER) >= ?"
            "   AND CAST(fcst_time AS INTEGER) BETWEEN ? AND ?"
            " ORDER BY ABS(CAST(fcst_time AS INTEGER) - ?) LIMIT 1",
            (area_name, fresh_since, arrival - half, arrival + half, arrival),
        ).fetchone()
    except sqlite3.Error:
        return None
    finally:
        con.close()
    if not row or not row[1]:
        return None
    return int(row[1]), row[2], row[3], int(row[0])
