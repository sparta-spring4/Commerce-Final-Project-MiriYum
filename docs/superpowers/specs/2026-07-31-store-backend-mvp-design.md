# 매장·검색 백엔드 1차 MVP 설계

## 목표

GitHub Issue #33부터 #36까지의 매장·검색 백엔드 기능을 의존성 순서대로 구현한다. 프론트엔드 Issue #37, #38과 본인확인 개발 스텁 기본값 변경은 이 범위에 포함하지 않는다.

## 범위와 작업 단위

각 Issue는 별도의 브랜치와 격리된 Git worktree를 사용한다.

| Issue | 작업 단위 | 선행 기준 |
| --- | --- | --- |
| #33 | 매장 등록·조회·수정 및 중앙 운영자 권한 검증 | 최신 `dev`, PR #60 운영자 인증 계약 |
| #34 | 영업시간·브레이크타임·예약 접수 시간대 버전 게시 | #33 |
| #35 | 메뉴 기본정보·불변 버전·게시/공개/판매 상태 관리 | #33 |
| #36 | 공개 검색·상세·메뉴 조회 및 예약 가용성 조합 | #34, #35, 예약 Issue #48 공개 계약 |

#34와 #35는 #33을 공통 기반으로 하는 형제 브랜치·worktree로 진행한다. #36은 두 변경이 통합된 기준과 #48의 `ReservationService` 일괄 가용성 계약이 모두 준비된 뒤 완성한다. 선행 변경이 아직 `dev`에 병합되지 않은 동안에는 로컬 적층 브랜치로 개발하되, 최종 PR에는 해당 Issue의 변경만 남도록 선행 PR 병합 후 기준 브랜치를 갱신한다.

## 선행 인증 통합

PR #60은 매장 운영자 계정, `store-operator` JWT namespace, `AuthenticatedPrincipal`, Security filter chain을 제공한다. #33은 임시 principal이나 가짜 계정 테이블을 만들지 않고 이 공개 계약을 사용한다.

현재 PR #60의 실제 head에는 `V5__create_store_operator_accounts.sql`이 있지만 최신 `dev`의 V5는 요청 제한 테이블이 사용한다. 로컬 통합 기준에서는 운영자 계정 migration을 V6으로 정렬해야 한다. 이 충돌 보정은 인증 선행 변경의 정합성 작업으로 분리하며 #33의 제품 변경으로 섞지 않는다.

`miriyum.identity-verification.dev-stub-enabled` 기본값과 `MIRIYUM_IDENTITY_VERIFICATION_STUB_ENABLED` 설정은 변경하지 않는다.

## #33 매장 핵심 설계

매장은 인증된 운영자 계정 한 개를 대표 운영자로 참조한다. 요청 본문의 운영자 ID나 역할은 받지 않고 SecurityContext의 `AuthenticatedPrincipal`에서 `STORE_OPERATOR` namespace와 account ID를 사용한다.

매장 aggregate는 다음 의미를 분리한다.

- 입점 검증: `APPROVED`
- 운영 상태: `OPEN`, `TEMPORARILY_CLOSED`, `CLOSED`
- 픽업 자격: `ELIGIBLE`, `INELIGIBLE`
- 공식 업종: `CAFE`, `BAKERY`, `OTHER`
- 공개 검색 카테고리와 태그: #31 catalog code

`CAFE`와 `BAKERY`는 픽업 자격이 있고 `OTHER`는 없다. `OTHER`와 `pickupEnabled=true` 조합은 값을 자동 보정하지 않고 `STORE_008`로 전체 거절한다.

사업자등록번호는 정규화 값과 활성 귀속 여부를 저장한다. 애플리케이션 형식 검증과 MySQL 유일 제약을 함께 사용해 병렬 등록 중 한 건만 성공하게 한다. 운영 종료는 과거 참조를 연쇄 삭제하지 않는다.

`StoreService`는 등록·조회·수정 외에 다른 도메인이 사용할 중앙 관리 권한 검증 메서드를 제공한다. 존재하는 매장을 다른 운영자가 관리하려 하면 `STORE_003` 403을 반환하며 404로 숨기지 않는다.

등록과 수정은 #32의 `IdempotencyExecutor`를 호출하는 도메인 트랜잭션 안에서 실행한다. 멱등 기록, 사업자번호 귀속, 매장 변경 결과는 같은 트랜잭션에서 확정한다.

## #34 운영 일정 설계

정규 영업시간, 브레이크타임, 예약 접수 시간대는 현재 행을 제자리 수정하지 않고 전체 주간 설정의 불변 버전을 게시한다.

모든 구간은 `[startTime, endTime)`으로 해석한다. 교차 자정 구간을 추측하지 않으며 시작이 종료보다 빠른 같은 요일 구간만 허용한다. 브레이크타임은 영업 구간 내부여야 하고 예약 접수 구간은 영업시간 내부이면서 브레이크타임과 겹치지 않아야 한다.

PUT은 일부 요일 병합이 아니라 새 전체 버전을 만든다. 같은 멱등 키와 요청 지문의 재시도는 저장된 결과를 재생하며 새 버전을 만들지 않는다. 설정 변경은 기존 예약을 자동 취소하거나 이동하지 않는다.

일정 서비스는 예약 Issue #48이 사용할 현재 유효 구간 조회 계약을 제공한다. 예약 수용량이나 예약 종료 계산 규칙은 저장하지 않는다.

