# Backend 명령 레지스트리

계약 상태: ACTIVE

실행 파일이 존재하고 성공 출력과 종료 코드가 관찰된 명령만 `CONFIGURED`일 수 있다. 모든 명령은 `backend/`에서 실행한다.

| 명령 ID | 상태 | 정확한 명령 | 필수 입력 | 예상 출력 | 증거 | 실패 의미 |
|---|---|---|---|---|---|---|
| `backend.wrapper.version` | `CONFIGURED` | `.\gradlew.bat --version` | 커밋된 Wrapper 스크립트, Wrapper JAR, Wrapper 속성, 네트워크 또는 검증된 로컬 배포본 | Gradle `9.6.1` 및 종료 코드 `0` | 현재 변경 증거와 함께 명령 출력, 종료 코드, Wrapper 해시를 보존한다 | 0이 아닌 종료, 잘못된 버전, 다운로드 실패 또는 체크섬 거부는 `FAIL`을 의미한다 |
| `backend.test` | `CONFIGURED` | `.\gradlew.bat test` | Java 21 toolchain, 해결된 의존성, 애플리케이션 및 테스트 소스 | context test가 성공하고 명령이 `0`으로 종료한다 | 현재 변경 증거와 함께 Gradle 테스트 출력, 종료 코드, 테스트 보고서를 보존한다 | 컴파일, context 시작, assertion, 의존성 또는 toolchain 실패는 `FAIL`을 의미한다 |
| `backend.build` | `CONFIGURED` | `.\gradlew.bat build` | `backend.test`와 동일한 입력 | 컴파일, 테스트, 패키징이 종료 코드 `0`으로 성공한다 | 현재 변경 증거와 함께 Gradle 빌드 출력, 종료 코드, 생성된 보고서를 보존한다 | 모든 컴파일, 테스트, 패키징, 의존성 또는 toolchain 실패는 `FAIL`을 의미한다 |

위 세 명령은 파일이 존재하고 스캐폴드 검증 중 각각 종료 코드 `0`을 반환한 후에만 활성화되었다.

DB 통합, API 스모크, Docker, CI, 배포 명령은 실행 표면이 `NOT CONFIGURED`이므로 안정적인 명령 ID가 없다.
