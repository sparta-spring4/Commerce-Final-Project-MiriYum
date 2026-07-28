# Backend 구현 가드레일

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
   ├─ store
   ├─ reservation
   ├─ menuhold
   └─ pickup
```

각 도메인은 실제 필요가 확인된 경우에만 `controller`, `service`, `repository`, `entity`, `dto/request`, `dto/response`, `exception`을 만든다. `booking`, `account`, `application` wrapper package를 만들지 않는다. 초기 구현에서 Command/Query/Application Service, Facade, Manager, `ServiceImpl`을 분리하거나 모든 Service에 형식적인 interface를 만들지 않는다.

호출 방향은 `Controller → Service → Repository`다. Controller가 Repository를 직접 호출하지 않고 business logic을 `global` 또는 범용 `util`로 옮기지 않는다.

## Service와 교차 도메인 계약

1차 MVP는 도메인별 주 Service 하나로 시작한다.

- `AuthService`
- `StoreService`
- `ReservationService`
- `MenuHoldService`
- `PickupService`

API·유스케이스 소유 Service가 교차 도메인 transaction을 조정한다. 다른 도메인은 소유자의 공개 Service 메서드와 DTO만 사용하며 Entity·Repository·내부 구현에 직접 접근하지 않는다.

- 쓰기 메서드: `@Transactional`
- 읽기 메서드: `@Transactional(readOnly = true)`

복잡도가 실제로 확인되고 별도 Issue에 분리 근거가 기록된 경우에만 Service 분리나 Facade를 검토한다. 선행 공개 계약이 필요하면 루트 `contract-first` 순서를 따른다. production `return null`, 가짜 성공 응답, 빈 구현, `UnsupportedOperationException`, 중복 API로 미준비 계약을 대신하지 않는다.

## 영속성 및 마이그레이션

- MySQL은 영속성의 정본이며, 마이그레이션이 존재하는 시점부터 Flyway가 스키마 변경을 소유한다.
- `application.properties`는 8단계의 첫 설정 Issue에서 `application.yml`로 전환하고 두 형식을 중복 유지하지 않는다.
- DB 연결 URL·사용자명·비밀번호는 각각 `MIRIYUM_DB_URL`, `MIRIYUM_DB_USERNAME`, `MIRIYUM_DB_PASSWORD` 환경 변수로 주입하고 저장소 기본값으로 넣지 않는다.
- H2를 MySQL의 증거로 사용하지 않는다.
- DB 의존 통합은 `NOT CONFIGURED` 상태를 유지한다. [ADR-002](../../docs/adr/ADR-002-staged-technology-adoption.md) 및 [ADR-004](../../docs/adr/ADR-004-scaffold-toolchain-and-test-baseline.md)의 활성화 조건에 한해서만 Testcontainers를 검토한다.

## 서버 보안 및 경계

- 범위가 정해진 기능이 이를 정의한 후에는 Spring Security가 서버 인증 및 인가 경계를 소유한다.
- 사용자, 역할, 인증 흐름, 공개 경로 또는 secret 처리를 임의로 만들지 않는다.
- controller는 HTTP 경계에, service는 유스케이스 및 트랜잭션 경계에, repository는 영속성 경계에 둔다.

Controller와 Service는 생성자 주입을 사용하며 필요한 경우 `@RequiredArgsConstructor`를 사용한다. request/response DTO는 `record`를 우선한다. 공개 JSON ID는 문자열로 표현한다. `userId`, `role`, `accountType`을 요청값으로 신뢰하지 않고 인증 principal을 사용한다.

## Entity와 Lombok

- Entity에 `@Setter`, `@Data`, `@RequiredArgsConstructor`, 상속 필드 `final`, blanket `@Builder`를 사용하지 않는다.
- JPA 기본 생성자는 `protected`로 둔다.
- private constructor와 의미 있는 static factory를 우선한다.
- 범용 `changeStatus` 대신 허용 전이를 드러내는 상태 변경 메서드를 사용한다.
- cross-domain 관계는 scalar FK ID와 소유 도메인의 공개 Service로 다룬다.

## 응답·오류·검증

JSON 성공 응답은 `ApiResponse<T>(code, message, data)`를 사용한다. 반환 데이터가 없으면 `data: null`, 빈 조회는 `data: []`로 표현한다. 파일·stream 같은 비 JSON 응답은 envelope 예외가 될 수 있다.

기술 공통 오류는 `CommonErrorCode`, 도메인별 오류는 `AuthErrorCode`, `StoreErrorCode`, `ReservationErrorCode`, `MenuHoldErrorCode`, `PickupErrorCode` 하나씩만 사용한다. 계정 상태·회원정보 오류도 인증 도메인의 `AuthErrorCode`가 소유하며 별도 `AccountErrorCode`나 `domain.account` package를 만들지 않는다. ErrorCode는 HTTP status, 외부 code, message를 제공하고 `ServiceException(ErrorCode)`와 `GlobalExceptionHandler`가 처리한다. Security `401/403`도 같은 JSON 오류 구조를 사용한다.

요청 DTO `record`에 Bean Validation을 선언하고 Controller에서 `@Valid`를 사용한다. 구조·형식 검증은 DTO, business 정책 검증은 Service가 소유한다. exception, SQL, stack trace, secret, token, 민감 입력값을 응답에 노출하지 않는다.

## 테스트

- 핵심 Service의 성공·실패와 상태 전이·인가·정원·재고·날짜·시간을 unit test로 검증한다.
- validation, HTTP status, 성공·오류 envelope, Security와 `Idempotency-Key`를 MockMvc로 검증한다.
- Flyway, DB constraint, 조건부 수량 변경, 동시성·멱등성, rollback·취소 복구와 픽업 분리는 Testcontainers MySQL로 검증한다.
- H2만으로 MySQL 동작을 증명하지 않는다.
- given/when/then 구조, camelCase test method와 한국어 `@DisplayName`을 사용한다.
- 시간은 `Clock`, 동시성은 barrier/latch로 제어하고 `sleep`에 의존하지 않는다.
- DB 검증이 인수 조건인데 Testcontainers가 `NOT CONFIGURED`이면 완료·병합 가능으로 표시하지 않는다.

## 설정·형식·Javadoc

runtime 설정은 실제 구현 Issue에서만 변경한다. YAML은 2칸 들여쓰기를 사용한다. Java 21, Spring Boot 4.1.0, Gradle 9.6.1을 임의 변경하지 않는다. Lombok은 실제 구현 Issue에서 추가하고 Testcontainers MySQL image는 첫 DB 통합 Issue의 검증된 정확 버전으로 고정한다. QueryDSL, Valkey와 외부 SDK는 필요한 단계와 Issue 전에는 추가하지 않는다.

Java는 4칸 들여쓰기, UTF-8, final newline, wildcard import 금지를 지킨다. package는 lowercase, constant와 enum은 `UPPER_SNAKE_CASE`다. `process`, `handle`처럼 목적이 모호한 이름을 피한다.

Controller class와 public endpoint, Service class와 public method, cross-domain 공개 Service, Entity 생성·상태 전이, 복잡한 Repository, 공통 응답·오류·Security와 의미 있는 enum·DTO에는 계약 중심 Javadoc을 작성한다. 의미, 사전 조건, 부작용과 실패 조건을 설명하고 필요한 `@param`, `@return`, `@throws`를 사용한다. getter, 명백한 private method와 spec 전체를 반복하지 않는다. author/date 주석은 쓰지 않고 TODO는 `// TODO(#issue): 이유` 형식으로만 사용한다.

## 연기된 기능

Docker, Compose, CI, 배포, API 스모크, runner, 스키마, skill, cache, hook은 이 스캐폴드에서 구성되지 않았다. 정본 활성화 경로와 별도로 승인된 허용 목록을 통해서만 추가한다.
