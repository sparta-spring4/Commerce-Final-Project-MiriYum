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

Issue #271 설정 Runtime은 설정 저장·조회·version 조건부 전체 교체와 비활성화 closure
연계를 소유한다. Issue #380 AUTO 오픈 Runtime은 아래의 영속 작업·접수 원장과 CAS로 이
설정을 소비한다. 자동 작업은 조회 predicate의 boolean 결과만으로 접수를 열지 않는다.

## AUTO 접수 오픈 Runtime (Issue #380)

AUTO planner는 현재 `enabled=true`, `receptionMode=AUTO`인 설정과 Store/Schedule 공개
영업 구간 DTO를 읽어 `waiting_auto_open_jobs`에 작업을 만든다. 작업의 논리 식별자는
`storeId + businessIntervalKey + expectedSettingsVersion`이며 SHA-256 멱등 키와 DB unique
제약으로 중복 계획을 흡수한다. 작업에는 다음 값이 불변 snapshot으로 남는다.

- `storeId`, Schedule이 발급한 opaque `businessIntervalKey`, 현지 `businessDate`
- 영업 구간 UTC 시작·종료와 `scheduledAt = startsAt - advanceOpenMinutes`
- `expectedSettingsVersion`, `expectedAdvanceOpenMinutes`, 멱등 키

worker는 기본적으로 꺼져 있다. `MIRIYUM_WAITING_AUTO_OPEN_ENABLED=true`와 worker ID,
계획 horizon·batch, claim batch, lease, 재시도, poll 값을 모두 명시한 배포에서만 켠다.
여러 worker는 MySQL `FOR UPDATE SKIP LOCKED`로 due 작업을 claim한다. claim마다 attempt와
단조 증가 fencing token을 올리고 lease owner/만료를 기록한다. 만료된 lease는 다른 worker가
더 높은 token으로 회수할 수 있고, 이전 owner/token은 완료·실패 기록을 쓰지 못한다.
각 poll은 planning 직전과 claim 직전에 현재 시각을 별도로 읽고, batch 안의 각 작업도 execute
직전과 failure 기록 직전에 현재 시각을 다시 읽는다. 따라서 앞 작업이 오래 걸려도 뒤 작업은
과거 lease·영업 구간 시각으로 실행되지 않는다.

실제 OPEN 효과의 직렬화 순서는 하나의 짧은 `READ_COMMITTED` 트랜잭션 안에서 다음과 같다.

1. Store 공개 Service를 통한 Store 행 잠금
2. Schedule 공개 Service 내부의 현재 schedule state 잠금과 영업 구간 재구성
3. 작업 행 잠금과 owner·fencing token·lease 재검증
4. `waiting_settings`의 `enabled=true`, `AUTO`, settings version, 사전 오픈 분 조건부 CAS
5. immutable `waiting_reception_windows` 한 건 삽입
6. 작업 `COMPLETED`

CAS는 내부 JPA `lock_version`만 증가시키고 공개 settings `version`은 바꾸지 않는다. CAS가
0건이면 작업을 `INVALIDATED`로 닫고 접수 원장을 만들지 않는다. window unique 제약은
재시도·중복 실행의 마지막 방어선이다. window 삽입 또는 작업 완료가 실패하면 CAS를 포함한
전체 트랜잭션이 롤백된다. 따라서 check 직후 설정이 바뀌어도 오래된 작업은 OPEN 효과를
확정할 수 없다.

설정 비활성화, `MANUAL`/`PAUSED` 전환, settings version 또는 `advanceOpenMinutes` 변경은
미claim 이전 작업을 제한 batch로 선제 무효화한다. 이미 claim된 작업도 실행 CAS가 같은
조건을 다시 검사해 실패 폐쇄한다. Store 부적격, schedule 교체, 정기 휴무, 영업 구간
key/경계 변경도 현재 구간 재구성 실패로 무효화한다. 임시휴점은 영업 구간 identity를
분할하거나 제거하지 않고 `[startAt, endAt)`에 현재 활성일 때만 잠금 조회를 실패 폐쇄하며,
정확한 `endAt`부터 같은 구간을 다시 사용할 수 있다. 그 때문에 정확히 같은 snapshot의
`INVALIDATED/STALE_INTERVAL` 작업만 attempt·lease·failure를 초기화하고 fencing token을
증가시켜 `PENDING`으로 재활성화한다. `STALE_SETTINGS`, `COMPLETED`,
`RECONCILIATION_REQUIRED` 작업은 재활성화하지 않는다. DB deadlock·lock timeout·일시 연결
장애만 bounded exponential retry 대상으로 삼고, 알 수 없는 오류와 무결성 오류는
`RECONCILIATION_REQUIRED`로 격리한다.

