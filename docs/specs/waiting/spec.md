# 기능 명세: 웨이팅 운영 설정

> 단계: `고도화`
>
> 기능 소유자: `reservation` 도메인의 Waiting capability
>
> 계약 범위: Issue #271 매장 운영자 설정 조회·교체와 비활성화 영향 조회,
> Issue #272 중앙 원장·운영자 전이, Issue #307 계정당 활성 웨이팅 1개 제한
>
> 구현 순서: `#271 계약 + #307 계약 → #272 원장·종결 공개 계약/runtime → #271 설정 runtime`.
> 활성 대기 팀 판정과 종결의 상태 전이·실행 결과는 Issue #272가 소유한다.

이 문서는 [현장·원격 웨이팅 정책](../../service-policies/05-waiting.md),
[사용자와 권한](../../02-users-and-permissions.md),
[데이터 및 API 계약](../../07-data-and-api-contracts.md),
[공통 인증·오류 계약](../mvp1-common/spec.md)을 매장 운영자 설정 계약에 적용한다.
HTTP 형상은 같은 디렉터리의 [OpenAPI](openapi.yaml)가 소유한다.

## 현재 범위와 미래 경계

현재 고도화 범위는 매장별로 다음 세 값만 설정한다.

- 웨이팅 기능 활성 여부 `enabled`
- 접수 모드 `AUTO`, `MANUAL`, `PAUSED`
- 자동 접수를 영업 시작보다 먼저 여는 시간 `advanceOpenMinutes` (`0..180`분)

웨이팅 등록 반경은 플랫폼 공통 3km이며 이 설정으로 변경하지 않는다. 다음 항목은 미래
고도화 경계이고 현재 HTTP 필드나 경로를 만들지 않는다.

- 매장별 등록 반경 설정
- 좌석·테이블 조건과 조건별 호출 대상 선택
- 순번 미루기
- 웨이팅 등록용 현장 증명 QR

Waiting은 Reservation이 소유하는 capability다. 새 최상위 Java 도메인이나 별도 배포 단위를
만들지 않는다. 이 계약은 store-operator audience 진입점에 고도화 path를 조합하지만 1차 MVP
aggregate에는 넣지 않는다. production Java, migration, frontend 또는 생성 클라이언트는
추가하지 않는다.

## 계정당 활성 웨이팅 1개

Issue #307은 `WAIT-008`을 버전 설정형 다중 한도에서 일반 사용자 계정당 고정 1건으로
대체한다.

- 계정은 전체 매장을 합쳐 활성 웨이팅을 최대 1건만 유지한다.
- 현재 고도화 기본 범위의 대표자 관계를 계정 활성 관계로 계산한다. 향후 구성원 합류를
  활성화할 때도 대표자·구성원 역할과 관계없이 같은 계정 단위 제한을 적용한다.
- `WAIT-007`의 같은 매장 중복은 이 계정 전체 검사에 포섭한다. 생성·합류·대표자 이전은
  계정 활성 관계를 한 번만 검사·점유하고 별도 매장 단위 유일성 제약이나 오류 경로를 두지
  않는다. 같은 계정·같은 팀 관계는 멱등 결과 또는 기존 관계를 유지하고, 다른 활성 팀은
  `WAITING_011`로 거부한다.
- 다른 매장의 새 팀 생성·합류는 기존 활성 관계를 자동 취소·교체·병합하지 않고
  `409 WAITING_011 ACCOUNT_ACTIVE_WAITING_EXISTS`로 거부한다. 이 오류는 사용자가 기존
  웨이팅을 유효하게 종결해야 하며, 재조회·재시도 가능한 membership 전제 충돌
  `WAITING_008`과 의미를 공유하지 않는다.
- 같은 명령의 멱등 재전송은 최초 결과를 반환한다. 다른 멱등 키나 다른 요청 지문은 기존
  활성 웨이팅을 변경할 권한이 아니다.
