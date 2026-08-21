# Backend 구현 가드레일

- `platformoperator`는 일반 사용자·매장 운영자와 분리된 최상위 도메인이며 HTTP root는 `/api/v1/platform-operators/**`만 사용한다.
- 플랫폼 운영자 Controller와 활성 보안 체인은 `miriyum.platform-operator.enabled=true`일 때만 등록한다. 기본값 OFF에서는 MVC 404로 수렴한다.

계약 상태: ACTIVE

## 빌드 및 애플리케이션 경계

- Java 21, Spring Boot 4.1.0 및 커밋된 Gradle 9.6.1 Wrapper를 사용한다.
- `com.miriyum` 아래에 Spring Boot 애플리케이션 하나를 유지한다.
- [ADR-001](../../docs/adr/ADR-001-domain-packages-three-layer.md)의 도메인 및 3계층 경계를 적용한다.
- 실제 구현에 필요할 때만 도메인 패키지와 그 하위 controller, service, repository, entity, DTO 또는 exception을 만든다.
- 자리표시자 controller, 예제 endpoint, 빈 패키지 또는 추측성 추상화를 추가하지 않는다.

기본 구조는 다음과 같다.

```text
com.miriyum
├─ global
│  ├─ config
│  ├─ exception
│  ├─ response
│  └─ security
└─ domain
   ├─ auth
   ├─ consumer
   ├─ storeoperator
   ├─ store
   ├─ schedule
   ├─ menu
   ├─ search
   ├─ alternative
   ├─ recommendation
   ├─ reservation
   ├─ menuhold
   ├─ pickup
   └─ payment
```

각 도메인은 실제 필요가 확인된 경우에만 `controller`, `service`, `repository`, `entity`, `dto`, `exception`을 만든다. 검증된 계산 책임은 `recommendation.ranking`처럼 목적이 분명한 capability 패키지에 둘 수 있지만, 영속 조회 구현은 `repository` 패키지에 둔다. HTTP 호출자 구분이 필요한 Controller와 HTTP DTO만 `publicapi`, `consumer`, `storeoperator` 하위로 나눈다. `publicapi`는 별도 제휴 API가 아니라 인증 principal을 요구하지 않는 공개 HTTP 조회 경계다. 사용자 유형 자체가 도메인인 `consumer`, `storeoperator`는 Controller와 HTTP DTO를 `auth`, `account` 목적별로 나눈다. Service·Repository·Entity를 호출자별로 복제하지 않는다.

`booking`, `account`, `application` wrapper package를 만들지 않는다. 초기 구현에서 Command/Query/Application Service, Manager, `ServiceImpl`을 분리하거나 모든 Service에 형식적인 interface를 만들지 않는다. 복잡도가 실제로 확인되어 Issue와 테스트로 근거가 남은 경우에만 목적이 명확한 Service나 Facade를 추가한다.

호출 방향은 `Controller → Service → Repository`다. Controller가 Repository를 직접 호출하지 않고 business logic을 `global` 또는 범용 `util`로 옮기지 않는다.

## Service와 교차 도메인 계약

1차 MVP는 도메인별 주 Service에서 시작하고, 동시성·조회 projection·공개 교차 도메인 계약처럼 독립 책임이 검증된 경우에만 목적별 Service를 둔다.

- `AuthService`
- `StoreService`
- `StoreScheduleQueryService`
- `MenuTransactionFacade`
- `ReservationService`
- `MenuHoldService`
- `PickupService`
- `PaymentService`

예약과 MenuHold의 교차 트랜잭션은 예약 소유 포트와 MenuHold 소유 adapter로만 연결한다. 1차 MVP 즉시 확정 흐름은 `ReservationMenuHoldPort`·`ReservationMenuHoldAdapter`, 고도화 10분 임시 선점 흐름은 `ReservationTemporaryMenuHoldPort`·`ReservationTemporaryMenuHoldAdapter`를 사용한다. 두 경계는 scalar DTO만 교환하며 Reservation에서 MenuHold Entity·Repository를 직접 참조하지 않는다. 임시 선점 종결은 ReservationHold를 잠근 뒤 임시 MenuHold 루트만 선잠그는 계약과, 수용량 처리 뒤 MenuHold 상태·재고를 적용하는 계약을 분리해 `ReservationHold → temporary MenuHold → capacity bucket PK → inventory bucket PK` 순서를 지킨다. MenuHold가 예약 시간을 해석할 때는 `ReservationService` 전체가 아니라 `ReservationTimeResolutionService`만 의존한다. 도메인 의존 그래프의 순환 baseline은 0건이다.

