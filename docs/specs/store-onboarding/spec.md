# Store Onboarding Review Contract

## Purpose

모든 향후 신규 매장 신청은 사업자등록증 1개와 자동검사를 거친다. `miriyum.store.onboarding.manual-review-enabled`는 자동검사 통과 뒤 플랫폼 운영자 심사를 추가할지만 신청 version에 고정한다. 기존 승인 매장은 소급 재심사하거나 파일 제출을 요구하지 않는다.

실제 private S3 bucket·IAM·환경값과 staging 신청/일회 열람 smoke는 #223의 활성화 선행 조건이다. #277 코드 완료만으로 운영 저장소 활성화가 완료됐다고 보지 않는다.

## Store Operator Contract

- 기존 `POST /api/v1/store-operators/stores`가 유일한 신규 신청 진입점이다. 별도 신규 신청 API를 안내하지 않는다.
- 요청은 `multipart/form-data`이며 JSON `application` part와 `businessRegistrationEvidence` part를 각각 정확히 하나 요구한다.
- 증빙은 PDF, JPEG, PNG만 허용하고 최대 10,485,760 bytes다. 선언 MIME과 파일 signature가 일치해야 하며 암호화 PDF는 거절한다.
- `application`은 사업자등록번호·법적 상호명·대표자명·개업일·주업태명·주종목명, 사업장 주소와 검색용 매장 주 카테고리 코드를 서로 다른 필드로 받는다. 사업자등록증의 업태·종목을 검색 카테고리로 변환하지 않는다.
- 과거 픽업 업종 제한을 위해 사용한 `businessType(CAFE/BAKERY/OTHER)`은 신청 입력·snapshot·Store와 공개 계약에서 제거한다. 모든 업종의 거래 기능은 `modes`가 결정하며 클라이언트가 호환용 임의 값을 만들지 않는다.
- 매장 등록 화면은 활성 `GET /api/v1/store-categories` 결과를 사용해 한식·중식·일식·양식·아시아 음식·카페·베이커리·주점·기타의 현재 활성 catalog를 선택하게 한다. 표시명이나 code를 프런트에 별도 하드코딩하지 않는다.
- 접수 성공은 Store가 아니라 `202 Accepted` 신청 데이터를 반환한다. 데이터는 `applicationId`, `applicationVersion`, `status`, `reviewRequired`, `nextAction`, nullable `storeId`만 포함한다.
- 프런트는 `202`를 매장 생성 완료로 낙관하지 않고 소유 신청 상태 화면으로 이동한다. 상태 화면은 같은 신청 GET을 재조회하며 `storeId`가 생긴 승인 상태에서만 매장 관리 진입을 제공한다.
- 같은 운영자·멱등 키·요청 fingerprint 재전송은 최초 결과를 반환한다. JSON 또는 증빙 SHA-256이 다른 재사용은 충돌로 거절한다.
- 보완 요청 뒤 `POST /api/v1/store-operators/onboarding-applications/{applicationId}/versions`로 새 불변 version을 제출한다. 이전 version의 배정·결정·증빙 접근은 무효다.

## Workflow

1. 신청을 `RECEIVED`로 예약하고 `reviewRequired`를 snapshot한다.
2. private pending 객체를 저장하고 #344 증빙 원장에 현재 version을 연결한 뒤 `AUTO_CHECKING` 작업을 만든다.
3. 자동검사는 두 모드 모두 필수다. 확정 불일치는 `REJECTED`, 일시 장애는 `AUTO_CHECKING`에서 내구성 재시도한다.
4. 자동검사 통과 시 `reviewRequired=false`는 단일 finalizer가 `AUTO_APPROVED`와 Store를 함께 확정한다.
5. 자동검사 통과 시 `reviewRequired=true`는 `REVIEW_READY` 사건 하나를 만든다. 배정된 플랫폼 운영자 한 명이 승인·거절·보완 요청 중 하나를 결정한다.
6. 수동 승인도 같은 finalizer만 호출한다. Store는 승인 전에는 존재하지 않으며 동시 자동/수동 확정에도 하나만 생성된다.

## Platform Operator Contract

- 목록·상세·최초 자기 배정·명시적 재배정·결정·증빙 열람만 제공한다. 담당자별 한 명 배정을 사용하며 사용자에게 보이는 시간 제한·갱신 UI는 두지 않는다.
- 결정 action은 `APPROVE`, `REJECT`, `REQUEST_CHANGES`뿐이다. 현재 권한, 사건 배정, 사건/application version, 멱등 키와 일회 재인증을 모두 검증한다.
- 자동검사 실패 또는 미완료는 플랫폼 운영자가 우회 승인할 수 없다. 최초 유효한 종결 결정 하나만 남는다.
- 증빙은 `ONBOARDING_EVIDENCE_READ`, 현재 배정과 5분 일회 재인증을 모두 요구한다. 안정적인 GET endpoint가 byte stream을 반환하며 URL·객체 키·파일 ID를 노출하지 않는다.
- 재배정, 권한 회수, 신청 version 교체, 사건 종결은 기존 열람 승인을 즉시 무효화한다. 승인값은 저장소 읽기 전에 소비하므로 읽기 실패로 재사용할 수 없다.

