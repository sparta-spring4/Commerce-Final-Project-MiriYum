# RuleInterpreter 결정적 검색 조건 해석 설계

## 1. 목적과 범위

GitHub Issue #110의 목표는 하나의 한국어 검색 입력에서 서버가 허용한 구조화 조건과 남은 일반 키워드를 결정적으로 분리하는 순수 Java `RuleInterpreter`를 제공하는 것이다.

이 설계는 현재 활성 정본인 다음 계약을 구현 경계로 사용한다.

- `docs/06-system-architecture.md`: 명시적 `Clock`·`ZoneId`, 승인 사전, 남은 키워드의 결정적 해석
- `docs/07-data-and-api-contracts.md`: 모호한 값의 비추측, 경고와 키워드 fallback
- `docs/service-policies/13-ad-recommendation.md`: 허용 조건만 반환하고 조회 계층이 QueryDSL predicate를 조립
- `docs/adr/ADR-007-unified-search-mysql.md`의 2026-07-27 개정: 외부 AI 없는 규칙 해석과 버전된 회귀 검증

`RuleInterpreter`는 검색 결과를 조회하거나 추천하지 않는다. QueryDSL, Repository, MySQL, Controller, HTTP DTO, OpenAPI, 외부 API, AI/LLM, 사용자 현재 위치와 거리 계산은 이 Issue에 포함하지 않는다.

## 2. 패키지와 공개 구성요소

구현은 `com.miriyum.domain.store.search.interpreter` 아래에만 둔다. #82가 먼저 병합되지 않은 현재 `dev` 기준으로 별도 최상위 `search` 패키지를 만들지 않는다.

주요 구성요소는 다음과 같다.

- `RuleInterpreter`: 해석 파이프라인의 단일 진입점
- `InterpretationRequest`: 원문, 승인 사전, `ZoneId`를 묶은 입력
- `SearchVocabulary`: 사전 버전과 종류별 승인 항목
- `VocabularyEntry`: 서버 승인 code와 하나 이상의 alias
- `InterpretedSearchCondition`: 승인 code, 수치·날짜·시각 조건, 남은 키워드
- `InterpretationResult`: 규칙 버전, 사전 버전, 조건, warning 목록
- `InterpretationWarning`: 원문을 복제하지 않는 기계 판독 warning code와 대상 필드

`RuleInterpreter`는 생성자로 `Clock`을 받는다. 상대 날짜는 요청의 `ZoneId`와 `Clock`을 함께 사용하며 JVM 기본 시간대를 읽지 않는다. 규칙 버전은 구현에 고정된 명시적 상수로 결과에 포함한다.

## 3. 승인 사전 계약

사전은 호출자가 전달하며 인터프리터가 지역·카테고리·태그 code를 발명하지 않는다. 지원 종류는 다음 네 가지다.

- 지역 code
- 매장 카테고리 code
- 메뉴 카테고리 code
- 태그 code

각 항목은 불변 `code`와 alias 목록을 가진다. 생성 시 다음 설정 오류를 거부한다.

- 공백 code 또는 alias
- 같은 종류 안에서 하나의 정규화 alias가 서로 다른 code를 가리키는 경우
- 같은 code·alias의 중복 입력
- 비어 있는 사전 버전

사전 alias는 사용자 입력과 같은 Unicode·대소문자 정규화를 거친다. 가장 긴 alias를 먼저 매칭하고, 같은 종류의 복수 code는 중복을 제거하면서 입력에서 처음 나타난 순서를 보존한다. 사전 구성 자체가 모호하면 사용자 warning으로 숨기지 않고 생성 단계에서 실패시켜 잘못된 서버 설정을 조기에 드러낸다.

## 4. 해석 결과 모델

구조화 결과는 SQL 문자열이나 QueryDSL 연산자를 노출하지 않고 다음 허용 값만 가진다.

