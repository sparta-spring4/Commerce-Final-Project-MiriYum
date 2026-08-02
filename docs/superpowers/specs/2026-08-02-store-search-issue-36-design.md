# Issue #36 공개 매장 검색 백엔드 2단계 설계

## 문서 성격

- 대상 Issue: `#36 [Search] 공개 매장·메뉴 검색과 예약 가용성 조합 구현`
- 적용 단계: 1차 MVP
- 범위: backend only. Frontend는 모든 공개 API가 완료된 뒤 별도 작업한다.
- 정본: `docs/specs/store-search/spec.md`, `docs/specs/store-search/openapi.yaml`
- 이 문서는 구현 결정을 기록하는 비정본 설계 문서이며 정본을 대체하지 않는다.

## 목표

비회원이 공개 가능한 매장을 목록·상세·메뉴 API로 조회하고, 매장명·현재 게시 메뉴명·지역 표시명과 승인된 필터로 검색할 수 있게 한다. 완전한 예약 조건이 있을 때만 예약 도메인의 공개 일괄 가용성 계약을 호출하여 `AVAILABLE` 또는 `UNAVAILABLE`을 조합한다. 예약 조건이 없으면 예약 도메인을 호출하지 않고 `NOT_REQUESTED`를 반환한다.

현재 예약 PR #79의 계약은 모든 후보 매장에 하나의 공통 `endTime`을 요구한다. 그러나 정본은 `endTime`을 매장별 활성 예약 접수 시간대와 서비스 정책으로 서버가 계산하도록 요구한다. 검색 도메인이 이 정책을 추측하거나 예약 저장소를 직접 읽을 수 없으므로 구현을 검색 코어와 가용성 통합의 두 단계로 분리한다.

## 선택한 접근

### 선택: 검색 코어 선행, 계약 보완 후 공개 API 통합

1단계에서는 예약 도메인에 의존하지 않는 검색 조건, 공개 후보 조회, 공개 DTO 투영과 테스트를 구현한다. 이 결과를 예약 조건이 동작하지 않는 불완전한 공개 API로 배포하지 않는다. 2단계에서 Store 일정 조회 계약과 Reservation 매장별 batch 계약이 `dev`에 병합되면 세 공개 API를 완성하고 전체 계약 테스트를 수행한다.

이 방식은 이미 가능한 검색 구현을 진행하면서도 production fake, 임의 종료 시각, 예약 repository 직접 접근, API의 부분 동작을 만들지 않는다.

### 검토했지만 선택하지 않은 접근

1. **PR #79 병합까지 전체 대기**
   - 장점: rebase와 중간 구현이 가장 단순하다.
   - 단점: 예약과 무관한 검색 규칙·쿼리·상태 필터 검증도 불필요하게 지연된다.
2. **PR #79 위에 #36을 stacked 구현**
   - 장점: 현재 ReservationService 코드를 조기에 컴파일할 수 있다.
   - 단점: PR #79가 최신 `dev`보다 오래됐고 공통 `endTime` 계약 자체가 #36을 만족하지 못한다. 스택만으로 계약 공백이 해결되지 않는다.
3. **임시 adapter 또는 가짜 가용성으로 전체 API 완성**
   - 선택하지 않는다. 저장소의 contract-first 규칙과 #36의 명시적 금지사항을 위반한다.

## 선행 정리

제품 코드 구현 전에 Issue #36을 현재 저장소 구조와 실제 의존성에 맞춘다.

- 구현 경로를 `com/miriyum/store/search/**`가 아니라 ADR-001의 도메인 루트인 `com/miriyum/domain/store/search/**`로 정정한다.
- 예약 의존성을 Issue #48과 PR #79로 연결한다.
- Issue의 `커서` 문구와 활성 OpenAPI의 offset page 계약 중 정본을 하나로 확정한다. 1차 구현은 OpenAPI의 `page`, `size`, 허용 sort를 따른다.
- Phase 2 계약 보완 항목을 `blocked by`로 명시한다.

## 1단계: 예약 비의존 검색 코어

### 포함 범위

