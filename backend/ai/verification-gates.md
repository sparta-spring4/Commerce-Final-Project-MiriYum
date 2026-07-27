# Backend 검증 게이트

계약 상태: ACTIVE

루트 [검증 및 완료 계약](../../ai/verification-and-completion.md)의 결과 어휘와 완료 주장 필드를 사용한다.

## 게이트 순서

1. 작업 허용 목록, 기존 사용자 경로 해시, 비어 있는 staged index를 확인한다.
2. `backend.wrapper.version`을 실행한다.
3. 가장 좁은 관련 자동화 테스트를 실행한다. 스캐폴드에서는 `backend.test`이다.
4. `backend.build`를 실행한다.
5. 정확한 diff, `git diff --check`, 생성 경로 범위, 증거를 검사한다.

게이트는 정확한 명령 또는 검토가 실행되어 예상 결과를 생성한 경우에만 `PASS`이다. `FAIL` 또는 `BLOCKED`이면 중단하며, 승인된 범위 안에서만 수정한 뒤 원래 게이트를 다시 실행한다.

## 증거 경계

Backend 검증 증거에는 정확한 명령, 작업 디렉터리, 종료 코드, 관찰한 출력, 영향을 받은 테스트, 남은 위험, 증거 위치를 기록한다. 명령 레지스트리 항목 자체는 실행 증거가 아니다.

초기 context test는 영속성과 Flyway 자동 구성을 제외한 Spring 애플리케이션 wiring만 증명한다. MySQL 호환성, 마이그레이션, 트랜잭션 격리, 데이터베이스 제약, API 동작, 인증, 인가 또는 cross-end 통합을 증명하지 않는다.

DB 의존 통합, API 스모크, CI, Docker, 배포, E2E는 실행 표면과 활성화 증거가 존재할 때까지 `NOT CONFIGURED`이다.
