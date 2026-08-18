# 07. 데이터 및 API 계약

## 플랫폼 운영자 인증 계약

`platform-operator-openapi.yaml`은 기존 세 audience와 분리된 네 번째 진입점이며 1차 MVP aggregate에는 포함하지 않는다. V39의 `platform_operator_accounts`가 계정·상태·권한/세션 버전의 원본이고, `platform_operator_auth_events`는 비밀번호·토큰·원문 세션 식별자 없이 인증 사건만 append-only로 보존한다.

플랫폼 운영자 Access JWT는 15분이며 모든 보호 요청에서 MySQL 계정 상태·버전과 Valkey 중앙 세션을 함께 확인한다. 중앙 세션은 유휴 30분·절대 8시간·동시 1개이고 로그인 교체, refresh 회전, logout, 계정 중지와 버전 변경을 원자적 회수 경계로 처리한다. 상세 계약은 [플랫폼 운영자 인증 명세](specs/platform-operator-auth/spec.md)를 따른다.

## 플랫폼 운영자 권한·재인증 계약

V40의 역할·직접 권한 grant, 사건 배정과 재인증 승인 원장이 중앙 정본이다. 고위험 명령은 현재 계정 행을 잠그고 권한 version, 세부 권한, 사건 배정과 목적·대상·세션에 결속된 미소비 5분 승인을 같은 명령 트랜잭션에서 확인한다. 상세 계약은 [플랫폼 운영자 권한·재인증 명세](specs/platform-operator-authorization/spec.md)를 따른다.

## 현재 플랫폼 운영자 capabilities 계약

`GET /api/v1/platform-operators/me`는 JWT claims가 아니라 현재 MySQL role grant와 직접 permission grant를 중앙 `authority_version`으로 다시 검증해 합산한다. 응답은 version·활성 역할·최종 유효 권한만 포함하며 `SUPER_ADMIN`을 전체 catalog 보유자로 확장하지 않는다. 상세 계약은 [현재 플랫폼 운영자 capabilities 명세](specs/platform-operator-capabilities/spec.md)를 따른다.

## 회원지원 데이터·API 계약

V44의 복구·제재·이의·추가 승인·감사·mock 확인 원장이 회원지원 사건의 정본이다. 소비자와 식당 운영자 계정의 `support_version` CAS가 복구와 제재의 동시 전이를 직렬화하며, 패자의 사건·일회 승인·감사는 같은 transaction에서 rollback한다. 연락처와 인증 비밀 원문은 신규 원장에 저장하지 않는다. 상세 계약은 [회원지원 기능 명세](specs/member-support/spec.md)와 [OpenAPI](specs/member-support/openapi.yaml)를 따른다.

## 플랫폼 운영자 관리·감사 계약

V43은 `SUPER_ADMIN` role grant의 singleton 제약과 수정·삭제를 trigger로 거부하는 `platform_operator_audit_events`를 추가한다. 관리 명령 멱등성은 V4 공통 원장을 재사용한다. 기존 인증 원장과 신규 관리 원장은 각각 `AUTH:*`, `ADMIN:*` event key의 안전 projection으로 통합 조회하고 원 사건은 연결 보정 사건으로만 바로잡는다. 공통 보존기간·TTL·cleanup은 ADMIN-009가 확정되기 전까지 구성하지 않는다. 상세 계약은 [운영자 관리·감사 명세](specs/platform-operator-management-audit/spec.md)를 따른다.

## OpenAPI 소유권과 진입점

기능별 원본은 `docs/specs/<기능>/openapi.yaml`이 소유하며, `docs/specs/mvp1-common/openapi.yaml`은 path를 갖지 않는 공통 계약 전용이다. 클라이언트별 진입점은 `public-openapi.yaml`, `consumer-openapi.yaml`, `store-operator-openapi.yaml`, `platform-operator-openapi.yaml`이며, 서로 path가 중복되지 않는다. 기능별 원본의 path는 네 클라이언트 진입점 중 정확히 하나에 노출한다. `mvp1-openapi.yaml`은 1차 MVP 통합 진입점이므로 그 path 집합은 public·consumer·store-operator 진입점 path 합집합의 부분집합이어야 하며, 이후 단계인 platform-operator 경로를 포함하지 않는다. 진입점과 aggregate는 path item을 다시 정의하지 않고 단일 `$ref`로만 연결한다. TypeScript 계약은 기능별 원본에서 생성하며 수동 편집하지 않는다.

## API URL 정본 문법