API·유스케이스 소유 Service가 교차 도메인 transaction을 조정한다. 다른 도메인은 소유자의 공개 Service 메서드와 DTO만 사용하며 Entity·Repository·내부 구현에 직접 접근하지 않는다.

매장 입점 심사는 Store 도메인이 신청·version·자동검사·심사사건·최종 매장 생성의 정본을 소유한다. Platform Operator는 `StoreOnboardingReviewWorkflow`와 `StoreOnboardingContracts`만 사용하며 Store onboarding Entity·Repository를 직접 참조하지 않는다. 사업자등록증 원문은 URL·object key·file ID 없이 bytes로만 전달하고, 현재 사건 배정·`ONBOARDING_EVIDENCE_READ`·사건/version/증빙에 결속된 5분 일회 재인증을 소비한 뒤 별도 읽기로 수행한다.

2차 MVP의 통합 검색·추천 projection은 다음 네 읽기 전용 QueryDSL reader에 한해 Store·Menu의 생성 `Q*` 메타모델을 직접 읽을 수 있다.

- `recommendation/repository/RecommendationSignalRepository.java`
- `search/repository/IntegratedStoreSearchPredicates.java`
- `search/repository/IntegratedStoreSearchRepository.java`
- `search/repository/MenuAlternativeCandidateRepository.java`

이 예외는 Store·Menu Entity를 반환하거나 변경하는 계약이 아니다. 위 reader는 scalar·projection만 반환하고 쓰기, Entity materialization 공개, 다른 도메인 확장을 금지한다. `DomainPackageArchitectureTest`는 정확한 경로와 대상 도메인만 허용하며 그 밖의 타 도메인 Entity·Repository·생성 `Q*` import를 실패시킨다.

- 쓰기 메서드: `@Transactional`
- 읽기 메서드: `@Transactional(readOnly = true)`

복잡도가 실제로 확인되고 별도 Issue에 분리 근거가 기록된 경우에만 Service 분리나 Facade를 검토한다. 선행 공개 계약이 필요하면 루트 `contract-first` 순서를 따른다. production `return null`, 가짜 성공 응답, 빈 구현, `UnsupportedOperationException`, 중복 API로 미준비 계약을 대신하지 않는다.

## 영속성 및 마이그레이션

- MySQL은 영속성의 정본이며, 마이그레이션이 존재하는 시점부터 Flyway가 스키마 변경을 소유한다.
- `application.properties`는 8단계의 첫 설정 Issue에서 `application.yml`로 전환하고 두 형식을 중복 유지하지 않는다.
- DB 연결 URL·사용자명·비밀번호는 각각 `MIRIYUM_DB_URL`, `MIRIYUM_DB_USERNAME`, `MIRIYUM_DB_PASSWORD` 환경 변수로 주입하고 저장소 기본값으로 넣지 않는다.
- H2를 MySQL의 증거로 사용하지 않는다.
- DB 의존 통합은 Testcontainers MySQL로 구성되어 있다. `@SpringBootTest`, `@Testcontainers` 또는 `MySQLContainer`를 사용하는 테스트 클래스에는 class-level `@Tag("integration")`을 선언하고 `backend.integration-test`로 실행한다. 순수 JUnit·Mockito 및 `@WebMvcTest` slice 테스트는 태그 없이 `backend.test`에 둔다.

## 서버 보안 및 경계

- 범위가 정해진 기능이 이를 정의한 후에는 Spring Security가 서버 인증 및 인가 경계를 소유한다.
- 사용자, 역할, 인증 흐름, 공개 경로 또는 secret 처리를 임의로 만들지 않는다.
- controller는 HTTP 경계에, service는 유스케이스 및 트랜잭션 경계에, repository는 영속성 경계에 둔다.
- `controller.publicapi`는 인증 principal을 필수로 받지 않고, `controller.consumer`와 `controller.storeoperator`는 예상한 token namespace를 검증한다.

Controller와 Service는 생성자 주입을 사용하며 필요한 경우 `@RequiredArgsConstructor`를 사용한다. request/response DTO는 `record`를 우선한다. 공개 JSON ID는 문자열로 표현한다. `userId`, `role`, `accountType`을 요청값으로 신뢰하지 않고 인증 principal을 사용한다.

## Entity와 Lombok

- Entity에 `@Setter`, `@Data`, `@RequiredArgsConstructor`, 상속 필드 `final`, blanket `@Builder`를 사용하지 않는다.
- JPA 기본 생성자는 `protected`로 둔다.
- private constructor와 의미 있는 static factory를 우선한다.
- 범용 `changeStatus` 대신 허용 전이를 드러내는 상태 변경 메서드를 사용한다.
- cross-domain 관계는 scalar FK ID와 소유 도메인의 공개 Service로 다룬다.

## 응답·오류·검증

