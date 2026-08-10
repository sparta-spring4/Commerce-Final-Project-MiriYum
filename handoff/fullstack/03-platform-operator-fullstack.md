# MiriYum 플랫폼 운영자 풀스택 화면 구현 상세 지시서

> **문서 지위:** 이 문서는 프론트 작업을 위한 비정본 인계 자료다. 제품·정책·아키텍처·API 사실은 이 문서가 소유하지 않는다. 충돌하거나 구현 시점이 달라졌다면 [`AGENTS.md`](../../AGENTS.md), [`ai/document-routing.md`](../../ai/document-routing.md), [`docs/00-index.md`](../../docs/00-index.md), 활성 [`service-policies`](../../docs/service-policies/README.md), 도메인별 `spec.md`·`openapi.yaml`, 실제 Controller·테스트 순으로 다시 확인한다.

> **직접 대조:** [`docs/01-product-vision.md`](../../docs/01-product-vision.md), [`docs/02-users-and-permissions.md`](../../docs/02-users-and-permissions.md), [`docs/05-functional-requirements.md`](../../docs/05-functional-requirements.md), [`docs/06-system-architecture.md`](../../docs/06-system-architecture.md)를 기준으로 한다. 현재 플랫폼 운영자용 승인 OpenAPI와 Controller는 없으므로 새 계약이 승인되기 전에는 실제 라우트·클라이언트·mock success를 만들지 않는다.

## 현재 단계와 구현 조건

1차 MVP와 2차 MVP에는 플랫폼 운영자 기능을 실제 서비스에 포함하지 않는다.

플랫폼 운영자는 고도화 전용이다. 1차·2차 MVP에는 계정·인증·OpenAPI가 없으므로 현재 빌드에 로그인, route, menu, API client, mock success를 만들지 않는다. 아래는 고도화 계약이 승인됐을 때 `03-platform-operator-design.md`를 구현하는 구조 지침이다.

구현을 시작하려면 플랫폼 운영자 인증 namespace, 역할·권한, 회원·입점·매장·예약·웨이팅·결제 복구 OpenAPI, 상태 전이·감사·민감정보 계약이 모두 있어야 한다. 일반 사용자·대표자 API를 재사용해 우회하지 않는다.

## 독립 셸 구조

- 권장 prefix: `/admin`
- 공개 route는 로그인만, 나머지는 운영자 인증·권한 guard 적용
- 일반 운영자와 슈퍼관리자는 route metadata와 서버 권한을 모두 검사
- Access·Refresh·CSRF는 별도 namespace와 쿠키 Path 사용
- 세션·토큰 수명은 고도화 구현 시 승인되는 인증 OpenAPI와 서버 응답을 기준으로 적용하고 과거 세션 정책의 수치를 선반영하지 않는다.
- feature modules: dashboard, members, onboarding, stores, reservations, waiting, payment-recovery, operators, audit
- 민감 원문 query는 기본 목록 query와 분리하고 재인증·사건 배정·필드 권한 확인

## 화면별 구현 계약

### 1. 운영자 로그인
- route `/admin/login`
- 승인된 별도 session·refresh·csrf endpoint만 사용
- 공개 회원가입 없음; 임시 비밀번호 변경 필요 응답은 `/admin/first-password-change`로 이동
- 일반 사용자·대표자 토큰은 namespace 불일치로 거부

### 2. 운영 홈
- route `/admin`
- 서버 capability와 권한에 따라 카드·query를 생성
- 한 집계 실패가 전체 화면을 성공·실패로 덮지 않도록 영역별 상태와 기준 시각 표시

### 3. 회원 관리
- routes `/admin/members`, `/admin/members/:accountType/:accountId`
- 목록 query key에 계정 유형·상태·기간·page 포함
- 제재·복구는 범용 account PATCH가 아니라 승인된 명령 endpoint 사용
- 고위험 명령은 사유, 재인증, Idempotency-Key, 결과 재조회와 감사 식별자 표시
- 404로 다른 유형 ID 존재 여부를 추측하지 않는다.

### 4. 입점 신청 목록
- routes `/admin/onboarding`, `/admin/onboarding/:applicationId`
- 신청·자료 version, 자동 검사, 배정, 상태를 서버 원장으로 사용

