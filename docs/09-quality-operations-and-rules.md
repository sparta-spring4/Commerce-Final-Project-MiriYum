# 09. 품질·운영·규칙

## 증거 원칙

검증 기록은 정확한 명령, 환경·정책·스키마 버전, 결과와 증거를 남긴다. 상태는 `PASS`, `FAIL`, `BLOCKED`, `NOT RUN`, `NOT CONFIGURED`, `NOT APPLICABLE` 가운데 하나를 사용한다. 실행 파일·외부 환경·기능이 없으면 성공으로 추측하지 않는다.

| 변경 유형 | 최소 검증 |
|---|---|
| 문서 전용 | 링크, 중복 소유, 단계 표기, ADR 날짜별 개정, 형식과 diff 범위 |
| 백엔드 로직 | 컴파일, 단위 테스트와 관련 구조 테스트 |
| MySQL·API | Flyway, Testcontainers MySQL 통합, 계약·오류·멱등 smoke |
| 프런트엔드 | typecheck, build, Vitest·컴포넌트 테스트 중 구성된 항목 |
| 핵심 사용자 흐름 | 기능이 실행 가능해진 뒤 계정별 E2E와 동시성·복구 검증 |

검증하지 않은 Docker Compose, GitHub Actions, AWS, 외부 제공자와 required check를 활성 상태라고 부르지 않는다.

## 공통 구조·보안 gate

- 방식 A의 단일 Spring Boot·단일 MySQL 경계를 확인한다.
- 모듈 간 repository/entity 직접 접근과 controller→repository 직접 호출을 구조 테스트로 차단한다. 순환 baseline은 빈 집합이며, 구조 테스트는 전체 도메인 그래프의 상호 도달 쌍과 실제 순환 edge가 모두 0건인지 검사한다.
- `AudienceOpenApiContractTest`는 public·consumer·store-operator path 집합의 무중복 분할, aggregate 합집합 일치, 구 URL 부재와 단일 `$ref` path item을 검증한다.
- 1차 MVP에서는 일반 사용자·매장 운영자의 테이블·PK·principal·토큰 namespace가 분리됐는지 확인한다. 플랫폼 운영자 계정·JWT 검증 gate는 해당 기능을 구현하는 고도화에서 추가한다.
- 교차 namespace JWT, 클라이언트 역할 값, 이메일·외부 로그인에 의한 자가 승격을 거부한다.
- 매장 명령이 현재 계정 상태, 대상 매장의 `store_operator_account_id` 일치와 매장 상태를 MySQL에서 재검증하는지 확인한다.
- 로그·응답·지표에 토큰·비밀·연락처·정밀 위치·증빙·결제수단 원문이 남지 않는지 검사한다.

## 코드 구현 gate

### 코드 형식 자동화

- 최소 형식 계약은 루트 `.editorconfig`가 소유한다.
- Checkstyle, Spotless와 Git hook 같은 빌드 강제 도구는 반복 문제와 도입 효과의 근거가 생긴 뒤 별도 Issue로 검토한다. 현재 상태는 `NOT CONFIGURED`다.

## 구현 테스트 gate

- 핵심 Service 성공·실패와 상태 전이·인가·정원·재고·날짜·시간은 unit test로 검증한다.
- 공통 응답·인증·검증·멱등성 계약은 Controller 테스트로 검증한다.
- Flyway·DB constraint·조건부 갱신·동시성·멱등성·rollback·취소 복구·픽업 분리는 Testcontainers MySQL로 검증한다.
- given/when/then, camelCase test method와 한국어 `@DisplayName`을 사용한다.
- 시간은 `Clock`, 동시성은 barrier/latch로 제어하고 임의 `sleep`에 의존하지 않는다.
- H2 통과만으로 MySQL 고유 동작이나 동시성을 증명하지 않는다.
- DB 검증이 인수 조건인데 Testcontainers가 구성되지 않았으면 `NOT CONFIGURED` 또는 `BLOCKED`이며 완료·병합 가능으로 판정하지 않는다.

### CI 테스트 분류

