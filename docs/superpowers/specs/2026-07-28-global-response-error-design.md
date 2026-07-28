# Global 공통 응답·예외 및 YAML 설정 설계

> 추적 Issue: [#29](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/29)
> 기준 브랜치: `dev`
> 기준 계약: PR #27에서 병합된 1차 MVP 공통 명세와 OpenAPI
> 설계 승인일: 2026-07-28

## 목적

모든 1차 MVP 백엔드 도메인이 동일한 성공 응답과 오류 응답을 사용하도록 최소 Global 기반을 먼저 구현한다. 동시에 기존 `application.properties`를 단일 `application.yml`로 전환하되, 비밀값 공급 기술은 미리 선택하지 않고 기존 환경변수 인터페이스를 유지한다.

이 작업은 매장·검색 담당을 포함한 후속 도메인 구현의 선행 계약이다. 다른 도메인의 Entity, Repository, Service 또는 오류 코드를 Global이 소유하거나 대신 구현하지 않는다.

## 설계 원칙

- Agora 프로젝트의 `global/response`와 `global/exception` 구분은 구조 참고 자료로 사용한다.
- 실제 응답 모양, 오류 코드와 소유권은 MiriYum의 활성 정본을 따른다.
- 일반 JSON 성공과 오류는 서로 다른 응답 타입으로 표현한다.
- 승인된 공통 오류 코드만 구현하며 예상 기능을 위한 코드를 추가하지 않는다.
- Security, JWT와 인증 `401/403`은 인증 도메인 소유로 남긴다.
- 실제 Bean 설정이 없는 빈 `global.config` 패키지와 범용 `AppConfig`를 만들지 않는다.
- 설정 공급원은 환경변수 계약에 중립적이어야 한다. GitHub Secrets, AWS Parameter Store 또는 프로필 파일을 선결정하지 않는다.

## 패키지 구조

```text
com.miriyum.global
├─ response
│  ├─ ApiResponse
│  └─ PageMetadata
└─ exception
   ├─ ErrorCode
   ├─ CommonErrorCode
   ├─ ServiceException
   ├─ ErrorResponse
   ├─ ValidationErrorDetail
   └─ GlobalExceptionHandler
```

### `ApiResponse<T>`

일반 JSON 성공 응답의 공통 타입이다.

```text
ApiResponse<T>
- code: "SUCCESS"
- message: 사용자 안내 문구
- data: 구체 결과 또는 null
```

- `code`, `message`, `data`는 직렬화 결과에 항상 존재한다.
- 결과가 없어도 `data`를 생략하지 않고 JSON `null`로 반환한다.
- 파일, 바이너리, SSE와 `204 No Content`에는 강제하지 않는다.
- 오류를 `ApiResponse`의 `ERROR` 상태로 표현하지 않는다.

### `PageMetadata`

기능별 페이지 응답의 `data.page`에서 재사용하는 공통 값이다.

```text
PageMetadata
- number: 0 기반 현재 페이지
- size: 적용된 페이지 크기
- totalElements: 전체 결과 수
- totalPages: 전체 페이지 수
- hasNext: 다음 페이지 존재 여부
```

빈 결과는 `number: 0`, `totalElements: 0`, `totalPages: 0`, `hasNext: false`를 표현할 수 있다. 기능별 목록 DTO는 `items`와 `PageMetadata`를 조합하며 Global이 기능별 목록 타입을 만들지 않는다.

### `ErrorCode`

공통 오류와 도메인 오류가 같은 예외 처리 흐름을 사용하는 인터페이스다.

```java
public interface ErrorCode {
    HttpStatus getHttpStatus();
    String getCode();
    String getMessage();
}
```

- `CommonErrorCode`와 각 도메인 ErrorCode enum이 구현한다.
- Global은 도메인 ErrorCode enum을 생성하거나 수정하지 않는다.
- HTTP 상태는 응답 본문에 중복하지 않는다.

### `CommonErrorCode`

활성 공통 오류 카탈로그의 `COMMON_001`부터 `COMMON_012`까지 정확히 구현한다.

| enum 상수 | 외부 코드 | HTTP |
|---|---|---|
| `VALIDATION_FAILED` | `COMMON_001` | 400 |
| `MALFORMED_REQUEST` | `COMMON_002` | 400 |
| `IDEMPOTENCY_KEY_REQUIRED` | `COMMON_003` | 400 |
| `INVALID_IDEMPOTENCY_KEY` | `COMMON_004` | 400 |
| `ENDPOINT_NOT_FOUND` | `COMMON_005` | 404 |
| `METHOD_NOT_ALLOWED` | `COMMON_006` | 405 |
| `IDEMPOTENCY_KEY_REUSED` | `COMMON_007` | 409 |
| `CONCURRENT_MODIFICATION` | `COMMON_008` | 409 |
| `UNSUPPORTED_MEDIA_TYPE` | `COMMON_009` | 415 |
| `TOO_MANY_REQUESTS` | `COMMON_010` | 429 |
| `INTERNAL_SERVER_ERROR` | `COMMON_011` | 500 |
| `SERVICE_UNAVAILABLE` | `COMMON_012` | 503 |

기본 메시지는 `docs/specs/mvp1-common/spec.md` C-011을 그대로 사용한다. 현재 Global MVC 처리기가 직접 발생시키지 않는 오류도 승인된 카탈로그 타입으로 제공하되, 멱등성·요청 제한·인프라 장애의 실제 발생 지점은 해당 기능 Issue가 연결한다.

### `ServiceException`

도메인 Service와 공통 기능이 승인된 `ErrorCode`를 전달하는 예외다.

- 생성자는 null이 아닌 `ErrorCode`를 받는다.
- `GlobalExceptionHandler`는 예외의 `ErrorCode`가 제공하는 HTTP 상태, 외부 코드와 기본 메시지를 그대로 사용한다.
- 임의 문자열 메시지로 공개 계약을 우회하지 않는다.

### 오류 응답 DTO

```text
ErrorResponse
- code
- message
- details (선택)

ValidationErrorDetail
- field
- reason
```

- 일반 오류에는 `details`를 직렬화하지 않는다.
- 검증·파싱 오류에서 안전한 세부 정보가 있을 때만 `details`를 포함한다.
- `field`는 공개 JSON 필드 경로이며 요청 전체 오류는 `$`를 사용한다.
- `reason`에는 거절된 실제 값, 비밀번호, 토큰, 비밀값 또는 내부 예외 정보를 포함하지 않는다.

## 예외 처리 흐름

```text
Controller 또는 Service 예외
        │
        ▼
GlobalExceptionHandler
        │
        ├─ ServiceException ───────────────▶ 전달된 ErrorCode
        ├─ MethodArgumentNotValidException ▶ COMMON_001 + 전체 안전한 details
        ├─ HandlerMethodValidationException▶ COMMON_001 + 안전한 details
        ├─ HttpMessageNotReadableException ▶ COMMON_002
        ├─ NoResourceFoundException ───────▶ COMMON_005
        ├─ HttpRequestMethodNotSupported   ▶ COMMON_006
        ├─ HttpMediaTypeNotSupported       ▶ COMMON_009
        └─ 그 밖의 Exception ──────────────▶ COMMON_011
        │
        ▼
ResponseEntity<ErrorResponse>
```

### Bean Validation

- 발견한 모든 필드 오류를 수집한다.
- 요청 본문 DTO 검증과 query·path·header의 Controller 메서드 파라미터 검증을 모두 `COMMON_001`로 변환한다.
- 중복된 필드·사유 쌍은 한 번만 반환한다.
- 응답 순서는 테스트가 안정적이도록 공개 필드 경로와 사유를 기준으로 결정적으로 정렬한다.
- 중첩 목록 경로는 `menuSelections[0].quantity`처럼 Spring의 공개 경로를 유지한다.
- 객체 전체 오류는 특정 공개 필드로 안전하게 귀속할 수 없으면 `$`를 사용한다.

### 잘못된 요청 본문

잘못된 JSON, 타입 불일치, 공개 enum 이외의 값과 알 수 없는 JSON 필드는 `COMMON_002`로 처리한다.

- `application.yml`에서 알 수 없는 요청 필드를 거절하도록 Jackson 역직렬화 옵션을 명시한다.
- 파서 내부 메시지, 대상 Java 타입, 원본 입력값은 응답에 노출하지 않는다.
- 안전하게 특정할 수 있는 공개 필드 경로가 없다면 `details`를 생략한다.

### 예상하지 못한 오류

`Exception` 최종 처리기는 항상 `COMMON_011`의 공개 메시지를 사용한다. 내부 예외명, stack trace, SQL, 시스템 경로와 비밀값을 응답에 포함하지 않는다. 서버 로그 정책은 이 Issue에서 새로 정의하지 않는다.

### 인증 예외 경계

Spring Security 필터 구간은 MVC `@RestControllerAdvice` 이전에 실행될 수 있다. 인증 담당자는 후속 구현에서 `AuthErrorCode`와 인증 오류 writer/entry point를 소유하되, 직렬화 결과는 같은 `ErrorResponse` 구조를 재사용한다.

Global PR은 다음을 구현하지 않는다.

- `SecurityFilterChain`
- JWT 필터·검증기
- `AuthenticationEntryPoint`
- `AccessDeniedHandler`
- `AuthErrorCode`
- `401/403` 매핑

## YAML 설정

`application.properties`를 삭제하고 다음 의미를 가진 단일 `application.yml`로 교체한다.

```yaml
spring:
  application:
    name: miriyum-backend
  datasource:
    url: ${MIRIYUM_DB_URL}
    username: ${MIRIYUM_DB_USERNAME}
    password: ${MIRIYUM_DB_PASSWORD}
  jackson:
    deserialization:
      fail-on-unknown-properties: true
```

- 저장소에 DB URL, 사용자명, 비밀번호 기본값을 넣지 않는다.
- `application-local.yml`, `application-test.yml`을 만들지 않는다.
- GitHub Secrets와 AWS Parameter Store 연동을 추가하지 않는다.
- 향후 공급원이 환경변수를 채우면 애플리케이션 설정 계약은 변경하지 않아도 된다.

## 의존성

Bean Validation이 현재 Spring Boot 의존성에 포함되지 않았다면 `backend/build.gradle.kts`에 Spring Boot validation starter만 추가한다. Lombok은 이 Issue에서 추가하지 않고 record, 명시적 생성자와 접근자를 사용한다.

## 테스트 설계

### 응답 단위 테스트

- 성공 응답의 `code`가 `SUCCESS`인지 검증한다.
- `message`와 제네릭 `data`가 보존되는지 검증한다.
- 결과가 없을 때 JSON에 `"data": null`이 포함되는지 검증한다.
- `PageMetadata`가 승인된 필드를 정확히 제공하는지 검증한다.

### 오류 카탈로그 단위 테스트

- `COMMON_001~012`가 빠짐없이 존재하는지 검증한다.
- 각 코드의 HTTP 상태, 외부 코드와 기본 메시지가 C-011과 일치하는지 검증한다.
- 외부 코드가 중복되지 않는지 검증한다.
- `ServiceException`이 전달받은 ErrorCode를 보존하는지 검증한다.

### MVC 예외 처리 테스트

테스트 전용 Controller와 DTO를 테스트 소스에만 두고 MockMvc로 검증한다. production에 예제 endpoint나 placeholder Controller를 만들지 않는다.

- 하나의 잘못된 요청에서 여러 검증 오류가 `COMMON_001` details로 반환된다.
- query·path·header 메서드 파라미터 검증도 `COMMON_001`로 반환된다.
- 중첩 공개 필드 경로가 유지된다.
- 오류 응답에 거절된 실제 값이 포함되지 않는다.
- 잘못된 JSON·타입·enum·알 수 없는 필드가 `COMMON_002`다.
- 없는 공개 경로가 `COMMON_005`다.
- 존재하는 경로의 잘못된 메서드가 `COMMON_006`이다.
- 지원하지 않는 Content-Type이 `COMMON_009`다.
- `ServiceException`이 도메인 ErrorCode 계약을 그대로 변환한다.
- 예상하지 못한 예외가 `COMMON_011`이며 내부 메시지를 숨긴다.
- 일반 오류는 `details`를 생략한다.

### 회귀 검증

```powershell
cd backend
.\gradlew.bat test
.\gradlew.bat build
git diff --check
```

초기 context test는 기존과 같이 DataSource, JPA와 Flyway 자동 구성을 제외한다. 이 PR은 MySQL·Flyway·DB 제약·인증·도메인 API가 동작함을 증명하지 않는다.

## 파일 허용 목록

- `backend/build.gradle.kts`
- `backend/src/main/resources/application.properties`
- `backend/src/main/resources/application.yml`
- `backend/src/main/java/com/miriyum/global/response/**`
- `backend/src/main/java/com/miriyum/global/exception/**`
- `backend/src/test/java/com/miriyum/global/response/**`
- `backend/src/test/java/com/miriyum/global/exception/**`
- `backend/src/test/java/com/miriyum/global/config/**`
- `docs/superpowers/specs/2026-07-28-global-response-error-design.md`
- `docs/superpowers/plans/2026-07-28-global-response-error*.md`

허용 목록 밖의 파일이 필요하면 Issue #29의 범위와 계약을 먼저 갱신한다.

## 구현 및 통합 순서

1. 설계 문서를 최신 `dev` 기반 Issue #29 브랜치에 커밋한다.
2. 상세 구현 계획을 작성한다.
3. 실패하는 응답·오류 단위 테스트를 먼저 작성한다.
4. 실패하는 MVC 예외 처리 테스트를 작성한다.
5. 필요한 최소 production 코드를 구현한다.
6. `application.properties`를 `application.yml`로 교체한다.
7. 관련 테스트와 전체 backend build를 실행한다.
8. allowlist, diff와 계약 정합성을 검토한다.
9. Draft PR을 `dev` 대상으로 열고 작성자를 제외한 검토를 요청한다.
10. Global PR이 병합된 뒤 각 도메인 구현이 최신 `dev`의 공통 타입을 사용한다.

## 위험과 롤백

- Global 응답 타입은 모든 도메인이 소비하므로 병합 뒤 필드명이나 외부 오류 의미를 임의 변경하지 않는다.
- `COMMON_011` 최종 처리가 인증·도메인 오류를 가리지 않도록 구체 예외 처리 테스트를 유지한다.
- Security 필터 오류 형식은 인증 구현 전까지 완성되지 않았으며 이 PR의 완료 주장에 포함하지 않는다.
- 설정 공급원은 미결정 상태를 유지한다. 특정 공급자 도입은 별도 Issue와 검증을 요구한다.
- 문제가 생기면 Issue #29 PR 전체를 되돌릴 수 있다. 도메인별 임시 성공 봉투나 공통 오류 코드 재정의는 롤백 대안이 아니다.