- Waiting 원장이 유효한 종결을 확정하고 계정 활성 관계를 한 번 해제한 뒤에만 새 웨이팅을
  허용한다. 비종결 `RESERVATION_CONVERTING` 동안은 관계를 유지하고 전환 실패로 기존
  `WAITING`에 복귀해도 해제하지 않는다. 예약 전환 완료 등 실제 종결이 확정된 뒤에만 해제한다.
- 차단 응답은 사용자가 정리할 자신의 기존 활성 웨이팅 최소 정보만 제공하고 다른 구성원
  정보는 노출하지 않는다.
- 예약은 활성 웨이팅 수에 포함하지 않는다. 서로 다른 매장 또는 겹치지 않는 시간대의 복수
  예약 허용 계약을 변경하지 않는다.

이 계약 PR은 일반 사용자 웨이팅 생성·합류 HTTP path를 새로 만들지 않는다. 해당 path는
별도 소유 Issue에서 활성화할 때 계정 중복 전용 `WAITING_011` response를 연결한다. 현재
OpenAPI의 store-operator path와 공용 `WaitingLedgerConflict`에는 `WAITING_011`이나 계정 중복
예시를 노출하지 않고, `WAITING_008`은 기존 활성 membership 전제 충돌 의미를 유지한다.
`WAITING_011`은 응답 `code`의 wire 값이고 `ACCOUNT_ACTIVE_WAITING_EXISTS`는 서버 오류 식별자 이름이다.
향후 consumer operation도 기존 오류 응답과 같이 `code`, `message` 두 필드만 반환하며 별도
`errorKey`나 `details` 필드를 추가하지 않는다.

### #272 runtime·migration 인계

현재 `dev`의 V36은 `waiting_active_memberships`에
`uk_waiting_active_memberships_store_consumer UNIQUE (store_id, consumer_account_id)`를 두므로
계정 전체 1건 계약과 다르다. #272는 기존 V36을 수정하지 않고 V42 forward migration에서 이
제약을 제거한 뒤 `UNIQUE (consumer_account_id)`로 교체한다.

V42의 제약 변경 전에 다음 사전 대사를 수행한다.

```sql
SELECT consumer_account_id, COUNT(*) AS active_membership_count
FROM waiting_active_memberships
GROUP BY consumer_account_id
HAVING COUNT(*) > 1;
```

- 결과가 1건이라도 있으면 migration과 배포를 차단하고 데이터와 기존 제약을 그대로 유지한다.
- 중복 관계를 자동 취소·삭제·병합하지 않는다. Waiting 소유자가 원장·감사 기록을 확인하고
  도메인의 유효한 종결 operation으로 계정당 활성 관계가 1건 이하가 되도록 처리한다.
- 같은 사전 대사를 다시 실행해 초과 계정 0건을 재확인한 뒤에만 기존 제약을 제거하고
  `UNIQUE (consumer_account_id)`를 추가한다.

#272의 production Java·forward migration은 이 계약 PR이 `dev`에 병합된 뒤 정확한 허용
목록과 실제 MySQL 동시성 검증으로 정렬한다.

## 안전한 기본값과 조회

매장 설정 행이 아직 없더라도 두 GET은 오류나 `null` 설정을 반환하지 않는다. 설정 조회는
다음 안전한 값을 `200`으로 반환한다.

| 필드 | 기본값 |
|---|---|
| `enabled` | `false` |
| `receptionMode` | `PAUSED` |
| `advanceOpenMinutes` | `60` |
| `version` | `0` |

비활성화 영향 조회도 설정 행이 없으면 `version=0`을 사용한다. `activeTeamCount`는 조회
시점의 `WAITING`, `CALLED`, `ARRIVED`, 비종결 `RESERVATION_CONVERTING` 팀 수다. 종결 가능성
판정은 Issue #272가 `202 Accepted`, 작업 식별자와 상태 조회 계약/runtime을 `dev`에 제공할 때
일괄 종결 action과 함께 추가한다. 그 전에는 응답에 노출하지 않는다. 설정 행의 부재를 매장
부재로 해석하지 않는다.

