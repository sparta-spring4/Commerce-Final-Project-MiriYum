# Global Response, Error, and YAML Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the Issue #29 Global success response, common error catalog, MVC exception translation, page metadata, and provider-neutral YAML configuration required by every MVP1 backend domain.

**Architecture:** Keep success and error envelopes separate under `com.miriyum.global`. Domain errors implement a small `ErrorCode` interface and flow through one `ServiceException` handler, while Spring MVC input and routing failures map to the approved `COMMON_001`–`COMMON_012` catalog. Configuration stays in one `application.yml` and consumes only the existing `MIRIYUM_DB_*` environment-variable contract.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring MVC 7, Jakarta Bean Validation, Jackson, JUnit Jupiter, AssertJ, MockMvc, Gradle Wrapper 9.6.1.

## Global Constraints

- Work only on Issue #29 allowlisted paths.
- Use Java 21, Spring Boot 4.1.0, and Gradle Wrapper 9.6.1 without version changes.
- Use `ApiResponse<T>(code, message, data)` for ordinary JSON success and always serialize `data`, including `null`.
- Use a separate `ErrorResponse(code, message, details)` for failures; omit `details` when empty.
- Implement exactly `COMMON_001` through `COMMON_012` with the HTTP status, external code, and Korean default message approved in C-011.
- Do not implement JWT, Spring Security configuration, `AuthErrorCode`, `401/403`, domain ErrorCode enums, Flyway migrations, or idempotency persistence.
- Do not add Lombok, Spring Retry, Redis, Valkey, profile YAML files, GitHub Secrets integration, or AWS Parameter Store integration.
- Do not create an empty `global.config` package or production placeholder Controller.
- Do not expose rejected values, parser internals, exception class names, stack traces, SQL, system paths, tokens, or secrets in error responses.
- Follow test-first red-green-refactor for every production Java type.

---

### Task 1: Success Response and Page Metadata

**Files:**
- Create: `backend/src/test/java/com/miriyum/global/response/ApiResponseTest.java`
- Create: `backend/src/test/java/com/miriyum/global/response/PageMetadataTest.java`
- Create: `backend/src/main/java/com/miriyum/global/response/ApiResponse.java`
- Create: `backend/src/main/java/com/miriyum/global/response/PageMetadata.java`

**Interfaces:**
- Produces: `public record ApiResponse<T>(String code, String message, T data)`
- Produces: `public static <T> ApiResponse<T> success(String message, T data)`
- Produces: `public record PageMetadata(int number, int size, long totalElements, int totalPages, boolean hasNext)`

- [ ] **Step 1: Write failing response tests**

```java
class ApiResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void successWrapsMessageAndDataWithSuccessCode() {
        ApiResponse<String> response = ApiResponse.success("요청 성공", "result");

        assertThat(response.code()).isEqualTo("SUCCESS");
        assertThat(response.message()).isEqualTo("요청 성공");
        assertThat(response.data()).isEqualTo("result");
    }

    @Test
    void successSerializesNullData() throws JsonProcessingException {
        String json = objectMapper.writeValueAsString(ApiResponse.success("요청 성공", null));
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.get("code").asText()).isEqualTo("SUCCESS");
        assertThat(root.get("message").asText()).isEqualTo("요청 성공");
        assertThat(root.has("data")).isTrue();
        assertThat(root.get("data").isNull()).isTrue();
    }
}
```

```java
class PageMetadataTest {

    @Test
    void exposesApprovedZeroBasedPageFields() {
        PageMetadata page = new PageMetadata(0, 20, 0, 0, false);

        assertThat(page.number()).isZero();
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(page.hasNext()).isFalse();
    }
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
cd backend
.\gradlew.bat test --tests "com.miriyum.global.response.*"
```

Expected: test compilation fails because `ApiResponse` and `PageMetadata` do not exist.

- [ ] **Step 3: Implement the response records**

```java
public record ApiResponse<T>(
        String code,
        String message,
        T data
) {
    private static final String SUCCESS_CODE = "SUCCESS";

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(SUCCESS_CODE, message, data);
    }
}
```

```java
public record PageMetadata(
        int number,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
}
```

Add contract-focused Javadoc to both public records and the `success` factory.

- [ ] **Step 4: Run tests and verify GREEN**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.global.response.*"
```

Expected: response tests pass.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/miriyum/global/response backend/src/test/java/com/miriyum/global/response
git commit -m "feat(global): 공통 성공 응답 추가"
```