- 순수 JUnit·Mockito 및 `@WebMvcTest` slice 테스트는 태그 없이 빠른 `test` task에서 실행한다.
- `@SpringBootTest`, `@Testcontainers` 또는 `MySQLContainer`를 사용하는 테스트 클래스에는 class-level `@Tag("integration")`을 선언한다.
- `integrationTest` task는 `@Tag("integration")` 테스트를 모두 실행하며, CI 전용 `integrationTestShardA/B` task는 각각 `integration-shard-a/b` 태그를 실행한다. `test` task는 integration 태그를 제외하고, `build`는 전체 통합 테스트를 포함한다.
- `Backend CI`는 unit job과 두 integration shard job을 병렬 실행하고, 모두 성공한 뒤에만 required check 이름인 `backend-ci`를 성공 처리한다. 새 통합 테스트가 기본 태그 또는 정확히 하나의 shard 태그를 빠뜨리면 Gradle 검증 task가 실패한다.
- 위 marker를 직접 사용하지 않아도 외부 DB, Docker 또는 느린 Spring runtime에 의존하는 테스트는 통합 테스트로 분류하고 그 근거를 PR에 기록한다.

## `1차 MVP` 검증 gate

### Testcontainers MySQL

첫 단계부터 Testcontainers MySQL로 다음을 검증한다.

- Flyway가 빈 DB와 지원되는 이전 스키마에서 같은 최종 상태로 수렴한다.
- 계정 유형별 유일 제약과 `stores.store_operator_account_id` FK가 애플리케이션 우회 쓰기를 거부한다.
- 예약 인원·팀 수와 메뉴 수량의 비음수 조건·조건부 SQL이 실제 MySQL에서 동작한다.
- 고정 잠금 순서, 교착·병렬 요청과 제한 재시도가 초과 판매를 만들지 않는다.
- `예약+메뉴 홀드`의 일부 자원 실패가 전체 롤백되고 픽업 예약이 예약 인원·팀 수를 차감하지 않는다.
- 같은 멱등 키 재전송이 하나의 결과만 만든다.

정확한 MySQL·Testcontainers 버전은 Spring Boot 호환성과 재현 시험을 통과한 뒤 잠근다. H2나 mock 통과를 MySQL 정합성 증거로 사용하지 않는다.

### 무상태 JWT

- Access/Refresh JWT의 종류·서명·만료·발급자·대상·namespace 변조를 거부한다.
- 서버·MySQL·Valkey에 정상 토큰·폐기 목록·세션이 생성되지 않는지 확인한다.
- 보호 명령은 토큰만 믿지 않고 현재 계정·매장 권한을 재검증한다.
- 즉시 개별 폐기가 없는 `1차 MVP` 한계를 UI·정책과 일치시킨다.

## `2차 MVP` 검증 gate

- 고정 한국어 질의셋에서 `RuleInterpreter`가 동일 입력·`Clock`에 동일 조건·경고를 반환한다.
- 모호한 입력을 추측하지 않고 남은 키워드와 경고로 보존한다.
- QueryDSL projection의 결과·실행 계획과 DB 부하를 기준 조회와 비교한다.
- 유효 예약·확정 메뉴 이력만 선호 신호이며 취소·실패·단순 조회·노쇼가 제외되는지 회귀 테스트한다.
- 동일 MySQL 스냅샷에서 Java 점수 계산이 같은 순위·설명을 만든다.
- 품절 대안은 같은 매장을 먼저 검증한다. 같은 매장 후보가 없을 때 원 매장의 검증된 저장 좌표·bounding box·Haversine을 사용해 3km 경계값은 포함하고 3km 초과 후보는 거부하는지 회귀 테스트한다.
- 추천 중 외부 지도 호출과 사용자 현재 위치 요청·저장·사용이 없는지 검사한다.
- AI/LLM·Spring AI·벡터 DB·검색 클러스터·메시지 브로커 의존성이 없는지 확인한다.

## `고도화` 검증 gate

`고도화`에 진입하면 아래 승인 기능과 검증은 모두 필수다. 정확한 공급자 버전·수치·토폴로지는 아래 결정 gate를 통과할 때까지 미정일 수 있지만, 그 미정을 기능 생략이나 `NOT APPLICABLE` 근거로 사용하지 않는다. 시간과 근거가 남을 때 별도 승인해 검토하는 후보는 `향후 고도화`뿐이다.