## 전체 교체와 버전

`PUT /api/v1/store-operators/stores/{storeId}/waiting-settings`는 부분 변경이 아니라 전체
교체다. 요청은 `expectedVersion`, `enabled`, `receptionMode`, `advanceOpenMinutes`를 모두
포함하고, 비활성화할 때만 `disableAction`을 선택적으로 포함한다.

- `expectedVersion`은 조회한 현재 `version`과 정확히 같아야 한다.
- 설정 행이 없을 때의 현재 버전은 `0`이다. 최초 성공 요청은 버전 `1`을 만든다.
- 새 멱등 명령이 성공할 때마다 버전은 현재 값에서 정확히 1 증가한다.
- 버전은 단조 증가하며 삭제하거나 재사용하지 않는다.
- 오래된 `expectedVersion`은 변경 없이 `409 WAITING_001`을 반환한다.
- 멱등 재전송은 최초 결과를 재생하며 버전을 다시 증가시키지 않는다.

## 설정 일관성

- `enabled=false`이면 `receptionMode`는 반드시 `PAUSED`다. `AUTO` 또는 `MANUAL`과 함께
  보내면 `400 COMMON_001`이다.
- `enabled=true`이면 `AUTO`, `MANUAL`, `PAUSED`를 모두 선택할 수 있다. `PAUSED`는 기능을
  유지하면서 신규 접수만 일시중지한다.
- `advanceOpenMinutes`는 모든 모드에서 `0..180`이며, `AUTO`가 아닌 동안에도 다음 전체
  교체를 위해 값을 보존한다.
- `disableAction`은 `enabled=false` 요청에서만 허용한다. 활성화 요청에 포함하면
  `400 COMMON_001`이다.

## 비활성화와 활성 팀

Frontend는 비활성화 전에 `GET .../deactivation-impact`로 현재 버전과 활성 팀 수를 확인할 수
있다. 실제 명령 시점에 활성 팀이 있으면 `disableAction`이 반드시 필요하다. 영향 조회와
명령 사이의 경합은 PUT이 다시 판정한다.

- `disableAction`이 없으면 설정을 바꾸지 않고 `409 WAITING_002`를 반환한다.
- `KEEP_ACTIVE`는 설정을 `enabled=false`, `receptionMode=PAUSED`로 교체해 신규 등록을 막되
  기존 활성 팀을 그대로 유지한다. 기존 팀의 조회·호출·정상 종결 경로는 계속 사용할 수
  있어야 한다.
- 현재 공개 계약에서 `disableAction`은 `KEEP_ACTIVE`만 허용한다. 활성 팀 일괄 종결은
  Issue #272가 `202 Accepted`, 작업 식별자와 상태 조회 계약/runtime을 `dev`에 제공한 뒤
  이 요청 계약에 추가한다. 그 전에는 일괄 종결 action을 공개 입력으로 노출하지 않는다.

활성 팀이 없으면 `disableAction` 없이 비활성화할 수 있다. `disableAction`이 제공된 경우에도
서버는 명령 시점의 활성 팀과 권한을 다시 확인한다. `RESERVATION_CONVERTING`도 활성 팀이므로
한 건이라도 있으면 `disableAction` 없이 비활성화하지 않고 `409 WAITING_002`를 반환한다.

## 멱등성

PUT은 매장 운영자 Bearer 인증과 공통 `Idempotency-Key`를 요구한다. 요청 지문은 인증 주체,
HTTP method, 정규화된 route, 실제 `storeId`, 그리고 `disableAction`을 포함한 전체 JSON
body로 만든다.

- 같은 주체·키·지문의 재전송은 최초 HTTP 상태, 오류 코드와 정규화된 payload를 반환한다.
- 필수 `Idempotency-Key`가 없으면 `400 COMMON_003`, UUID 형식이나 길이가 잘못되면
  `400 COMMON_004`이며 업무 로직과 멱등 저장소에 진입하지 않는다.
