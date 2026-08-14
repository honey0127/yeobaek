# 여백 서버 운영 가이드

> "매번 내 터미널에서 `uvicorn` 을 켜야 하나?" — 아니다. 아래 셋 중 하나를 고르면 된다.

| 방법 | 내 PC 꺼도 되나 | 비용 | 난이도 | 언제 |
| --- | --- | --- | --- | --- |
| **A. Fly.io + GitHub 자동배포** | ✅ | 무료 티어 | ★☆☆ (최초 1회 설정) | **기본 추천.** 심사·시연·실사용 |
| **B. 내 PC 상시 실행(docker compose)** | ❌ (PC 는 켜져 있어야) | 전기세 | ★☆☆ | 개발 중, 집 서버가 있을 때 |
| **C. 개발용 수동 실행** | ❌ | — | — | 코드 고치는 중일 때만 |

핵심 차이는 **누가 프로세스를 살려주느냐**다. C 는 터미널이, B 는 Docker 데몬이,
A 는 Fly.io 가 살려준다. A·B 는 서버가 죽으면 자동으로 다시 띄운다.

---

## A. Fly.io + GitHub 자동배포 (추천)

한 번만 설정하면 그 뒤로는 **`main` 에 push 할 때마다 자동으로 검증 → 배포**된다.
터미널을 열 일이 없다.

### A-1. 최초 설정 (1회)

```bash
# 1) flyctl 설치  https://fly.io/docs/hands-on/install-flyctl/
#    (Windows PowerShell) pwsh -c "iwr https://fly.io/install.ps1 -useb | iex"
flyctl auth login

# 2) 앱 생성 (yeobaek/ 안에서)
cd yeobaek
flyctl launch --no-deploy      # fly.toml 이 이미 있으니 "기존 설정 사용" 선택
```

`yeobaek-api` 이름을 이미 누가 쓰고 있으면 `fly.toml` 의 `app = "yeobaek-api"` 를
`yeobaek-api-honey0127` 같은 이름으로 바꾼다. 주소는 `https://<app 이름>.fly.dev`.

```bash
# 3) API 키 주입 (이미지에 안 들어간다 — 반드시 여기서)
flyctl secrets set SEOUL_API_KEY=... TOURAPI_KEY=... TATS_API_KEY=...

# 4) (선택·권장) 제보 데이터를 재배포에도 유지할 볼륨
flyctl volumes create yeobaek_data --size 1 --region nrt
#    만든 뒤 fly.toml 아래쪽 [mounts] 3줄의 주석을 푼다.

# 5) 첫 배포
flyctl deploy
curl https://<app 이름>.fly.dev/health
```

`{"status":"ok","engine_available":true,"places_loaded":7361,...}` 면 성공.

### A-2. 자동배포 켜기 (1회)

```bash
flyctl auth token          # 출력된 토큰 복사
```

GitHub 저장소 → **Settings → Secrets and variables → Actions → New repository secret**
→ 이름 `FLY_API_TOKEN`, 값은 위 토큰.

이제 `.github/workflows/deploy.yml` 이 `main` push 마다:
`서버 기동 → demo_smoke 10종 엔드포인트 검증 → flyctl deploy → /health 확인` 을 돌린다.
토큰을 안 넣으면 검증까지만 하고 배포는 조용히 건너뛴다(빨간 X 안 남는다).

> PR 에서도 스모크 테스트는 돌지만 배포는 `main` 에서만 한다.

### A-3. 운영 명령

```bash
flyctl status                 # 머신 상태
flyctl logs                   # 실시간 로그
flyctl secrets list           # 주입된 키 이름(값은 안 보임)
flyctl apps restart yeobaek-api
flyctl deploy                 # 손으로 배포하고 싶을 때
```

### A-4. 콜드 스타트

`min_machines_running = 0` 이라 트래픽이 없으면 머신이 꺼진다(과금 절약). 다시 요청이
오면 자동으로 켜지지만 **첫 요청이 몇 초 느리다.** 시연 직전에 `curl .../health` 로
한 번 깨워두면 된다. 항상 켜두고 싶으면 `min_machines_running = 1` 로 바꾼다
(무료 한도를 넘길 수 있으니 요금 페이지 확인).

---

## B. 내 PC·집 서버 상시 실행 (docker compose)

