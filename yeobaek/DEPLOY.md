# 여백 서버 배포 (Fly.io)

이 디렉터리(`yeobaek/`)의 `Dockerfile`은 C++ 엔진(pybind11)까지 컨테이너 안에서 직접 빌드해
FastAPI 서버 하나로 묶는다.

> **이 문서를 만든 에이전트는 배포를 대신 실행하지 못했다.**
> 작업 환경에 `flyctl` 이 없고, 네트워크 정책이 `fly.io`·`dl.google.com` 을 막고 있으며,
> 도커 데몬도 떠 있지 않다. 무엇보다 **Fly.io 계정 토큰과 공공 API 키는 본인만 가지고 있다**.
> 아래는 그대로 따라 하면 되는 순서다. 최초 빌드 로그는 꼭 눈으로 확인할 것.

---

## ⚠ 먼저 알아야 할 것 — 이미지에 데이터가 들어가는 조건

`data/*.db` 와 `data/*.npy` 는 `.gitignore` 에 있다(스크립트로 재현 가능한 산출물이라
저장소에 넣지 않는다). 그래서 이미지에 실제 데이터가 들어가는지는 **어디서 배포하느냐**로 갈린다.

| 배포 방법 | `data/` 내용 | 결과 |
|---|---|---|
| **내 PC에서 `flyctl deploy`** | 스크립트로 만든 실제 DB·임베딩이 디스크에 있음 | 명소 검색·히트맵·감성 쌍둥이 **모두 동작** |
| **GitHub Actions 자동 배포** | 새로 체크아웃해서 `.gitkeep` 뿐 | 서버는 뜨지만 `places_loaded: 0` — 검색·추천이 전부 빈 결과 |

**그러므로 최초 배포와 데이터 갱신은 반드시 내 PC에서 한다.**
자동 배포(`.github/workflows/deploy.yml`)는 그 뒤 *서버 코드만* 고쳤을 때 쓰는 용도다.
자동 배포만으로 운영하려면 아래 '데이터를 유지하는 두 가지 방법' 중 하나를 골라야 한다.

---

## 0) 준비물

- Fly.io 계정 (무료 가입, 카드 등록이 필요할 수 있음 — 무료 한도 안에서는 과금되지 않음)
- `flyctl` CLI: https://fly.io/docs/hands-on/install-flyctl/
  (Windows PowerShell: `pwsh -c "iwr https://fly.io/install.ps1 -useb | iex"`)
- **데이터가 만들어져 있을 것.** 아직이면 먼저:

  ```bash
  cd yeobaek
  pip install -r scripts/requirements.txt
  export TOURAPI_KEY=발급키
  python scripts/collect_tourapi.py --nationwide --pages 5
  python scripts/build_area_map.py
  python scripts/build_embeddings.py
  ls -lh data/          # yeobaek.db, embeddings.npy 가 보여야 한다
  ```

## 1) 로그인 (대화형 — 직접 실행)

```bash
flyctl auth login
```

## 2) 앱 생성 (최초 1회)

`flyctl deploy` 는 **앱이 이미 있어야** 동작한다. 처음에는 만들어 주자.

```bash
cd yeobaek
flyctl launch --no-deploy     # fly.toml 이 이미 있으므로 "기존 설정을 쓸까?" 에 Yes
```

**`yeobaek-api` 는 이미 제3자가 선점했다.** 그래서 `flyctl launch` 가 접미사를 붙인
`yeobaek-api-plucky-voice-2925` 로 앱이 만들어졌고, `fly.toml` 도 그 이름을 쓴다.
**배포 주소는 `https://yeobaek-api-plucky-voice-2925.fly.dev` 다.**

이름을 바꾸고 싶으면 `fly.toml` 의 `app` 과 `local.properties` 의
`DEV_BASE_URL`/`PROD_BASE_URL` 을 **반드시 함께** 고쳐야 한다. 한쪽만 고치면 앱이
엉뚱한 서버(남의 `yeobaek-api`)에 붙는데, 그쪽도 `/health` 에 200 을 주기 때문에
"서버는 살아있는데 장소만 안 뜨는" 형태로 나타나 원인 찾기가 어렵다.

## 3) API 키 주입 (이미지에는 절대 넣지 않는다)

```bash
flyctl secrets set SEOUL_API_KEY=... TOURAPI_KEY=... TATS_API_KEY=...
```

키가 없어도 서버는 뜨지만(중립값 폴백) 실제 혼잡 예보를 보여주려면 최소 `SEOUL_API_KEY`
는 필요하다.

## 4) 배포

```bash
cd yeobaek
flyctl deploy
```

C++ 엔진 컴파일까지 하므로 최초 빌드는 몇 분 걸린다. 로그에서
`yeobaek_engine` 타깃이 성공하는지 확인할 것.

## 5) 배포 확인

```bash
curl https://<앱 이름>.fly.dev/health
```

이런 응답이면 성공이다:

```json
{"status":"ok","engine_available":true,"places_loaded":7361,"seoul_api_key_set":true,"llm_enabled":false}
```

체크 포인트:

- **`{"status":"ok"}` 만 오고 나머지 필드가 없다** → 우리 서버가 아니다. 주소를 잘못 봤다는
  뜻이다(선점당한 `yeobaek-api.fly.dev` 등). `fly.toml` 의 `app` 이름과 대조할 것.