- 같은 키를 다른 지문에 재사용하면 `409 COMMON_007`이며 설정이나 버전을 바꾸지 않는다.
- 조건부 버전 갱신의 동시 충돌은 새 성공을 추측하지 않고 `409 COMMON_008`로 반환한다.
- 재생 전에도 현재 인증과 매장 권한을 다시 검증해 과거 응답이 다른 운영자에게 노출되지
  않게 한다.

## Store 공개 권한 의존성

Waiting은 인증된 매장 운영자 ID와 `StoreId`를 Store 도메인의 최소 공개 Service 권한
계약에 전달하고, 공개 DTO 결과만 사용해 대표 운영자 권한과 요청 가능한 매장 상태를
검증한다. Reservation/Waiting 코드는 Store의 Entity나 Repository를 직접 참조하거나
Store 테이블을 우회 조회하지 않는다.

판정 순서와 허용 상태는 다음과 같다.

1. 유효한 `store-operator` Access JWT와 현재 계정을 확인한다. 계정이 존재하지만 현재 상태가
   이용을 허용하지 않으면 `403 AUTH_011`이다.
2. 대상 매장의 현재 대표 운영자 소유권을 확인한다.
3. 두 GET은 `verificationStatus=APPROVED`인 매장의 `operationStatus=OPEN`,
   `TEMPORARILY_CLOSED`, `CLOSED`를 모두 허용한다. 폐점 뒤에도 저장된 설정과 남은 활성 팀
   영향을 안전하게 확인할 수 있지만 변경하지는 않는다.
4. PUT은 `verificationStatus=APPROVED`이면서 `operationStatus=OPEN` 또는
   `TEMPORARILY_CLOSED`일 때만 허용한다. `CLOSED`이면 `409 STORE_005`이며 설정과 버전을
   바꾸지 않는다.

입점 검증 상태를 `APPROVED`로 확인할 수 없으면 GET과 PUT 모두 `409 STORE_007`로 실패
폐쇄한다. 이는 Store 정본의 두 상태 축과 기존 오류를 사용하며 Waiting 전용 Store 상태나
오류 코드를 만들지 않는다.

매장은 공개 탐색으로 존재를 확인할 수 있는 자원이므로 [공통 인증·오류 계약](../mvp1-common/spec.md)의
authorized enumeration 정책을 따른다. 매장이 존재하지만 인증된 운영자가 현재 대표 운영자가
아니면 거짓 `404`로 숨기지 않고 `403 STORE_003`을 반환한다. `404 STORE_001`은 실제 매장을
찾을 수 없을 때만 반환한다. 어느 응답도 대표 운영자의 식별자나 비공개 매장 상태를 본문에
넣지 않는다.

## 오류 계약

| HTTP | code | 의미 |
|---|---|---|
| `400` | `COMMON_001` | 필수 필드, 숫자 범위 또는 필드 간 일관성 검증 실패 |
| `400` | `COMMON_002` | 잘못된 JSON·타입·enum 또는 알 수 없는 필드로 본문을 읽을 수 없음 |
| `400` | `COMMON_003` | 필수 `Idempotency-Key` 헤더 누락 |
| `400` | `COMMON_004` | `Idempotency-Key` UUID 형식 또는 길이 오류 |
| `401` | `AUTH_001` | 유효한 store-operator Access JWT가 없음 |
| `403` | `AUTH_011` | 현재 매장 운영자 계정 상태가 이용을 허용하지 않음 |
| `403` | `STORE_003` | 존재하는 대상 매장의 현재 대표 운영자가 아님 |
| `404` | `STORE_001` | 대상 매장이 실제로 존재하지 않음 |
| `409` | `STORE_005` | `CLOSED` 매장에서 설정 교체를 요청함 |
| `409` | `STORE_007` | 대상 매장의 입점 검증 상태를 `APPROVED`로 확인할 수 없음 |
| `409` | `WAITING_001` | `expectedVersion`이 현재 설정 버전과 다름 |
| `409` | `WAITING_002` | 활성 팀이 있어 비활성화 action이 필요함 |
| `409` | `COMMON_007` | 멱등 키를 다른 요청 지문에 재사용함 |
| `409` | `COMMON_008` | 동시 조건부 변경 충돌 |
| `429` | `COMMON_010` | 요청 제한 초과 |

