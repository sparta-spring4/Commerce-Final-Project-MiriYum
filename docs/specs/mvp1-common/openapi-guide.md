# 1차 MVP OpenAPI 작성 안내

이 문서는 OpenAPI를 처음 작성하는 팀원이 기능별 `openapi.yaml`을 같은 방식으로 작성하도록 돕는 사용 설명서다. 정책은 [공통 명세](spec.md)가 소유하며 이 문서는 새 정책을 만들지 않는다.

## 한 문장으로 이해하기

백엔드의 `ApiResponse<T>`에서 `T`가 무엇인지 OpenAPI의 `data`에 구체적으로 적으면 된다.

```java
ApiResponse<ReservationDetailResponse>
```

```yaml
data:
  $ref: "#/components/schemas/ReservationDetail"
```

Java 성공 봉투 클래스를 API마다 새로 만들 필요는 없다. OpenAPI 응답 스키마만 실제 `data` 형태별로 만든다.

## 가장 먼저 복사할 성공 응답

다음 예시를 복사한 뒤 `ReservationDetailSuccessResponse`와 `ReservationDetail` 두 이름만 현재 기능에 맞게 바꾼다.

```yaml
ReservationDetailSuccessResponse:
  type: object
  additionalProperties: false
  required:
    - code
    - message
    - data
  properties:
    code:
      $ref: "../mvp1-common/openapi.yaml#/components/schemas/SuccessCode"
    message:
      $ref: "../mvp1-common/openapi.yaml#/components/schemas/SuccessMessage"
    data:
      $ref: "#/components/schemas/ReservationDetail"
```

`required`는 응답에 반드시 있어야 하는 필드다. `additionalProperties: false`는 문서에 없는 필드가 실수로 추가되는 것을 막는다.

## 단건 조회

```yaml
ReservationDetail:
  type: object
  additionalProperties: false
  required:
    - reservationId
    - status
  properties:
    reservationId:
      $ref: "../mvp1-common/openapi.yaml#/components/schemas/PublicId"
    status:
      type: string
      enum:
        - CONFIRMED
        - CANCELLED
        - FULFILLED
```

공개 ID는 숫자처럼 보여도 JSON 문자열이다.

```json
{
  "reservationId": "123"
}
```

## 목록 조회

목록이 비어도 오류가 아니며 `items: []`를 반환한다.

```yaml
ReservationListData:
  type: object
  additionalProperties: false
  required:
    - items
  properties:
    items:
      type: array
      items:
        $ref: "#/components/schemas/ReservationSummary"
```

프론트엔드는 `items.length === 0`이면 빈 결과 화면을 보여준다. 서버는 별도의 `empty` 필드를 추가하지 않는다.

## 페이지 조회

```yaml
ReservationPageData:
  type: object
  additionalProperties: false
  required:
    - items
    - page
  properties:
    items:
      type: array
      items:
        $ref: "#/components/schemas/ReservationSummary"
    page:
      $ref: "../mvp1-common/openapi.yaml#/components/schemas/PageMetadata"
```

페이지 번호는 0부터 시작하며 기본 크기는 20, 최대 크기는 100이다.

## 생성 성공

생성 성공은 보통 `201 Created`와 생성된 자원의 문자열 ID를 반환한다.

```yaml
ReservationCreatedData:
  type: object
  additionalProperties: false
  required:
    - reservationId
    - status
  properties:
    reservationId:
      $ref: "../mvp1-common/openapi.yaml#/components/schemas/PublicId"
    status:
      type: string
      const: CONFIRMED
```

상태 변경 명령에는 공통 멱등성 헤더를 연결한다.

```yaml
parameters:
  - $ref: "../mvp1-common/openapi.yaml#/components/parameters/IdempotencyKey"
```

## 반환 데이터가 없는 성공

JSON 응답을 유지하면서 실제 결과가 없으면 `data`는 빈 객체가 아니라 `null`이다.

