# Backend 명령 레지스트리

계약 상태: ACTIVE

실행 파일이 존재하고 성공 출력과 종료 코드가 관찰된 명령만 `CONFIGURED`일 수 있다. 모든 명령은 `backend/`에서 실행한다.

| 명령 ID | 상태 | 정확한 명령 | 필수 입력 | 예상 출력 | 증거 | 실패 의미 |
|---|---|---|---|---|---|---|
| `backend.wrapper.version` | `CONFIGURED` | `.\gradlew.bat --version` | 커밋된 Wrapper 스크립트, Wrapper JAR, Wrapper 속성, 네트워크 또는 검증된 로컬 배포본 | Gradle `9.6.1` 및 종료 코드 `0` | 현재 변경 증거와 함께 명령 출력, 종료 코드, Wrapper 해시를 보존한다 | 0이 아닌 종료, 잘못된 버전, 다운로드 실패 또는 체크섬 거부는 `FAIL`을 의미한다 |
| `backend.test` | `CONFIGURED` | `.\gradlew.bat test` | Java 21 toolchain, 해결된 의존성, 애플리케이션 및 테스트 소스 | `@Tag("integration")`이 없는 빠른 unit·slice 테스트가 성공하고 명령이 `0`으로 종료한다 | 현재 변경 증거와 함께 Gradle 테스트 출력, 종료 코드, 테스트 보고서를 보존한다 | 컴파일, context 시작, assertion, 의존성 또는 toolchain 실패는 `FAIL`을 의미한다 |
| `backend.integration-test` | `CONFIGURED` | `.\gradlew.bat integrationTest` | Java 21 toolchain, Docker가 실행 가능한 환경, 해결된 의존성, Testcontainers 이미지 pull 권한 | `@Tag("integration")` Testcontainers 또는 Spring 통합 테스트가 성공하고 명령이 `0`으로 종료한다 | 현재 변경 증거와 함께 Gradle 테스트 출력, 종료 코드, `integrationTest` 보고서를 보존한다 | Docker/Testcontainers 시작, Flyway, context 시작, assertion, 의존성 또는 toolchain 실패는 `FAIL`을 의미한다 |
| `backend.integration-test-shard-a` | `CONFIGURED` | `.\gradlew.bat integrationTestShardA` | `backend.integration-test`와 동일한 입력 | `@Tag("integration-shard-a")` 통합 테스트가 성공하고 명령이 `0`으로 종료한다 | 현재 변경 증거와 함께 Gradle 테스트 출력, 종료 코드, `integrationTestShardA` 보고서를 보존한다 | Docker/Testcontainers 시작, Flyway, context 시작, assertion, 의존성 또는 toolchain 실패는 `FAIL`을 의미한다 |
| `backend.integration-test-shard-b` | `CONFIGURED` | `.\gradlew.bat integrationTestShardB` | `backend.integration-test`와 동일한 입력 | `@Tag("integration-shard-b")` 통합 테스트가 성공하고 명령이 `0`으로 종료한다 | 현재 변경 증거와 함께 Gradle 테스트 출력, 종료 코드, `integrationTestShardB` 보고서를 보존한다 | Docker/Testcontainers 시작, Flyway, context 시작, assertion, 의존성 또는 toolchain 실패는 `FAIL`을 의미한다 |
| `backend.integration-test-shard-c` | `CONFIGURED` | `.\gradlew.bat integrationTestShardC` | `backend.integration-test`와 동일한 입력 | `@Tag("integration-shard-c")` 통합 테스트가 성공하고 명령이 `0`으로 종료한다 | 현재 변경 증거와 함께 Gradle 테스트 출력, 종료 코드, `integrationTestShardC` 보고서를 보존한다 | Docker/Testcontainers 시작, Flyway, context 시작, assertion, 의존성 또는 toolchain 실패는 `FAIL`을 의미한다 |
| `backend.integration-test-shard-d` | `CONFIGURED` | `.\gradlew.bat integrationTestShardD` | `backend.integration-test`와 동일한 입력 | `@Tag("integration-shard-d")` 통합 테스트가 성공하고 명령이 `0`으로 종료한다 | 현재 변경 증거와 함께 Gradle 테스트 출력, 종료 코드, `integrationTestShardD` 보고서를 보존한다 | Docker/Testcontainers 시작, Flyway, context 시작, assertion, 의존성 또는 toolchain 실패는 `FAIL`을 의미한다 |
| `backend.build` | `CONFIGURED` | `.\gradlew.bat build` | `backend.test`, `backend.integration-test`와 동일한 입력 | 컴파일, 빠른 테스트, 통합 테스트, 패키징이 종료 코드 `0`으로 성공한다 | 현재 변경 증거와 함께 Gradle 빌드 출력, 종료 코드, 생성된 보고서를 보존한다 | 모든 컴파일, 테스트, 패키징, 의존성 또는 toolchain 실패는 `FAIL`을 의미한다 |