- `regionCodes`: 순서 보존 code 목록
- `storeCategoryCodes`: 순서 보존 code 목록
- `menuCategoryCodes`: 순서 보존 code 목록
- `tagCodes`: 순서 보존 code 목록
- `priceRange`: 원화 기준 최소·최대 포함 범위
- `partySize`: 양의 정수 인원
- `reservationDate`: `LocalDate`
- `reservationTime`: `LocalTime`
- `remainingKeyword`: 승인된 토큰만 제거하고 공백을 정돈한 잔여 문자열

가격 범위의 양 끝은 선택 값이다. 최소·최대는 모두 포함 경계이며 음수, 역전 범위와 `long` 범위를 넘는 값은 허용하지 않는다. 인원은 양의 정수만 해석하고 실제 매장의 최소·최대 예약 인원 정책은 후속 조회·가용성 계층이 검증한다.

## 5. 정규화와 인식 규칙

입력은 다음 순서로 정규화한다.

1. null은 빈 입력으로 취급하지 않고 잘못된 호출로 거부한다.
2. Unicode NFKC로 호환 문자를 정규화해 전각 숫자·단위를 일관되게 만든다.
3. 제어 문자는 구분 공백으로 바꾸되 따옴표, SQL 기호와 일반 문장부호는 삭제하지 않는다.
4. 연속 공백을 하나로 줄이고 앞뒤 공백을 제거한다.
5. 사전 비교용 문자열만 `Locale.ROOT` 대소문자 정규화를 사용한다. 결과 키워드는 정규화된 원래 표기를 유지한다.

초기 규칙 버전은 추측을 피하기 위해 다음 명시 형식만 지원한다.

- 가격: `N원`, `N천원`, `N만원`과 `이상`, `이하`, `미만`, `초과`, 명시 범위 `N~M원`
- 인원: 양의 정수 뒤의 `명`
- 날짜: `오늘`, `내일`, `모레`, `yyyy-MM-dd`, `yyyy년 M월 d일`
- 시각: `HH:mm`, `오전/오후 H시`, 선택적인 `M분`
- 사전 값: 승인 alias의 독립된 어휘 일치

`만원대`, `점심`, `저녁`, 연도 없는 `M월 d일`, 시각이 없는 `쯤` 표현처럼 정책적 추론이 필요한 값은 임의 변환하지 않는다. 인식 가능한 모호 표현에는 warning을 남기고 원문 조각을 키워드에 보존한다. 검색 품질 평가로 새 표현을 승인할 때 규칙 버전과 회귀 데이터셋을 함께 올린다.

## 6. 파이프라인과 충돌 처리

인터프리터는 원문을 차례로 잘라내는 가변 문자열 방식 대신 원본 정규화 문자열에 대한 span 후보를 수집한다.

1. 사전 matcher가 종류·code·입력 span 후보를 만든다.
2. 가격·인원·날짜·시각 parser가 값·입력 span 후보를 만든다.
3. 후보 해결기가 겹침, 중복과 모순을 판정한다.
4. 승인된 span만 제거하고 나머지를 `remainingKeyword`로 조립한다.

동일 필드에 같은 값이 반복되면 하나로 축약한다. 서로 다른 단일 값 또는 양립할 수 없는 범위가 함께 나오면 어느 하나도 임의 선택하지 않는다. 해당 필드를 비우고 필드별 conflict warning을 반환하며, 충돌에 참여한 모든 span은 키워드에 남긴다.

사전 code 목록은 같은 종류 안에서 OR 후보이므로 복수 값을 허용한다. 서로 다른 종류의 matcher가 같은 span을 요구하면 어느 쪽도 소비하지 않고 ambiguity warning과 키워드 fallback을 사용한다.

## 7. Warning과 실패 경계

warning은 원문이나 민감 입력을 복제하지 않고 enum code와 필드만 가진다. 최소 warning 분류는 다음과 같다.