### 인증·파일·실시간

- Valkey Refresh 회전 경쟁은 한 번만 성공하고 폐기·교체 토큰 재사용이 계열 폐기로 수렴한다.
- Valkey 중단 중 로그인·갱신·로그아웃의 실패 폐쇄와 기존 Access JWT의 현재 권한 검증 경계를 장애 주입한다.
- S3 업로드·DB 메타데이터·삭제 사이의 부분 실패, 고아·누락 객체 대사와 비공개 접근 만료를 검증한다.
- SSE 중복·역순·연결 중단·재연결 뒤 HTTP/MySQL 상태로 수렴하고 Valkey Pub/Sub 유실이 업무 상태를 바꾸지 않는지 확인한다.

### 결제·내구성 작업

- PortOne 조회·Webhook의 위조, 중복, 순서 역전, 응답 유실과 결과 불명을 sandbox·가짜 어댑터 경계에서 검증한다.
- 브라우저 성공 주장만으로 결제·예약이 확정되지 않고 서버 검증이 같은 멱등 경로를 호출하는지 확인한다.
- 알림·자동 승계·결제 복구의 기능별 MySQL 작업을 생성 직후, 임대 중, 외부 호출 뒤에 중단하고 체크포인트에서 재개한다.
- DB 임대·fencing·멱등 키·제한 재시도·격리·대사가 중복 알림·승계·청구·환불을 만들지 않는지 검증한다.
- 노쇼 후보·확정·정정이 자동 승계, 예약 자원 반환 원장과 일반 가용 재공개를 만들지 않는 회귀 테스트를 고정한다.

### UI·권한

- 단계별 shell·라우트·SDK·환경변수 노출을 검사한다.
- 1차 MVP에서는 일반 사용자와 매장 운영자의 UI·권한·개인정보 조회 경계를 분리하고 플랫폼 운영자 shell·라우트·SDK를 만들지 않는다. 플랫폼 운영자 UI 검증은 고도화에서 해당 기능을 구현할 때 추가한다.
- 파일·SSE·결제의 중간·오류·결과 불명 상태를 확정으로 표시하지 않는지 검증한다.

## 금지 기술 gate

Kafka, 범용 Outbox, 마이크로서비스, WebSocket과 검색 클러스터가 승인 전 의존성·설정·빈 패키지·배포 전제에 들어오면 검증을 실패시킨다. 조건부 기술을 도입하려면 현재 선택의 측정 실패, 먼저 시도한 단순 개선, 비교 후보, 마이그레이션·롤백과 새 단점을 ADR 날짜별 개정에 남긴다.

## 추측 수치 결정 gate

다음 값은 각 행의 결정 시점에 책임자가 검증 근거를 확인하고 승인하기 전에는 문서·코드·설정·환경변수의 확정값으로 고정하지 않는다.

