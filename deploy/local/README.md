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

Vite도 `deploy/local/.env`를 읽는다. 브라우저 번들에는
`MIRIYUM_PORTONE_STORE_ID`와 `MIRIYUM_PORTONE_CHANNEL_KEY` 두 값만 주입한다.
`MIRIYUM_PORTONE_API_SECRET`과 `MIRIYUM_PORTONE_WEBHOOK_SECRET`은 backend
컨테이너에만 전달되며 frontend 코드에는 포함되지 않는다.

dev server 포트를 바꾸면 `.env`의 `MIRIYUM_ALLOWED_ORIGIN`도 같은 값으로 바꾼다. 두 값이 다르면 재발급과 로그아웃이 `AUTH` Origin 오류로 거절된다.

## PortOne V2 토스페이먼츠 예약금 로컬 테스트

`deploy/local/.env`에서 다음 항목을 로컬 테스트 값으로 채운다. 값을 명령행 인자나
로그에 직접 쓰지 않는다.

```dotenv
MIRIYUM_PAYMENT_ENABLED=true
MIRIYUM_RESERVATION_DEPOSIT_WORKER_ENABLED=true
MIRIYUM_PAYMENT_CURSOR_SECRET=<32자 이상의 로컬 전용 값>
MIRIYUM_PORTONE_API_SECRET=<PortOne V2 API Secret>
MIRIYUM_PORTONE_WEBHOOK_SECRET=
MIRIYUM_PORTONE_STORE_ID=<PortOne Store ID>
MIRIYUM_PORTONE_CHANNEL_KEY=<토스페이먼츠 V2 테스트 Channel Key>
```

첫 로컬 검증은 frontend의 결제 확인·최종화 호출을 사용하므로 공개 HTTPS tunnel과
webhook 등록이 필요 없다. `MIRIYUM_PORTONE_WEBHOOK_SECRET`은 비워 둘 수 있다.
결제창 성공 뒤 confirmation 중단·새로고침이 발생하면 결제 화면의
`이미 결제했다면 상태 확인`으로 backend confirmation을 다시 호출한다. 이 호출 또는
webhook이 PortOne 조회 결과를 Payment 원장에 반영한다.

예약금 process worker는 PortOne을 직접 조회하지 않고 저장된 Payment 원장을 기준으로
예약 최종화·만료·보상 상태를 수렴한다. 따라서 중단 뒤 Payment 원장에 반영된 결제를
서버에서 계속 조정하거나 예약 취소 뒤 생성된 환불 처분 의무를 비동기로 처리하려면
`MIRIYUM_RESERVATION_DEPOSIT_WORKER_ENABLED=true`가 필요하다. 기본값은 `false`이므로
해당 복구·환불 검증을 하지 않는 로컬 환경에서는 작업기가 실행되지 않는다.

예약금이 필요한 202 응답을 만들려면 테스트할 매장에 대표 메뉴가 `CONFIGURED`로
설정돼 있어야 한다. 매장 ID를 확인한 뒤 Docker MySQL에서 그 매장의 예약금 정책만
직접 활성화한다. 이 작업은 로컬 데이터에만 수행한다.

```sql
SELECT store_id, name FROM stores ORDER BY store_id;
SELECT store_id, version, status
FROM representative_menu_settings
ORDER BY store_id;

SET @store_id = <테스트할 숫자 store_id>;
INSERT INTO store_reservation_deposit_policies (
    store_id, enabled, rate_percent, policy_version, lock_version,
    created_at, updated_at
) VALUES (
    @store_id, TRUE, 20, 1, 0, NOW(6), NOW(6)
)
ON DUPLICATE KEY UPDATE
    enabled = TRUE,
    rate_percent = 20,
    policy_version = policy_version + 1,
    lock_version = lock_version + 1,
    updated_at = NOW(6);
```

설정을 바꾼 뒤 backend를 다시 만든다.

```bash
docker compose --env-file deploy/local/.env \
  -f deploy/local/docker-compose.dev.yml up -d --build mysql valkey backend
```

다른 터미널에서 host Vite를 실행하고 `http://localhost:5173`에서 예약한다.

```bash
cd frontend
pnpm install
pnpm run dev
```

예약 생성이 202이면 `/reservation-requests/{id}/payment`로 이동한다. 토스 테스트
카드 결제 후 backend가 Payment를 `PAID`로 확인하고 예약 요청을 최종화했을 때만
기존 `/reservations/{id}/complete` 화면으로 이동한다.

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
