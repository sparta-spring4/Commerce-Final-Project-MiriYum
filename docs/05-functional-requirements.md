# 기능 요구사항 색인

이 색인은 정책 범위를 구현 약속으로 바꾸지 않는다. 상세 규칙, 상태 전이, 예외 및 인수 조건은 각 [서비스 정책 문서](service-policies/README.md)가 단일 원본이다. 정책 상태·중요도와 MVP 제공 여부는 서로 독립적이며, 같은 정책 그룹 안에서도 현재 MVP와 후속 범위가 함께 존재할 수 있다.

`MVP 관계`는 다음 네 값으로 구분한다.

- `초기 핵심`: 기능 그룹의 대표 사용자 흐름을 1차 MVP에 제공한다.
- `부분 초기`: 기능 그룹의 일부 하위 범위만 1차 MVP에 제공하며 나머지는 상세 정책의 후속·TODO 경계를 따른다.
- `초기 공통 기준`: 별도 초기 업무 도메인이나 독립 기능을 만들지 않고 모든 관련 초기 기능의 구현·운영 기준으로 적용한다.
- `비초기`: 정책 추적과 미래 경계만 보존하며 현재 기능·권한·필수 구현을 활성화하지 않는다.

| 요구사항 그룹 | 정책 ID | MVP 관계 | 소유 도메인 | 기능 명세 | MVP 경계 |
|---|---|---:|---|---|---|
| 도메인 경계 기준 | DOMAIN-000 | 초기 공통 기준 | 모든 초기 도메인 | [정책 원본](service-policies/00-policy-template.md) | 모든 초기 기능이 정책 소유권과 공통 검토 항목을 따른다. |
| 회원·인증·계정 | AUTH-001, AUTH-002, AUTH-003, AUTH-004, AUTH-005, AUTH-006, AUTH-007, AUTH-008, AUTH-009, AUTH-010, AUTH-011, AUTH-012 | 부분 초기 | auth | [정책 원본](service-policies/01-member-auth.md) | 현재 계정·인증 흐름은 초기 범위다. `AUTH-004` 주류 연령 확인과 각 정책의 명시적 미래·TODO 조각은 제외한다. |
| 매장 입점·대표자 권한 | STORE-001, STORE-002, STORE-003, STORE-004, STORE-005, STORE-006, STORE-007, STORE-008, STORE-009, STORE-010, STORE-011, STORE-012, STORE-013, STORE-014 | 부분 초기 | store | [정책 원본](service-policies/02-store-onboarding.md) | 신규 입점과 현재 운영 권한은 초기 범위다. `STORE-013` 본사·지점 예외와 `STORE-014` 폐업·양도·대표자 변경 절차는 제외한다. |
| 매장 운영·영업시간·메뉴 | OPER-001, OPER-002, OPER-003, OPER-004, OPER-005, OPER-006, OPER-007, OPER-008, OPER-009, OPER-010 | 초기 핵심 | store | [정책 원본](service-policies/03-store-operation.md) | 매장·영업시간·메뉴의 기본 운영을 제공하며 주류 등 비초기 기능에만 필요한 명령은 활성화하지 않는다. |
| 방문 예약 | RES-001, RES-002, RES-003, RES-004, RES-005, RES-006, RES-007, RES-008, RES-009, RES-010, RES-011, RES-012, RES-013, RES-014, RES-015 | 부분 초기 | booking | [정책 원본](service-policies/04-reservation.md) | 즉시 확정 예약 흐름을 제공하며 매장 승인제 등 상세 정책이 후속으로 정한 진입 경로는 제외한다. |
| 현장·원격 웨이팅 | WAIT-001, WAIT-002, WAIT-003, WAIT-004, WAIT-005, WAIT-006, WAIT-007, WAIT-008, WAIT-009, WAIT-010, WAIT-011, WAIT-012, WAIT-013, WAIT-014, WAIT-015, WAIT-016, WAIT-017 | 부분 초기 | booking | [정책 원본](service-policies/05-waiting.md) | 로그인 사용자, 공통 3km, 단일 FIFO와 기본 호출 흐름을 제공한다. 정책 문서의 `1차 MVP 정책별 적용 범위`에 적힌 고급 경계는 제외한다. |
| 메뉴 홀드 | HOLD-001, HOLD-002, HOLD-003, HOLD-004, HOLD-005, HOLD-006, HOLD-007, HOLD-008, HOLD-009, HOLD-010, HOLD-011, HOLD-012, HOLD-013 | 초기 핵심 | booking | [정책 원본](service-policies/06-menu-hold.md) | 예약 연계 메뉴 홀드와 확정된 업종별 예외를 제공하고 상세 정책의 수량·복구 기준을 적용한다. |
| 코스·테이스팅 | TASTE-001, TASTE-002, TASTE-003, TASTE-004, TASTE-005, TASTE-006, TASTE-007, TASTE-008, TASTE-009, TASTE-010, TASTE-011, TASTE-012 | 비초기 | booking | [정책 원본](service-policies/07-course-tasting.md) | 코스·테이스팅, 관련 선결제·설문·행사·회차 기능은 1차 MVP에서 제외한다. |
| 결제·환불·정산 | PAY-001, PAY-002, PAY-003, PAY-004, PAY-005, PAY-006, PAY-007, PAY-008, PAY-009, PAY-010, PAY-011, PAY-012, PAY-013, PAY-014, PAY-015 | 부분 초기 | payment | [정책 원본](service-policies/08-payment-refund.md) | 예약금·취소·환불의 서비스 흐름은 초기 범위다. 운영 PG 계약 `PAY-003`과 실제 식당 정산 `PAY-012`는 TODO다. |
| 체크인·노쇼 | CHECK-001, CHECK-002, CHECK-003, CHECK-004, CHECK-005, CHECK-006, CHECK-007, CHECK-008, CHECK-009, CHECK-010 | 부분 초기 | booking | [정책 원본](service-policies/09-checkin-noshow.md) | 회전형 QR, 권한 있는 매장 운영자의 보조 처리와 공통 5분 기준을 제공한다. 정책별 표의 자동 판정·제재·이의·고급 복구는 제외한다. |
| 취소 자리·수량 자동 승계 | TRANSFER-001, TRANSFER-002, TRANSFER-003, TRANSFER-004, TRANSFER-005, TRANSFER-006, TRANSFER-007, TRANSFER-008, TRANSFER-009 | 초기 핵심 | booking | [정책 원본](service-policies/10-waitlist-transfer.md) | 취소로 반환된 예약 수용량과 메뉴 수량의 대기 등록·FIFO 제안·선점·전환 흐름을 제공한다. |
| 사용자·식당 구독 | SUB-001, SUB-002, SUB-003, SUB-004, SUB-005, SUB-006, SUB-007, SUB-008, SUB-009, SUB-010 | 비초기 | payment | [정책 원본](service-policies/11-subscription.md) | 사용자 구독과 매장 Pro, 구독자 전용 혜택·유료 청구는 1차 MVP에서 제외한다. |
| 리뷰·신뢰·어뷰징 | TRUST-001, TRUST-002, TRUST-003, TRUST-004, TRUST-005, TRUST-006, TRUST-007, TRUST-008, TRUST-009, TRUST-010, TRUST-011, TRUST-012 | 초기 핵심 | review | [정책 원본](service-policies/12-review-trust.md) | 방문 기반 리뷰와 신고·신뢰 안전 기준을 제공하며 미래 기능의 우선권이나 금전 책임을 만들지 않는다. |
| 광고·추천 | ADS-001, ADS-002, ADS-003, ADS-004, ADS-005, ADS-006, ADS-007, ADS-008 | 부분 초기 | store | [정책 원본](service-policies/13-ad-recommendation.md) | 일반 검색·자연어·취향·품절 대체 추천은 초기 무료 기능이다. 광고 상품·과금·광고 제거는 제외한다. |
| 분석·수요 리포트 | ANALYTICS-001, ANALYTICS-002, ANALYTICS-003, ANALYTICS-004, ANALYTICS-005, ANALYTICS-006, ANALYTICS-007, ANALYTICS-008, ANALYTICS-009 | 부분 초기 | store | [정책 원본](service-policies/14-analytics-report.md) | 매장 Free 기본 운영 통계는 초기 범위다. Pro 비교·해석·추천·자동 리포트는 제외한다. |
| 운영자·분쟁·수동 복구 | ADMIN-001, ADMIN-002, ADMIN-003, ADMIN-004, ADMIN-005, ADMIN-006, ADMIN-007, ADMIN-008, ADMIN-009, ADMIN-010, ADMIN-011, ADMIN-012 | 초기 공통 기준 | 모든 초기 도메인 | [정책 원본](service-policies/15-admin-operation.md) | 현재 입점·분쟁·복구·장애 흐름에 필요한 최소 권한·감사 기준을 적용한다. TODO와 비초기 기능용 절차는 활성화하지 않는다. |
| 알림 | NOTI-001, NOTI-002, NOTI-003, NOTI-004, NOTI-005, NOTI-006, NOTI-007, NOTI-008, NOTI-009, NOTI-010 | 부분 초기 | notification | [정책 원본](service-policies/16-notification.md) | 초기 거래의 필수·선택 알림과 실패 처리를 제공한다. `NOTI-009` 보관 기간의 미정 범위는 TODO다. |
| 개인정보·보안 | PRIV-001, PRIV-002, PRIV-003, PRIV-004, PRIV-005, PRIV-006, PRIV-007, PRIV-008, PRIV-009, PRIV-010, PRIV-011, PRIV-012, PRIV-013 | 초기 공통 기준 | 모든 초기 도메인 | [정책 원본](service-policies/17-privacy-security.md) | 확정된 최소 수집·암호화·접근·삭제 안전 기준을 적용한다. `AUTH-004` 연령 정보와 명시적 TODO는 현재 기능으로 활성화하지 않는다. |
| 대규모 트래픽·분산 환경·장애 복구 | SCALE-001, SCALE-002, SCALE-003, SCALE-004, SCALE-005, SCALE-006, SCALE-007, SCALE-008, SCALE-009, SCALE-010, SCALE-011, SCALE-012, SCALE-013, SCALE-014, SCALE-015, SCALE-016 | 초기 공통 기준 | 모든 초기 도메인 | [정책 원본](service-policies/18-scale-reliability.md) | 초기 용량·SLO와 동시성·복구 불변식을 적용한다. 구체 인프라 도입은 [시스템 아키텍처](06-system-architecture.md)의 단계적 기준을 따른다. |