접수 생성 gate는 먼저 Store/Schedule 현재 구간을 잠그고 그 다음 settings 행을 잠근다.
AUTO는 현재 settings version과 같은 `waiting_reception_windows`가 `acceptingFrom <= now <
acceptingUntil`인 경우만 허용한다. MANUAL은 `enabled=true`, `MANUAL`이고 Store 현지
`businessDate 00:00 <= now < 해당 영업일의 마지막 구간 종료`일 때만 허용한다. disabled,
PAUSED, Store 부적격, 휴무, stale/missing 구간, 정확한 종료 경계는 모두
`WAITING_012 WAITING_RECEPTION_CLOSED`로 실패 폐쇄하며 팀·membership·순번·감사·이벤트를
쓰기 전에 거절한다.
생성 명령의 최초 `occurredAt`은 팀·membership·감사·이벤트에 유지하지만, retry마다 새
트랜잭션 안에서 gate 판정 현재 시각을 다시 읽는다. 따라서 첫 check 뒤 접수 종료 경계를
지난 retry가 최초 시각으로 접수를 확정할 수 없다.

Waiting production 코드는 Store Entity·Repository를 직접 참조하지 않는다. 경계 adapter는
Store의 `StoreService`/`StoreWaitingReceptionProfile`과 Schedule의
`WaitingOperatingIntervalService`/`WaitingOperatingIntervalSnapshot` 공개 계약만 소비하고,
Waiting 소유 값으로 즉시 매핑한다. 이 worker는 내부 HTTP path, operation 또는 OpenAPI
schema를 추가하지 않는다.

관측 metric은 bounded `outcome`과 안정된 `failure_class` label만 사용한다. 구조화 로그는
job/store/interval/settings version/owner/fence/attempt만 기록하며 사용자·연락처·좌표·자유
입력은 기록하지 않는다. 장애 복구는 lease 회수와 retry 원장을 사용하며 격리 backlog는
운영자가 원인을 확인한 뒤 별도 복구한다.

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
계정 전체 1건 계약과 다르다. #272는 기존 V36을 수정하지 않고 V44 forward migration에서 이
제약을 제거한 뒤 `UNIQUE (consumer_account_id)`로 교체한다.

V44의 제약 변경 전에 다음 사전 대사를 수행한다.

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
시점의 `WAITING`, `CALLED`, `ARRIVED`, 비종결 `RESERVATION_CONVERTING` 팀 수다. 설정 행의
부재를 매장 부재로 해석하지 않는다.

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

## 신규 접수 설정 gate

일반 사용자 팀 생성은 원장 행을 만들기 전에 같은 트랜잭션에서 현재 매장의 설정 행을
잠근다. 설정 행이 없거나 `enabled=false`이거나 `receptionMode=PAUSED`이면 팀, 활성
membership, 순번, 감사와 상태 이벤트를 만들지 않고 `409 WAITING_012
WAITING_RECEPTION_CLOSED`를 반환한다. 설정 교체도 같은 설정 행을 먼저 잠가 생성과
직렬화한다. 생성이 먼저 확정된 뒤의 `KEEP_ACTIVE`는 그 팀을 유지하고, 비활성화 또는
일시중지가 먼저 확정되면 뒤늦은 생성은 실패 폐쇄한다.

현재 활성 OpenAPI에는 일반 사용자 팀 생성 path가 없으므로 #271은 해당 HTTP path를
추가하지 않는다. `WAITING_012`는 향후 consumer operation이 연결할 공개 Reservation 오류
계약이며 현재 runtime의 `WaitingCreationService`에서도 동일한 wire code를 사용한다.

## 비활성화와 활성 팀

Frontend는 비활성화 전에 `GET .../deactivation-impact`로 현재 버전과 활성 팀 수를 확인할 수
있다. 실제 명령 시점에 활성 팀이 있으면 `disableAction`이 반드시 필요하다. 영향 조회와
명령 사이의 경합은 PUT이 다시 판정한다.

- `disableAction`이 없으면 설정을 바꾸지 않고 `409 WAITING_002`를 반환한다.
- `KEEP_ACTIVE`는 설정을 `enabled=false`, `receptionMode=PAUSED`로 교체해 신규 등록을 막되
  기존 활성 팀을 그대로 유지한다. 기존 팀의 조회·호출·정상 종결 경로는 계속 사용할 수
  있어야 한다.
