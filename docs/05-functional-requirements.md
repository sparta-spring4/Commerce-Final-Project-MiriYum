# 기능 요구사항 색인

이 색인은 정책 범위를 구현 약속으로 바꾸지 않는다. 상세 규칙, 상태 전이, 예외 및 인수 조건은 각 [서비스 정책 문서](service-policies/README.md)가 단일 원본이다. `정책 결정 상태`와 `적용 단계`는 서로 독립적이며, 확정 정책도 지정 단계 전에는 기능·권한·API·UI·상태 진입 경로를 활성화하지 않는다.

`적용 단계`는 다음 네 값 또는 필요한 조합으로 구분한다.

- `1차 MVP`: 계정, 구조화 사업자 정보의 자동 입점, 텍스트 기반 매장·메뉴 탐색과 예약·메뉴 홀드·픽업 예약의 기본 거래.
- `2차 MVP`: AI 없는 규칙 해석, QueryDSL 조회, 이력 기반 결정적 추천, 품절 대안과 지도 탐색.
- `고도화`: 단계 진입 시 모두 구현·활성화·검증해야 하는 웨이팅·SSE, 결제·환불, 체크인·노쇼, 일반 취소 승계, Free 기본 통계, 플랫폼 운영자와 알림.
- `향후 고도화`: 현재 활성화하지 않으며 시간과 근거가 남을 때 별도 승인해 검토하는 코스·구독·리뷰·광고 상품·Pro 및 AI 설명·채팅 후보.

공급자 정확 버전·운영 수치·토폴로지 같은 결정 게이트는 각 기능 활성화 전까지 미정일 수 있지만, 이를 이유로 `고도화`의 승인 기능을 구현자 선택이나 시간 여유 항목으로 바꾸지 않는다.

정책 결정 상태 집계와 단계별 미결정 세부 집계도 분리한다.

- 정책 ID 상태는 `확정` 195개, `팀원 상의 필요` 0개, `TODO` 22개로 합계 217개다.
- `AUTH-007`의 Access JWT 1시간·Refresh JWT 14일, 브라우저 전달·저장·CSRF 경계, 1차 MVP 동시 로그인 무제한, 정상 비밀번호·이메일 변경 시 기존 로그인 유지와 Access JWT 만료 전 기존 중앙 재검증 경계 유지가 확정됐다. `1차 MVP` 인증 세부에 남은 `팀원 상의 필요` 항목은 없다.
- `TASTE-003`, `TASTE-008`, `TASTE-012`, `SUB-001`, `SUB-002`, `SUB-003`, `SUB-007`, `ADS-001`은 뒤 단계 `TODO`이므로 앞 단계 미결정 집계에 포함하지 않는다.

