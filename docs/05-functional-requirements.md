# 기능 요구사항 색인

이 색인은 정책 범위를 구현 약속으로 바꾸지 않는다. 상세 규칙, 상태 전이, 예외 및 인수 조건은 각 [서비스 정책 문서](service-policies/README.md)가 단일 원본이다. `정책 연계·비초기` 항목은 추적을 위한 연결이며 초기 구현 확정이 아니다.

| 요구사항 그룹 | 정책 ID | 초기 제공 | 소유 도메인 | 기능 명세 |
|---|---|---:|---|---|
| 도메인 경계 기준 | DOMAIN-000 | 초기 기준 | 모든 초기 도메인 | [정책 원본](service-policies/00-policy-template.md) |
| 회원·인증·계정 | AUTH-001, AUTH-002, AUTH-003, AUTH-004, AUTH-005, AUTH-006, AUTH-007, AUTH-008, AUTH-009, AUTH-010, AUTH-011, AUTH-012 | 초기 기준 | auth | [정책 원본](service-policies/01-member-auth.md) |
| 매장 입점·대표자 권한 | STORE-001, STORE-002, STORE-003, STORE-004, STORE-005, STORE-006, STORE-007, STORE-008, STORE-009, STORE-010, STORE-011, STORE-012, STORE-013, STORE-014 | 초기 기준 | store | [정책 원본](service-policies/02-store-onboarding.md) |
| 매장 운영·영업시간·메뉴 | OPER-001, OPER-002, OPER-003, OPER-004, OPER-005, OPER-006, OPER-007, OPER-008, OPER-009, OPER-010 | 초기 기준 | store | [정책 원본](service-policies/03-store-operation.md) |
| 방문 예약 | RES-001, RES-002, RES-003, RES-004, RES-005, RES-006, RES-007, RES-008, RES-009, RES-010, RES-011, RES-012, RES-013, RES-014, RES-015 | 초기 기준 | booking | [정책 원본](service-policies/04-reservation.md) |
| 현장·원격 웨이팅 | WAIT-001, WAIT-002, WAIT-003, WAIT-004, WAIT-005, WAIT-006, WAIT-007, WAIT-008, WAIT-009, WAIT-010, WAIT-011, WAIT-012, WAIT-013, WAIT-014, WAIT-015, WAIT-016, WAIT-017 | 초기 기준 | booking | [정책 원본](service-policies/05-waiting.md) |
| 메뉴 홀드 | HOLD-001, HOLD-002, HOLD-003, HOLD-004, HOLD-005, HOLD-006, HOLD-007, HOLD-008, HOLD-009, HOLD-010, HOLD-011, HOLD-012, HOLD-013 | 초기 기준 | booking | [정책 원본](service-policies/06-menu-hold.md) |
| 코스·테이스팅 | TASTE-001, TASTE-002, TASTE-003, TASTE-004, TASTE-005, TASTE-006, TASTE-007, TASTE-008, TASTE-009, TASTE-010, TASTE-011, TASTE-012 | 정책 연계·비초기 | booking | [정책 원본](service-policies/07-course-tasting.md) |
| 결제·환불·정산 | PAY-001, PAY-002, PAY-003, PAY-004, PAY-005, PAY-006, PAY-007, PAY-008, PAY-009, PAY-010, PAY-011, PAY-012, PAY-013, PAY-014, PAY-015 | 초기 기준 | payment | [정책 원본](service-policies/08-payment-refund.md) |
| 체크인·노쇼 | CHECK-001, CHECK-002, CHECK-003, CHECK-004, CHECK-005, CHECK-006, CHECK-007, CHECK-008, CHECK-009, CHECK-010 | 초기 기준 | booking | [정책 원본](service-policies/09-checkin-noshow.md) |
| 취소 자리·수량 자동 승계 | TRANSFER-001, TRANSFER-002, TRANSFER-003, TRANSFER-004, TRANSFER-005, TRANSFER-006, TRANSFER-007, TRANSFER-008, TRANSFER-009 | 정책 연계·비초기 | booking | [정책 원본](service-policies/10-waitlist-transfer.md) |
| 사용자·식당 구독 | SUB-001, SUB-002, SUB-003, SUB-004, SUB-005, SUB-006, SUB-007, SUB-008, SUB-009, SUB-010 | 정책 연계·비초기 | payment | [정책 원본](service-policies/11-subscription.md) |
| 리뷰·신뢰·어뷰징 | TRUST-001, TRUST-002, TRUST-003, TRUST-004, TRUST-005, TRUST-006, TRUST-007, TRUST-008, TRUST-009, TRUST-010, TRUST-011, TRUST-012 | 초기 기준 | review | [정책 원본](service-policies/12-review-trust.md) |
| 광고·추천 | ADS-001, ADS-002, ADS-003, ADS-004, ADS-005, ADS-006, ADS-007, ADS-008 | 정책 연계·비초기 | store | [정책 원본](service-policies/13-ad-recommendation.md) |
| 분석·수요 리포트 | ANALYTICS-001, ANALYTICS-002, ANALYTICS-003, ANALYTICS-004, ANALYTICS-005, ANALYTICS-006, ANALYTICS-007, ANALYTICS-008, ANALYTICS-009 | 정책 연계·비초기 | store | [정책 원본](service-policies/14-analytics-report.md) |
| 운영자·분쟁·수동 복구 | ADMIN-001, ADMIN-002, ADMIN-003, ADMIN-004, ADMIN-005, ADMIN-006, ADMIN-007, ADMIN-008, ADMIN-009, ADMIN-010, ADMIN-011, ADMIN-012 | 정책 연계·비초기 | 모든 초기 도메인 | [정책 원본](service-policies/15-admin-operation.md) |
| 알림 | NOTI-001, NOTI-002, NOTI-003, NOTI-004, NOTI-005, NOTI-006, NOTI-007, NOTI-008, NOTI-009, NOTI-010 | 초기 기준 | notification | [정책 원본](service-policies/16-notification.md) |
| 개인정보·보안 | PRIV-001, PRIV-002, PRIV-003, PRIV-004, PRIV-005, PRIV-006, PRIV-007, PRIV-008, PRIV-009, PRIV-010, PRIV-011, PRIV-012, PRIV-013 | 정책 연계·비초기 | 모든 초기 도메인 | [정책 원본](service-policies/17-privacy-security.md) |
| 대규모 트래픽·분산 환경·장애 복구 | SCALE-001, SCALE-002, SCALE-003, SCALE-004, SCALE-005, SCALE-006, SCALE-007, SCALE-008, SCALE-009, SCALE-010, SCALE-011, SCALE-012, SCALE-013, SCALE-014, SCALE-015, SCALE-016 | 정책 연계·비초기 | 모든 초기 도메인 | [정책 원본](service-policies/18-scale-reliability.md) |