### 5. 입점 신청 상세·심사
- 승인·반려·보완을 별도 명령으로 연결하고 낙관 확정 금지
- 자료 version이 바뀌면 열린 폼을 무효화하고 재조회
- 사업자등록증은 짧은 수명의 권한 URL로만 보고 브라우저 캐시·로그·analytics에 URL을 남기지 않는다.

### 6. 매장 관리
- routes `/admin/stores`, `/admin/stores/:storeId`
- 비활성화·운영 중지는 승인된 명령·사유·영향 미리보기 사용
- 대표자 일상 관리 endpoint나 재고 endpoint를 플랫폼 권한으로 재사용하지 않는다.

### 7. 예약 관리
- routes `/admin/reservations`, `/admin/reservations/:reservationId`
- 예약, 메뉴 홀드, 결제, 체크인·노쇼는 각 원장 API의 읽기 계약을 조합하되 클라이언트에서 상태를 합성하지 않는다.
- 수동 복구는 사건 배정·권한·멱등·감사 endpoint가 있을 때만 활성화

### 8. 웨이팅 모니터링
- routes `/admin/waiting`, `/admin/waiting/:waitingId`
- 실시간 연결과 snapshot 조회를 분리하고 마지막 event cursor·기준 시각 표시
- 정상 호출 명령은 대표자 소유이며 플랫폼 우회 명령을 만들지 않는다.

### 9. 결제·환불 복구
- route `/admin/payment-recovery/:caseId`
- 제공자 결과 재조회와 내부 원장을 함께 표시하되 카드 원문 금지
- 금액·권한에 따른 1인/추가 슈퍼관리자 승인 상태를 서버 계약대로 표시
- 결과 불명 명령을 새 결제로 재시도하지 않는다.

### 10. 플랫폼 운영자 생성
- routes `/admin/operators`, `/admin/operators/new`, `/admin/operators/:operatorId`
- 슈퍼관리자 guard와 서버 권한 필수
- 생성·임시 비밀번호 전달은 별도 명령과 재인증 사용

### 11. 운영자 권한·상태 관리
- 중지와 권한 변경은 별도 명령·재인증·사유를 사용
- 자기 핵심 권한 제거·마지막 슈퍼관리자 제거는 서버 오류를 명확히 표시

### 12. 감사 이력
- routes `/admin/audit`, `/admin/audit/:eventId`
- 서버가 허용한 마스킹 필드만 렌더링
- 비밀번호·토큰·인증번호·결제수단·사업자증빙 원문을 검색 index나 프론트 로그에 넣지 않는다.

## 1차·2차 MVP 가시성

플랫폼 기능 flag가 꺼진 빌드는 `/admin` route chunk 자체를 등록하지 않고, 일반·대표자 navigation과 API client import graph에도 포함하지 않는다. 발표용 디자인 시안은 정적 프로토타입으로 분리하며 실제 성공 API를 흉내 내지 않는다.

## 공통 오류·동시성 처리

- 401: 별도 운영자 인증 복구 후 로그인
- 403: 권한 없음 또는 재인증 필요를 서버 code로 구분
- 404: 권한 범위의 존재 은닉을 유지
- 409: 이미 처리, version 변경, 상태 전이 충돌 후 최신 상세 재조회
- 429: Retry-After 표시
- 모든 명령은 버튼 잠금만 믿지 않고 Idempotency-Key 사용
- 처리 성공 응답 유실 시 같은 키 조회·상태 재조회로 복구

## 검증 체크리스트

- 1차·2차 빌드에 `/admin` route·menu·네트워크 요청이 없다.
- 일반 사용자·대표자 자격으로 운영 콘솔 접근이 불가능하다.
- 슈퍼관리자 전용 기능이 일반 운영자에게 렌더링·호출되지 않는다.
- 자료 version 변경과 중복 심사를 성공으로 덮지 않는다.
- 민감 URL·원문이 storage·log·analytics에 남지 않는다.
- 고위험 작업은 사유·재인증·권한·멱등·감사 조건을 모두 통과한다.