```yaml
NoDataSuccessResponse:
  type: object
  additionalProperties: false
  required:
    - code
    - message
    - data
  properties:
    code:
      $ref: "../mvp1-common/openapi.yaml#/components/schemas/SuccessCode"
    message:
      $ref: "../mvp1-common/openapi.yaml#/components/schemas/SuccessMessage"
    data:
      $ref: "../mvp1-common/openapi.yaml#/components/schemas/NoData"
```

본문 자체가 필요 없는 API를 명시적으로 `204 No Content`로 설계한 경우에는 성공 봉투를 보내지 않는다.

## 오류 응답

공통 오류는 공통 response component를 참조한다.

```yaml
responses:
  "400":
    $ref: "../mvp1-common/openapi.yaml#/components/responses/BadRequest"
  "401":
    $ref: "../mvp1-common/openapi.yaml#/components/responses/Unauthorized"
  "403":
    $ref: "../mvp1-common/openapi.yaml#/components/responses/Forbidden"
```

수용량 부족·재고 부족·잘못된 상태처럼 도메인 의미가 있는 오류는 기능별 `ErrorCode`와 OpenAPI에 정확한 코드·HTTP 상태를 작성한다.

## 인증 표기

보호 API:

```yaml
security:
  - bearerAuth: []
```

기능 OpenAPI의 `components.securitySchemes.bearerAuth`는 공통 보안 스키마를 참조한다.

공개 API:

```yaml
security: []
```

일반 보호 API가 Refresh Token 쿠키를 Access Token 대신 사용하도록 작성하지 않는다.

## 날짜와 시간

```yaml
serviceDate:
  $ref: "../mvp1-common/openapi.yaml#/components/schemas/LocalDate"
startTime:
  $ref: "../mvp1-common/openapi.yaml#/components/schemas/LocalTime"
endTime:
  $ref: "../mvp1-common/openapi.yaml#/components/schemas/LocalTime"
```

시간 구간은 `[startTime, endTime)`이다. 초 단위를 임의로 추가하거나 UTC 날짜로 바꾸지 않는다.

## 프론트엔드에서 읽는 법

성공 응답의 공통 모양은 같다.

```ts
type ApiResponse<T> = {
  code: "SUCCESS";
  message: string;
  data: T;
};
```

기능별 `T`만 달라진다.

```ts
type ReservationDetailResponse = ApiResponse<ReservationDetail>;
type ReservationPageResponse = ApiResponse<ReservationPageData>;
```

프론트엔드는 `message`가 아니라 HTTP 상태와 오류 `code`로 분기한다.

## 금지 예시

다음처럼 `data`를 비워 두면 문자열이나 잘못된 객체도 lint를 통과할 수 있으므로 금지한다.

```yaml
data: {}
```

성공 봉투를 `allOf`로 조합하지 않는다.

```yaml
allOf:
  - $ref: "#/components/schemas/SuccessEnvelope"
  - type: object
```

내부 `Long` ID를 JSON 숫자로 공개하지 않는다.

```yaml
reservationId:
  type: integer
  format: int64
```

## 제출 전 체크리스트

- 응답의 `code`, `message`, `data`가 모두 `required`인가?
- `data`가 실제 기능 스키마를 참조하는가?
- 객체에 `additionalProperties: false`가 있는가?
- ID가 공통 `PublicId` 문자열인가?
- 날짜·시간이 공통 component를 참조하는가?
- 상태 변경에 `Idempotency-Key`가 있는가?
- 보호 API에 Bearer 인증과 `401`, 필요한 `403`이 있는가?
- 개인 자원의 실제 부재와 다른 사용자 소유가 같은 도메인 `404`인가?
- 빈 목록이 `200`과 `items: []`인가?
- 결제·노쇼·플랫폼 운영자 등 뒤 단계 필드가 섞이지 않았는가?
- 고정 Redocly CLI `2.35.1` lint와 통합 bundle을 통과했는가?
