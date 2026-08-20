# 로컬 개발 실행 환경

기여자 PC에서 MySQL과 backend API를 띄우고, frontend dev server가 same-origin으로 그 API에 도달하게 한다.

staging·production 구성은 이 디렉터리가 소유하지 않는다. 배포 경로는 [`../docker-compose.prod.yml`](../docker-compose.prod.yml)과 [`docs/deployment/`](../../docs/deployment/)를 따른다.

## 사전 요구사항

- Docker Desktop
- Node.js와 pnpm — frontend dev server를 함께 띄울 때만 필요하다

호스트에 JDK를 설치하지 않아도 된다. backend 이미지는 `eclipse-temurin:21-jdk-jammy` 안에서 빌드하므로 호스트 Java 버전과 무관하다.

## 실행

```bash
cd deploy/local
cp .env.example .env
docker compose -f docker-compose.dev.yml up -d --build
```

최초 실행은 Gradle 의존성 수집으로 수 분이 걸린다. `.env`는 `.gitignore`가 제외하므로 커밋되지 않는다.

기동을 확인한다.

```bash
docker compose -f docker-compose.dev.yml ps
curl http://127.0.0.1:8080/api/v1/store-categories
```

카탈로그가 공통 성공 봉투로 돌아오면 Flyway migration과 seed가 적용된 것이다.

## frontend 연결

```bash
cd frontend
pnpm install
pnpm run dev
```

`frontend/vite.config.ts`가 `/api`를 `http://127.0.0.1:8080`으로 프록시한다. 브라우저에서 보면 dev server와 API가 같은 오리진이므로 `SameSite=Lax` 쿠키와 Origin 검증이 동작한다.

dev server 포트를 바꾸면 `.env`의 `MIRIYUM_ALLOWED_ORIGIN`도 같은 값으로 바꾼다. 두 값이 다르면 재발급과 로그아웃이 `AUTH` Origin 오류로 거절된다.

## 중지와 초기화

```bash
# 중지 (데이터 유지)
docker compose -f docker-compose.dev.yml down

# 데이터까지 삭제하고 빈 DB에서 다시 시작
docker compose -f docker-compose.dev.yml down -v
docker compose -f docker-compose.dev.yml up -d --build
```

backend 소스를 바꾼 뒤에는 이미지를 다시 빌드한다.

```bash
docker compose -f docker-compose.dev.yml up -d --build backend
```

## SSE 부하테스트 프로필

기본 `docker-compose.dev.yml`은 SSE runtime과 Valkey를 활성화하지 않는다. SSE 연결·재연결 시험은
`docker-compose.loadtest.yml`과 `loadtest` profile을 명시했을 때만 Nginx SSE proxy와 HTTPS Caddy
proxy를 추가한다.

`deploy/local/.env`의 `MIRIYUM_SSE_CURSOR_SECRET`은 32자 이상의 로컬 전용 값으로 바꾸고,
JWT secret 및 `MIRIYUM_NOTIFICATION_HISTORY_CURSOR_SECRET`과 서로 다른 값을 사용한다. 이 값은
backend에만 전달되며 Nginx, Caddy, k6 컨테이너에는 전달되지 않는다.

기존 HTTP `loadtest`는 `grafana/k6:2.1.0`을 유지한다. SSE는 자동 확장 registry 대신
`sse-loadtest` 전용 이미지를 빌드해 `k6 v1.2.2`와 `xk6-sse v0.1.12`를 고정한다. 실제 실행
순서와 summary·장애 복구 경계는 `docs/deployment/sse-runtime-runbook.md`를 따른다.

```powershell
docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest up -d mysql valkey backend sse-proxy loadtest-proxy
```

이 프로필의 기본 30초 timeout, 5초 heartbeat, 총 200개·계정별 6개 연결 한도는 로컬 검증 입력이다.
staging·production 운영값이나 활성화 승인을 뜻하지 않는다. 일반 API는 Caddy에서 backend로 직접
전달하고 세 SSE endpoint만 공유 Nginx snippet을 통과한다.

`slow-client` backpressure 검증에서만 `MIRIYUM_LOADTEST_SSE_TIMEOUT=PT90S`와
`MIRIYUM_LOADTEST_SSE_HEARTBEAT_INTERVAL=PT0.001S`로 backend를 재생성한다. 실행 후 두 값을
기본값으로 복원해 backend와 proxy를 다시 만든다. 해당 실행은 4 KiB TCP receive buffer가 설정된
`sse-slow-loadtest`만 사용하며, 일반 `sse-loadtest`와 운영 설정에는 적용하지 않는다.

## 포트

| 대상 | 주소 | 비고 |
|---|---|---|
| backend API | `127.0.0.1:8080` | 로컬 전용 바인딩 |
| MySQL | `127.0.0.1:3307` | `.env`의 `MYSQL_HOST_PORT`. 호스트의 기존 MySQL 3306과 충돌을 피한다 |
| frontend dev server | `localhost:5173` | `strictPort`라 점유 시 다른 포트로 옮기지 않고 실패한다 |

backend는 Docker 네트워크 안에서 `mysql:3306`으로 접속한다. 호스트 포트 3307은 DB 도구로 들여다볼 때만 쓴다.

## 알려진 제약

- 이 구성은 안정된 API를 띄우는 용도다. backend 소스를 고칠 때마다 이미지 재빌드가 필요하므로 backend 반복 개발 루프를 대체하지 않는다.
- `MIRIYUM_IDENTITY_VERIFICATION_STUB_ENABLED=true`가 기본값이다. 본인확인 제공업체가 선정되지 않아 스텁을 끄면 가입이 `COMMON_012`로 차단된다.
- `.env`의 값은 로컬 전용이다. staging·production 비밀값을 여기에 넣지 않는다.
- SSE 부하테스트 종료 시 기존 MySQL 데이터를 보존하려면 `down`을 사용하고 `down -v`는 사용하지 않는다.

## 문제 해결

**`MYSQL_HOST_PORT` 충돌** — 3307도 사용 중이면 `.env`에서 다른 포트로 바꾼다. backend는 이 값을 쓰지 않으므로 다른 변경은 필요 없다.

**backend가 계속 재시작한다** — `docker compose -f docker-compose.dev.yml logs backend`로 확인한다. Flyway migration 실패면 스키마가 코드와 어긋난 상태이므로 `down -v`로 초기화한다.

**frontend에서 401·403이 반복된다** — `.env`의 `MIRIYUM_ALLOWED_ORIGIN`과 실제 dev server 오리진이 같은지 확인한다.