PC 는 켜져 있어야 하지만, **터미널을 닫아도·재부팅해도** 서버가 계속 돈다.

```bash
cd yeobaek
cp .env.example .env      # API 키 채우기(비워도 뜬다)
docker compose up -d      # 백그라운드 상시 실행
```

- `restart: unless-stopped` — 컨테이너가 죽거나 PC 를 재부팅해도 Docker 데몬이 뜨면 같이 뜬다.
  (Docker Desktop 설정에서 "Start Docker Desktop when you sign in" 을 켜 둘 것)
- 상태 `docker compose ps` · 로그 `docker compose logs -f` · 중지 `docker compose down`
- 코드 고친 뒤 반영: `docker compose up -d --build`
- 제보 데이터는 `yeobaek-data` 볼륨에 남는다(컨테이너를 지워도 유지).

앱에서 접속할 주소는 `local.properties` 의 `DEV_BASE_URL`:
- 에뮬레이터 → `http://10.0.2.2:8000/`
- 실기기(같은 와이파이) → `http://<PC 의 LAN IP>:8000/`

### B-1. 밖에서도 접속하게 하려면 (Cloudflare Tunnel)

공유기 포트포워딩 없이 https 주소를 얻는 방법. 무료다.

```bash
# cloudflared 설치 후
cloudflared tunnel --url http://localhost:8000
```

출력되는 `https://<랜덤>.trycloudflare.com` 을 앱의 `PROD_BASE_URL` 에 넣으면 된다.
(임시 주소라 재시작하면 바뀐다. 고정 주소가 필요하면 Cloudflare 계정으로 named tunnel 사용.)

### B-2. Docker 없이 리눅스 서비스로 (systemd)

```ini
# /etc/systemd/system/yeobaek.service
[Unit]
Description=Yeobaek API
After=network.target

[Service]
WorkingDirectory=/home/<사용자>/yeobaek/yeobaek
Environment="SEOUL_API_KEY=..."
ExecStart=/home/<사용자>/yeobaek/yeobaek/.venv/bin/python -m uvicorn server.main:app --host 0.0.0.0 --port 8000
Restart=always
User=<사용자>

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now yeobaek     # 부팅 시 자동 실행 + 지금 실행
systemctl status yeobaek
journalctl -u yeobaek -f                # 로그
```

Windows 라면 `nssm install yeobaek` 으로 같은 걸 서비스로 등록할 수 있다.

---

## C. 개발용 수동 실행

코드를 고치는 중일 때만 쓴다.

```bash
cd yeobaek
uvicorn server.main:app --reload --host 0.0.0.0 --port 8000
```

`--reload` 는 파일이 바뀌면 자동 재시작한다. `--host 0.0.0.0` 이어야 폰에서 접근된다.

---

## 앱의 서버 주소 갱신

루트 `local.properties`(git 추적 안 됨):

```properties
DEV_BASE_URL=http://10.0.2.2:8000/
PROD_BASE_URL=https://<app 이름>.fly.dev/
```

각각 debug/release 빌드의 `BuildConfig.BASE_URL` 로 들어간다(`app/build.gradle.kts`).
값을 바꾸면 다시 빌드해야 반영된다.

---

## 데이터 영속성 메모

이미지에는 수집 결과 seed DB 가 들어 있고, 실제로 읽고 쓰는 DB 는 `/data/yeobaek.db` 다
(`docker-entrypoint.sh` 가 비어 있을 때만 seed 를 복사한다). 여행자 제보(`reports`)와
예보 관측 누적(`forecast_cache`)이 여기 쌓이므로, **볼륨을 안 붙이면 재배포 때 사라진다.**
A-1 의 4번(Fly 볼륨) 또는 B 의 compose 볼륨을 쓰면 유지된다.

## 검증 상태

- `.github/workflows/deploy.yml` 의 스모크 단계는 이 저장소 환경에서 실제로 통과하는
  `scripts/demo_smoke.py`(10/10) 를 그대로 돌린다.
- **Dockerfile / compose / fly.toml 은 이 환경에 Docker 데몬·flyctl 이 없어 실행 검증하지
  못했다.** 최초 `docker compose up --build` 또는 `flyctl deploy` 때 빌드 로그를 꼭 확인할 것.
