# 기능 명세: 웨이팅 운영 설정

> 단계: `고도화`
>
> 기능 소유자: `reservation` 도메인의 Waiting capability
>
> 계약 범위: Issue #271 매장 운영자 설정 조회·교체와 비활성화 영향 조회
>
> 구현 순서: `#271 계약 → #272 원장·종결 공개 계약/runtime → #271 설정 runtime`.
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
시점의 활성 팀 수이고, `canCloseActiveTeams`는 Issue #272가 정의하는 종결 가능성 판정 결과다.
설정 행의 부재를 매장 부재로 해석하지 않는다.

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

Frontend는 비활성화 전에 `GET .../disable-impact`로 현재 버전과 활성 팀 수를 확인할 수
있다. 실제 명령 시점에 활성 팀이 있으면 `disableAction`이 반드시 필요하다. 영향 조회와
명령 사이의 경합은 PUT이 다시 판정한다.

- `disableAction`이 없으면 설정을 바꾸지 않고 `409 WAITING_002`를 반환한다.
- `KEEP_ACTIVE`는 설정을 `enabled=false`, `receptionMode=PAUSED`로 교체해 신규 등록을 막되
  기존 활성 팀을 그대로 유지한다. 기존 팀의 조회·호출·정상 종결 경로는 계속 사용할 수
  있어야 한다.
- `CLOSE_ACTIVE_TEAMS`는 운영자가 활성 팀 종결을 명시적으로 선택했다는 intent다. 어떤
  팀이 영향을 받는지, 상태 전이, 원자성, 알림과 실행 결과는 Issue #272가 소유하며 이
  계약은 그 세부 동작을 중복 정의하지 않는다. #271 설정 runtime은 #272가 공개 실행
  계약과 runtime을 `dev`에 먼저 제공하기 전에는 이 action의 성공을 구현하지 않는다.

활성 팀이 없으면 `disableAction` 없이 비활성화할 수 있다. `disableAction`이 제공된 경우에도
서버는 명령 시점의 활성 팀과 권한을 다시 확인한다.

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
- 비활성화 전에 영향 GET을 호출한다. `activeTeamCount>0`이면 `KEEP_ACTIVE`와
  `CLOSE_ACTIVE_TEAMS` 중 명시적 선택을 받는다.
- `WAITING_001`이면 최신 설정을 다시 조회하고 사용자 입력을 보존한 채 재확인을 요청한다.
- `WAITING_002`이면 비활성화 영향을 다시 조회하고 action 선택 화면으로 돌아간다.
- `COMMON_001`은 필드 검증 안내, `COMMON_002`는 요청 직렬화 오류로 처리한다.
  `COMMON_003`·`COMMON_004`는 client 요청 구성 오류이므로 새 멱등 키를 임의 생성해 자동
  재시도하지 않는다.
- `AUTH_011`은 계정 제한 상태, `STORE_003`은 현재 대표 운영자 소유권 실패로 구분한다.
- `STORE_005`이면 폐점 매장의 설정 변경 UI를 비활성화하고, `STORE_007`이면 입점 검증 상태를
  새로 확인하기 전 설정 조회·변경 성공을 추측하지 않는다.
- `KEEP_ACTIVE` 성공은 기존 팀이 유지되고 신규 등록만 차단된 상태로 표시한다.
- `CLOSE_ACTIVE_TEAMS`의 팀별 진행·결과 UI는 Issue #272의 실행 계약을 받은 뒤 구현한다.
- Client는 `message` 문자열이 아니라 HTTP 상태와 `code`로 분기하고 `401`, `403`, `404`,
  `409`, `429`를 서로 다른 상태로 처리한다.

두 path는 `store-operator-openapi.yaml`에 post-MVP1/high-level audience 계약으로 연결되고
`mvp1-openapi.yaml`에서는 제외된다. 현재 작업은 frontend나 생성 클라이언트를 만들지 않는다.
후속 contract-first 구현은 audience 진입점으로 노출 범위를 확인하되 TypeScript 생성 입력은
기능 원본인 이 디렉터리의 `openapi.yaml`을 사용하고, 1차 MVP aggregate에서 생성하지 않는다.
