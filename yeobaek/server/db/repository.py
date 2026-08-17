"""SQLite 리포지토리 — places / place_area_map 읽기 (절차서 3-4).

읽기 위주이며 커넥션은 요청마다 열고 닫는다(sqlite 는 가볍다).
서버 부팅 시 all_places() 로 C++ SpatialIndex 를 적재한다.
"""
from __future__ import annotations
import math
import sqlite3
from pathlib import Path
from typing import Iterable, Optional

from ..config import settings


def _haversine_km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    r = 6371.0088
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


def _connect() -> sqlite3.Connection:
    conn = sqlite3.connect(settings.DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db(schema_path: Optional[str] = None) -> None:
    """스키마 적용(멱등). schema.sql 을 실행한다."""
    schema_path = schema_path or str(Path(__file__).with_name("schema.sql"))
    with open(schema_path, "r", encoding="utf-8") as f:
        sql = f.read()
    conn = _connect()
    try:
        conn.executescript(sql)
        conn.commit()
    finally:
        conn.close()


def _row_to_place(r: sqlite3.Row) -> dict:
    return {
        "content_id": r["content_id"],
        "title": r["title"],
        "addr": r["addr"],
        "lng": r["mapx"],
        "lat": r["mapy"],
        "area_code": r["area_code"],
        "sigungu_code": r["sigungu_code"],
        "cat": r["cat"],
        "overview": r["overview"],
    }


def get_place(content_id: int) -> Optional[dict]:
    conn = _connect()
    try:
        r = conn.execute("SELECT * FROM places WHERE content_id=?", (content_id,)).fetchone()
        return _row_to_place(r) if r else None
    finally:
        conn.close()


def get_places(ids: Iterable[int]) -> dict[int, dict]:
    ids = list(ids)
    if not ids:
        return {}
    conn = _connect()
    try:
        qs = ",".join("?" * len(ids))
        rows = conn.execute(f"SELECT * FROM places WHERE content_id IN ({qs})", ids).fetchall()
        return {r["content_id"]: _row_to_place(r) for r in rows}
    finally:
        conn.close()


def all_places() -> list[dict]:
    """SpatialIndex 적재용: 좌표가 있는 모든 장소."""
    conn = _connect()
    try:
        rows = conn.execute(
            "SELECT * FROM places WHERE mapx IS NOT NULL AND mapy IS NOT NULL"
        ).fetchall()
        return [_row_to_place(r) for r in rows]
    finally:
        conn.close()


def get_area_map(ids: Iterable[int]) -> dict[int, tuple[str, float]]:
    """content_id → (seoul_area_name, dist_km)"""
    ids = list(ids)
    if not ids:
        return {}
    conn = _connect()
    try:
        qs = ",".join("?" * len(ids))
        rows = conn.execute(
            f"SELECT content_id, seoul_area_name, dist_km FROM place_area_map "
            f"WHERE content_id IN ({qs})",
            ids,
        ).fetchall()
        return {r["content_id"]: (r["seoul_area_name"], r["dist_km"]) for r in rows}
    finally:
        conn.close()


def get_area_name(content_id: int) -> Optional[str]:
    m = get_area_map([content_id])
    return m[content_id][0] if content_id in m else None


def add_report(kind: str, lat: float, lng: float,
               text: Optional[str] = None, content_id: Optional[int] = None) -> int:
    """여행자 실시간 제보 저장(크라우드소싱). 반환: report id."""
    conn = _connect()
    try:
        cur = conn.execute(
            "INSERT INTO user_reports(content_id,lat,lng,kind,text,created_at) "
            "VALUES(?,?,?,?,?,datetime('now'))",
            (content_id, float(lat), float(lng), kind, text),
        )
        conn.commit()
        return int(cur.lastrowid)
    finally:
        conn.close()


def nearby_reports(lat: float, lng: float, radius_km: float = 3.0,
                   limit: int = 50, max_age_hours: int = 12) -> list[dict]:
    """반경 내 최근 제보를 최신순으로."""
    conn = _connect()
    try:
        rows = conn.execute(
            "SELECT * FROM user_reports "
            "WHERE created_at >= datetime('now', ?) "
            "ORDER BY created_at DESC",
            (f"-{int(max_age_hours)} hours",),
        ).fetchall()
    finally:
        conn.close()
    out = []
    for r in rows:
        d = _haversine_km(lat, lng, r["lat"], r["lng"])
        if d <= radius_km:
            out.append({
                "id": r["id"], "content_id": r["content_id"],
                "lat": r["lat"], "lng": r["lng"], "kind": r["kind"],
                "text": r["text"], "created_at": r["created_at"],
                "dist_km": round(d, 2),
            })
        if len(out) >= limit:
            break
    return out


def insert_adhoc_place(title: str, lat: float, lng: float,
                       area_name: Optional[str] = None,
                       dist_km: Optional[float] = None) -> int:
    """DB 에 없는 임의 위치를 즉석 등록(사용자가 지도에서 고른 지점).
    content_id 는 음수 영역을 써서 TourAPI id(양수)와 충돌하지 않게 한다."""
    conn = _connect()
    try:
        row = conn.execute("SELECT MIN(content_id) AS m FROM places").fetchone()
        min_id = row["m"] if row and row["m"] is not None else 0
        new_id = min(int(min_id) - 1, -1)
        conn.execute(
            "INSERT INTO places(content_id,title,addr,mapx,mapy,cat,updated_at) "
            "VALUES(?,?,?,?,?,?,datetime('now'))",
            (new_id, title, None, float(lng), float(lat), None),
        )
        if area_name:
            conn.execute(
                "INSERT OR REPLACE INTO place_area_map(content_id,seoul_area_name,dist_km) "
                "VALUES(?,?,?)", (new_id, area_name, dist_km),
            )
        conn.commit()
        return new_id
    finally:
        conn.close()


def nearby_places(lat: float, lng: float, radius_km: float = 3.0,
                  limit: int = 12) -> list[dict]:
    """좌표 반경 내 장소를 가까운 순으로. 지도 이동 시 지도 라벨/추천에 사용.

    한때는 '수백 건이라 전체 스캔으로 충분'했지만 전국 수집 후 7천 건이 넘어가면서,
    지도를 옮길 때마다 전 행을 읽어 파이썬에서 haversine 을 7천 번 도는 비용이
    그대로 응답 지연이 됐다. 그래서 SQL 에서 위경도 사각형으로 먼저 걸러낸 뒤
    남은 것만 정확한 거리로 계산한다(사각형은 원을 포함하므로 결과는 같다).
    """
    # 위도 1도 ≈ 111km. 경도 1도는 극에 가까울수록 짧아지므로 cos 로 보정한다.
    #
    # 이때 cos 은 **중심 위도가 아니라 사각형에서 극에 가장 가까운 위도**로 잡아야 한다.
    # 중심 위도로 잡으면 원의 위·아래 끝 근처가 사각형 밖으로 밀려 결과가 빠진다
    # (반경 12km 무작위 대조에서 실제로 걸렸다). 사각형은 원보다 커도 무방하다 —
    # 아래에서 정확한 haversine 으로 다시 거르기 때문이다.
    dlat = radius_km / 111.0
    worst_lat = min(abs(lat) + dlat, 89.9)
    cos_lat = math.cos(math.radians(worst_lat))
    # 극지방에서 0으로 나누지 않도록 하한을 둔다(국내에선 걸릴 일 없지만 안전하게).
    dlng = radius_km / max(111.0 * cos_lat, 1e-6)
    conn = _connect()
    try:
        rows = conn.execute(
            "SELECT * FROM places"
            " WHERE mapx IS NOT NULL AND mapy IS NOT NULL"
            "   AND mapy BETWEEN ? AND ? AND mapx BETWEEN ? AND ?",
            (lat - dlat, lat + dlat, lng - dlng, lng + dlng),
        ).fetchall()
    finally:
        conn.close()
    out = []
    for r in rows:
        d = _haversine_km(lat, lng, r["mapy"], r["mapx"])
        if d <= radius_km:
            p = _row_to_place(r)
            p["dist_km"] = round(d, 2)
            out.append((d, p))
    # 정렬은 반올림 전 거리로 한다. 표시용 dist_km(2자리)로 정렬하면 10m 차이가
    # 같은 값이 되어 순서가 행 읽는 순서에 좌우된다(인덱스 유무로도 바뀐다).
    # content_id 를 뒤에 둬 완전한 동점에서도 순서가 항상 같게 한다.
    out.sort(key=lambda t: (t[0], t[1]["content_id"]))
    return [p for _, p in out[:limit]]


def search_places(q: str, limit: int = 20) -> list[dict]:
    """제목 부분일치 검색(이름으로 장소 추가하는 실사용 흐름). 좌표 있는 장소만."""
    conn = _connect()
    try:
        rows = conn.execute(
            "SELECT * FROM places WHERE title LIKE ? "
            "AND mapx IS NOT NULL AND mapy IS NOT NULL "
            "ORDER BY length(title), title LIMIT ?",
            (f"%{q}%", int(limit)),
        ).fetchall()
        return [_row_to_place(r) for r in rows]
    finally:
        conn.close()