### Task 2: Common Error Types and Catalog

**Files:**
- Create: `backend/src/test/java/com/miriyum/global/exception/CommonErrorCodeTest.java`
- Create: `backend/src/test/java/com/miriyum/global/exception/ServiceExceptionTest.java`
- Create: `backend/src/test/java/com/miriyum/global/exception/ErrorResponseTest.java`
- Create: `backend/src/main/java/com/miriyum/global/exception/ErrorCode.java`
- Create: `backend/src/main/java/com/miriyum/global/exception/CommonErrorCode.java`
- Create: `backend/src/main/java/com/miriyum/global/exception/ServiceException.java`
- Create: `backend/src/main/java/com/miriyum/global/exception/ErrorResponse.java`
- Create: `backend/src/main/java/com/miriyum/global/exception/ValidationErrorDetail.java`

**Interfaces:**
- Produces: `HttpStatus ErrorCode.getHttpStatus()`
- Produces: `String ErrorCode.getCode()`
- Produces: `String ErrorCode.getMessage()`
- Produces: `ErrorCode ServiceException.getErrorCode()`
- Produces: `ErrorResponse.from(ErrorCode)` and `ErrorResponse.of(ErrorCode, List<ValidationErrorDetail>)`

- [ ] **Step 1: Write failing catalog and response tests**

The catalog test must assert the exact tuples:

```java
assertThat(CommonErrorCode.values()).extracting(
        CommonErrorCode::getCode,
        CommonErrorCode::getHttpStatus,
        CommonErrorCode::getMessage
).containsExactly(
        tuple("COMMON_001", BAD_REQUEST, "입력값이 올바르지 않습니다."),
        tuple("COMMON_002", BAD_REQUEST, "요청 본문을 읽을 수 없습니다."),
        tuple("COMMON_003", BAD_REQUEST, "Idempotency-Key 헤더가 필요합니다."),
        tuple("COMMON_004", BAD_REQUEST, "Idempotency-Key 형식이 올바르지 않습니다."),
        tuple("COMMON_005", NOT_FOUND, "요청한 API 경로가 존재하지 않습니다."),
        tuple("COMMON_006", METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
        tuple("COMMON_007", CONFLICT, "동일한 Idempotency-Key를 다른 요청에 사용할 수 없습니다."),
        tuple("COMMON_008", CONFLICT, "동시 요청 충돌로 처리하지 못했습니다. 다시 시도해 주세요."),
        tuple("COMMON_009", UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다."),
        tuple("COMMON_010", TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
        tuple("COMMON_011", INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
        tuple("COMMON_012", SERVICE_UNAVAILABLE, "서비스를 일시적으로 사용할 수 없습니다.")
);
```

Also assert that all external codes are unique, `ServiceException` retains the exact ErrorCode instance, ordinary `ErrorResponse.from` has no details, and `ErrorResponse.of` retains safe details.

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.global.exception.CommonErrorCodeTest" --tests "com.miriyum.global.exception.ServiceExceptionTest" --tests "com.miriyum.global.exception.ErrorResponseTest"
```

Expected: test compilation fails because the error types do not exist.

- [ ] **Step 3: Implement the minimal error model**

Implement `ErrorCode` as the three-method interface from the design. Implement `CommonErrorCode` with the exact twelve rows above, an explicit constructor, and explicit getter methods without Lombok.

Implement:

```java
public class ServiceException extends RuntimeException {
    private final ErrorCode errorCode;

    public ServiceException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode, "errorCode").getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
