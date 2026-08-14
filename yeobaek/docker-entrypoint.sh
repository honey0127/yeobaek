#!/bin/sh
# 여백 서버 컨테이너 진입점.
#
# 이미지에는 수집 결과가 담긴 seed DB(/app/data/yeobaek.db)가 들어 있다. 그런데 앱이
# 쓰는 데이터(여행자 제보 reports, 예보 관측 누적 forecast_cache)도 같은 파일에 쌓이므로,
# 컨테이너 파일시스템에 그대로 두면 재배포할 때마다 전부 날아간다.
#
# 그래서 실제로 읽고 쓰는 DB 는 /data (볼륨을 붙일 수 있는 위치)에 두고, 거기가 비어
# 있을 때만 이미지의 seed 를 한 번 복사한다. 볼륨을 안 붙여도 그대로 동작한다
# (그 경우 컨테이너 수명만큼만 유지 — 기존과 동일).
set -e

DB_PATH="${YEOBAEK_DB:-/data/yeobaek.db}"
SEED_DB="${YEOBAEK_SEED_DB:-/app/data/yeobaek.db}"

mkdir -p "$(dirname "$DB_PATH")"

if [ ! -f "$DB_PATH" ] && [ -f "$SEED_DB" ]; then
    echo "[entrypoint] seed DB 복사: $SEED_DB -> $DB_PATH"
    cp "$SEED_DB" "$DB_PATH"
fi

exec python -m uvicorn server.main:app --host 0.0.0.0 --port "${PORT:-8080}"
