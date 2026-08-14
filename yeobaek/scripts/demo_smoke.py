"""시연 시나리오 고정 — API 키 없이 seed DB 로 전체 흐름 검증 (Phase 5).

사용:
    cd yeobaek
    uvicorn server.main:app --port 8000 &
    python scripts/demo_smoke.py

출력:
    /health    → ok
    /schedule  → 혼잡 회피 재정렬(경복궁 → 덕수궁 순서 변경) + savedCongestionPct
    /match     → 경복궁 대안 감성 쌍둥이 top-K
    /card      → 설득 카드(template 기반, LLM 없이)
    /monitor/surge → 실시간 급증 SSE 스트림 개시(open 이벤트)

seed DB 는 스크립트 실행 시 자동 주입된다(기존 동명 row 는 덮어씀).
"""
from __future__ import annotations
import json
import sqlite3
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from server.config import settings  # noqa: E402

BASE = "http://127.0.0.1:8000"

# ── seed 명소 (API 키 없이 쓸 수 있는 최소 fixture) ──
SEED_PLACES = [
    # content_id, title, addr, lng, lat, area_code, cat, overview
    (126508, "경복궁",   "서울 종로구 사직로 161",   126.9770, 37.5796, 1, "A02>A0201>A02010100",
     "조선왕조의 법궁으로 정문인 광화문을 통해 들어가면 웅장한 근정전이 맞이한다."),
    (126521, "덕수궁",   "서울 중구 세종대로 99",    126.9751, 37.5659, 1, "A02>A0201>A02010100",
     "대한제국의 황궁으로 근대와 전통이 공존하는 고궁이다. 돌담길이 유명하다."),
    (126540, "창덕궁",   "서울 종로구 율곡로 99",    126.9910, 37.5794, 1, "A02>A0201>A02010100",
     "유네스코 세계문화유산으로 자연과 조화를 이루는 비원(후원)이 백미이다."),
    (126555, "운현궁",   "서울 종로구 삼일대로 464", 126.9876, 37.5748, 1, "A02>A0201>A02010400",
     "흥선대원군의 사저로 조선 말기 정치의 중심지였다."),
]

# 서울 예보지점 매핑(경복궁·덕수궁·창덕궁·운현궁 → 경복궁 예보지점으로 단순 매핑)
SEED_AREA_MAP = [
    (126508, "경복궁", 0.1),
    (126521, "덕수궁", 1.2),
    (126540, "창덕궁", 0.8),
    (126555, "운현궁", 0.6),
]


def seed_db() -> None:
    con = sqlite3.connect(settings.DB_PATH)
    con.execute("""
        CREATE TABLE IF NOT EXISTS places(
            content_id INTEGER PRIMARY KEY, title TEXT NOT NULL,
            addr TEXT, mapx REAL, mapy REAL, area_code INTEGER,
            sigungu_code INTEGER, cat TEXT, overview TEXT, updated_at TEXT
        )""")
    con.execute("""
        CREATE TABLE IF NOT EXISTS place_area_map(
            content_id INTEGER PRIMARY KEY, seoul_area_name TEXT NOT NULL, dist_km REAL
        )""")
    for row in SEED_PLACES:
        con.execute(
            "INSERT INTO places(content_id,title,addr,mapx,mapy,area_code,cat,overview,updated_at)"
            " VALUES (?,?,?,?,?,?,?,?,datetime('now'))"
            " ON CONFLICT(content_id) DO UPDATE SET"
            " title=excluded.title, addr=excluded.addr, mapx=excluded.mapx,"
            " mapy=excluded.mapy, cat=excluded.cat, overview=excluded.overview,"
            " updated_at=excluded.updated_at",
            row,
        )
    for cid, area, dist in SEED_AREA_MAP:
        con.execute(
            "INSERT INTO place_area_map(content_id,seoul_area_name,dist_km) VALUES(?,?,?)"
            " ON CONFLICT(content_id) DO UPDATE SET"
            " seoul_area_name=excluded.seoul_area_name, dist_km=excluded.dist_km",
            (cid, area, dist),
        )
    con.commit()
    con.close()
    print(f"[seed] {len(SEED_PLACES)}개 명소 주입 완료 → {settings.DB_PATH}")


def _url(path: str) -> str:
    """URL 에 한글이 그대로 들어가면 urllib 이 ascii 인코딩에서 터진다(예: ?q=경복궁).
    경로·쿼리 구분자는 남기고 비ASCII 만 퍼센트 인코딩한다."""
    return BASE + urllib.parse.quote(path, safe="/?=&")


def _req(method: str, path: str, body: dict | None = None):
    url = _url(path)
    data = json.dumps(body).encode() if body else None
    headers = {"Content-Type": "application/json"}
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return {"__http_error__": e.code, "detail": e.read().decode()[:300]}
    except Exception as e:
        return {"__error__": str(e)}