오류 본문은 공통 `ErrorResponse`를 사용한다. `COMMON_001`은 안전한 공개 필드별 `details`를
포함할 수 있고, `COMMON_002`는 원본 입력값 없이 안전하게 특정 가능한 JSON 필드 경로만
포함할 수 있다. 내부 소유자 식별자, 비공개 Entity 상태나 예외를 응답에 노출하지 않는다.

## Frontend handoff

- 화면 진입 시 설정 GET을 호출하고 받은 `version`을 다음 PUT의 `expectedVersion`으로
  사용한다.
- 비활성화 전에 영향 GET을 호출한다. `activeTeamCount>0`이면 기존 팀 유지에 동의한 경우에만
  `KEEP_ACTIVE`를 명시한다. 일괄 종결 선택은 Issue #272의 실행 계약이 제공되기 전에는
  노출하지 않는다.
- `WAITING_001`이면 최신 설정을 다시 조회하고 사용자 입력을 보존한 채 재확인을 요청한다.
- `WAITING_002`이면 비활성화 영향을 다시 조회하고 action 선택 화면으로 돌아간다.
- `COMMON_001`은 필드 검증 안내, `COMMON_002`는 요청 직렬화 오류로 처리한다.
  `COMMON_003`·`COMMON_004`는 client 요청 구성 오류이므로 새 멱등 키를 임의 생성해 자동
  재시도하지 않는다.
- `AUTH_011`은 계정 제한 상태, `STORE_003`은 현재 대표 운영자 소유권 실패로 구분한다.
- `STORE_005`이면 폐점 매장의 설정 변경 UI를 비활성화하고, `STORE_007`이면 입점 검증 상태를
  새로 확인하기 전 설정 조회·변경 성공을 추측하지 않는다.
- `KEEP_ACTIVE` 성공은 기존 팀이 유지되고 신규 등록만 차단된 상태로 표시한다.
- 활성 팀 일괄 종결의 팀별 진행·결과 UI는 Issue #272의 실행 계약을 받은 뒤 구현한다.
- Client는 `message` 문자열이 아니라 HTTP 상태와 `code`로 분기하고 `401`, `403`, `404`,
  `409`, `429`를 서로 다른 상태로 처리한다.

두 path는 `store-operator-openapi.yaml`에 post-MVP1/high-level audience 계약으로 연결되고
`mvp1-openapi.yaml`에서는 제외된다. 현재 작업은 frontend나 생성 클라이언트를 만들지 않는다.
후속 contract-first 구현은 audience 진입점으로 노출 범위를 확인하되 TypeScript 생성 입력은
기능 원본인 이 디렉터리의 `openapi.yaml`을 사용하고, 1차 MVP aggregate에서 생성하지 않는다.

## 웨이팅 원장과 운영자 전이 (Issue #272)

`고도화` Waiting 원장은 매장과 KST 영업일별로 하나의 중앙 FIFO `queueSequence`를 부여한다.
활성 membership은 일반 사용자 계정당 전체 매장을 합쳐 하나만 유지하며, 동일 팀도 중복 활성
등록을 허용하지 않는다. 목록은 항상 `(queueSequence, waitingTeamId)` 오름차순으로 정렬한다.
cursor는 이 복합 키를 담은 opaque 값이며, 다음 페이지는 직전 cursor보다 큰 복합 키부터
시작한다.

매장 운영자는 다음 post-MVP1 store-operator 경로만 사용한다. 이 일곱 경로는
`store-operator-openapi.yaml`에만 연결하며 `mvp1-openapi.yaml`에는 절대 추가하지 않는다.