위 여덟 명령은 파일이 존재하고 각 명령의 성공적인 종료 코드 `0`이 관찰된 후에만 활성화되었다.

## 활성화 대기 로컬 전체 검증 명령

| 명령 ID | 상태 | 정확한 명령 | 필수 입력 | 예상 출력 | 현재 증거와 활성화 조건 | 실패 의미 |
|---|---|---|---|---|---|---|
| `backend.full-verification` | `NOT CONFIGURED` | `.\scripts\run-backend-full-verification.ps1 -ParallelShards Auto` | PowerShell 7, Java 21, Docker/Testcontainers, 해결된 의존성, Windows에서는 Job Object를 사용할 수 있는 host | unit+assemble과 integration A~D가 모두 exit `0`, 각 XML의 `tests > 0`, `failures/errors/skipped == 0`, child별 격리 로그·report와 최종 종료 코드 | commit `9303cbb8`에서 Common 14건, Gradle 1건, WindowsNative 4건과 PR #351의 Ubuntu·Windows contract 및 Backend CI가 성공했다. 경쟁 workload와 임시 container가 없는 34GB Windows에서 Auto=2 전체 실행은 2,925 tests와 전체-run peak를 완전하게 기록했지만 wall time 42분 42.61초로 25분 목표에 실패했다. 기능·containment·peak·CI 증거는 충족했으나 성능 목표가 남아 있으므로 `CONFIGURED`로 승격하지 않는다 | child timeout·cancel·containment 실패, non-zero exit, XML 누락·malformed·0 tests·failure/error/skipped, 또는 활성화 전제 미충족은 각각 `FAIL` 또는 `NOT CONFIGURED`다 |

`-ParallelShards`는 기본 `Auto`이고 명시적 override는 `1`, `2`, `4`만 허용한다. Auto=1은 저메모리 안전 보장이 아니라 최소 병렬 fallback이며 Docker Desktop/WSL2 VM memory cap은 host 가용 메모리 탐지에 포함되지 않는다. 이 명령은 선택적 backend 전용 runner이고 범용 command/verification runner의 상태를 바꾸지 않는다.

## 러너 플랫폼별 호출

위 "정확한 명령"은 Windows 로컬 기준 `.\gradlew.bat` 표기다. Linux CI 러너(`ubuntu-24.04`, `.github/workflows/backend-ci.yml`, Issue #92)에서는 같은 명령 ID를 `./gradlew`로 호출한다. 예: `backend.wrapper.version`은 `./gradlew --version`, `backend.test`는 `./gradlew test`, shard 명령은 `./gradlew integrationTestShardA`부터 `./gradlew integrationTestShardD`까지, `backend.build`는 `./gradlew build`다. 래퍼·버전·검증 대상은 동일하며 호출 표기만 플랫폼에 따라 다르다. GitHub Windows 러너는 Linux 컨테이너를 지원하지 않아 Testcontainers MySQL을 실행할 수 없으므로 CI 러너는 Linux(`./gradlew`)를 사용한다.

DB 통합 명령은 `backend.integration-test`로 구성되어 있다. API 스모크와 E2E에는 아직 안정적인 명령 ID가 없다. Docker와 staging 배포는 로컬 명령 레지스트리가 아니라 [배포 runbook](../../docs/deployment/docker-ecr-ssm-cd.md)의 통제된 workflow와 실행 증거로 검증한다. CI 워크플로와 required check의 상태는 `ai/verification-and-completion.md`가 소유한다.