| 결정 대상 | 결정 시점 | 책임자 | 필수 검증 근거 |
|---|---|---|---|
| MySQL·Testcontainers 정확 버전 | 1차 MVP 의존성 잠금과 DB 통합 gate 활성화 전 | 백엔드 기술 책임자 | Spring Boot·Java 호환성, 빈 DB Flyway, 실제 MySQL 제약·잠금·조건부 SQL·병렬 경합의 재현 결과 |
| JWT 수명·전달·저장·CSRF·키 회전 | 1차 MVP 인증 계약과 배포 경계 확정 전 | 인증·보안 책임자 | 위협 모델, Access/Refresh 용도 분리, 브라우저 전달 경계, 교차 namespace·만료·키 전환 회귀 |
| QueryDSL·Kakao SDK 정확 버전 | 2차 MVP 검색·지도 의존성 잠금 전 | 검색·지도 연동 기술 책임자 | Java·Spring·TypeScript 호환성, QueryDSL MySQL 통합, Kakao 계약·지도 표시·좌표 확인 시험 |
| 규칙 사전·추천 가중치·동률 | 2차 MVP 추천 평가셋 승인과 출시 gate 전 | 추천 정책 책임자와 제품 책임자 | 버전된 한국어 질의셋, 동일 입력 결정성, 설명 가능성, 품절 대안 3km 포함 경계와 개인정보 비사용 |
| Valkey 버전·토폴로지·Refresh 수치 | 고도화 인증 상태 활성화 전 | 인증 책임자와 플랫폼 운영 책임자 | 회전 경쟁·재사용 탐지·로그인 단위 종료, 지연·중단·복구 장애 주입, 용량·비용 측정 |
| S3 지역·암호화·수명주기·URL 수치 | 고도화 파일 저장 활성화 전 | 개인정보·보안 책임자와 플랫폼 운영 책임자 | 계약·법률 검토, 비공개 접근 만료, 업로드/DB 부분 실패, 고아·누락 객체 대사와 삭제 전파 |
| 알림 재시도와 SSE 연결 수치 | 고도화 알림·실시간 전달 활성화 전 | 알림·실시간 기능 책임자 | 중복·역순·연결 중단·재연결, 전달 유실, 부하·백오프와 HTTP/MySQL 상태 수렴 결과 |
| 웨이팅·승계·체크인·노쇼 수치 | 각 고도화 기능 계약과 상태 전이 승인 전 | 해당 도메인 정책 책임자와 제품 책임자 | 소유 서비스 정책, 경계 시각·경합·취소·실패 복구·사용자 알림 회귀 |
| PortOne PG·수단·Webhook 허용오차·대사 주기 | 고도화 결제 계약·sandbox 검증과 운영 전환 전 | 결제 책임자와 보안·운영 책임자 | 실제 계약, 승인·취소·결과 불명, Webhook 위조·중복·역순, 멱등 대사와 비밀 분리 |
| 향후 AI·구독·채팅·본인확인 기술 | 향후 기능별 승인과 시제품 의존성 추가 전 | 기능별 제품·개인정보·기술 책임자 | 문제·대안·법률·비용 검토, 실패 대체·데이터 최소화·회귀·운영 책임. AI는 결정적 TOP3의 설명 전용과 템플릿 대체 검증 포함 |

승인 기록이나 필수 검증 근거 없이 정확 버전·수치·제공자·토폴로지를 고정하면 이 gate는 실패한다. 후보값을 시험 입력으로 쓰는 경우에도 확정값과 구분하고 결과·환경·되돌림 조건을 기록한다.

결정 시 문제, 대안, 선택·거절 이유, 알려진 단점, 관측 신호, 단순 개선, 다음 후보, 검증, 마이그레이션과 롤백을 기록한다.

## 문서·변경 gate

- 활성 정본만 제품 사실의 근거로 사용한다.
- 같은 규칙의 소유 문서가 하나인지, 모든 상대 링크가 존재하는지 검사한다.
- 네 단계 이름과 포함·비목표가 현재 승인 기준에 맞는지 검사한다.
- ADR 최초 본문·결정 이력을 보존하고 같은 파일에 날짜별 개정이 이어졌는지 확인한다.
- 금지된 예약 용어와 한정되지 않은 주체명이 없는지 검색한다.
- `git diff --check`와 허용 파일 allowlist로 형식·범위를 검증한다.

## 배포·복구 gate

배포 자동화나 AWS·Terraform은 핵심 흐름, Flyway, 비밀 분리, 상태 확인, Docker 실행, 백업·복원과 비용 책임이 검증된 뒤 승인한다. S3를 `고도화`에서 사용한다는 결정만으로 전체 AWS 배포·Terraform을 활성화하지 않는다.

