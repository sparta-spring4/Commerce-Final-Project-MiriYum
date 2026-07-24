# 루트 통합 계약

계약 상태: ACTIVE
조합된 cross-end 통합 runtime 상태: NOT CONFIGURED
정본 소유자: 루트 cross-end 분류, 인계(handoff) 경계 및 조합된 통합 증거
활성화 조건(trigger): 범위가 backend와 frontend 경계를 가로지르거나 공유 계약을 변경함
필수 입력: 소유 Issue, 정본 데이터/API 문서, 관련 기능 명세 및 이용 가능한 경우 검증된 엔드포인트 출력
예상 출력: 공유 인수 조건에 대응되는 명시적 backend/frontend 인계(handoff) 및 조합된 증거
추론 금지: 엔드포인트 scaffold 검증으로 배포된 서비스, 제품 API 동작 또는 성공적인 cross-end 통합을 추론하지 않음

## runtime 경계

Backend scaffold 검증 surface: CONFIGURED
Frontend scaffold 검증 surface: CONFIGURED
조합된 cross-end 통합 runtime: NOT CONFIGURED

두 엔드포인트 scaffold, 로컬 AI 계약, wrapper/package script 및 검증 command ID는 이제 존재하며 성공적인 활성화 증거를 갖는다. 이 엔드포인트 검증 ID를 조합할 때는 [루트 명령 조합 레지스트리](command-registry.md)만 사용한다.

제품 API, 인증 교환, 공유 데이터 인계(handoff) 또는 통합 사용자 흐름 surface는 생성되거나 검증되지 않았다. 따라서 엔드포인트 scaffold를 사용할 수 있어도 조합된 cross-end 통합 runtime은 `NOT CONFIGURED`에서 변경되지 않는다.

## 작업 분류

- **backend-only:** frontend가 사용하는 계약을 바꾸지 않는 서버 소유 동작 또는 데이터 변경이다. 활성화 후 backend 로컬 소유자를 통해 라우팅한다.
- **frontend-only:** backend가 제공하는 계약을 바꾸지 않는 클라이언트 소유 표현 또는 상호작용 변경이다. 활성화 후 frontend 로컬 소유자를 통해 라우팅한다.
- **cross-end:** 조정된 backend 및 frontend 작업이 필요한 API, 인증 흐름, 인가 결과, 오류 의미, 데이터 형태, 멱등성 동작 또는 인수 흐름의 변경이다.

엔드포인트 중 하나라도 이용할 수 없으면 cross-end 작업에서 계약을 정의하거나 검토할 수는 있지만, 통합 실행은 통과가 아닌 `NOT CONFIGURED`로 보고해야 한다.

## 정본 계약 링크

저장소 전체의 데이터 및 API 원칙에는 `docs/07-data-and-api-contracts.md`를 사용한다. 지속적인 기능 동작, 구체적인 요청 및 응답 의미, 실패 의미 및 인수 조건에는 관련 `docs/specs/<feature>/spec.md`를 사용한다. 규칙을 여기에 복사하지 말고 해당 소유자 문서로 링크한다.

제품, 정책, UI, 아키텍처 또는 품질 관련 사실은 각각의 `docs/` 소유자 문서에 남긴다. 이 문서는 엔드포인트 경계 전반의 조정만 소유한다.

## 필수 인계(handoff)

모든 cross-end 변경에 대해 다음을 기록한다.

1. 계약 소유자와 인수 조건
2. 요청, 응답, 인증 및 인가 가정
3. 오류 코드, 사용자에게 표시되는 오류 의미 및 재시도 또는 멱등성 기대 사항
4. 데이터의 단일 진실 원천, 선택성, 순서, 정밀도, 시간 및 호환성 제약
5. frontend에 필요한 backend 출력 및 증거
6. backend에 필요한 frontend 소비 및 증거
7. 미해결 위험과 다음 결정을 내릴 소유자

추측한 payload, 만들어 낸 명령 또는 문서화되지 않은 오류 동작을 handoff하지 않는다. delegate의 artifact는 통합 검토의 입력일 뿐, 그 자체로 완료 증거가 아니다.

## 통합 증거 경계

엔드포인트 증거는 엔드포인트 명령 또는 CI 실행과 함께 유지한다. Pull Request는 로그를 복제하지 않고 링크를 조합해 공유 인수 조건에 대응시킨다.

`root.verify.scaffold`는 backend와 frontend scaffold 검증 증거만 조합한다. 이는 조합된 계약, smoke, API 또는 사용자 흐름 증거가 아니다.

완전한 cross-end 증거 세트에는 다음이 포함된다.

- 정본 계약 또는 명세의 개정
- backend 검증 결과와 증거
- frontend 검증 결과와 증거
- 실행 가능한 surface가 있는 경우 조합된 계약, smoke 또는 사용자 흐름 결과
- 실제로 실행한 명령, 관찰 결과, 남은 위험 및 증거 링크

누락된 엔드포인트 또는 조합 runtime 증거는 해당하는 경우 `NOT RUN` 또는 `NOT CONFIGURED`로 유지해야 한다. 문서 검토만으로 API 호환성, 인증 동작, 오류 처리, 데이터 일관성 또는 통합 사용자 흐름을 증명할 수 없다.

## 활성화 경계

각 엔드포인트 scaffold와 해당 로컬 소유 문서는 함께 생성되고 검증되었다. 루트 명령 레지스트리는 결과로 생성된 `CONFIGURED` 엔드포인트 command ID만 참조할 수 있으며, 이 문서는 framework 규칙이나 리터럴 엔드포인트 명령 문자열을 절대 소유하지 않는다.

cross-end 통합 실행은 실제 공유 runtime, 안전한 입력, 성공 및 실패 동작, 그리고 조합된 증거가 검증된 후에만 활성화할 수 있다. 그때까지 cross-end 작업은 계약을 정의하거나 검토할 수 있지만 통합 실행은 `NOT CONFIGURED`로 보고해야 한다.