- `CLOSE_ACTIVE_TEAMS`는 먼저 새 설정 버전으로 비활성화한 뒤 Issue #272의 공개 Service를
  통해 그 버전에 결박된 비동기 일괄 종결 작업을 생성하고 작업 snapshot과 `202 Accepted`를
  반환한다. 설정 저장과 작업 생성은 하나의 트랜잭션으로 확정한다.
- 종결 worker는 각 claim 처리 시 job의 `storeId/settingsVersion`과 현재 설정의 동일 version,
  `enabled=false`를 설정 행 잠금 아래 다시 확인한다. 설정이 재활성화됐거나 더 최신 version이면
  팀, membership, 감사와 이벤트를 바꾸지 않고 해당 stale 항목만 완료해 재시도하지 않는다.

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

### IN_APP 알림 사건 handoff

Waiting의 상태 전이 트랜잭션은 `waiting_status_events`에 team ID, 1부터 시작하는
`eventSequence`, 공개 상태와 중앙 발생 시각을 `PENDING`으로 기록한다. `eventSequence`는
Notification의 양수 `resourceVersion` 계약을 위해 `WaitingTeam.version + 1`로 고정하며,
생성 상태의 team version `0`은 event sequence `1`이다. 상태 사건 저장 실패는 해당 상태
전이도 rollback하고, 사건 저장 뒤 Notification 전달 실패는 이미 확정된 Waiting 상태를
되돌리지 않는다.

IN_APP dispatcher는 상태 사건 ID 순서로 한 행씩 잠그고 `CALLED`, `CANCELLED`, `NO_SHOW`,
`CHECKED_IN`, `CLOSED_BY_STORE`만 각각 `WAITING_CALLED`, `WAITING_CANCELLED`,
`WAITING_NO_SHOW`, `WAITING_CHECKED_IN`, `WAITING_CLOSED_BY_STORE`로 기록한다. `WAITING`,
`ARRIVED`, `RESERVATION_CONVERTING`, `RESERVATION_CONVERTED`는 상태 알림 작업을 만들지 않는다.
그러나 모든 상태 사건은 새 작업 생성 여부와 별개로 Notification 소유 재판정 Service에 team
ID, 상태 사건 ID와 `eventSequence`를 전달한다. 이 Service는 해당 팀의 모든 `PENDING`
`WAITING_ENTRY_IMMINENT` 작업을 lease·보류 여부와 무관하게 재판정 대상으로 전환한다.
모든 적용 가능한 Notification 작업 기록과 재판정 갱신이 같은 MySQL 트랜잭션에서 내구성 있게
확정된 뒤에만 상태 사건을 `PUBLISHED`로 바꾼다. 대상 작업이 없거나 이미 종결된 경우도
상태를 되돌리지 않는 멱등 no-op으로 확정한 뒤 `PUBLISHED`한다. 같은 사건 재처리는 동일한
source event ID와 payload의 논리 알림 및 동일한 재판정 결과로 수렴한다.

입장 임박은 상태 전이가 아니므로 별도 `waiting_entry_imminent_events` 원장을 사용한다.
앞선 활성 팀이 최초로 2팀 이하가 된 순간의 team ID·event sequence·발생 시각을 저장하고
team ID 유일성 제약으로 팀별 한 건만 허용한다. 등록 시 이미 2팀 이하인 팀과 순번에 영향을
주는 종결 사건 뒤 새로 적격이 된 팀을 같은 기준으로 판정한다. 임박 사건의 생성·재처리·알림
실패는 team 상태, queue sequence와 실제 호출 횟수를 변경하지 않는다. 고정한 event sequence는
최초 원 사건의 멱등 식별이며 이후 모든 `WaitingTeam.version + 1`과 일치해야 하는 발송 조건이
아니다.

Notification은 `WaitingNotificationSource`로 현재 team 상태, `consumerAccountId`,
요청된 원 사건과 목적별 현재 상태, 매장 표시명과 호출 제한 시각을 재검증한다. 상태 사건은
`eventSequence = version + 1`을 사용하고, 사건이 현재 상태에서 유효하면 요청된 event sequence를
context의 `resourceVersion`으로 반환한다. `CALLED` 목적은 중앙 `calledAt`과 정확히 10분 뒤
`arrivalDeadline`을 사용한다.