- 인증된 일반 사용자 API는 `/api/v1/consumers/**`, 매장 운영자 API는 `/api/v1/store-operators/**`, 플랫폼 운영자 API는 `/api/v1/platform-operators/**`, 공개 매장 API는 `/api/v1/stores/**`를 사용한다.
- 로그인 주체가 소유한 예약·픽업·결제·알림은 `/consumers/me/**` 아래에 둔다. 인증 쿠키 범위를 분리하는 계정 lifecycle은 `/auth`를 유지한다.
- 고정 segment는 lowercase kebab-case를 사용한다. `POST`로 생성하는 상태 전이·사건은 `publications`, `publication-cancellations`, `retirements`, `cancellations`, `calls`, `arrivals`, `check-ins`처럼 복수 명사로 표현한다.
- `visibility`, `selling-status`, `end-at`, `deactivation-impact`처럼 하나의 값·projection을 나타내는 segment와 `portone` 같은 provider segment는 단수일 수 있다. 동사를 endpoint 끝에 두는 명령형 URL은 추가하지 않는다.
- 아직 Spring route가 없는 계약 우선 path item은 `x-miriyum-runtime-status: contract-only`와 양수 `x-miriyum-owner-issue`를 함께 선언한다. 구현이 추가되는 PR에서 두 필드를 제거한다.

## MySQL 원본과 방식 A

활성 업무 데이터의 원본은 하나의 MySQL이다. Spring Data JPA와 repository 경계의 좁은 명시적 SQL로 접근하고 Flyway로 스키마 이력을 관리한다. 캐시, SSE, 객체 저장소와 외부 제공자의 성공은 MySQL 계정·권한·예약·수량·결제 원장을 대체하지 않는다.

트랜잭션은 하나의 Service 유스케이스에 둔다. 함께 성공하거나 실패해야 하는 변경은 한 트랜잭션으로 묶고 외부 네트워크 호출, 사용자 대기와 긴 계산을 DB 트랜잭션 안에 유지하지 않는다.

## 계정 물리 분리 계약

| 계정 유형 | 테이블 | PK | principal | 토큰 namespace |
|---|---|---|---|---|
| 일반 사용자 | `consumer_accounts` | `consumer_account_id` | `consumer:{id}` | `consumer` |
| 매장 운영자 | `store_operator_accounts` | `store_operator_account_id` | `store-operator:{id}` | `store-operator` |
| 플랫폼 운영자 | `platform_operator_accounts` | `platform_operator_account_id` | `platform-operator:{id}` | `platform-operator` |

- 공통 계정 행과 역할 열로 세 유형을 합치지 않는다.
- 감사·멱등·외부 참조에는 계정 유형과 해당 PK를 함께 기록한다.
- 이메일·휴대전화·외부 로그인 식별자의 일치는 교차 계정 병합·전환·승격 근거가 아니다.
- 매장 명령은 매장 운영자 계정 상태, 대상 매장의 `store_operator_account_id` 일치와 승인·운영 상태를 MySQL에서 함께 검증한다.
- API가 받은 역할 값이나 다른 namespace의 JWT를 권한 근거로 사용하지 않는다.
- 회원가입·로그인·재발급·로그아웃 API는 계정 유형별 진입 경로와 스키마를 사용하고 요청의 `role`·`accountType`으로 계정 유형을 선택하거나 변경하지 않는다.
- 예약·예약 결합 메뉴 홀드·사용자 픽업 예약은 `consumer_account_id`를 참조하고, 1차 MVP의 `stores`는 `store_operator_account_id`를 직접 참조한다. 범용 `user_id` FK는 사용하지 않는다.
- `1차 MVP` Flyway에는 `consumer_accounts`와 `store_operator_accounts`만 포함한다. `platform_operator_accounts`와 해당 API는 `고도화`에서 다른 계정 테이블의 역할 열이나 PK를 변경하지 않고 추가한다.

## 단계별 인증 전달 계약

`1차 MVP`의 Access JWT와 Refresh JWT는 서버 정상 목록·폐기 목록을 저장하지 않는다. 두 토큰은 종류·서명·만료·발급자·대상·계정 namespace를 검증하고, Refresh JWT는 같은 계정 유형의 토큰만 갱신한다. Access JWT 유효기간은 발급 시각부터 1시간, Refresh JWT 유효기간은 발급 시각부터 14일이며 1차 MVP의 일반 사용자·매장 운영자와 모든 런타임 프로필에 같은 값을 적용한다. 고도화에서 플랫폼 운영자 인증을 추가할 때도 같은 기본 수명을 적용한다.