## Data and Privacy Boundary

- 신청, version snapshot, 자동검사 job, review case와 결정은 Store 도메인이 소유한다. Platform Operator는 Store 공개 Service/DTO만 사용하며 onboarding entity/repository 또는 파일 저장소를 직접 참조하지 않는다.
- 동일 신청/version의 활성 사건은 하나이고, 사건 version당 종결 결정은 하나다. 작업 claim은 lease owner·증가 fencing token·만료 시각으로 stale worker를 배제한다.
- 원본 증빙, 객체 URL/key, 전체 사업자등록번호, 대표자명, 자동검사 제공자 payload, 재인증 승인값, token과 session은 응답·로그·분석·감사 JSON에서 제외한다.
- 증빙 조회 성공과 실패를 별도 감사 트랜잭션에 public ID, version, actor/authority snapshot, 허용된 결과·사유와 correlation ID만 기록한다.
- #344의 CURRENT/REPLACED 원장, 보존·파기 정책과 private confirmed 파일 조건을 그대로 사용한다.

## Migration and Frontend Handoff

- 이번 expand 단계의 순방향 Flyway migration은 `stores.business_type`과 `store_onboarding_application_versions.business_type`을 nullable로 완화하되 column과 기존 check constraint를 유지한다. 신규 코드는 두 값을 읽거나 쓰지 않고, 롤링 배포 중 구 코드는 기존 값을 계속 읽고 쓸 수 있어야 한다.
- 실제 column·constraint 삭제는 모든 구 task가 제거되고 신규 코드의 null 쓰기·롤백 호환성과 기존 데이터 보존을 검증한 뒤 별도 contract migration에서 수행한다.
- Store 생성·수정·멱등 fingerprint, onboarding snapshot·자동검사·finalizer와 공개 DTO에서 `BusinessType`을 제거한다. 검색 카테고리 또는 사업자등록증 업태·종목에서 호환 값을 파생하지 않는다.
- `docs/specs/store-onboarding/openapi.yaml`이 신규 신청 POST와 신청 상태 GET을 소유한다. `docs/specs/store-search/openapi.yaml`은 같은 collection의 소유 매장 GET만 유지하고 과거 JSON POST를 제거한다.
- 프런트 codegen은 onboarding OpenAPI를 별도 생성한다. 공통 typed client는 동일 URL의 Store Search GET과 Store Onboarding POST를 method 단위로 합성하며, 문서 간 중복 path를 교집합으로 조용히 병합하지 않는다.
- 로컬 mock 사업자 검증 adapter는 실제 PDF/JPEG/PNG signature 검사를 통과한 증빙과 일치하는 구조화 입력에 대해서만 기존 자동검사 흐름을 진행한다. production 성공을 가짜 응답으로 대체하지 않는다.

## Acceptance Criteria

- 두 switch 모드 모두 사업자등록증과 자동검사 없이는 Store가 생성되지 않는다.
- 신규 신청은 `businessType` 없이 접수·snapshot·자동검사·최종 Store 생성까지 완료된다. 호환 column은 expand 단계 동안 null을 허용한 채 유지되며, 구 task 0건과 혼합 버전·롤백 검증 전에는 제거하지 않는다.
- 매장 운영자는 사업자등록증의 구조화 필수 정보, 비공개 증빙 한 개와 활성 검색 카테고리 한 개를 제출할 수 있고 `202` 이후 신청 상태를 다시 확인할 수 있다.
- switch 변경은 이미 접수된 version의 `reviewRequired`에 영향을 주지 않는다.
- 자동 모드는 검사 통과 뒤 Store 하나, 수동 모드는 운영자 승인 전 Store 0개다.
- 보완 version 제출 뒤 이전 version 명령과 열람은 거절된다.
- 같은 사업자등록번호와 동시 승인/거절 경합은 기존 Store를 변경하지 않고 단일 결과에 수렴한다.
- 플랫폼 운영자 기능 flag가 꺼지면 심사 Controller와 해당 OpenAPI 경로가 노출되지 않는다.
- #223 완료 문구에는 실제 private 저장소에 대한 #277 신청과 5분 일회 열람 staging smoke 결과를 포함한다.