`WAITING_ENTRY_IMMINENT`는 현재 상태가 `WAITING`이면 최초 사건을 `FOUND`로 유지한다. 현재 상태가
`RESERVATION_CONVERTING`이면 전환 결과가 확정될 때까지 작업을 취소하거나 최종 실패로 보내지 않는
Waiting 전용 `TEMPORARILY_UNAVAILABLE` 보류다. 이 context는 요청된 event sequence와
`sourceState=RESERVATION_CONVERTING`을 반환해 원장 조회 장애와 구분한다. 보류는 일반 일시 장애의
bounded retry 횟수를 소비하지 않으며 전환 결과 상태 사건 기반 재판정과 유한한 주기 재조회가
다시 판정을 깨운다. 전환 실패로 같은
활성 membership의 `WAITING`에 복귀하면 최초 사건은 다시 `FOUND`다. 실제 호출 `CALLED`, 도착
`ARRIVED`, 예약 전환 완료 또는 다른 종결 상태가 확정되면 오래된 입장 임박 작업을 `SUPERSEDED`로
처리한다. 상태 사건 목적도 현재 상태가 해당 목적과 일치할 때만 `FOUND`이며 더 최신 상태가 있으면
`SUPERSEDED`다. 알림의 지연·실패는 도착·체크인·취소·미응답·매장 종료를 바꾸지 않는다.

Notification 재판정은 기존 작업 version을 증가시키고 lease를 무효화하며 `nextAttemptAt`을 DB
현재 시각으로 당긴다. Worker의 전달·취소·실패·재시도·보류는 획득한 lease와 작업 version이
모두 일치할 때만 성공한다. Worker 보류가 먼저 확정되면 상태 사건 재판정이 작업을 깨우고,
상태 사건 재판정이 먼저 확정되면 이전 version의 Worker 보류가 실패해 최신 상태를 다시 읽는다.
따라서 전환 실패 `WAITING` 사건이 Worker 보류 기록보다 먼저 처리돼도 입장 임박 작업이 영구
정지하지 않는다. 주기 재조회 시점은 versioned Notification runtime 정책이 유한하게 정하며,
Waiting dispatcher가 별도 worker 설정이나 Notification repository 직접 접근을 만들지 않는다.

이번 단계의 `PENDING`/`PUBLISHED`는 IN_APP dispatcher 진행 상태만 나타낸다. SSE endpoint,
재연결 cursor와 실시간 fan-out은 후속 계약에서 immutable 사건 ID를 독립적으로 소비하며 이
publication state를 SSE 소비 완료로 재사용하지 않는다. AUTO 접수 오픈 worker와 settings
version CAS는 Issue #380이 별도로 소유한다.

### 운영자 응답 개인정보 경계

목록 item은 `waitingTeamId`, `status`, `queueSequence`, `partySize`, `createdAt`, `version`만
노출한다. 상세는 여기에 `storeId`와 상태별 시각(`calledAt`, `arrivedAt`, `checkedInAt`,
`cancelledAt`)만 추가한다. 어떤 운영자 목록·상세·종결 작업 응답에도 consumer ID, 계정 ID,
전화번호, 좌표, 원본 접수 식별자 또는 멱등 키를 넣지 않는다.

### 소비자 웨이팅 API (#408)

소비자 JWT(`consumer` namespace)만 다음 경계를 호출한다. 운영자 JWT와 인증되지 않은 요청은
공통 JWT 인증 오류 계약으로 거절한다.

| path | method | 의미 |
|---|---|---|
| `/api/v1/consumers/me/stores/{storeId}/waiting-availabilities` | `GET` | 중앙 시각 기준 접수 가능 여부와 등록에 사용할 `businessDate` 조회 |
| `/api/v1/consumers/me/stores/{storeId}/waiting-teams` | `POST` | #409 위치 증명 연결 뒤 `businessDate`, `partySize`로 원격 웨이팅 등록 |
| `/api/v1/consumers/me/waiting-teams/current` | `GET` | 인증 소비자의 단일 활성 웨이팅 조회 |
| `/api/v1/consumers/me/waiting-teams/{waitingTeamId}/cancellations` | `POST` | 본인 웨이팅을 `expectedVersion`으로 취소 |