[#120](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/120)의 ECR·SSM 백엔드 API 사전 배포는 `staging` EC2만 대상으로 하며 프론트엔드 사용자 shell과 최종 same-origin 배포를 대체하지 않는다. 이 경로는 ECR 이미지의 SHA 추적, 비밀의 서버 분리, SSM 배포, Docker 실행과 loopback health 확인만 증명한다. `dev`에 통합된 뒤 CI가 성공한 SHA만 staging에 배포한다. 실제 `dev` 배포 실행·ECR push·SSM command·EC2 health의 성공 증거가 없으면 각각 `NOT CONFIGURED` 또는 `NOT RUN`으로 기록하며, 프론트엔드 소유자가 `/` 정적 제공과 `/api` 프록시를 포함한 후속 범위를 승인할 때까지 핵심 사용자 흐름 배포 성공으로 선언하지 않는다. 운영 배포는 이 경로와 분리해 `main` 전용 workflow, 별도 EC2·IAM 역할·GitHub Environment 승인으로 구성한다.

### staging 비용 가드레일과 종료 체크리스트

현재 staging은 단일 EC2 Docker Compose, Private ECR과 SSM 배포만 사용한다. 이 경계 밖의 NAT Gateway, ALB, RDS, ElastiCache와 managed Kafka는 실제 운영 전환의 가격 계산과 팀 승인을 통과하기 전까지 생성하지 않는다. Budget은 청구 데이터 갱신 지연이 있으므로 자동 차단 장치가 아니라 조기 경보로만 사용하며, 실행 중인 DB를 Budget action으로 자동 종료하지 않는다.

- 모든 신규 AWS 리소스에는 `project=miriyum`, `environment=staging`, `owner`, `expires-at` 태그를 같은 대소문자로 적용한다. 비용 할당 태그 `project`, `environment`은 Billing 화면에 나타난 뒤 활성화하며, 그 전 상태는 `NOT RUN`으로 기록한다.
- 현재 자동 CD는 EC2를 시작하거나 SSM online 상태를 기다리지 않는다. 따라서 자동 CD가 활성화된 개발 기간에는 staging EC2를 중지하지 않는다. 계획된 휴지·종료로 중지했다면 다음 `dev` 병합 또는 수동 배포 전에 EC2를 시작하고 instance status check와 SSM online 상태를 확인한다. 중지는 컴퓨팅 비용만 줄이며 EBS와 EIP 등 연결 리소스 비용을 없애지 않는다는 점을 기록한다.
- `miriyum-backend` ECR은 최신 10개 이미지만 보관하는 lifecycle policy를 사용한다. 정책은 현재 실행 중인 컨테이너를 중단하지 않으며, ECR의 오래된 이미지 저장 비용만 정리한다.
- CloudWatch Logs 보존 기간은 관측 이슈에서 별도 설정하고, S3 수명 주기 정책은 S3를 실제 도입하는 기능 이슈에서 설정한다. 아직 설정하지 않은 항목은 `NOT CONFIGURED`로 기록한다.

| 시점 | 확인 대상 | 해야 할 일 |
|---|---|---|
| 계획된 휴지·종료 전 | staging EC2, Docker Compose | 다음 `dev` 병합 또는 수동 배포가 없음을 확인한 뒤 필요한 증거와 데이터 보존 여부를 확인하고 EC2를 중지한다. 재개 시에는 EC2 시작, instance status check, SSM online 확인을 마친 뒤 배포한다. |
| 주 1회 | Budgets, Cost Explorer | 실제 비용과 예측 비용, 태그별 비용 반영 상태를 확인한다. Budget 알림 미수신은 비용이 임계값에 도달하지 않았으면 `NOT RUN`으로 남긴다. |
| 리소스 생성 전 | NAT Gateway, ALB, RDS, ElastiCache, MSK | 가격 계산·목적·종료일·소유자를 Issue에 기록하고 팀 승인을 받는다. |
| 프로젝트 종료 전 | EC2, EBS volume/snapshot, Elastic IP/Public IPv4, ECR image, CloudWatch Logs, S3 object, IAM role | 더 이상 필요 없는 리소스와 데이터 보존 필요성을 확인한 뒤 삭제한다. EC2 종료 전에는 필요한 DB·로그·증빙을 별도 보관하고, 계정에 남은 Elastic IP는 연결 해제만 하지 말고 release한 뒤 Public IPv4 할당과 Billing을 대조한다. |
| 프로젝트 종료 전 | NAT Gateway, ALB, RDS, ElastiCache, MSK | 생성된 적이 있다면 서비스별 콘솔과 Billing에서 잔존 리소스가 없는지 대조하고 삭제 증거를 남긴다. |

롤링 배포는 expand→migrate→contract, 이전·신규 버전 혼합 계약, 작업 임대 인계와 롤백을 검증한다. 복구 성공은 원장·객체·캐시·삭제 전파·외부 대사까지 확인한 뒤에만 선언한다.
