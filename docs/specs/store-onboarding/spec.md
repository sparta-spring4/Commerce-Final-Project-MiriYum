# Store Onboarding Review Contract

## Purpose

모든 향후 신규 매장 신청은 사업자등록증 1개와 자동검사를 거친다. `miriyum.store.onboarding.manual-review-enabled`는 자동검사 통과 뒤 플랫폼 운영자 심사를 추가할지만 신청 version에 고정한다. 기존 승인 매장은 소급 재심사하거나 파일 제출을 요구하지 않는다.

실제 private S3 bucket·IAM·환경값과 staging 신청/일회 열람 smoke는 #223의 활성화 선행 조건이다. #277 코드 완료만으로 운영 저장소 활성화가 완료됐다고 보지 않는다.

## Store Operator Contract

- 기존 `POST /api/v1/store-operators/stores`가 유일한 신규 신청 진입점이다. 별도 신규 신청 API를 안내하지 않는다.
- 요청은 `multipart/form-data`이며 JSON `application` part와 `businessRegistrationEvidence` part를 각각 정확히 하나 요구한다.
- 증빙은 PDF, JPEG, PNG만 허용하고 최대 10,485,760 bytes다. 선언 MIME과 파일 signature가 일치해야 하며 암호화 PDF는 거절한다.
- 접수 성공은 Store가 아니라 `202 Accepted` 신청 데이터를 반환한다. 데이터는 `applicationId`, `applicationVersion`, `status`, `reviewRequired`, `nextAction`, nullable `storeId`만 포함한다.
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

## Acceptance Criteria

- 두 switch 모드 모두 사업자등록증과 자동검사 없이는 Store가 생성되지 않는다.
- switch 변경은 이미 접수된 version의 `reviewRequired`에 영향을 주지 않는다.
- 자동 모드는 검사 통과 뒤 Store 하나, 수동 모드는 운영자 승인 전 Store 0개다.
- 보완 version 제출 뒤 이전 version 명령과 열람은 거절된다.
- 같은 사업자등록번호와 동시 승인/거절 경합은 기존 Store를 변경하지 않고 단일 결과에 수렴한다.
- 플랫폼 운영자 기능 flag가 꺼지면 심사 Controller와 해당 OpenAPI 경로가 노출되지 않는다.
- #223 완료 문구에는 실제 private 저장소에 대한 #277 신청과 5분 일회 열람 staging smoke 결과를 포함한다.