| 경로 | method | 계약 |
|---|---:|---|
| `/stores/{storeId}/waiting-teams` | `GET` | FIFO 목록과 cursor 조회 |
| `/stores/{storeId}/waiting-teams/{waitingTeamId}` | `GET` | 팀 원장 상세 조회 |
| `/stores/{storeId}/waiting-teams/{waitingTeamId}/calls` | `POST` | FIFO 선두 `WAITING` 팀 호출 |
| `/stores/{storeId}/waiting-teams/{waitingTeamId}/arrivals` | `POST` | `CALLED` 팀 도착 처리 |
| `/stores/{storeId}/waiting-teams/{waitingTeamId}/check-ins` | `POST` | `ARRIVED` 팀 입장 처리 |
| `/stores/{storeId}/waiting-teams/{waitingTeamId}/cancellations` | `POST` | 활성 팀 취소 처리 |
| `/stores/{storeId}/waiting-closure-jobs/{jobId}` | `GET` | 활성 팀 종결 작업 조회 |

두 조회와 모든 명령은 store-operator Bearer 인증 및 기존 Store 공개 권한 판정을 요구한다.
모든 `POST`는 공통 `Idempotency-Key`와 대상 팀의 `expectedVersion`을 요구한다. 같은
주체·명령·키·전체 요청 지문의 재전송은 최초 결과를 반환하고, 다른 지문으로 같은 키를
재사용하면 `COMMON_007`이다. 조건부 version 충돌은 `COMMON_008`이다.

### 상태와 전이

원장 상태 enum은 `WAITING`, `CALLED`, `ARRIVED`, `CHECKED_IN`, `CANCELLED`, `NO_SHOW`,
`CLOSED_BY_STORE`, `RESERVATION_CONVERTING`, `RESERVATION_CONVERTED`로 고정한다. `CHECKED_IN`,
`CANCELLED`, `NO_SHOW`, `CLOSED_BY_STORE`, `RESERVATION_CONVERTED`는 종결 상태다.
`RESERVATION_CONVERTING`은 예약 선점·결제 결과를 기다리는 비종결 상태이며 계정 활성
membership을 유지한다. 전환 실패로 `WAITING`에 복귀해도 같은 활성 membership을 유지한다.

Waiting 원장은 전환 시작 시각 `reservationConvertingAt`, Payment 공개 ID 문자열
`waitingPaymentId`, 최종 Reservation 양의 scalar ID `reservationReferenceId`, 완료 시각
`reservationConvertedAt`만 소유한다. Reservation Entity·Repository·JPA 연관관계·외래 키를
참조하지 않는다. 완료 시 활성 membership 삭제는 application service가 원자적으로 조정하며
entity 전이 자체의 부작용이 아니다.

| 현재 상태 | 허용 운영자 명령 | 다음 상태 | 조건 |
|---|---|---|---|
| `WAITING` | call | `CALLED` | 같은 매장·KST 영업일 활성 큐의 FIFO 선두 |
| `WAITING` | cancel | `CANCELLED` | 현재 version 일치 |
| `CALLED` | arrive | `ARRIVED` | 현재 version 일치 |
| `CALLED` | cancel | `CANCELLED` | 현재 version 일치 |
| `ARRIVED` | check-in | `CHECKED_IN` | 현재 version 일치 |
| `ARRIVED` | cancel | `CANCELLED` | 현재 version 일치 |
| `WAITING` | 예약 전환 시작 | `RESERVATION_CONVERTING` | 현재 version, Payment 공개 ID, 발생 시각 일치; 활성 membership 유지 |
| `RESERVATION_CONVERTING` | 예약 전환 실패 | `WAITING` | 현재 version과 같은 Payment 공개 ID 일치; 시도 필드를 지우고 활성 membership 유지 |
| `RESERVATION_CONVERTING` | 예약 전환 완료 | `RESERVATION_CONVERTED` | 현재 version과 같은 Payment 공개 ID 일치; 양의 최종 Reservation scalar ID와 완료 시각 기록 |
| `RESERVATION_CONVERTING` | cancel | `CANCELLED` | 현재 version 일치, 예약 선점·결제 성공·매장 종료와 경합 시 먼저 확정된 결과 하나만 유지하고 필요한 보상 후속 작업 기록 |
| 종결 상태 | 없음 | 없음 | 새 전이는 거부 |