def _sse_first_event(path: str, timeout: float = 8.0) -> dict:
    """SSE 스트림에 붙어 **첫 이벤트 한 건만** 읽고 끊는다(무한 스트림이라 블로킹 방지).
    /monitor/surge 는 연결 직후 open 이벤트를 보내므로 이걸로 개시 여부를 확인한다."""
    req = urllib.request.Request(_url(path), headers={"Accept": "text/event-stream"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            ctype = (r.headers.get("Content-Type") or "").split(";")[0]
            if ctype != "text/event-stream":
                return {"__error__": f"content-type={ctype!r} (expected text/event-stream)"}
            event, data = None, None
            for _ in range(20):                     # 첫 이벤트 블록(빈 줄)까지만
                raw = r.readline()
                if not raw:
                    break
                line = raw.decode().strip()
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data = line[5:].strip()
                elif not line and event:
                    return {"event": event, "data": json.loads(data or "{}")}
            return {"__error__": "SSE 이벤트를 받지 못했다"}
    except urllib.error.HTTPError as e:
        return {"__http_error__": e.code, "detail": e.read().decode()[:300]}
    except Exception as e:
        return {"__error__": str(e)}


def _ok(label: str, got: dict) -> bool:
    if "__error__" in got or "__http_error__" in got:
        print(f"  ✗ {label}: {got}")
        return False
    print(f"  ✓ {label}")
    return True


def run_demo() -> bool:
    passed = 0
    total = 0

    # 1. /health
    total += 1
    r = _req("GET", "/health")
    if _ok("/health", r) and r.get("status") == "ok":
        print(f"     engine_available={r.get('engine_available')} "
              f"places_loaded={r.get('places_loaded')}")
        passed += 1

    # 2. /places/search
    total += 1
    r = _req("GET", "/api/v1/places/search?q=경복궁")
    if _ok("/places/search?q=경복궁", r) and r.get("results"):
        print(f"     hit: {r['results'][0]['title']}  lat={r['results'][0].get('lat')}")
        passed += 1

    # 3. /schedule — 혼잡도 없는(fallback) 상태에서도 응답 확인
    total += 1
    r = _req("POST", "/api/v1/schedule", {
        "start_time": "2026-10-11T10:00:00",
        "stops": [126508, 126521, 126540],
        "allow_substitution": False,
        "keep_order": False,
    })
    if _ok("/schedule(3-stop)", r):
        ordered = r.get("ordered", [])
        titles = [s["title"] for s in ordered]
        print(f"     순서: {titles}  saved={r.get('saved_congestion_pct')}%")
        passed += 1

    # 4. /match — 경복궁 대안
    total += 1
    r = _req("POST", "/api/v1/match", {"content_id": 126508, "radius_km": 5.0, "top_k": 3})
    if _ok("/match(경복궁)", r):
        twins = r.get("twins", [])
        print(f"     쌍둥이 {len(twins)}개: {[t['title'] for t in twins]}")
        passed += 1

    # 5. /card — 경복궁 → 창덕궁 설득 카드
    total += 1
    r = _req("POST", "/api/v1/card", {"source_id": 126508, "alt_id": 126540})
    if _ok("/card(경복궁→창덕궁)", r):
        print(f"     headline: {r.get('headline')}")
        print(f"     generated_by: {r.get('generated_by')}")
        print(f"     diff_pct: {r.get('grounded_facts', {}).get('congestion_diff_pct')}%")
        passed += 1

    # 6. /resolve_now — 서울 경복궁 좌표 실시간 (엔진 없으면 valid=False 정상)
    total += 1
    r = _req("GET", "/api/v1/resolve_now?lat=37.5796&lng=126.9770")
    if _ok("/resolve_now(경복궁)", r):
        print(f"     level={r.get('level')} valid={r.get('valid')}")
        passed += 1

    # 7. /places/nearby
    total += 1
    r = _req("GET", "/api/v1/places/nearby?lat=37.57&lng=126.98&radius_km=5")
    if _ok("/places/nearby(종로)", r):
        print(f"     {len(r.get('results',[]))}곳 발견")
        passed += 1

    # 8. /districts
    total += 1
    r = _req("GET", "/api/v1/districts")
    if _ok("/districts", r):
        print(f"     {len(r.get('districts',[]))}개 지역구")
        passed += 1

    # 9. /reports POST + GET
    total += 1
    r = _req("POST", "/api/v1/reports",
             {"kind": "busy", "lat": 37.5796, "lng": 126.9770, "text": "시연 테스트 제보"})
    r2 = _req("GET", "/api/v1/reports?lat=37.5796&lng=126.9770")
    if _ok("/reports(POST+GET)", r) and _ok("/reports GET", r2):
        print(f"     제보 id={r.get('id')}  근방 제보={len(r2.get('reports',[]))}건")
        passed += 1

    # 10. /monitor/surge — 실시간 급증 SSE(모듈4). open 이벤트로 스트림 개시만 확인
    #     (급증 알림 자체는 실제 혼잡이 임계를 넘어야 나오므로 시연 스모크 범위 밖)
    total += 1
    r = _sse_first_event("/api/v1/monitor/surge?stops=126508,126521&interval=5")
    if _ok("/monitor/surge(SSE)", r) and r.get("event") == "open":
        d = r.get("data", {})
        print(f"     감시 {len(d.get('watching', []))}곳  주기={d.get('interval_sec')}s  "
              f"임계={d.get('surge_level')}({d.get('surge_level_label')})")
        passed += 1

    print(f"\n{'='*50}")
    print(f"결과: {passed}/{total} 통과")
    return passed == total


if __name__ == "__main__":
    print("=== 여백 시연 시나리오 스모크 테스트 ===\n")
    print("[단계1] seed DB 주입...")
    seed_db()
    print("\n[단계2] 서버 엔드포인트 검증...\n"
          "  (서버 미실행 시: uvicorn server.main:app --port 8000 먼저 실행)\n")
    ok = run_demo()
    sys.exit(0 if ok else 1)