- `AMBIGUOUS_DICTIONARY_TERM`
- `AMBIGUOUS_PRICE`
- `CONFLICTING_PRICE`
- `INVALID_PARTY_SIZE`
- `CONFLICTING_PARTY_SIZE`
- `AMBIGUOUS_DATE`
- `CONFLICTING_DATE`
- `AMBIGUOUS_TIME`
- `CONFLICTING_TIME`
- `OUT_OF_RANGE_NUMBER`

알 수 없는 일반 표현 자체는 오류가 아니며 warning 없이 키워드로 남을 수 있다. 반면 지원 문법과 닮았지만 안전하게 해석할 수 없는 표현은 warning과 키워드 fallback을 사용한다.

잘못 구성된 사전, null 필수 입력과 유효하지 않은 `ZoneId`는 프로그래밍·구성 오류이므로 결과 warning으로 정상화하지 않고 명시적 예외로 거부한다. 해석 과정은 로그를 남기지 않으며 특히 원문 전체, 잔여 키워드와 warning 대상 조각을 기록하지 않는다.

## 8. 보안과 결정성

`RuleInterpreter`는 입력을 실행 가능한 표현으로 변환하지 않는다. SQL·JPQL·QueryDSL predicate·정규식 소스·외부 provider payload를 만들지 않으며 문자열에 포함된 작은따옴표, 주석 기호와 SQL 키워드는 인식된 승인 조건이 아니면 그대로 `remainingKeyword`에 보존한다.

같은 원문, 같은 사전 내용·버전, 같은 규칙 버전, 같은 `Clock` 시각과 같은 `ZoneId`에는 `equals` 기준으로 같은 결과가 나와야 한다. 결과 목록과 warning은 안정된 입력 순서로 반환하고 hash 기반 순회 순서에 의존하지 않는다.

## 9. 테스트 전략

모든 테스트는 Spring context와 Testcontainers 없이 순수 단위 테스트로 실행한다.

- Unicode NFKC, 공백과 전각 숫자 정규화
- 지역·매장 카테고리·메뉴 카테고리·태그 복수 code의 입력 순서와 중복 제거
- 가격 이상·이하·범위와 경계 포함 여부
- 양의 인원과 잘못된 인원
- 고정 `Clock`·`ZoneId`에서 오늘·내일·모레 및 절대 날짜
- 24시간제와 오전·오후 시각
- 동일 값 중복과 가격·인원·날짜·시각 conflict
- 모호 표현의 warning과 잔여 키워드
- 알 수 없는 표현과 SQL injection 문자열의 보존
- 같은 입력 스냅샷의 반복 실행 결과 동일성
- 잘못된 사전의 빠른 실패

집중 검증은 interpreter 패키지 테스트와 compile을 사용한다. 전체 backend 테스트는 별도 최종 gate로 실행하되 현재 `dev` 기준 Testcontainers MySQL 반복 기동으로 실행 시간이 10분을 넘길 수 있다는 관측을 미해결 테스트 실패와 구분해 기록한다.

## 10. 후속 이슈 경계

- #111은 이 결과 모델을 QueryDSL 조건으로 변환하되 `RuleInterpreter`에 조회 책임을 역류시키지 않는다.
- #112는 HTTP 요청·응답과 예약 가용성 재검증을 소유한다.
- #113은 검색 조건과 이력 snapshot을 결정적 추천 점수 입력으로 사용한다.
- #114는 저장된 매장 좌표와 가용성 계약을 사용하며 사용자 현재 위치나 추천 중 외부 지도 호출을 추가하지 않는다.
- #115는 frontend 작업이며 #109 backend Epic의 완료 조건에 포함하지 않는다.

## 11. 롤백

#110은 production route와 persistence를 추가하지 않으므로 롤백은 interpreter 패키지와 해당 테스트 제거로 제한된다. 후속 이슈가 결과 모델을 소비하기 전에는 runtime 동작에 영향을 주지 않는다. 규칙 변경은 기존 버전을 조용히 바꾸지 않고 규칙 버전과 회귀 데이터셋을 함께 갱신한다.