`RESERVATION_CONVERTING`에서는 cancel 외 call·arrive·check-in을 `409 WAITING_006`으로 거부한다.
전환 중 cancel 또는 매장 종료가 먼저 확정되면 `waitingPaymentId`와
`reservationConvertingAt`을 보존하고 최종 Reservation 참조는 설정하지 않아, 검증된 paid
callback이 후속 보상을 식별할 수 있게 한다.
명령은 다른 매장의 팀을 읽거나 전이할 수 없고, stale version·비선두 call·이미 종결된 팀은
성공으로 추측하지 않는다. 구현은 한 유효 전이만 상태·감사·공개 상태 사건을 만들고, 전환 중
취소가 먼저 확정되면 예약 선점 해제 또는 뒤늦은 결제 승인 취소·환불 후속 작업을 기록하도록
조건부 version과 활성 membership 제약을 함께 사용한다.

### 운영자 응답 개인정보 경계

목록 item은 `waitingTeamId`, `status`, `queueSequence`, `partySize`, `createdAt`, `version`만
노출한다. 상세는 여기에 `storeId`와 상태별 시각(`calledAt`, `arrivedAt`, `checkedInAt`,
`cancelledAt`)만 추가한다. 어떤 운영자 목록·상세·종결 작업 응답에도 consumer ID, 계정 ID,
전화번호, 좌표, 원본 접수 식별자 또는 멱등 키를 넣지 않는다.

### 활성 팀 종결 작업과 #271 경계

`PENDING`, `PROCESSING`, `COMPLETED`, `RECONCILIATION_REQUIRED`는 종결 작업 상태 enum이다.
작업 조회는 대상 snapshot의 총 팀 수와 completed·failed·reconciliation-required 팀 수를
반환한다. 실패 또는 결과 불명은 성공으로 추측하지 않고 `RECONCILIATION_REQUIRED`와 대사
필요 수로 남긴다.

Issue #271은 설정 `PUT`, 비활성화 intent 및 해당 명령의 `202 Accepted`/작업 생성 계약을
소유한다. Issue #272는 생성된 `waiting-closure-jobs/{jobId}`의 조회, 원장 전이와 작업 실행만
소유한다. 이 원장 계약은 #271의 settings path, `WaitingDisableAction`, 설정 version 또는
`202` 응답을 다시 정의하거나 변경하지 않는다.

### 오류 계약

| HTTP | code | 의미 |
|---:|---|---|
| `404` | `WAITING_003` | 대상 매장 범위의 웨이팅 팀을 찾을 수 없음 |
| `404` | `WAITING_004` | 대상 매장 범위의 웨이팅 종결 작업을 찾을 수 없음 |
| `409` | `WAITING_005` | 대상 팀 version이 `expectedVersion`과 다름 |
| `409` | `WAITING_006` | 현재 상태에서 요청한 전이가 허용되지 않음 |
| `409` | `WAITING_007` | call 대상이 활성 FIFO의 선두가 아님 |
| `409` | `WAITING_008` | 활성 membership의 현재 상태와 요청 전제가 충돌함 |
| `409` | `WAITING_009` | 종결 작업이 아직 완료되지 않았거나 대사가 필요함 |
| `409` | `WAITING_010` | 종결 작업 대상 처리 중 실패가 발생함 |

`409 WAITING_011 ACCOUNT_ACTIVE_WAITING_EXISTS`는 향후 일반 사용자 생성·합류 요청에서
계정에 이미 활성 웨이팅이 있을 때만 사용한다. 현재 표의 store-operator ledger operation에는
노출하지 않으며 해당 consumer path를 소유한 Issue가 별도 response로 연결한다.