JSON 성공 응답은 `ApiResponse<T>(code, message, data)`를 사용한다. 반환 데이터가 없으면 `data: null`, 빈 조회는 `data: []`로 표현한다. 파일·stream 같은 비 JSON 응답은 envelope 예외가 될 수 있다.

기술 공통 오류는 `CommonErrorCode`, 도메인별 오류는 `AuthErrorCode`, `StoreErrorCode`, `ReservationErrorCode`, `MenuHoldErrorCode`, `PickupErrorCode`, `PaymentErrorCode` 하나씩만 사용한다. 계정 상태·회원정보 오류도 인증 도메인의 `AuthErrorCode`가 소유하며 별도 `AccountErrorCode`나 `domain.account` package를 만들지 않는다. ErrorCode는 HTTP status, 외부 code, message를 제공하고 `ServiceException(ErrorCode)`와 `GlobalExceptionHandler`가 처리한다. Security `401/403`도 같은 JSON 오류 구조를 사용한다.

요청 DTO `record`에 Bean Validation을 선언하고 Controller에서 `@Valid`를 사용한다. 구조·형식 검증은 DTO, business 정책 검증은 Service가 소유한다. exception, SQL, stack trace, secret, token, 민감 입력값을 응답에 노출하지 않는다.

## 테스트

- 핵심 Service의 성공·실패와 상태 전이·인가·정원·재고·날짜·시간·금액·통화·결제 멱등성을 unit test로 검증한다.
- validation, HTTP status, 성공·오류 envelope, Security와 `Idempotency-Key`를 MockMvc로 검증한다.
- Flyway, DB constraint, 조건부 수량 변경, 동시성·멱등성, rollback·취소 복구, 픽업 분리와 결제·Webhook·환불 원장 중복 방지는 Testcontainers MySQL로 검증한다.
- H2만으로 MySQL 동작을 증명하지 않는다.
- given/when/then 구조, camelCase test method와 한국어 `@DisplayName`을 사용한다.
- 시간은 `Clock`, 동시성은 barrier/latch로 제어하고 `sleep`에 의존하지 않는다.
- DB 검증이 인수 조건이면 `backend.integration-test` 또는 이를 포함하는 `backend.build`의 성공 증거 없이는 완료·병합 가능으로 표시하지 않는다.

## 설정·형식·Javadoc

runtime 설정은 실제 구현 Issue에서만 변경한다. YAML은 2칸 들여쓰기를 사용한다. Java 21, Spring Boot 4.1.0, Gradle 9.6.1을 임의 변경하지 않는다. Lombok은 실제 구현 Issue에서 추가하고 Testcontainers MySQL image는 첫 DB 통합 Issue의 검증된 정확 버전으로 고정한다. QueryDSL, Valkey와 외부 SDK는 필요한 단계와 Issue 전에는 추가하지 않는다.

Issue #368의 검색 LLM은 OpenAI `gpt-4o-mini` Structured Outputs 어댑터로 최대 8개의 음식 개념만 반환한다. 정확 MySQL 검색을 먼저 실행하고 최초 응답 부족 시에만 호출하며, 후보·가용성·알레르기 안전·재고·예약·점수·순위는 현재 MySQL과 결정적 코드가 확정한다. 벡터 저장소·외부 색인·캐시는 두지 않는다. `OPENAI_API_KEY`는 환경 Secret으로만 주입하고 원문·응답·Secret을 로그에 남기지 않는다.

Java는 4칸 들여쓰기, UTF-8, final newline, wildcard import 금지를 지킨다. package는 lowercase, constant와 enum은 `UPPER_SNAKE_CASE`다. `process`, `handle`처럼 목적이 모호한 이름을 피한다.

Controller class와 public endpoint, Service class와 public method, cross-domain 공개 Service, Entity 생성·상태 전이, 복잡한 Repository, 공통 응답·오류·Security와 의미 있는 enum·DTO에는 계약 중심 Javadoc을 작성한다. 의미, 사전 조건, 부작용과 실패 조건을 설명하고 필요한 `@param`, `@return`, `@throws`를 사용한다. getter, 명백한 private method와 spec 전체를 반복하지 않는다. author/date 주석은 쓰지 않고 TODO는 `// TODO(#issue): 이유` 형식으로만 사용한다.

## 연기된 기능

Docker Compose, staging CD, `Backend CI`와 Gradle 의존성 cache는 구성되어 있으며, 각각의 runtime 결과는 배포 runbook과 CI 실행 링크로 확인한다. API 스모크, runner, 기계 판독 schema, skill과 선택적 local hook은 아직 구성되지 않았으며 정본 활성화 경로와 별도로 승인된 허용 목록을 통해서만 추가한다.