등록과 취소는 표준 UUID `Idempotency-Key`가 필수다. 같은 키와 같은 요청 지문은 최초 HTTP
상태와 결과를 재생하며, 같은 키를 다른 지문에 재사용하면 `409 COMMON_007`이다. 등록은 기존
`WaitingCreationService`를 사용하고 source는 `REMOTE`로 고정한다. availability 결과와 등록
사이에 설정이나 영업 구간이 닫힐 수 있으므로 등록 트랜잭션은 Store/Schedule 접수 게이트를
다시 잠그고 검사하며, 닫힌 경우 어떤 팀·membership·감사·상태 사건도 기록하지 않는다.

#409가 계정·매장·목적에 결속된 단기 위치 증명 세션을 등록 트랜잭션에서 한 번만 소비하도록
연결하기 전에는 `miriyum.waiting.consumer-registration.location-proof-connected`의 기본값을
`false`로 유지한다. 이 상태의 등록 POST는 활성 계정만 재확인한 뒤 `409 WAITING_012`로 실패
폐쇄하며 `WaitingCreationService`를 호출하지 않는다. 조회·availability·취소는 이 게이트의
영향을 받지 않는다. 속성을 `true`로 바꾸는 배포 권한과 위치 증명-팀 생성 원자 결합 검증은
#409가 소유한다.

availability는 Waiting 설정 유무를 접수 가능 여부로 해석하기 전에 Store 존재를 확인한다. 없는
매장은 `404 STORE_001`, 설정이 없거나 접수가 닫힌 기존 매장은 `200 accepting=false`다. 등록의
Store 잠금 검증은 운영 상태 `STORE_005`, 입점 검증 `STORE_007`, Waiting 기능 제재
`STORE_015`를 각각 원래 HTTP 상태와 코드로 반환한다.

활성 membership은 계정당 하나뿐이다. 서로 다른 매장을 향한 병렬 등록도 DB unique 제약과
bounded retry를 거쳐 하나만 성공하고 나머지는 `409 WAITING_011`이다. 소비자 취소와 운영자
call/cancel이 같은 version으로 경합하면 팀 row lock에서 먼저 확정된 전이만 성공하며 나머지는
`409 WAITING_005`다. 취소 성공은 활성 membership을 제거하고 `CONSUMER` actor 감사를 한 번만
기록한다.

소비자 snapshot은 `waitingTeamId`, `storeId`, `businessDate`, `status`, `queueSequence`,
`teamsAhead`, `partySize`, `createdAt`, `calledAt`, `arrivalDeadline`, `arrivedAt`, `cancelledAt`,
`version`만 공개한다. `teamsAhead`는 같은 매장·영업일의 앞선 활성 FIFO 팀 수를 조회 시점에
계산한다. 다른 소비자의 팀은 존재 여부와 소유권을 구분하지 않고 `404 WAITING_003`으로
응답한다. 계정 ID, 운영 메모, 좌표, 원본 식별자, 멱등 키는 반환하지 않는다.

플랫폼 3km 정책은 유지하고 위치 판정·좌표 수집 구현은 #409가 소유한다. #408은 위치를
입력받거나 검증 완료를 주장하지 않으며, 후속 위치 증명 경계가 연결되기 전에는 등록을 기본
비활성화해 원격 팀·순번·membership이 생성되지 않도록 실패 폐쇄한다.

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

The callback is a fast path, not the sole durable trigger. On the reconciliation schedule, the
compensation runner scans at most 100 Waiting ledger IDs per poll in descending primary-key order.
Each traversal fixes the current maximum Waiting-team ID as an upper watermark and reads only
`id <= watermark AND id < cursor ORDER BY id DESC LIMIT 100`; terminal status, preserved payment,
and compensation absence are evaluated after that bounded ledger page is read. An empty page starts
a new traversal from the newest ID. Thus a sparse candidate cannot make one poll examine an
unbounded historic range, continuously arriving higher IDs cannot displace the current traversal,
and an older row that becomes eligible after its cursor was passed is retried on the next traversal.
For each candidate it verifies the Payment-owned source identity and historical-paid snapshot through
`PaymentService`, re-locks the terminal Waiting team, and records the same deterministic compensation
payload as the callback. A not-yet-paid source remains eligible for a later scan without creating a
false compensation. Callback/reconciliation races converge on the existing unique keys, so process
restart or callback loss cannot strand a paid terminal conversion without a durable refund handoff.
The runner emits the identifier-free
`waiting_conversion_compensation_handoff_recovered` aggregate only after Payment verification and
successful durable handoff creation. Raw terminal candidates are not reported as missing because an
unpaid or invalid source is a normal negative candidate rather than a compensation incident.

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