기존 `COMMON_001`~`COMMON_004`, `AUTH_001`, `AUTH_011`, `STORE_001`, `STORE_003`,
`STORE_005`, `STORE_007`, `COMMON_007`, `COMMON_008`, `COMMON_010`의 의미는 변경하지
않는다. 각 ledger operation의 response status 집합은 `200`, `400`, `401`, `403`, `404`,
`409`, `429`로 고정한다.

### Reservation conversion compensation runtime

When a paid waiting conversion loses to cancellation or store closure, Waiting records one durable
compensation item for the `(waitingTeamId, paymentId)` pair. The item preserves the refund amount,
currency, policy version, deterministic source event, normalized UUID idempotency key, and reason;
an exact retry replays the existing item, while conflicting immutable input is rejected.

Workers claim due items with `FOR UPDATE SKIP LOCKED`. Each claim has a lease owner, expiry, and a
monotonically increasing fencing token. An expired lease may be reclaimed, and a stale owner/token
cannot complete, requeue, or reconcile the reclaimed item. Failed results use a bounded three-attempt
policy; exhausted or ambiguous work is terminally marked `RECONCILIATION_REQUIRED`.

The provider call is made outside the Waiting database transaction and only through
`PaymentService.requestRefund(RequestRefundCommand)`. Only `RefundStatus.COMPLETED` completes the
compensation. Payment reconciliation or an unknown provider outcome maps to compensation
reconciliation. `SERVICE_UNAVAILABLE` and `CONCURRENT_MODIFICATION` failures consume the bounded
retry budget; other service errors reconcile immediately. Every processing failure emits the
structured `waiting_conversion_compensation_failed` event, and a positive reconciliation backlog
emits the identifier-free `waiting_conversion_compensation_reconciliation_required` aggregate
event every 60000 ms.

The compensation runner is enabled when its property is absent and can be disabled with
`MIRIYUM_WAITING_COMPENSATION_ENABLED=false`. `application.yml`, the production environment
example, and production Compose expose the enable switch, the 5000 ms initial/fixed delays, and the
60000 ms reconciliation observation delay. Both jobs run on a dedicated single-thread scheduler
that is not a default candidate and interrupts work on application shutdown.

### Internal reservation conversion orchestration

`WaitingReservationConversionService` is an internal boundary; it does not add a Waiting HTTP API.
`begin` performs a short team/consumer preflight transaction, suspends any ambient caller
transaction while `PaymentService.prepareWaitingReservationDeposit` independently commits the
Payment source, and then uses another short transaction to lock the team for
`WAITING -> RESERVATION_CONVERTING`. Consequently an outer caller rollback cannot leave a committed
converting team without its matching Payment row. A matching `fail` locks the team and returns only
`RESERVATION_CONVERTING -> WAITING`; it retains the active membership. Both transitions append one
SYSTEM audit and public status event.

`completeVerified` first locks the Waiting team and, in that same transaction, locks and verifies the
Payment row. The global lock order is Waiting then Payment. A matching conversion may become
`RESERVATION_CONVERTED` only while Payment is currently `PAID` and its refund ledger is empty,
including no in-flight `PROCESSING` refund; it then removes exactly one active membership and records
only the caller-supplied positive Reservation scalar ID. Exact replay of the same payment/final
scalar returns before Payment access and has no further membership, audit, or event effect. Waiting
never imports or accesses a Reservation Entity, Repository, aggregate, or migration.

Operator cancellation and claimed store closure serialize with completion on the same Waiting team
row. The first terminal transition wins. If cancellation or closure wins, the preserved payment
identity and verified historical-paid snapshot produce one deterministic compensation item with
reason `WAITING_CANCELLED` or `WAITING_CLOSED_BY_STORE`; callback replay converges on that same item
without changing the terminal version or final Reservation reference. Terminal compensation uses
the locked historical-paid verifier, so a paid payment that has since been partially or fully
refunded can still converge without weakening the stricter new-conversion rule.