```

Implement records:

```java
public record ValidationErrorDetail(String field, String reason) {
}
```

```java
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(
        String code,
        String message,
        List<ValidationErrorDetail> details
) {
    public static ErrorResponse from(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static ErrorResponse of(
            ErrorCode errorCode,
            List<ValidationErrorDetail> details
    ) {
        return new ErrorResponse(
                errorCode.getCode(),
                errorCode.getMessage(),
                List.copyOf(details)
        );
    }
}
```

Add contract-focused Javadoc to public types and methods.

- [ ] **Step 4: Run tests and verify GREEN**

Run the exact Task 2 test command. Expected: all Task 2 tests pass.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/miriyum/global/exception backend/src/test/java/com/miriyum/global/exception
git commit -m "feat(global): 공통 오류 계약 추가"
```

### Task 3: MVC Exception Translation

**Files:**
- Modify: `backend/build.gradle.kts`
- Create: `backend/src/test/java/com/miriyum/global/exception/GlobalExceptionHandlerTest.java`
- Create: `backend/src/main/java/com/miriyum/global/exception/GlobalExceptionHandler.java`

**Interfaces:**
- Consumes: `ErrorCode`, `CommonErrorCode`, `ServiceException`, `ErrorResponse`, `ValidationErrorDetail`
- Produces: `ResponseEntity<ErrorResponse>` for approved MVC and service exceptions

- [ ] **Step 1: Write the failing MockMvc test fixture**

Add a nested test-only Controller with:

```java
@PostMapping(path = "/test/validation", consumes = MediaType.APPLICATION_JSON_VALUE)
ApiResponse<Void> validate(@Valid @RequestBody TestRequest request) {
    return ApiResponse.success("요청 성공", null);
}

@GetMapping("/test/service-error")
void serviceError() {
    throw new ServiceException(TestErrorCode.STORE_NOT_FOUND);
}

@GetMapping("/test/unexpected")
void unexpected() {
    throw new IllegalStateException("secret-internal-message");
}
```

Use a request record containing two invalid fields and a nested list field. Configure standalone MockMvc with `GlobalExceptionHandler`, a Jakarta Validator, and `DispatcherServlet#setThrowExceptionIfNoHandlerFound(true)`.

Assert:

- invalid body returns 400, `COMMON_001`, all safe details, deterministic order, and no rejected values;
- malformed JSON, wrong type, unknown enum, and unknown field return 400 `COMMON_002`;
- test ErrorCode through `ServiceException` keeps its status, code, and message;
- unsupported method returns 405 `COMMON_006`;
- unsupported content type returns 415 `COMMON_009`;
- missing route returns 404 `COMMON_005`;
- unexpected exception returns 500 `COMMON_011` and does not contain `secret-internal-message`;
- ordinary error JSON omits `details`.

- [ ] **Step 2: Run the handler test and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.global.exception.GlobalExceptionHandlerTest"
```

Expected: test compilation fails because validation support and `GlobalExceptionHandler` are missing.

- [ ] **Step 3: Add the validation starter**

Add exactly:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-validation")
```

Do not add Lombok or another validation library.

- [ ] **Step 4: Implement `GlobalExceptionHandler` minimally**

Use `@RestControllerAdvice` and handlers for:

- `ServiceException`;
- `MethodArgumentNotValidException`;
- `HandlerMethodValidationException`;
- `ConstraintViolationException`;
- `HttpMessageNotReadableException`;
- `NoHandlerFoundException` and servlet `NoResourceFoundException`;
- `HttpRequestMethodNotSupportedException`;
- `HttpMediaTypeNotSupportedException`;
- final `Exception`.

Create a private deterministic detail collector:

```java
private List<ValidationErrorDetail> normalizeDetails(
        Stream<ValidationErrorDetail> details
) {
    return details
            .filter(detail -> detail.field() != null && !detail.field().isBlank())
            .filter(detail -> detail.reason() != null && !detail.reason().isBlank())
            .distinct()
            .sorted(Comparator.comparing(ValidationErrorDetail::field)
                    .thenComparing(ValidationErrorDetail::reason))
            .toList();
}
```

Map field errors to their public field path, global and cross-parameter errors to `$`, and method parameters to the explicit `@RequestParam`, `@PathVariable`, or `@RequestHeader` name when available. Never read or serialize `FieldError#getRejectedValue`.

- [ ] **Step 5: Run the handler test and verify GREEN**

Run the exact Task 3 test command. Expected: all handler tests pass.

- [ ] **Step 6: Run all Global tests**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.global.*"
```

Expected: all Global tests pass.

- [ ] **Step 7: Commit**

```powershell
git add backend/build.gradle.kts backend/src/main/java/com/miriyum/global/exception backend/src/test/java/com/miriyum/global/exception
git commit -m "feat(global): MVC 예외 응답 통합"
```

### Task 4: Replace Properties with Provider-Neutral YAML

**Files:**
- Create: `backend/src/test/java/com/miriyum/global/config/ApplicationYamlTest.java`
- Delete: `backend/src/main/resources/application.properties`
- Create: `backend/src/main/resources/application.yml`

**Interfaces:**
- Preserves: `MIRIYUM_DB_URL`
- Preserves: `MIRIYUM_DB_USERNAME`
- Preserves: `MIRIYUM_DB_PASSWORD`
- Produces: strict unknown-property Jackson configuration

- [ ] **Step 1: Write a failing YAML resource test**

Load `application.yml` from the classpath as text and assert it contains:

```text
name: miriyum-backend
url: ${MIRIYUM_DB_URL}
username: ${MIRIYUM_DB_USERNAME}
password: ${MIRIYUM_DB_PASSWORD}
fail-on-unknown-properties: true
```

Also assert that `application.properties` is absent from the classpath.

- [ ] **Step 2: Run the YAML test and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.global.config.ApplicationYamlTest"
```

Expected: test fails because `application.yml` is absent and `application.properties` is present.

- [ ] **Step 3: Replace the configuration file**

Delete `application.properties` and create:

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

- [ ] **Step 4: Run the YAML and context tests**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.global.config.ApplicationYamlTest" --tests "com.miriyum.MiriyumApplicationTests"
```

Expected: both tests pass without DB environment variables because the context test keeps DataSource, JPA, and Flyway auto-configuration excluded.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/resources/application.yml backend/src/main/resources/application.properties backend/src/test/java/com/miriyum/global/config/ApplicationYamlTest.java
git commit -m "chore(global): 애플리케이션 설정을 YAML로 전환"
```

### Task 5: Contract and Repository Verification

**Files:**
- Modify only if evidence requires a correction: Issue #29 allowlisted implementation and test files
- Verify: `docs/specs/mvp1-common/spec.md`
- Verify: `docs/specs/mvp1-common/openapi.yaml`
- Verify: `docs/superpowers/specs/2026-07-28-global-response-error-design.md`

**Interfaces:**
- Consumes: all Task 1–4 deliverables
- Produces: verified branch ready for PR to `dev`

- [ ] **Step 1: Run focused tests**

```powershell
cd backend
.\gradlew.bat test --tests "com.miriyum.global.*"
```

Expected: all Global tests pass.

- [ ] **Step 2: Run the full backend test suite**

```powershell
.\gradlew.bat test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run the full backend build**

```powershell
.\gradlew.bat build
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Check contract values and forbidden scope**

```powershell
rg -n "COMMON_0(0[1-9]|1[0-2])|SUCCESS|fail-on-unknown-properties" backend/src docs/superpowers/specs/2026-07-28-global-response-error-design.md
rg -n "SecurityFilterChain|Jwt|AuthErrorCode|Redis|Valkey|ParameterStore|application-(local|test)\\.ya?ml" backend/src/main backend/build.gradle.kts
```

Expected: approved values are present; forbidden scope has no new matches in Issue #29 changes.

- [ ] **Step 5: Check repository scope and formatting**

```powershell
git status --short
git diff --check origin/dev...HEAD
git diff --name-only origin/dev...HEAD
```

Expected: every changed path is inside the Issue #29 allowlist and no whitespace errors are reported.

- [ ] **Step 6: Review the complete diff**

Review:

```powershell
git diff --stat origin/dev...HEAD
git diff origin/dev...HEAD
```

Confirm that error JSON does not expose rejected values or internal messages and no placeholder production code exists.

- [ ] **Step 7: Commit any evidence-driven correction**

If verification found an implementation defect, first add a focused failing regression test, verify RED, make the minimal correction, verify GREEN, and commit:

```powershell
git add backend/build.gradle.kts backend/src/main/resources backend/src/main/java/com/miriyum/global backend/src/test/java/com/miriyum/global docs/superpowers/specs/2026-07-28-global-response-error-design.md docs/superpowers/plans/2026-07-28-global-response-error.md
git commit -m "fix(global): 공통 응답 계약 검증 보완"
```

If no correction is needed, do not create an empty commit.

- [ ] **Step 8: Push and open a Draft PR**

Push `feature/29-global-response-errors` and create a Draft PR targeting `dev`. The PR body must include:

- `Closes #29`;
- exact acceptance-condition mapping;
- test/build command results;
- `NOT APPLICABLE` for DB integration, Security, and domain API tests;
- remaining authentication-owner dependency;
- rollback by reverting the PR;
- Human understanding questions grounded in the final diff.