| 요구사항 그룹 | 정책 ID | 정책 결정 상태 | 적용 단계 | 소유 도메인 | 기능 명세 | 단계 경계 |
|---|---|---|---|---|---|---|
| 도메인 경계 기준 | DOMAIN-000 | 확정 | 전 단계 공통 | 모든 도메인 | [정책 원본](service-policies/00-policy-template.md) | 각 단계 기능이 정책 소유권과 공통 검토 항목을 따른다. |
| 회원·인증·계정 | AUTH-001, AUTH-002, AUTH-003, AUTH-004, AUTH-005, AUTH-006, AUTH-007, AUTH-008, AUTH-009, AUTH-010, AUTH-011, AUTH-012 | 확정·검토 혼재 | `1차 MVP`, `고도화` | auth | [정책 원본](service-policies/01-member-auth.md) | 1차는 계정별 무저장 Access/Refresh JWT, Access 1시간·Refresh 14일, 승인된 브라우저 전달·저장·CSRF 경계, 동시 로그인 무제한, 정상 정보 변경 시 기존 로그인 유지와 기존 중앙 재검증 경계를 적용한다. 고도화는 Valkey 회전·폐기·재사용 탐지와 일반 사용자 카카오 로그인이다. |
| 매장 입점·매장 운영자 권한 | STORE-001, STORE-002, STORE-003, STORE-004, STORE-005, STORE-006, STORE-007, STORE-008, STORE-009, STORE-010, STORE-011, STORE-012, STORE-013, STORE-014 | 확정·TODO 혼재 | `1차 MVP`, 일부 `고도화` | store | [정책 원본](service-policies/02-store-onboarding.md) | 1차는 입력한 사업자 정보와 프로젝트의 더미 사업자 기준 데이터를 비교하고 중앙 고유성을 확인한다. 더미 업종이 카페 또는 베이커리로 분류되면 픽업 자격을 부여하며 그 밖의 업종은 픽업만 실패 폐쇄한다. 정확한 더미 코드는 구현 fixture가 소유하고 국세청 공식 진위확인·버전된 업종 허용 매핑·파일·S3·플랫폼 운영자 심사는 고도화 경로다. |
| 매장 운영·영업시간·메뉴 | OPER-001, OPER-002, OPER-003, OPER-004, OPER-005, OPER-006, OPER-007, OPER-008, OPER-009, OPER-010 | 확정 | `1차 MVP`, 일부 `고도화` | store | [정책 원본](service-policies/03-store-operation.md) | 1차는 텍스트 기반 매장·영업시간·메뉴와 세 거래별 운영 모드를 제공한다. 검색 카테고리·태그는 더미 사업자 업종과 픽업 자격 상태를 만들지 않으며, 매장·메뉴 이미지 업로드·S3·플랫폼 운영자 콘텐츠 검수는 고도화에서 활성화한다. |
| 예약 | RES-001, RES-002, RES-003, RES-004, RES-005, RES-006, RES-007, RES-008, RES-009, RES-010, RES-011, RES-012, RES-013, RES-014, RES-015 | 확정 | `1차 MVP`, 일부 `고도화` | booking | [정책 원본](service-policies/04-reservation.md) | 1차는 결제 없는 즉시 확정 예약이다. 예약금·취소·환불 연결은 고도화에서만 진입한다. |
| 현장·원격 웨이팅 | WAIT-001, WAIT-002, WAIT-003, WAIT-004, WAIT-005, WAIT-006, WAIT-007, WAIT-008, WAIT-009, WAIT-010, WAIT-011, WAIT-012, WAIT-013, WAIT-014, WAIT-015, WAIT-016, WAIT-017 | 확정 | `고도화` | booking | [정책 원본](service-policies/05-waiting.md) | 로그인 사용자, 공통 3km, 단일 FIFO, 호출·종결과 SSE 상태 전달을 고도화에서 제공한다. |
| 메뉴 홀드·픽업 예약 | HOLD-001, HOLD-002, HOLD-003, HOLD-004, HOLD-005, HOLD-006, HOLD-007, HOLD-008, HOLD-009, HOLD-010, HOLD-011, HOLD-012, HOLD-013 | 확정 | `1차 MVP` | booking | [정책 원본](service-policies/06-menu-hold.md) | 메뉴 선택은 건너뛸 수 있다. 픽업 예약은 `store`의 중앙 `카페·베이커리 자격 확인` 상태와 근거 버전을 매 활성화·확정 때 검증하며 예약 인원·팀 수를 만들지 않는다. 자격 부재는 픽업만 실패 폐쇄하고 일반 입점·예약 기능과 분리한다. |
| 코스·테이스팅 | TASTE-001, TASTE-002, TASTE-003, TASTE-004, TASTE-005, TASTE-006, TASTE-007, TASTE-008, TASTE-009, TASTE-010, TASTE-011, TASTE-012 | 확정·TODO 혼재 | `향후 고도화` | booking | [정책 원본](service-policies/07-course-tasting.md) | 관련 선결제·설문·행사·회차와 미결정 세부를 앞 단계 기능·권한으로 활성화하지 않는다. |
| 결제·환불·정산 | PAY-001, PAY-002, PAY-003, PAY-004, PAY-005, PAY-006, PAY-007, PAY-008, PAY-009, PAY-010, PAY-011, PAY-012, PAY-013, PAY-014, PAY-015 | 확정·TODO 혼재 | `고도화` | payment | [정책 원본](service-policies/08-payment-refund.md) | 예약금·취소·환불은 PortOne V2 어댑터와 검증된 서버 조회 경계로 고도화에서 제공한다. 실제 식당 정산 `PAY-012`는 TODO다. |
| 체크인·노쇼 | CHECK-001, CHECK-002, CHECK-003, CHECK-004, CHECK-005, CHECK-006, CHECK-007, CHECK-008, CHECK-009, CHECK-010 | 확정 | `고도화` | booking | [정책 원본](service-policies/09-checkin-noshow.md) | 회전형 QR, 매장 운영자 보조 처리와 노쇼 절차를 고도화에서 제공한다. 노쇼는 취소·승계·자원 재공개를 만들지 않는다. |
| 취소 자리 자동 승계 | TRANSFER-001, TRANSFER-002, TRANSFER-003, TRANSFER-004, TRANSFER-005, TRANSFER-006, TRANSFER-007, TRANSFER-008, TRANSFER-009 | 확정 | `고도화` | booking | [정책 원본](service-policies/10-waitlist-transfer.md) | 확정 예약의 유효한 일반 취소로 반환된 예약 가능분만 대상이다. 변경 감소·거절·만료·시간 경과·노쇼와 메뉴 홀드 취소·수량 복구는 원인이 아니다. |
| 사용자·매장 구독 | SUB-001, SUB-002, SUB-003, SUB-004, SUB-005, SUB-006, SUB-007, SUB-008, SUB-009, SUB-010 | 확정·TODO 혼재 | `향후 고도화` | payment | [정책 원본](service-policies/11-subscription.md) | 사용자 구독·매장 Pro·유료 혜택은 향후 고도화 전까지 활성화하지 않는다. |
| 리뷰·신뢰·어뷰징 | TRUST-001, TRUST-002, TRUST-003, TRUST-004, TRUST-005, TRUST-006, TRUST-007, TRUST-008, TRUST-009, TRUST-010, TRUST-011, TRUST-012 | 확정 | `향후 고도화` | review | [정책 원본](service-policies/12-review-trust.md) | 리뷰·신고·답글·조작 탐지·신뢰 점수는 향후 도입 기준만 보존한다. |
| 광고·추천 | ADS-001, ADS-002, ADS-003, ADS-004, ADS-005, ADS-006, ADS-007, ADS-008 | 확정·TODO 혼재 | `2차 MVP`, `향후 고도화` | store | [정책 원본](service-policies/13-ad-recommendation.md) | 2차는 `RuleInterpreter`, QueryDSL, 결정적 Java 점수화·이력 추천, 같은 매장 우선과 원 매장의 검증된 저장 좌표 기준 3km 품절 대안, 카카오맵 표시다. 사용자 현재 위치와 추천 중 외부 지도 호출은 사용하지 않는다. 향후 LLM은 코드가 정한 TOP3 설명만 생성하고 실패 시 템플릿을 사용한다. 광고 상품도 향후 경계다. |
| 분석·수요 리포트 | ANALYTICS-001, ANALYTICS-002, ANALYTICS-003, ANALYTICS-004, ANALYTICS-005, ANALYTICS-006, ANALYTICS-007, ANALYTICS-008, ANALYTICS-009 | 확정 | `고도화`, `향후 고도화` | store | [정책 원본](service-policies/14-analytics-report.md) | Free 기본 운영 통계는 고도화, Pro 비교·해석·추천·자동 리포트·내보내기는 향후 고도화다. |
| 플랫폼 운영자·분쟁·수동 복구 | ADMIN-001, ADMIN-002, ADMIN-003, ADMIN-004, ADMIN-005, ADMIN-006, ADMIN-007, ADMIN-008, ADMIN-009, ADMIN-010, ADMIN-011, ADMIN-012 | 확정·TODO 혼재 | `고도화` | 모든 고도화 도메인 | [정책 원본](service-policies/15-admin-operation.md) | 별도 플랫폼 운영자 계정의 심사·분쟁·복구·장애 기능은 고도화에서만 진입한다. 뒤 단계 기능용 절차는 그 기능보다 먼저 활성화하지 않는다. |
| 알림 | NOTI-001, NOTI-002, NOTI-003, NOTI-004, NOTI-005, NOTI-006, NOTI-007, NOTI-008, NOTI-009, NOTI-010 | 확정·TODO 혼재 | `고도화` | notification | [정책 원본](service-policies/16-notification.md) | 거래·웨이팅·결제·체크인·운영 알림과 실패 처리를 고도화에서 제공한다. `NOTI-009` 보관 기간은 TODO다. |
| 개인정보·보안 | PRIV-001, PRIV-002, PRIV-003, PRIV-004, PRIV-005, PRIV-006, PRIV-007, PRIV-008, PRIV-009, PRIV-010, PRIV-011, PRIV-012, PRIV-013 | 확정·TODO 혼재 | 전 단계 공통 | 모든 도메인 | [정책 원본](service-policies/17-privacy-security.md) | 활성 단계의 최소 수집·암호화·접근·삭제 기준만 적용한다. 1차 입점은 파일 개인정보를 수집하지 않고 사업자 증빙·매장/메뉴 이미지 파일 통제는 고도화에서만 적용한다. |
| 대규모 트래픽·분산 환경·장애 복구 | SCALE-001, SCALE-002, SCALE-003, SCALE-004, SCALE-005, SCALE-006, SCALE-007, SCALE-008, SCALE-009, SCALE-010, SCALE-011, SCALE-012, SCALE-013, SCALE-014, SCALE-015, SCALE-016 | 확정 | 전 단계 공통 | 모든 도메인 | [정책 원본](service-policies/18-scale-reliability.md) | 활성 단계의 동시성·복구 불변식을 적용하며 뒤 단계 인프라는 선도입하지 않는다. |
