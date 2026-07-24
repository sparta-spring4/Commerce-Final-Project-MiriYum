# ADR-005: PortOne V2 결제 어댑터

- 상태: Accepted
- 결정일: 2026-07-24

## 배경

결제 연동 방식은 PortOne V2로 선택됐지만 현재 아키텍처와 API 문서는 결제 도메인의 어댑터와 외부 제공자 경계를 후보 또는 미결정 상태로 남겨 두었다. 브라우저 성공 응답을 내부 결제 성공으로 오인하지 않고, 로컬 개발과 운영 연동을 분리하면서도 결제·예약 상태가 하나의 검증 경로로 수렴하도록 지속 가능한 경계가 필요하다.

## 결정

결제 도메인은 내부 포트 `PaymentClient`를 소유한다. 운영 및 Docker 프로필은 `PortOnePaymentClient`를 사용하고, 로컬 프로필은 실제 외부 결제를 생성하지 않는 `LocalPaymentClient`를 사용한다. PortOne SDK·API 타입은 어댑터 경계에서 내부 모델로 변환하며 도메인 로직에 직접 노출하지 않는다.

서버는 MiriYum 내부 결제 레코드 ID와 내부 주문 ID를 먼저 만든다. 고객사가 채번하는 PortOne `paymentId`는 준비된 내부 주문 ID에서 일대일로 파생하고, 하나의 `paymentId`에 여러 결제 시도가 생길 수 있지만 최종 성공은 한 번만 허용한다. PortOne이 개별 결제 시도에 부여하는 `transactionId`는 하나의 `paymentId` 아래 시도마다 달라질 수 있다. 프런트엔드는 PortOne V2 브라우저 SDK를 로드하고 Store ID, Channel Key, 준비된 `paymentId`, 주문명, 최소 통화 단위 정수 금액과 통화, 활성화된 결제수단, 최소 고객 정보 및 복귀 URL로 결제를 요청한다.

브라우저 확정 요청은 이미 준비된 PortOne `paymentId`와 인증된 내부 결제 참조만 조회 선택자로 전달하며 둘 다 성공 근거가 아니다. 서버는 브라우저가 주장한 상태·금액·`transactionId`를 무시하고 알려진 `paymentId`를 PortOne V2 API에서 조회해 상태, 정확한 금액·통화와 내부 주문 매핑을 검증한다. `transactionId`는 인증된 서버 조회 또는 검증된 웹훅에서만 신뢰해 시도별로 저장한다. 검증된 웹훅은 `paymentId`, `transactionId`, 이벤트 유형과 타임스탬프 또는 메시지 식별자를 구분해 상관·중복 제거한 뒤 사용자 확정 요청과 같은 멱등 확정 경로를 호출한다. 확정 처리는 중앙 상태를 `CONFIRMING`으로 조건부 선점하고, 조회 예외가 발생하면 이전 상태로 되돌린다. 장시간 남은 `CONFIRMING`은 중앙 복구 작업이 서버 조회를 거쳐 결제 가능 상태 또는 확정 가능한 다음 상태로 수렴시킨다.

Store ID와 Channel Key는 프런트엔드 공개 설정이다. API Base URL은 서버 전용 비밀이 아닌 설정이고, API Secret과 Webhook Secret은 서버 비밀이다. 서버 전용 설정과 비밀을 프런트엔드에 노출하지 않으며 비밀은 로그 또는 저장소에도 노출하지 않는다.

## 검토한 대안

- V1 `window.IMP` 호환 경로: 현재 선택한 V2 계약을 이중화하고 구형 전역 API에 결합하므로 거절한다.
- PortOne SDK·API 타입을 도메인 로직에서 직접 사용: 외부 계약 변경이 결제 정책과 상태 모델에 전파되므로 거절한다.
- 계약·심사·운영 구성 전에 Toss Payments 또는 개별 결제수단을 고정: 어댑터 선택과 실제 PG·결제수단 활성화 조건을 혼합하므로 거절한다.
- Agora의 마켓플레이스·쿠폰·판매자 정산 모델 복제: MiriYum의 예약금·홀드·취소·환불 경계와 `PAY-012` 식당 정산 `TODO`를 침범하므로 거절한다.

## 긍정적 결과

- 결제 정책과 외부 SDK·API 변경이 내부 포트로 분리된다.
- 브라우저 확정 요청과 검증된 웹훅이 같은 멱등 경로로 수렴한다.
- 로컬 개발은 실제 외부 결제를 만들지 않고도 내부 결제 흐름을 검증할 수 있다.
- 공개 설정과 서버 비밀의 소유 경계가 명확해진다.

## 부정적 결과

- 어댑터 모델 변환, 서버 재조회, 웹훅 검증과 중앙 복구 작업을 별도로 구현·운영해야 한다.
- `CONFIRMING` 상태와 조건부 전이를 관측하고 장기 체류를 복구하는 운영 책임이 생긴다.
- PortOne V2 선택과 별개로 실제 PG 채널·결제수단의 계약·심사·운영 구성을 완료해야 한다.

## 재검토 조건

PortOne V2의 지원·보안·계약 조건이 현재 요구를 충족하지 못하거나, 다른 제공자가 필요한 결제·환불·웹훅·대사 능력을 더 안전하게 제공하거나, 로컬 어댑터가 실제 연동과의 계약 차이를 재현하지 못한다는 검증 결과가 나오면 재검토한다. 실제 식당 정산, 특정 PG 채널과 개별 결제수단은 각각의 계약·법률·운영 결정에서 검토하며 이 ADR만으로 확정하지 않는다.

## 관련 문서

- [Agora 결제 프런트 API](https://github.com/sparta-spring4/Commerce-live-chat-system-Agora/blob/bf662e2888c625fd27f751ad932022dbb6dc0e2d/frontend/src/api/paymentApi.js)
- [Agora PortOne 서버 클라이언트](https://github.com/sparta-spring4/Commerce-live-chat-system-Agora/blob/bf662e2888c625fd27f751ad932022dbb6dc0e2d/src/main/java/com/team7/agora/domain/payment/client/PortOnePaymentClient.java)
- [Agora PortOne 웹훅 컨트롤러](https://github.com/sparta-spring4/Commerce-live-chat-system-Agora/blob/bf662e2888c625fd27f751ad932022dbb6dc0e2d/src/main/java/com/team7/agora/domain/payment/controller/PaymentWebhookController.java)
- [PortOne V2 인증 결제 연동](https://developers.portone.io/opi/ko/integration/start/v2/checkout?v=v2)
- [PortOne V2 웹훅 연동](https://developers.portone.io/opi/ko/integration/webhook/readme-v2?v=v2)
- [PortOne V2 결제 연동 정책 정합성 설계](../superpowers/specs/2026-07-24-portone-v2-policy-alignment-design.md)
- [시스템 아키텍처](../06-system-architecture.md)
- [데이터 및 API 계약](../07-data-and-api-contracts.md)
- [결제·환불·정산 정책](../service-policies/08-payment-refund.md)
- [ADR-002](ADR-002-staged-technology-adoption.md)