- `SearchCriteria`
  - keyword trim·공백 정규화
  - SQL LIKE의 `%`, `_`, `\`를 리터럴로 escape
  - 승인된 다섯 region만 허용
  - store category code를 `CatalogService` 공개 검증으로 확인
  - sort allowlist와 안정적인 `storeId` tie-breaker
  - `serviceDate`, `startTime`, `partySize` all-or-none 검증
  - 완전한 예약 조건 없이 `availableOnly=true`이면 `COMMON_001`
- 공개 후보 조회
  - `CLOSED` 매장 제외
  - 매장명, 현재 게시·공개 메뉴명, 지역 표시명 단순 포함 검색
  - 메뉴명 매칭은 `EXISTS`로 처리하여 매장 중복 방지
  - 현재 게시 버전과 `VISIBLE` 메뉴만 공개 후보로 사용
  - `SOLD_OUT`, `PAUSED` 메뉴는 숨기지 않고 판매 상태를 그대로 투영
  - 허용된 정렬 뒤 항상 `storeId`를 추가해 결과 순서를 안정화
- 공개 projection/DTO
  - 공개 ID는 문자열로 반환
  - 예약 조건이 없는 코어 결과의 가용성은 `NOT_REQUESTED`
  - 내부 운영자 ID, 내부 버전 PK, 감사 정보는 노출하지 않음
- DB 인덱스
  - 상태·카테고리·지역·정렬·조인에 필요한 인덱스만 MySQL `EXPLAIN` 근거로 추가
  - `%keyword%`에 효과가 없는 명목상 B-tree나 FULLTEXT는 추가하지 않음

### 제외 범위

- 실제 `ReservationService` 호출과 `AVAILABLE/UNAVAILABLE` 조합
- `availableOnly=true` 결과 페이지 생성
- 불완전한 기능을 가진 공개 controller 배포
- 예약 수용량·종료 시각·영유아 정책 추측
- QueryDSL, 검색 엔진, 지도·거리, 추천, 이미지

### 구성 요소

- `search/model`: 조건·정렬·가용성 요청 상태처럼 영속성과 무관한 값 객체
- `search/repository`: Store/Menu 소유 테이블에서 공개 후보 projection을 읽는 전용 repository
- `search/service`: 조건 검증, 후보 조회, DTO 투영을 조정하는 읽기 전용 service
- `search/dto`: OpenAPI 공개 필드와 일치하는 응답 DTO

검색 service는 쓰기 트랜잭션이나 잠금을 획득하지 않는다. Store 도메인 내부의 공개 데이터만 조회하고 Reservation 또는 MenuHold의 Entity·Repository를 참조하지 않는다.

### 데이터 흐름

1. 원시 query parameter를 `SearchCriteria`가 정규화·검증한다.
2. `CatalogService`가 category code의 활성 여부를 판정한다.
3. 검색 repository가 상태·필터·keyword·정렬을 적용해 공개 후보 projection을 조회한다.
4. service가 내부 숫자 ID를 공개 문자열 ID로 바꾸고 예약 조건이 없는 결과에는 `NOT_REQUESTED`를 설정한다.
5. Phase 1 결과는 내부 테스트 대상이며 Phase 2 완료 전 불완전한 HTTP 계약으로 공개하지 않는다.

## 2단계: 가용성 계약과 공개 API 통합

### 필요한 선행 계약

1. **Store 공개 일정 계약**
   - 업무 날짜와 시작 시각을 기준으로 매장별 활성 영업·예약 접수 구간을 조회한다.
   - 실제 예약 생성과 동일한 규칙으로 각 매장의 점유 종료 시각을 확정하거나 예약 도메인이 계산하는 데 필요한 소유 데이터를 제공한다.
   - 검색 코드가 schedule repository나 버전 포인터를 직접 재구현하지 않게 한다.
2. **Reservation 매장별 batch 계약**
   - 후보별로 서버가 확정한 조건을 받거나 Store 공개 계약을 내부에서 사용한다.
   - 입력 후보와 결과의 `storeId` 대응 및 순서를 보존한다.
   - 매장별 반복 repository 조회 없이 제한된 batch/chunk로 판정한다.
   - 누락된 정책·버킷·결과는 fail-closed `UNAVAILABLE`이다.
3. **요청 계약 정렬**
   - PR #79의 `includesInfants`와 검색 OpenAPI 입력 차이를 정본에서 해결한다.
   - `availableOnly` 적용 전후 pagination 및 total 계산 방식을 정본에서 확정한다.

### 공개 API

- `GET /api/v1/stores`
- `GET /api/v1/stores/{storeId}`
- `GET /api/v1/stores/{storeId}/menus`

세 경로는 익명 접근을 허용한다. malformed 또는 다른 namespace 토큰이 있더라도 공개 GET의 익명 의미를 깨지 않는 기존 보안 규칙과 공통 envelope를 사용한다.

### 통합 데이터 흐름

1. 요청에 예약 조건이 없으면 1단계 검색 결과를 사용하고 `ReservationService`를 호출하지 않는다.
2. 예약 조건이 완전하면 Store 공개 계약으로 후보별 유효 점유 구간을 확정한다.
3. Reservation 공개 batch 계약을 한 번 또는 상한이 있는 chunk 단위로 호출한다.
4. `storeId`로 결과를 안정적으로 매핑하며 누락 결과는 `UNAVAILABLE`로 처리한다.
5. `availableOnly=false`이면 모든 후보에 상태를 조합한다.
6. `availableOnly=true`이면 가용성 판정 결과를 기준으로 필터한 뒤 정확한 page metadata를 계산한다. 이 과정은 정본에서 승인된 상한·페이지 전략을 따른다.
7. 상세 응답은 Store 공개 일정 계약으로 현재 활성 영업시간과 예약 접수 시간대를 hydrate한다.

## 오류 처리

- `COMMON_001` / 400
  - 예약 조건 일부만 제공
  - 예약 조건 없이 `availableOnly=true`
  - 허용되지 않은 region, sort, page, size 또는 형식
- `STORE_004` / 400
  - 미승인·비활성 store category code
- `STORE_001` / 404
  - 존재하지 않거나 공개 후보가 아닌 `CLOSED` 매장의 상세·메뉴 조회
- `UNAVAILABLE`
  - 활성 일정 없음
  - 예약 접수 구간 밖
  - 필요한 수용량 버킷 없음 또는 부족
  - batch 결과 누락

가용성 원장 부재는 검색 API의 새로운 오류 코드로 바꾸지 않는다. 예약 도메인의 내부 오류 의미를 임의의 STORE 코드로 복제하지 않는다.

## 테스트 설계

### 1단계

- 단위
  - 예약 조건 all-or-none 조합표
  - `availableOnly` 조합
  - keyword 공백 정규화와 `%`, `_`, `\` escape
  - region·sort allowlist
  - 공개 상태와 `NOT_REQUESTED` 투영
- Testcontainers MySQL
  - 매장명·현재 게시 메뉴명·지역 표시명 포함 검색
  - wildcard 문자의 리터럴 검색
  - 같은 매장의 여러 메뉴 매칭 시 중복 제거
  - `CLOSED`, `HIDDEN`, `RETIRED` 제외
  - `SOLD_OUT`, `PAUSED` 공개 의미
  - 정렬 동률에서 `storeId` 안정 순서
  - 필요한 인덱스와 대표 query의 `EXPLAIN`

### 2단계

- MockMvc
  - 익명 접근, validation, 공통 envelope, 404
  - 세 공개 API와 OpenAPI 필드 일치
  - 예약 조건 유무에 따른 Service 호출 여부
- 계약 테스트
  - 서로 다른 종료 시각을 가진 두 매장
  - 입력 순서와 `storeId` 결과 매핑
  - 누락 결과 fail-closed
  - batch/chunk 호출 상한
- pagination
  - unavailable 후보가 페이지 경계에 있을 때 items와 total 정확성
- 상세·메뉴
  - 현재 활성 일정, 대표 메뉴, 공개 메뉴 상태

## 완료 기준

### 1단계 완료

- 예약 비의존 검색 코어와 MySQL 테스트가 통과한다.
- 다른 도메인 repository 접근, production fake, 임시 종료 시각이 없다.
- 불완전한 공개 API를 완료 또는 배포 가능으로 주장하지 않는다.

### 2단계 완료

- 필요한 Store·Reservation 공개 계약이 최신 `dev`에 병합됐다.
- 세 공개 API가 OpenAPI와 일치하고 예약 조건 유무·가용성·pagination 계약 테스트가 통과한다.
- `./gradlew.bat clean build`와 `git diff --check`가 통과한다.
- Frontend는 이 시점 이후 별도 Issue로 시작한다.

## 위험과 롤백

- Phase 1과 Phase 2 사이 계약 변경으로 projection 또는 service 조정이 필요할 수 있다. 이를 줄이기 위해 Phase 1은 Reservation DTO에 의존하지 않는다.
- `%keyword%` 검색은 데이터 증가 시 느려질 수 있다. 인덱스 효과를 추측하지 않고 MySQL 실행계획과 측정값으로 후속 개선을 결정한다.
- Phase 1 변경은 검색 패키지와 해당 migration을 revert하여 되돌린다. 이미 적용된 Flyway 파일은 수정하지 않고 후속 migration으로 보정한다.