## #35 메뉴 설계

메뉴의 장기 식별자와 불변 정보 버전을 분리한다. 수정 요청은 새 버전을 생성하고 이전 버전과 과거 거래 스냅샷을 변경하지 않는다.

메뉴 버전은 이름, 설명, KRW 정수 가격, 대표 여부, 주 카테고리 한 개, 중복 없는 보조 카테고리 0~5개, 로컬 태그, 홀드/픽업 선택 허용을 가진다. 주·보조 카테고리는 #31 catalog를 사용하고 승인되지 않은 코드는 `STORE_004`로 거절한다.

다음 상태 축은 합치지 않는다.

- 게시 수명주기: `DRAFT`, `SCHEDULED`, `PUBLISHED`, `RETIRED`
- 공개 상태: `VISIBLE`, `HIDDEN`
- 판매 제어: `SELLING`, `PAUSED`

시간대별 수량 `AVAILABLE`, `SOLD_OUT`은 메뉴에 저장하지 않는다. 픽업 비자격 매장은 픽업 선택 허용을 활성화할 수 없다. 생성·수정과 새 버전 게시는 #32 멱등 결과와 같은 트랜잭션에서 확정한다.

## #36 공개 검색 설계

공개 API는 `GET /api/v1/stores`, `GET /api/v1/stores/{storeId}`, `GET /api/v1/stores/{storeId}/menus`를 제공한다. MySQL 정규화 검색 필드와 필요한 인덱스를 사용하며 QueryDSL이나 별도 검색 엔진은 도입하지 않는다.

검색 조건은 승인된 다섯 지역, #31 catalog code, 서버가 정규화하고 SQL wildcard를 escape한 keyword만 허용한다. keyword는 매장명, 게시 메뉴명, 지역 표시명에 대한 단순 포함 검색이다. `CLOSED` 매장과 `RETIRED` 또는 비공개 메뉴는 신규 공개 후보에서 제외하지만 과거 참조는 삭제하지 않는다.

예약 조건 `serviceDate`, `startTime`, `partySize`는 셋을 모두 보낸 경우에만 처리한다. 일부만 보내거나 완전한 조건 없이 `availableOnly=true`를 보내면 `COMMON_001`이다. 조건이 없으면 `NOT_REQUESTED`를 반환한다.

가용성 계산은 #48이 소유하는 `ReservationService` 일괄 공개 메서드와 DTO만 사용한다. 예약 Entity·Repository를 직접 참조하거나 임시 성공값, 가짜 수용량, store 소유의 대체 interface로 우회하지 않는다. #48 계약이 준비되지 않은 동안 #36은 검색 조건·공개 노출·MySQL 조회 부분까지만 검증 가능하며 전체 완료로 표시하지 않는다.

## 오류 처리

Store 도메인은 `StoreErrorCode`와 공통 `ServiceException`을 사용한다.

- `STORE_001`: 공개 또는 권한 범위에서 매장을 찾을 수 없음
- `STORE_002`: 활성 사업자등록번호 중복
- `STORE_003`: 대표 운영자 권한 없음
- `STORE_004`: 승인되지 않은 catalog code
- `STORE_005`: 현재 매장 상태에서 관리 불가
- `STORE_006`: 운영·브레이크·접수 구간 충돌
- `STORE_007`: 입점 검증 상태에서 운영 불가
- `STORE_008`: 픽업 자격 없음
- `STORE_009`: 메뉴 없음
- `STORE_010`: 메뉴 상태 전이 충돌

Bean Validation 형식 오류와 불완전한 검색 조건은 `COMMON_001`, 멱등 키 재사용은 #32의 `COMMON_007`을 그대로 사용한다. 예약 가용성 오류를 STORE 오류로 다시 정의하지 않는다.

## 테스트 전략

모든 Issue는 실패 테스트를 먼저 작성하고 최소 구현으로 통과시킨다.

- 단위 테스트: 상태 전이, 업종·픽업 조합, 권한, 시간 구간 경계, 메뉴 버전과 catalog 규칙, 검색 조건 정규화
- MockMvc: 인증 namespace, 400/403/404/409, 공통 envelope, `Idempotency-Key`, 공개 접근, pagination
- Testcontainers MySQL: Flyway V1 이후 전체 적용, FK, 활성 사업자번호 유일성, 버전 불변성, 검색 escape와 상태 필터, 병렬 명령과 rollback
- 계약 테스트: #34 일정 조회 계약과 #48 후보 순서·가용성 매핑
- 회귀 검증: 집중 테스트, `./gradlew clean build`, `git diff --check`

각 worktree에서는 해당 Issue 허용 경로만 stage·commit한다. 현재 작업 폴더의 기존 미추적 파일과 문서 변경은 이동·수정·삭제하지 않는다.

## 완료 기준

- #33, #34, #35는 각 Issue 인수 조건과 테스트를 독립적으로 충족한다.
- #36은 #48 일괄 가용성 계약을 실제로 소비하고 세 상태 의미와 후보 순서를 검증한 뒤에만 완료로 표시한다.
- 각 Issue는 별도의 브랜치, worktree, 검증 기록과 PR 범위를 유지한다.
- 본인확인 개발 스텁 설정은 변경하지 않는다.
