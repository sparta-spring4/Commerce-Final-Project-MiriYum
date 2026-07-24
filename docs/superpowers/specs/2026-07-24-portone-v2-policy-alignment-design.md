# PortOne V2 결제 연동 정책 정합성 설계

## 목표

사용자가 확정한 PortOne V2 사용 결정을 현재 결제 정책과 연결 문서에 반영한다. 현재 문서에서 PortOne V2가 Toss Payments·개별 결제수단과 함께 `PAY-003`의 목표 후보이자 `TODO`로 남은 모순을 제거하고, 실제 식당 정산 `PAY-012`와 운영 계약 전제는 별도 경계로 유지한다.

## 확정 결정

- MiriYum의 현재 결제 연동 어댑터는 PortOne V2로 확정한다.
- `PAY-003`은 PortOne V2 선택이 완료됐다는 의미로 `확정`으로 전환한다.
- 브라우저 결제 요청, 서버 API 조회, 웹훅 수신과 검증, 결제·환불 명령은 PortOne V2 경계를 통한다.
- 결제 성공은 사용자 화면이나 클라이언트 응답만으로 확정하지 않고, PortOne V2의 서버 조회와 검증된 웹훅을 내부 주문·금액 상태와 대조한 뒤 확정한다.

## 참조 구현 기준