로그인·재발급 성공 응답은 Access JWT를 응답 본문으로 전달하고 프런트엔드는 shell별 메모리에만 보관한다. 보호 API는 `Authorization: Bearer` 헤더를 사용한다. Refresh JWT는 계정 namespace별로 이름과 경로가 분리된 `HttpOnly`, `Secure`, `SameSite=Lax` 쿠키로만 전달하며 응답 본문이나 Web Storage에 원문을 노출하지 않는다. 토큰 재발급은 동일 Origin의 `POST` JSON 요청과 `Origin`·`Referer` 검증을 요구하고, 로그아웃에는 Spring Security CSRF 보호를 적용한다. 상세 계약과 인수 조건은 [1차 MVP 공통 명세 D-003](specs/mvp1-common/spec.md#d-003-브라우저-토큰-전달저장과-csrf-경계)을 따른다.

`고도화`에서는 Refresh Token 원문이 아닌 해시와 계정·로그인 단위·토큰 계열·만료·폐기·교체 상태를 Valkey에 둔다. 회전·폐기·재사용 탐지와 로그인 단위 종료는 원자적으로 처리한다. Valkey 장애 중 로그인·갱신·로그아웃은 실패 폐쇄하고, 기존 Access JWT는 현재 계정·권한 검증 경계를 통과한 경우에만 처리한다.

프런트엔드는 shell별로 확정된 namespace와 전달 방식을 사용하고 토큰을 읽어 계정 유형을 자가 승격하지 않는다.

## 예약 자원·수량 원장

예약 자원은 매장 날짜·시간대별 `전체 예약 가능 인원`과 `전체 예약 가능 팀 수`다. 한 예약은 겹치는 모든 시간대에서 요청 인원과 팀 1건을 함께 소비한다. 두 값 가운데 하나라도 부족하면 전체 요청을 실패시킨다. 개별 테이블·배치 식별자는 자원 계약이나 응답에 포함하지 않는다.

`예약+메뉴 홀드`는 메뉴를 건너뛰면 예약 자원만, 메뉴를 선택하면 모든 예약 자원과 메뉴 수량을 한 MySQL 트랜잭션에서 확정한다. 일부 시간대나 일부 메뉴만 성공한 상태를 남기지 않는다. 업종과 무관하게 `pickupEnabled`인 승인·영업 중 매장의 픽업 예약은 날짜·픽업 시간대·메뉴·수량만 소비하고 예약 인원·팀 수를 차감하지 않는다.

방문 완료는 Reservation과 연결 MenuHold의 상태만 FULFILLED로 종결한다. 예약 수용량, allocation, 메뉴 재고와 수량 원장은 정상 이행 이력으로 보존하며 조회·복구·삭제하지 않는다.

원장은 다음을 지킨다.

- DB 유일 제약과 비음수 조건을 최종 방어선으로 사용한다.
- 현재 버전·상태·수량 조건부 SQL을 우선하고 여러 행은 고정된 순서로 잠근다.
- 요청 멱등 키, 소유 계정 유형·PK, 정책·자원 버전과 결과를 기록한다.
- 교착·버전 충돌은 같은 명령의 안전한 재실행이 가능한 경우에만 제한 재시도한다.
- 결과 불명에는 새 성공을 추측하지 않고 중앙 상태를 조회·격리·대사한다.
- 모드 변경은 효력 이후 신규 요청에만 적용하고 기존 확정 건의 스냅샷을 유지한다.

## 멱등성과 오류 응답

재전송 가능한 상태 변경은 클라이언트 멱등 키 또는 도메인 고유 요청 식별자를 검증한다. 요청 지문은 HTTP method, 정규화된 route, 실제 path parameter, 승인된 query와 body field를 포함한다. 동일 주체·명령·키·전체 요청 지문의 재전송은 업무 트랜잭션과 함께 저장한 최초 HTTP 상태·응답 코드·정규화 결과 payload를 반환하고, 다른 전체 요청 지문의 키 재사용은 거부한다. 정확한 전달 위치·보관기간은 기능 계약에서 결정한다.

예약 방문 완료의 양수 storeId·reservationId는 경로 식별자로 fingerprint에 포함한다. 비양수 ID는 COMMON_001, 양수이지만 대상 매장 범위에 없는 예약은 RESERVATION_001이며, replay 전에도 현재 대표 소유권을 다시 확인한다.

오류 응답은 기계 판독 가능한 `code`, 사용자용 `message`, 필요한 경우에만 필드별 `details`를 가진 JSON 객체다. 내부 예외·스택 추적·비밀·개인정보는 응답에 넣지 않는다. `충돌`, `가용성 부족`, `권한 없음`, `결과 불명`, `외부 의존성 실패`를 서로 다른 의미로 유지한다.

## `2차 MVP` 검색·추천 계약

검색 입력은 하나의 검색창에서 받되 `RuleInterpreter`가 정규화, 명시적 시간대·가격·승인 코드·남은 키워드를 결정적으로 해석한다. 모호한 값은 추측하지 않고 경고와 남은 키워드를 반환한다.

QueryDSL은 선택 조합과 projection을 만들고 최종 후보는 MySQL에서 읽는다. 유효한 과거 예약과 확정 메뉴 선택만 이력 신호이며 취소·실패·단순 조회와 노쇼는 선호 신호가 아니다. Java 점수 계산은 같은 스냅샷에 같은 순위·설명을 만들어야 한다.

`1차 MVP` 매장 목록·검색의 `availableOnly`·`reservationAvailability`는 기존 `ReservationService` 공개 일괄 가용성 조회 계약을 사용한다. 품절 또는 마지막 수량 경합 실패 뒤 같은 매장 메뉴를 검증하는 `2차 추천 전용` 신규 `BulkAvailabilityPort` 또는 신규 batch 계약만 1차에서 선구현하지 않으며, `2차 MVP` 진입 전 별도 contract-first Issue/PR에서 기존 1차 계약의 재사용·확장 여부와 함께 확정한다. 같은 매장 후보가 없을 때만 원 매장의 검증된 저장 좌표를 기준으로 bounding box와 Java Haversine을 적용하며, 거리가 **3km 이내**인 후보만 허용하고 3km를 초과하면 거부한다. 사용자 현재 위치는 요청·저장·사용하지 않는다. Kakao Local REST는 입점·주소 변경 시 좌표 변환 포트 뒤에서만 호출하고 추천 요청 중에는 호출하지 않는다.

AI/LLM, Spring AI, 벡터 DB와 검색 클러스터는 `2차 MVP` 계약에 없다. 정확한 사전·가중치·동률 규칙은 고정 평가셋 검증과 승인 전 추측하지 않는다.

## `고도화` 외부 어댑터 계약

### S3

사업자등록증과 공개 매장/메뉴 이미지는 목적·권한·공개 범위를 분리한다. 객체 키·무결성 값·상태·보유 정책과 접근 감사는 MySQL 메타데이터가 소유한다. 비공개 자료는 암호화하고 짧은 접근 경로만 발급한다. 업로드·삭제·메타데이터 부분 실패는 체크포인트로 대사하며 고아 객체나 누락 객체를 정상 완료로 표시하지 않는다.

### PortOne V2

`PaymentClient`가 외부 SDK·API 타입을 내부 결제 모델과 분리한다. 브라우저가 보낸 성공 상태·금액·`transactionId`는 근거가 아니다. 서버 조회 또는 서명·시각 검증을 통과한 Webhook만 내부 주문·금액·통화와 대조해 같은 멱등 확정 경로를 호출한다. 결과 불명은 격리하고 새 청구를 추측 실행하지 않는다. 정확한 헤더, 허용오차, PG와 결제수단은 계약·샌드박스 검증 전 확정하지 않는다.

### SSE와 알림

SSE 사건에는 사용자에게 허용된 최소 상태와 재연결 식별자만 포함한다. 재연결·중복·순서 역전 뒤에도 클라이언트는 MySQL 조회 상태로 수렴해야 한다. Valkey Pub/Sub은 인스턴스 간 전달 보조일 뿐 재생 원장이나 성공 근거가 아니다.

## 기능별 MySQL 내구성 작업

고도화의 알림·자동 승계·결제 복구는 업무 상태와 기능별 후속 작업을 같은 MySQL 트랜잭션에 기록한다. 작업은 유형·대상·원인 명령으로 멱등 식별하고 DB 임대·fencing·체크포인트·제한 재시도·격리와 대사를 사용한다.

이를 범용 이벤트 플랫폼이나 범용 Outbox로 부르지 않는다. Kafka·메시지 브로커는 다수 독립 소비자·장기 재생·polling 병목이 관측되기 전에는 도입하지 않는다.

## 시간·금액·API 진화

서버 시각 저장·비교는 UTC 시점을 사용하고 API에는 UTC 또는 오프셋이 분명한 ISO 8601 값을 사용한다. 금액은 부동소수점으로 계산하지 않고 통화 코드와 정밀 십진 표현을 사용한다.

API 버전은 실제 비호환 소비자 변경이 생길 때 도입한다. 미구현 URL, 물리 컬럼, 정확한 수명·재시도·허용오차를 이 문서에서 만들지 않는다. 값을 결정할 때는 소유 정책·기능 계약, 호환 전환과 검증 증거를 함께 기록한다.
