# 06. 시스템 아키텍처

## 목표와 방식 A

MiriYum은 `1차 MVP`, `2차 MVP`와 `고도화`까지 **방식 A**를 유지한다.

- 백엔드 배포 단위는 하나의 Spring Boot 애플리케이션이다.
- 활성 업무 데이터의 원장은 하나의 MySQL이다.
- 도메인은 패키지와 공개 Service·DTO 계약으로 분리하되 다른 도메인의 Controller·Repository·Entity를 직접 사용하지 않는다.
- 기능·운영 증거 없이 백엔드, 데이터베이스 또는 배포 단위를 나누지 않는다.

이 선택은 계정 경계를 합친다는 뜻이 아니다. 일반 사용자, 매장 운영자와 플랫폼 운영자는 전용 테이블·PK·principal·토큰 namespace를 사용한다. 같은 애플리케이션과 MySQL 안에서도 세 인증 경계와 UI 진입 경로를 분리하고, 이메일·외부 로그인·클라이언트 역할 값으로 유형이나 권한을 바꾸지 않는다.

## 런타임과 저장소 구조

React·TypeScript·Vite 프런트엔드는 HTTP API로 Spring Boot와 통신한다. Spring MVC는 웹 경계, Spring Security는 계정 유형별 인증·인가, Spring Data JPA와 좁은 명시적 SQL은 MySQL 접근, Flyway는 스키마 이력을 담당한다.

`1차 MVP`의 최종 사용자 배포는 하나의 Vite 빌드와 하나의 Spring Boot 애플리케이션을 같은 Origin에서 제공하고 백엔드 API를 `/api` 아래에 둔다. 교차 Origin 자격 증명 요청은 허용하지 않으며, 실제 배포 단위를 분리해야 하는 근거가 생기면 CORS·쿠키·CSRF 경계를 함께 재검토한다.