사용자가 지정한 [Agora 저장소](https://github.com/sparta-spring4/Commerce-live-chat-system-Agora)의 `dev` 브랜치 커밋 `bf662e2888c625fd27f751ad932022dbb6dc0e2d`에 있는 PortOne V2 결제 구조를 MiriYum 구현 기준으로 사용한다.

- 서버는 MiriYum 내부 결제 레코드 ID와 내부 주문 ID를 먼저 만든다. PortOne `paymentId`는 고객사가 채번하는 결제 건 ID이므로 준비된 내부 주문 ID에서 일대일로 파생하고, 하나의 `paymentId`에 여러 결제 시도가 생길 수 있지만 최종 성공은 한 번만 허용한다.
- 프론트엔드는 PortOne V2 브라우저 SDK를 로드하고 `window.PortOne.requestPayment`에 `storeId`, `channelKey`, 준비된 `paymentId`, 주문명, 금액, 통화, 결제수단, 최소 고객 정보와 복귀 URL을 전달한다. 브라우저 확정 요청은 이 `paymentId`와 인증된 MiriYum 내부 결제 참조만 조회 선택자로 서버에 전달하며 어느 값도 성공 근거가 아니다.
- 서버 결제 도메인은 `PaymentClient` 포트를 소유하고, 운영·Docker 프로필은 `PortOnePaymentClient`, 로컬 프로필은 `LocalPaymentClient` 구현체를 사용한다.
- PortOne 운영 클라이언트는 API Secret으로 인증하고 이미 준비된 `paymentId`로 결제를 조회한다. 브라우저가 보낸 상태·금액·`transactionId` 주장은 무시하고 조회 응답의 상태, 정확한 금액·통화와 내부 주문 매핑을 검증한다. PortOne이 결제 시도별로 부여한 `transactionId`는 인증된 서버 조회 또는 검증된 웹훅에서만 신뢰해 시도별로 저장한다.
- 웹훅은 별도 공개 엔드포인트에서 원문 본문, PortOne 타임스탬프와 서명을 검증한다. HMAC-SHA256, 제한된 타임스탬프 허용오차와 상수 시간 비교를 사용하며, `paymentId`, `transactionId`, 이벤트 유형과 타임스탬프 또는 메시지 식별자를 구분해 상관·중복 제거한다. 유효한 `PAID` 사건도 사용자·운영자 확정과 같은 서버 조회·확정 경로를 호출한다.
- 결제 확정 시작 시 중앙 상태를 `CONFIRMING`으로 선점하고, 서버 조회 예외에는 이전 상태로 복구한다. 일정 시간 이상 남은 `CONFIRMING`은 중앙 작업이 다시 결제 가능 상태로 복구한다.
- Store ID와 Channel Key는 프론트엔드 공개 설정이다. API Base URL은 서버 전용 비밀이 아닌 설정이고, API Secret과 Webhook Secret은 서버 비밀이다. 서버 전용 설정과 비밀을 프론트에 노출하지 않으며 비밀은 로그·저장소에도 노출하지 않는다.

MiriYum에는 위 PortOne V2 연동 패턴만 적용한다. Agora의 중고거래·쿠폰·판매자 정산 모델, V1 `window.IMP` 호환 fallback, Agora 전용 상태명과 API 경로는 복제하지 않고 MiriYum의 예약금·홀드·취소·환불 정책에 맞춘다.

## 확정하지 않는 범위

- Toss Payments를 최초 PG 채널로 고정하지 않는다.
- 신용·체크카드, 토스페이, 카카오페이, 네이버페이를 현재 필수 결제수단으로 고정하지 않는다.
- 실제 활성 PG 채널과 결제수단은 PortOne 콘솔의 채널 구성, 계약, 업종·결제수단 심사와 운영 환경 설정이 완료된 항목만 허용한다.
- 계약·심사·수수료·취소·분쟁 조건은 운영 전제이며 PortOne V2라는 연동 어댑터 선택을 다시 미확정으로 돌리지 않는다.
- 식당 지급대행, 세무·법률 검토, 정산 주기와 실제 자금 흐름은 `PAY-012`의 `TODO`로 유지한다.
- PortOne V2 선택만으로 구독, 메뉴 대금 선결제, 코스·테이스팅 또는 비초기 결제 기능을 활성화하지 않는다.

## 문서별 정렬

### 결제 상세 정책

`docs/service-policies/08-payment-refund.md`에서 `PAY-003`을 `확정`으로 이동하고 PortOne V2를 단일 결제 어댑터 경계로 명시한다. Toss Payments와 네 가지 결제수단을 확정 후보처럼 나열한 문구는 제거한다. `PAY-006`, `PAY-010`, `PAY-011`, `PAY-014`, `PAY-015`의 제공자 조회·웹훅·환불·분쟁·대사 흐름은 PortOne V2 경계를 사용하도록 표현을 통일한다.

### 정책 마스터와 기능 요구사항

`docs/service-policies/README.md`에서 `PAY-003` 상태를 `확정`으로 바꾸고 현재 `TODO` 목록과 집계를 다시 계산한다. `docs/05-functional-requirements.md`는 예약금·취소·환불이 PortOne V2를 통해 제공되고 `PAY-012` 실제 식당 정산만 현재 결제 그룹의 명시적 `TODO`라는 경계를 요약한다.

### 아키텍처와 사용자 흐름

`docs/06-system-architecture.md`는 `payment` 모듈의 외부 결제 포트 구현체를 PortOne V2 어댑터로 고정한다. 도메인 로직은 PortOne SDK·API 모델을 직접 소유하지 않고 내부 결제 포트를 통해 호출하며, 채널·결제수단 설정은 외부 구성으로 둔다. 로컬과 운영 프로필의 결제 클라이언트 분리, 프론트 공개 설정·서버 전용 비밀이 아닌 설정·서버 비밀의 경계, `CONFIRMING` 복구 작업도 참조 구현과 같은 책임으로 기록한다.

`docs/04-user-flows.md`의 결제·취소 흐름은 사용자 행동과 실패 복구를 소유한다. 내부 결제·주문과 그 주문에서 일대일 파생한 PortOne `paymentId` 준비, PortOne V2 SDK 요청, 서버의 인증 조회 결과에 포함된 상태·금액·통화·내부 주문 매핑 및 결제 시도별 `transactionId` 검증, 동일 확정 경로의 웹훅 처리, 결과 불명확·장기 `CONFIRMING` 복구를 순서대로 명시하되 PG 채널이나 개별 결제수단을 약속하지 않는다.

### 데이터·API 계약

`docs/07-data-and-api-contracts.md`는 내부 결제 레코드 ID·내부 주문 ID·PortOne `paymentId`·PortOne `transactionId`의 채번 주체와 일대일 또는 시도별 관계를 소유한다. 브라우저 입력은 조회 선택자일 뿐 성공 근거가 아니며, 서버 조회와 검증된 웹훅만 상태·금액·통화·`transactionId`를 신뢰한다는 계약도 이 문서에 둔다.

### 아키텍처 결정 기록

`docs/adr/ADR-005-portone-v2-payment-adapter.md`는 PortOne V2 어댑터 선택, 식별자 신뢰 경계, 공개·서버 전용·비밀 설정 분류와 동일 멱등 확정·복구 결정을 장기 기록한다.

### 결정 기록

`miriyum-service-decisions.md`에 사용자의 현재 결정을 새 날짜 섹션으로 기록한다. 과거에 PortOne V2·Toss Payments를 후보로 둔 날짜별 기록은 역사로 보존하되, 현재 상태 목록에서는 `PAY-003`을 `TODO`에서 제거하고 최신 정책 집계를 반영한다.

## 공식 기술 근거

- PortOne V2는 결제 요청용 SDK, 서버 API와 웹훅 연동 경계를 제공한다.
- PortOne의 실제 PG사와 결제수단은 별도 채널 추가·전자결제 신청 과정으로 구성된다.
- 따라서 PortOne V2 연동 선택과 특정 PG 채널·결제수단 계약은 분리할 수 있다.
- Agora 참조 구현도 브라우저 SDK, 서버 결제 포트, 운영·로컬 구현체, 서버 조회 검증, 웹훅 검증과 확정 복구를 분리한다.

참조:

- [Agora 결제 프론트 API](https://github.com/sparta-spring4/Commerce-live-chat-system-Agora/blob/bf662e2888c625fd27f751ad932022dbb6dc0e2d/frontend/src/api/paymentApi.js)
- [Agora PortOne 서버 클라이언트](https://github.com/sparta-spring4/Commerce-live-chat-system-Agora/blob/bf662e2888c625fd27f751ad932022dbb6dc0e2d/src/main/java/com/team7/agora/domain/payment/client/PortOnePaymentClient.java)
- [Agora PortOne 웹훅 컨트롤러](https://github.com/sparta-spring4/Commerce-live-chat-system-Agora/blob/bf662e2888c625fd27f751ad932022dbb6dc0e2d/src/main/java/com/team7/agora/domain/payment/controller/PaymentWebhookController.java)
- [PortOne 결제 연동 문서](https://developers.portone.io/opi/ko/readme?v=v2)
- [PortOne V2 연동 준비](https://developers.portone.io/opi/ko/integration/ready/readme)
- [PortOne V2 인증 결제 연동](https://developers.portone.io/opi/ko/integration/start/v2/checkout?v=v2)
- [PortOne V2 웹훅 연동](https://developers.portone.io/opi/ko/integration/webhook/readme-v2?v=v2)
- [PortOne 전자결제 신청](https://developers.portone.io/opi/ko/console/guide/reg?v=v2)

## 검증 기준

- 현재 정본에서 `PAY-003`은 `확정`이고 `PAY-012`만 결제 그룹의 `TODO`로 남는다.
- 현재 정본에 PortOne V2를 목표 후보·미확정 제공자로 표현한 문장이 없다.
- Toss Payments와 네 가지 결제수단을 현재 필수 또는 확정 대상으로 표현하지 않는다.
- 결제 요청·웹훅·서버 조회·환불·분쟁·대사 흐름이 PortOne V2 어댑터 경계와 일치한다.
- 내부 결제 레코드 ID, 내부 주문 ID, 고객사가 채번한 PortOne `paymentId`와 PortOne이 시도별 채번한 `transactionId`가 분리되고, 브라우저가 보낸 상태·금액·`transactionId`를 성공 근거로 신뢰하지 않는다.
- 결제 흐름이 Agora 참조 구현의 준비·SDK 요청·서버 재검증·동일 웹훅 확정·`CONFIRMING` 복구 순서를 보존한다.
- 운영·로컬 결제 클라이언트, 프론트 공개 설정, 서버 전용 비밀이 아닌 설정과 서버 비밀이 분리되고 V1 `window.IMP` fallback을 현재 구현 요구로 만들지 않는다.
- 정책 마스터의 총계와 현재 결정 기록의 총계·목록이 일치한다.
- 기존 예약금, 환불, 결과 불명확 격리와 `PAY-012` 정산 `TODO`는 변경되지 않는다.
- 변경 파일의 상대 링크, UTF-8, BOM, 끝 개행과 `git diff --check`가 통과한다.

## 변경 허용 목록

- `docs/04-user-flows.md`
- `docs/05-functional-requirements.md`
- `docs/06-system-architecture.md`
- `docs/07-data-and-api-contracts.md`
- `docs/adr/ADR-005-portone-v2-payment-adapter.md`
- `docs/service-policies/08-payment-refund.md`
- `docs/service-policies/README.md`
- `miriyum-service-decisions.md`
- 이 설계 문서
- 대응 구현 계획
