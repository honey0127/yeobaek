"""전국 관광지 집중률(TatsCnctrRateService) 조회 + 좌표→시군구 해석.

서울 실시간 도시데이터는 서울 예보지점(반경 4km) 안에서만 혼잡을 알려준다.
그 밖의 장소는 지금까지 혼잡도가 아예 비어 있었는데, 전국 집중률 API 는 날짜
단위로 전국을 덮으므로 **서울 예보가 없을 때의 2차 트랙**으로 쓸 수 있다.

원래 이 로직은 api/districts.py 안에만 있었다(지역 탐색 화면 전용). 지도·코스
경로에서도 같이 쓰려고 여기로 옮기고, districts.py 는 이 모듈을 재사용한다.

한계는 분명히 해 둔다:
  - 집중률은 **날짜 단위**라 시간대별 예보가 아니다. 서울 예보가 있으면 그쪽이
    항상 우선이고, 이 값은 서울 예보가 없을 때만 쓴다.
  - 매칭이 **관광지 이름 기준**이라 API 목록에 없는 장소는 여전히 None 이다.
    없는 것을 채우지 않는다 — 커버리지가 늘어날 뿐 추정하지는 않는다.
"""
from __future__ import annotations

import datetime
import math
from typing import Optional

from .tats import TatsClient, TatsError

# 집중률(시군구, 오늘) 결과 캐시 — 하루 1회 호출로 트래픽 절약(일일 1000 제한).
_tats_cache: dict[str, dict[str, int]] = {}

# 좌표 → 시군구 코드 해석에 쓰는 기준점. api/districts.py 의 DISTRICTS 를 그대로
# 쓴다(순환 import 를 피하려고 최초 호출 때 늦게 불러온다).
_ANCHORS: Optional[list[dict]] = None

# 기준점에서 이보다 멀면 그 시군구로 보지 않는다. 집중률은 시군구 단위라
# 어설프게 먼 곳까지 끌어다 쓰면 다른 지자체 수치를 보여주게 된다.
MAX_ANCHOR_DIST_KM = 25.0


def _norm(s: str) -> str:
    return "".join((s or "").split()).replace("(", "").replace(")", "")


def _haversine_km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    r = 6371.0088
    dlat = math.radians(lat2 - lat1)
    dlng = math.radians(lng2 - lng1)
    a = (math.sin(dlat / 2) ** 2
         + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlng / 2) ** 2)
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


def _anchors() -> list[dict]:
    global _ANCHORS
    if _ANCHORS is None:
        from ..api.districts import DISTRICTS
        _ANCHORS = [d for d in DISTRICTS if d.get("signgu_cd")]
    return _ANCHORS


def resolve_sigungu(lat: float, lng: float) -> Optional[tuple[str, str]]:
    """좌표 → (area_cd, signgu_cd). 기준점이 너무 멀면 None.

    법정동 코드 전체 테이블을 새로 들이는 대신, 이미 검증해 둔 대표 지역 좌표를
    최근접으로 찾아 그 시군구 코드를 쓴다. 대표 지역 근처가 아니면 조용히 포기한다
    (틀린 지자체의 수치를 보여주느니 '정보 없음'이 낫다).
    """
    if lat is None or lng is None:
        return None
    best = None
    best_d = MAX_ANCHOR_DIST_KM
    for a in _anchors():
        d = _haversine_km(lat, lng, a["lat"], a["lng"])
        if d < best_d:
            best, best_d = a, d
    if best is None:
        return None
    return best["area_cd"], best["signgu_cd"]


def levels_for_sigungu(area_cd: str, signgu_cd: str) -> dict[str, int]:
    """(오늘 기준) 시군구 관광지명 → 혼잡 레벨. 실패/키없음 시 빈 dict."""
    today = datetime.date.today().strftime("%Y%m%d")
    ck = f"{signgu_cd}:{today}"
    if ck in _tats_cache:
        return _tats_cache[ck]
    levels: dict[str, int] = {}
    try:
        rows = TatsClient().list_by_sigungu(area_cd, signgu_cd)
        for r in rows:
            if r["ymd"] != today:
                continue
            levels[_norm(r["name"])] = r["level"]
        if not levels:   # 오늘자가 없으면 가장 이른 날짜로
            for r in rows:
                levels.setdefault(_norm(r["name"]), r["level"])
    except (TatsError, Exception):
        levels = {}
    _tats_cache[ck] = levels
    return levels


def match_level(levels: dict[str, int], title: str) -> Optional[int]:
    """관광지명 정규화 매칭(정확 → 부분포함)."""
    if not levels or not title:
        return None
    key = _norm(title)
    if key in levels:
        return levels[key]
    for name, lvl in levels.items():
        if name and (name in key or key in name):
            return lvl
    return None


def level_for_place(title: str, lat: float, lng: float) -> Optional[int]:
    """좌표·이름으로 전국 집중률 레벨을 찾는다. 못 찾으면 None.

    서울 예보가 없을 때만 호출할 것 — 시간대별 예보가 항상 우선이다.
    """
    sg = resolve_sigungu(lat, lng)
    if sg is None:
        return None
    return match_level(levels_for_sigungu(sg[0], sg[1]), title)