프론트엔드 배포 범위가 아직 승인되지 않은 동안 [#120](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/120)은 1차 MVP의 **staging 백엔드 API 사전 배포**만 구성한다. 이 경로는 `dev`에 통합되고 CI가 성공한 SHA만 staging EC2에 배포한다. Nginx가 `/api`만 Spring Boot에 프록시하고, Vite 정적 파일·사용자 shell·최종 same-origin 사용자 흐름은 제공하지 않는다. 따라서 이 경로의 성공은 최종 사용자 배포나 핵심 흐름 E2E 성공을 뜻하지 않는다. 프론트엔드 소유자가 배포 범위를 승인하는 후속 Issue에서 Nginx의 `/` 정적 제공과 `/api` 프록시를 함께 구성해 same-origin 배포를 완성한다. 운영 배포는 별도 EC2·IAM 역할·GitHub Environment 승인과 `main` 전용 workflow로 분리한다.

```text
frontend/
  일반 사용자 shell
  매장 운영자 shell
  플랫폼 운영자 shell(고도화)
        │
        ▼
하나의 Spring Boot
  auth ─ consumer ─ storeoperator
  store ─ schedule ─ menu ─ search
  reservation ─ menuhold ─ pickup
  payment(고도화) ─ notification(고도화)
        │
        ▼
하나의 MySQL 업무 원장
```

세 shell은 하나의 프런트엔드 빌드 안에 둘 수 있지만 로그인 시작점, 라우트 가드, principal 해석과 토큰 namespace를 공유하지 않는다. 다른 유형의 토큰으로 shell을 전환하거나 서버 권한을 얻을 수 없다.

저장소는 `backend/`, `frontend/`, `docs/`를 기준으로 한다. 실제 기능이 없는 패키지·설정·의존성·배포 구성은 미리 만들지 않는다.

## 계층과 모듈 의존 규칙

- controller는 요청 검증, 인증 주체 전달과 응답 변환만 담당한다.
- service 경계는 유스케이스, 권한 재검증과 트랜잭션을 소유한다.
- repository는 JPA와 고경합 쓰기에 필요한 좁은 명시적 SQL을 캡슐화한다.
- API DTO와 내부 entity를 분리하고 entity를 응답으로 직접 노출하지 않는다.
- 다른 모듈의 기능은 공개 Service 메서드와 DTO로만 사용한다.
- `global`은 보안·오류·관측 같은 기술 관심사만 소유하고 업무 규칙을 갖지 않는다.

HTTP 호출자 구분은 Controller와 HTTP DTO에서만 표현한다. `publicapi`는 인증 없는 공개 조회, `consumer`와 `storeoperator`는 각 principal namespace의 인증 경계다. Service·Repository·Entity는 호출자별로 복제하지 않는다. 계정 유형 자체가 도메인인 `consumer`와 `storeoperator`의 HTTP 경계는 `auth`와 `account` 목적별로 나눈다.

구조 테스트는 controller→repository 직접 호출, 모듈 간 repository/entity 직접 접근과 순환 의존을 거부한다.

도메인 의존 그래프의 순환 baseline은 빈 집합이다. 예약 내역 HTTP 경계는 `reservation.controller.consumer`가 소유하고, 예약은 `ReservationMenuHoldPort`만 의존하며 `ReservationMenuHoldAdapter`가 MenuHold 기능을 연결한다. MenuHold의 예약 시간 해석은 `ReservationTimeResolutionService`로 좁혀 역방향 Service 의존을 만들지 않는다. 구조 테스트는 전체 도메인 그래프에서 순환 pair와 edge가 모두 0건인지 검사한다.

공통 Store→Menu 잠금·검증 흐름은 `MenuTransactionFacade`가 소유하고 MenuHold와 Pickup이 사용한다. 이 Facade는 트랜잭션 조정 경계를 명시하며, 도메인 간 순환을 허용하는 예외가 아니다.

## `1차 MVP` 기술과 모듈

`1차 MVP`는 `auth`, `consumer`, `storeoperator`, `store`, `schedule`, `menu`, `search`, `reservation`, `menuhold`, `pickup`의 실제 기능만 둔다. Java 21, Spring Boot 4.1.0, Gradle Wrapper 9.6.1, Spring MVC, Spring Data JPA, Spring Security, Flyway와 MySQL을 사용한다. 프런트엔드는 Node.js 24.18.0, pnpm 11.17.0, React 19.2.8, TypeScript 7.0.2, Vite 8.1.5, Vitest 4.1.10과 React Testing Library 16.3.2를 사용한다.

정확히 확인되지 않은 MySQL·Testcontainers 버전은 적지 않는다. Testcontainers MySQL은 첫 단계부터 Flyway, MySQL 제약, 조건부 SQL, 락·격리와 전체 롤백을 검증하는 통합 테스트 경계다.

일반 조회는 JPA를 사용한다. 예약 인원·팀 수와 메뉴 수량처럼 경합이 큰 쓰기는 repository 안의 DB 유일 제약, 비음수 조건, 고정 잠금 순서, 조건부 SQL과 멱등 키로 보호한다. `예약+메뉴 홀드`는 선택된 모든 자원을 하나의 MySQL 트랜잭션으로 확정하며 부분 성공을 남기지 않는다. 픽업 예약은 메뉴 수량과 픽업 시간대만 사용한다.

계정 유형별 Access JWT와 Refresh JWT는 서버 정상 목록·폐기 목록 없이 검증한다. 토큰 유형·서명·만료·발급자·대상·계정 namespace를 확인하고 보호 명령은 현재 계정 상태, 대상 매장의 `store_operator_account_id` 일치와 매장 상태를 MySQL에서 다시 검증한다. `1차 MVP`에는 Valkey·Spring Data Redis를 넣지 않는다.

## `2차 MVP` 기술 확장

`2차 MVP`는 방식 A와 MySQL 원장을 유지하고 다음 기술만 추가한다.

- `RuleInterpreter`: 한국어 입력의 정규화, 명시적 시간대와 `Clock`, 가격, 승인된 지역·분위기·카테고리 사전 및 남은 키워드를 결정적으로 해석한다.
- QueryDSL: 선택 조건 조합과 projection을 타입 안전하게 구성한다.
- `recommendation`: 유효한 예약·확정 메뉴 이력을 요청 시 MySQL에서 집계하고 Java 점수 계산으로 설명 가능한 통합 검색 후보를 만든다. 1차 MVP의 지리 계산·반경 판정만 `search.geo`가 소유하고, 통합 검색의 추천 점수·정렬·사용자 이력 정책은 2차 MVP에서 별도 최상위 도메인으로 생성한다.
- `alternative`: 품절 메뉴의 적격성 정책·정렬·오케스트레이션을 소유한다. `search`가 제공하는 immutable 후보 조회 계약과 Reservation·MenuHold 공개 계약만 조합하며 Store·Menu Entity·Repository를 직접 참조하지 않는다. 통합 검색이 이미 `recommendation`을 사용하므로 대안 기능을 `recommendation` 아래에 두어 `search ↔ recommendation` 순환 의존을 만들지 않는다.
- 동기 추천 가용성 조회: `1차 MVP` 매장 목록·검색의 `availableOnly`·`reservationAvailability`는 기존 `ReservationService` 공개 일괄 가용성 조회 계약을 사용한다. 선구현 금지는 `2차 추천 전용` 신규 `BulkAvailabilityPort` 또는 신규 batch 계약에만 적용하며, 해당 계약은 `2차 MVP` 진입 전 별도 contract-first Issue/PR에서 기존 1차 계약의 재사용·확장 여부와 함께 확정한다.
- 카카오 좌표 포트: 입점·주소 변경 때만 Kakao Local REST를 호출한다. 지도 SDK는 결과 표시와 매장 운영자의 좌표 확인에만 사용한다.

품절 대안은 `com.miriyum.domain.alternative`에서 같은 매장 후보를 먼저 검증하고, 후보가 없을 때만 원 매장의 검증된 저장 좌표 기준 **3km 이내**의 매장을 허용한다. bounding box로 후보를 줄인 뒤 Java Haversine으로 3km 포함 경계를 확정한다. 대안 요청 중 외부 지도 호출은 하지 않으며 사용자 현재 위치도 요청·저장·사용하지 않는다. AI/LLM, Spring AI, 벡터 DB, 검색엔진, 추천 전용 서비스·DB·캐시는 `2차 MVP`에 없다.

## `고도화` 기술 확장

`고도화`는 승인된 기능과 함께 다음 경계를 추가한다.

- Valkey: Refresh Token 회전·폐기·재사용 탐지, 로그인 단위 종료, 속도 제한과 SSE 전달 보조
- S3: 사업자등록증·매장/메뉴 이미지 객체, MySQL 메타데이터와 객체 대사
- SSE: 웨이팅·알림 등 단방향 상태 갱신과 재연결
- PortOne V2: `PaymentClient` 뒤의 결제·Webhook·환불·대사 어댑터
- 기능별 MySQL 내구성 작업: 알림·자동 승계·결제 복구를 업무 상태와 같은 트랜잭션에 기록하고 DB 임대·fencing·멱등 키·제한 재시도·격리·대사로 처리
- `payment`, `notification` 및 승인된 웨이팅·체크인·플랫폼 관리 기능

SSE와 Valkey Pub/Sub은 전달 보조이며 MySQL 업무 원장을 대체하지 않는다. S3 객체 성공만으로 파일 업무 상태를 확정하지 않고 MySQL 메타데이터·체크포인트와 대사한다. 브라우저 결제 성공 주장은 최종 근거가 아니며 서버 조회나 검증된 Webhook이 같은 멱등 확정 경로를 호출한다.

## `향후 고도화`

AI 설명·FAQ·RAG, 구독, 채팅, 리뷰, 외부 본인확인과 추가 플랫폼 관리 기능은 기능별 승인 뒤 검토한다. 추천의 조건 해석·후보·필터·점수·순위·TOP3는 결정적 코드가 먼저 확정한다. LLM은 확정된 TOP3의 설명만 생성하며 조건 해석, 후보 생성, 필터, 점수, 순위나 TOP3를 만들거나 바꾸지 못한다. 실패·시간 초과·형식 오류에는 같은 코드 확정 결과의 템플릿 설명을 사용한다. 이 경계를 근거로 AI 파이프라인, 벡터 DB, 채팅 인프라나 추가 외부 제공자를 미리 설치하지 않는다.

## 선도입 금지와 전환 신호

Kafka, 범용 Outbox, 마이크로서비스, WebSocket과 검색 클러스터는 현재 단계의 공통 기반이 아니다.

- Kafka·범용 Outbox: 다수 독립 소비자, 장기 재생 또는 기능별 MySQL 작업 polling 병목이 실제로 확인된 뒤 비교한다.
- WebSocket: SSE로 충족할 수 없는 양방향 저지연 상호작용 요구가 확인된 뒤 비교한다.
- 검색 클러스터: 오타·관련도·부하가 MySQL 개선으로 목표를 지속 충족하지 못한 증거가 있을 때 비교한다.
- 방식 A 분리: 독립 배포 지연, 한 기능 때문에 생기는 전체 과잉 확장, 반복 SLO 침해, 다른 규제·복구 경계와 독립 팀·데이터 소유가 함께 확인될 때만 검토한다.

분리 전에는 모듈 포트, 쿼리·연결·작업 풀 격리, 별도 worker와 읽기 projection을 먼저 개선한다. 모든 전환은 결과 동등성, 성능, 장애 복구, 마이그레이션·롤백과 새 단점을 ADR의 날짜별 개정에 남긴다.

## 관련 ADR

- [ADR-001 방식 A와 도메인 패키지](adr/ADR-001-domain-packages-three-layer.md)
- [ADR-002 단계적 기술 도입](adr/ADR-002-staged-technology-adoption.md)
- [ADR-003 AWS·S3 단계](adr/ADR-003-aws-after-verification.md)
- [ADR-004 도구·Testcontainers 기준](adr/ADR-004-scaffold-toolchain-and-test-baseline.md)
- [ADR-005 PortOne V2](adr/ADR-005-portone-v2-payment-adapter.md)
- [ADR-006 JWT·Valkey 전환](adr/ADR-006-jwt-valkey-refresh-token.md)
- [ADR-007 규칙 기반 통합 검색](adr/ADR-007-unified-search-mysql.md)