- `engine_available: false` → C++ 엔진 빌드 실패. `flyctl logs` 로 원인 확인.
- `places_loaded: 0` → 데이터 없는 이미지가 올라갔다. 위 '⚠ 먼저 알아야 할 것' 참고.
- `seoul_api_key_set: false` → 3) 시크릿 주입을 건너뛴 것.

---

## 6) 앱과 연결하기 (배포 후 본인이 할 일)

여기부터가 앱 쪽 작업이다.

### 6-1. 서버 주소를 앱에 넣기

프로젝트 루트의 `local.properties`(깃 추적 안 됨)에 추가한다:

```properties
PROD_BASE_URL=https://<앱 이름>.fly.dev/
MAPS_API_KEY=<구글 지도 API 키>
```

- 끝의 **슬래시(`/`)를 빠뜨리면 안 된다** — Retrofit 의 baseUrl 규칙 때문에 경로가 어긋난다.
- 이 값이 릴리스 빌드의 `BuildConfig.BASE_URL` 로 들어간다(`app/build.gradle.kts` release 참고).
- 디버그 빌드는 `DEV_BASE_URL`(기본 `http://10.0.2.2:8000/`)을 쓴다. 배포 서버로 디버그 빌드를
  붙여 보고 싶으면 `DEV_BASE_URL` 에도 같은 https 주소를 넣으면 된다.

### 6-2. 디버그 빌드로 먼저 확인

```bash
./gradlew installDebug
```

홈 지도를 움직였을 때 '이 지역 추천' 카드가 채워지면 서버 연결이 맞는 것이다.

### 6-3. 릴리스 서명 준비 (스토어에 올릴 때만)

`keystore.properties` 를 루트에 만든다(`keystore.properties.example` 참고, 깃 추적 안 됨):

```properties
storeFile=키스토어파일.jks
storePassword=...
keyAlias=...
keyPassword=...
```

### 6-4. 릴리스 빌드

```bash
./gradlew bundleRelease      # Play 업로드용 .aab
# 또는
./gradlew assembleRelease    # 직접 설치용 .apk
```

`local.properties` 를 고친 뒤에는 **반드시 다시 빌드해야** 새 주소가 반영된다.

### 6-5. Play Console 에 올릴 때 챙길 것

- **포그라운드 서비스 용도 선언**: `FOREGROUND_SERVICE_DATA_SYNC` 를 쓰므로
  앱 콘텐츠 → 포그라운드 서비스 권한에 사유를 적어야 한다. 선언 없이 올리면 반려된다.
  문구 예: "여행 코스를 진행하는 동안 서버의 실시간 혼잡 스트림을 구독해 급증 시 알림".
- **권한 확인**: 빌드 후 `app/build/outputs/logs/manifest-merger-release-report.txt` 를 열어
  의도한 5개(INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, FOREGROUND_SERVICE,
  FOREGROUND_SERVICE_DATA_SYNC) 외에 위치 같은 민감 권한이 섞여 들어오지 않았는지 본다.
- **개인정보 처리방침**: `PRIVACY.md` 내용을 웹에 올리고 그 URL 을 등록.

---

## 7) 자동 배포 켜기 (선택)

`.github/workflows/deploy.yml` 이 `main` 에 `yeobaek/**` 변경이 올라오면
스모크 테스트 후 Fly 에 배포한다. 최초 1회만 준비하면 된다:

```bash
flyctl auth token          # 토큰 출력
```

GitHub 저장소 → Settings → Secrets and variables → Actions → New repository secret
→ 이름 `FLY_API_TOKEN`, 값은 위 토큰.

토큰이 없으면 배포 단계는 조용히 건너뛰고 스모크 테스트만 돈다.

**단, 앞에서 말한 대로 CI 배포는 데이터가 빠진 이미지를 만든다.** 아래 중 하나를 택할 것.

### 데이터를 유지하는 두 가지 방법

**(A) 볼륨 붙이기 — 쓰기 데이터(제보·예보 관측)를 살린다**

```bash
flyctl volumes create yeobaek_data --size 1 --region nrt
```

그리고 `fly.toml` 의 `[mounts]` 3줄 주석을 푼다. 이러면 재배포해도 `/data/yeobaek.db` 가
남는다. 다만 **임베딩(`embeddings.npy`)은 읽기 전용이라 이미지 안에 있어야 한다** —
CI 배포만 하면 감성 쌍둥이(`/match`)와 대안 카드가 빈손이 된다.

**(B) 데이터가 바뀔 때는 내 PC에서 배포 (권장)**

명소·임베딩을 새로 만들었을 때만 로컬에서 `flyctl deploy` 를 하고, 그 사이 서버 코드 수정은
CI 에 맡긴다. 가장 단순하고 실수할 여지가 적다.

---

## 무료 티어가 자동으로 꺼지는 것에 대해

`fly.toml` 의 `min_machines_running = 0` 때문에 트래픽이 없으면 머신이 완전히 꺼진다(과금 절약).
접속하는 순간 자동으로 켜지지만 **첫 요청은 몇 초 지연**될 수 있다.
시연·심사 직전에는 `curl https://<앱 이름>.fly.dev/health` 로 한 번 깨워 두는 걸 권장한다.
