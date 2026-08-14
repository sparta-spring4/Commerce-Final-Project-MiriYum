# ADR-004: 스캐폴드 도구 버전과 초기 테스트 기준

- 상태: Accepted
- 결정일: 2026-07-24

## 배경

백엔드·프런트엔드 스캐폴딩 계획은 재현 가능한 Wrapper와 잠금 파일을 만들기 위해 정확한 패치 버전을 활성화 입력값으로 요구한다. 기존 아키텍처 문서는 기술 계열과 Java 21만 확정했고 Spring Boot, Gradle, Node.js, pnpm과 프런트엔드 패키지 버전은 고정하지 않았다.

또한 Spring Data JPA, MySQL과 Flyway 의존성을 추가하더라도 아직 마이그레이션이나 DB 고유 동작이 없으므로, 초기 컨텍스트 테스트가 무엇을 증명하는지와 DB 통합을 언제 활성화할지 구분해야 한다.

## 결정

2026-07-24 공식 최신/LTS 메타데이터를 기준으로 다음 기준을 채택한다.

- Java 21, Spring Boot 4.1.0, Gradle Wrapper 9.6.1
- Gradle `bin` 배포판 SHA-256 `9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14`
- Gradle Wrapper JAR SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`
- Java 패키지 `com.miriyum`, 애플리케이션 클래스 `MiriyumApplication`
- Node.js 24.18.0 LTS와 pnpm 11.17.0
- React·React DOM 19.2.8, TypeScript 7.0.2, Vite 8.1.5, `@vitejs/plugin-react` 6.0.4
- Vitest 4.1.10, React Testing Library 16.3.2, Testing Library DOM 10.4.1, jest-dom 7.0.0, jsdom 29.1.1
- React 타입 패키지 19.2.17/19.2.3과 Node 24 타입 패키지 24.13.3

Spring 의존성 버전은 Spring Boot 의존성 관리가 단일 소유한다. 프런트엔드 의존성은 정확한 버전과 `pnpm-lock.yaml`로 고정한다.

초기 테스트에는 H2와 Testcontainers를 추가하지 않는다. 컨텍스트 테스트는 영속성과 Flyway가 없는 환경에서 기본 애플리케이션 연결 구성만 검증한다. MySQL 고유 동작, 첫 Flyway 마이그레이션, 트랜잭션/격리 또는 실제 DB 제약이 생길 때 ADR-002의 도입 조건에 따라 Testcontainers를 검토한다. 그 전까지 DB 통합은 `NOT CONFIGURED`다.

## 검토한 대안

- 생성기의 시점별 기본 버전 사용: 같은 계획을 다시 실행할 때 결과가 달라지므로 거절한다.
- 모든 의존성에 범위 버전 사용: 잠금 파일과 Wrapper의 재현 가능성을 약화하므로 거절한다.
- H2 기반 컨텍스트 테스트: MySQL과 다른 동작을 DB 호환성 증거로 오해할 수 있어 거절한다.
- 스캐폴드부터 Testcontainers 사용: 검증할 마이그레이션이나 DB 고유 요구가 아직 없어 ADR-002의 단계적 도입 원칙에 어긋난다.
- ESLint 도구 체인 동시 활성화: 최소 스캐폴드의 필수 검증 관문이 아니며 TypeScript 컴파일러, Vitest와 빌드로 현재 범위를 검증할 수 있어 후속으로 미룬다.

## 결과

- Wrapper, 잠금 파일과 엔드별 명령 레지스트리가 정확한 입력값을 사용할 수 있다.
- 컨텍스트 테스트와 DB 통합 증거의 의미가 분리된다.
- 버전 업그레이드는 이 ADR과 실행 증거를 함께 갱신해야 한다.
- 린트(lint)와 실제 DB 통합은 현재 `NOT CONFIGURED`로 남는다.

## 재검토 조건

지원 종료, 보안 수정, Spring Boot·Gradle·Node·Vite 호환성 변화, 첫 DB 마이그레이션 또는 린트(lint)가 필수 검증 관문이 되는 변경이 발생하면 정확한 기준과 검증 명령을 다시 검토한다.

## 관련 문서

- [백엔드 및 프런트엔드 스캐폴딩 설계](../superpowers/specs/2026-07-24-backend-frontend-scaffolding-design.md)
- [시스템 아키텍처](../06-system-architecture.md)
- [UI 및 프론트엔드 가이드라인](../08-ui-and-frontend-guidelines.md)
- [품질 운영 및 규칙](../09-quality-operations-and-rules.md)
- [ADR-002](ADR-002-staged-technology-adoption.md)

## 2026-07-27 날짜별 개정

### 재검토 조건 충족과 현재 결정

- 최초 스캐폴딩 결정과 Testcontainers 초기 미도입 본문은 DB 상호작용이 없던 시점의 기록으로 보존한다.
- 1차 MVP가 Flyway 마이그레이션, MySQL 제약, 예약 수용량·회차별 팀 수·메뉴 홀드 경합, 잠금과 조건부 SQL을 실제 구현하므로 본문의 “첫 DB 마이그레이션” 재검토 조건이 충족되었다.
- 따라서 **Testcontainers MySQL을 1차 MVP 필수 테스트 기준선으로 활성화**한다. H2 또는 인메모리 대체 DB의 성공으로 MySQL 통합 테스트를 대신하지 않는다.
- 기존에 확정한 Java 21, Spring Boot 4.1.0, Gradle 9.6.1과 프론트엔드 Node 24.18.0, pnpm 11.17.0, React 19.2.8, TypeScript 7.0.2, Vite 8.1.5, Vitest 4.1.10, React Testing Library 16.3.2 기준을 유지한다.

### 검증과 결과

- 백엔드 게이트는 컴파일·단위 테스트에 더해 실제 MySQL 컨테이너에서 Flyway clean-start, 제약 위반, 트랜잭션 롤백, 동시 조건부 갱신, 중복 멱등 키를 검증한다.
- 개발 머신에 설치된 임의 MySQL 인스턴스나 공유 DB 상태에 의존하지 않고 테스트마다 재현 가능한 스키마와 데이터를 만든다.
- 컨테이너 시작 시간과 Docker 실행 환경이라는 비용이 추가된다. 빠른 단위 테스트와 MySQL 통합 테스트를 분리하되, DB 의미에 의존하는 변경은 통합 게이트를 생략할 수 없다.

## 2026-08-05 날짜별 개정

### CI 테스트 분리와 병렬 실행

- Testcontainers MySQL 실행 시간이 CI 대부분을 차지한다는 측정 결과에 따라 JUnit 5의 `@Tag("integration")`으로 빠른 테스트와 통합 테스트를 분리한다.
- `@SpringBootTest`, `@Testcontainers` 또는 `MySQLContainer`를 사용하는 테스트 클래스는 `@Tag("integration")`을 선언한다. Gradle 검증 task가 이 marker를 사용하는 클래스의 태그 누락을 실패시킨다.
- GitHub Actions는 `unit-test`, `integration-test-a`, `integration-test-b`를 병렬 실행한다. `dev` 브랜치 보호와 호환되는 `backend-ci` 집계 job은 세 job과 CD workflow 계약 검증이 모두 성공할 때만 성공한다.
- `build`는 두 테스트 task를 모두 포함하므로 로컬 전체 검증과 CI의 병합 gate 의미를 유지한다. 병렬화는 테스트를 생략하는 변경이 아니라 wall-clock 시간을 줄이는 변경이다.

### 통합 테스트 shard 분할

- `integration-test`는 `integration-test-a`, `integration-test-b` 두 matrix job으로 다시 분할해 병렬 실행한다. 모든 통합 테스트 클래스는 `integration-shard-a` 또는 `integration-shard-b` 중 정확히 하나를 추가로 선언한다.
- Gradle 검증 task는 통합 marker와 shard tag의 누락 또는 중복을 실패시킨다. 두 shard가 모두 성공해야 `backend-ci` 집계 job이 성공하므로 기존 Required check 이름과 전체 테스트 게이트는 유지한다.

## 2026-08-13 날짜별 개정

### 통합 테스트 4-shard 확장

- #288 2단계에서 `integration-test`를 `integration-test-a`부터 `integration-test-d`까지 네 matrix job으로 확장해 병렬 실행한다. 모든 통합 테스트 클래스는 `integration-shard-a`~`integration-shard-d` 중 정확히 하나를 선언한다.
- Gradle 검증 task는 통합 marker와 shard tag의 누락 또는 중복을 실패시킨다. 네 shard가 모두 성공해야 `backend-ci` 집계 job이 성공하므로 기존 Required check 이름과 전체 테스트 게이트는 유지한다. Required check는 집계 job 이름 하나이므로 shard 수를 바꿔도 브랜치 보호 설정을 변경하지 않는다.
- shard 배치는 도메인 단위가 아니라 최근 실행 시간과 테스트 구성 정보를 함께 보아 균형 있게 정한다. `@SpringBootTest` properties·`@AutoConfigureMockMvc`·`@Testcontainers` 조합은 후보를 찾는 힌트일 뿐, Spring ApplicationContext 캐시 키에는 `@DynamicPropertySource`, `@MockitoBean` 등의 context customizer도 포함된다. 따라서 정적 시그니처가 같다는 이유만으로 캐시 공유나 기동 횟수를 단정하지 않으며, 컨텍스트 재사용을 최적화 근거로 삼을 때는 cache debug log 또는 동등한 실행 증거를 남긴다.

## 2026-08-14 날짜별 개정

### 로컬 Backend 전체 검증 병렬 runner 설계

Issue [#330](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/330)은 CI의 unit job과 integration shard A~D 병렬 구조를 Windows 로컬에서도 안전하게 재현하는 선택적 PowerShell 7 runner를 도입한다. 이 runner는 기존 `root.verify.backend`의 순서와 첫 실패 중단 계약을 대체하지 않는다. 현재 `backend.build`가 소유한 compile·unit·integration·assemble 범위를 빠르게 확인하는 로컬 실행 표면이며, 최종 gate와 각 Pull Request의 CI 증거는 기존 정본을 따른다.

적용 단계는 `고도화`다.

설계는 승인됐지만 runtime 상태는 아직 `NOT CONFIGURED`다. #330은 다른 JVM 안정화 Issue에 의존하지 않으며 runner-local 실행 profile만 소유한다. 전용 init script를 전달한 child Gradle invocation에 한해 Test JVM heap 1GB와 `maxParallelForks = 1`을 적용하고, 저장소의 `backend/build.gradle.kts`, 일반 `backend.build` 실행과 기존 CI의 JVM 정책은 변경하지 않는다.

### 실행 구조와 명령

`backend/`에서 다음 명령을 사용한다.

```powershell
pwsh -NoProfile -File .\scripts\run-backend-full-verification.ps1
pwsh -NoProfile -File .\scripts\run-backend-full-verification.ps1 -ParallelShards 1
pwsh -NoProfile -File .\scripts\run-backend-full-verification.ps1 -ParallelShards 2
pwsh -NoProfile -File .\scripts\run-backend-full-verification.ps1 -ParallelShards 4
```

runner, init script와 contract test를 `backend/scripts/`에 두는 것은 의도된 소유권 경계다. 세 파일은 모두 backend Gradle 검증 전용이고 backend 명령 레지스트리도 `backend/`를 실행 기준으로 삼는다. 저장소 루트 `scripts/`는 배포·workflow 등 저장소 전체 계약을 소유하므로, backend 전용 실행 자산은 `backend/scripts/`에 함께 두어 명령과 구현의 소유자를 일치시킨다.

기본값은 `Auto`다. `ParallelShards`는 동시에 실행할 integration worker의 상한이며 전체 child 수가 아니다. unit+assemble child 하나는 integration queue와 별도로 실행하므로 `ParallelShards=4`의 최대 동시 child는 다섯 개다.

- unit child는 `test assemble`을 실행한다.
- integration queue는 `integrationTestShardA`부터 `integrationTestShardD`까지 실행한다.
- 모든 child는 `--no-daemon --rerun-tasks --console=plain`을 사용한다.
- runner는 Gradle daemon heap을 1GB로 제한하고 전용 init script를 모든 child invocation에 전달한다.
- init script는 `projectsEvaluated` 이후 `test`, `integrationTest`, `integrationTestShardA`~`integrationTestShardD`를 포함한 모든 `Test` task의 `maxHeapSize`를 `1g`, `maxParallelForks`를 `1`로 설정한다.
- 각 Test task의 `doFirst`에서 실행 직전 최종 유효값을 다시 검증해 로그에 출력하며, 값이 `1g/1`이 아니거나 확인할 수 없으면 해당 child 실행을 중단한다.
- init script를 사용하지 않는 일반 Gradle 실행은 저장소에 선언된 기존 Test JVM 설정을 그대로 사용한다.
- 한 child가 실패하거나 timeout돼도 아직 시작하지 않은 integration shard를 계속 실행해 전체 진단을 모은다.

`Auto`는 실행 시작 시 자원을 한 번만 읽고 실행 중 병렬도를 증감하지 않는다. Windows는 OS가 보고한 현재 가용 물리 메모리, GitHub Actions Ubuntu는 `/proc/meminfo`의 `MemAvailable`, CPU는 현재 프로세스에 제공되는 논리 processor 수를 사용한다.

| 조건 | 선택값 | 최대 동시 child |
|---|---:|---:|
| 가용 RAM 20GB 이상, logical CPU 8 이상 | 4 | 5 |
| 가용 RAM 12GB 이상, logical CPU 4 이상 | 2 | 3 |
| 그 외 또는 자원 탐지 실패 | 1 | 2 |

메모리 기준은 runner-local Test JVM 1GB/fork 1과 Gradle daemon 1GB profile에서 고정 여유 4GB와 integration worker당 4GB의 합이다. 고정 여유는 unit child, Gradle process와 OS·Docker 여유를 함께 다루며, 기존 5-child benchmark에서 관찰한 Java peak 약 7.1GB와 Docker peak 미측정 위험을 반영한 잠정 sizing input이다. 명시적 1·2·4 override는 기준 미달 경고를 출력하되 사용자가 선택한 값을 적용한다.

`Auto=1`은 안전성 보장이 아니라 최소 병렬 fallback이다. 가용 RAM이 8GB 미만이거나 자원 탐지에 실패해도 1을 선택하되, 측정값 또는 탐지 실패와 호스트 압박 위험을 경고한 뒤 실행한다.

### child 격리와 process 수명 주기

한 실행은 `backend/build/local-verification/<run-id>/` 아래에 child별 독립 경로를 만든다.

- `build/<child-id>`: Gradle build directory와 XML·HTML report
- `project-cache/<child-id>`: Gradle project cache
- `logs/<child-id>.stdout.log`, `logs/<child-id>.stderr.log`: 분리된 출력

Gradle user home의 dependency cache는 공유하지만 build directory와 project cache를 공유하지 않는다. init script는 child별 절대 build directory를 설정하며, runner는 `--project-cache-dir`에 child별 경로를 전달한다.

Ubuntu process 실행은 `System.Diagnostics.ProcessStartInfo`를 사용하고 Wrapper 실행 파일과 각 인자를 `ArgumentList`로 전달한다. Windows는 `ProcessStartInfo`를 사용하지 않고 `CreateProcessW`와 `CREATE_SUSPENDED`를 사용하는 native launcher가 process 생성을 전담한다. native launcher는 stdin·stdout·stderr용 anonymous pipe를 만들고 child-side stdin read handle과 stdout·stderr write handle만 상속 가능하게 생성한다. parent-side stdin write handle과 stdout·stderr read handle은 비상속으로 설정한다. `STARTUPINFOEX.StartupInfo.dwFlags`에 `STARTF_USESTDHANDLES`를 지정하고 `hStdInput`, `hStdOutput`, `hStdError`에 각각 유효한 child-side stdin read, stdout write, stderr write handle을 설정한다. `PROC_THREAD_ATTRIBUTE_HANDLE_LIST`에는 이 세 child-side standard handle만 정확히 포함하고, `CreateProcessW`는 `bInheritHandles=TRUE`와 `EXTENDED_STARTUPINFO_PRESENT`를 함께 사용한다. `CreateProcessW` 성공 직후 parent가 보유한 세 child-side handle 복사본을 닫는다. 일반 runner는 parent-side stdin write handle도 즉시 닫아 child에 EOF를 제공하고, stdout·stderr parent read handle의 비동기 pump를 시작한 뒤 Job 귀속과 resume을 수행한다. contract fixture는 nonce를 parent-side stdin write handle에 기록한 뒤 닫고 child가 읽은 동일 Unicode 값을 stdout으로 반환하는지 확인해 stdin→child→stdout 연결을 검증한다. `ResumeThread` 성공 직후 thread handle을 닫고, process handle은 process 종료 대기와 `GetExitCodeProcess`가 끝날 때까지, parent read handle은 각 pump가 EOF를 받고 완료될 때까지, Job handle은 `ActiveProcesses == 0` 확인이 끝날 때까지 유지한 뒤 닫는다. 생성·귀속·resume·pump 중간 실패를 포함한 모든 경로가 동일한 소유권 순서로 handle을 회수하며, parent의 child-side write handle 또는 stdin write handle 잔존으로 EOF를 막아서는 안 된다. `cmd.exe /d /s /c` 뒤 단일 mutable command-line buffer를 구성하는 adapter는 공백·한글·`&`, `(`, `)`, `^`, `%`, `!`와 따옴표(`"`)가 포함된 경로·인자를 보존해야 한다. contract fixture는 따옴표 단독 입력과 따옴표가 공백·한글·각 meta character와 결합된 입력을 각각 child에 전달하고, child가 실제 수신한 argv가 원본과 byte-for-byte가 아니라 Unicode 문자열 값 기준으로 정확히 일치하는지 검증한다.

stdout과 stderr는 child 시작 직후 서로 독립적인 비동기 pump로 child별 파일에 drain한다. runner는 process 종료와 두 pump 완료를 모두 기다린 뒤 실제 `ExitCode`와 로그를 판정한다. fixture는 pipe buffer보다 큰 stdout·stderr를 동시에 생성해 교착과 종료 직전 로그 유실이 없음을 검증한다.

각 child timeout은 35분이다. Windows native launcher는 `CreateProcessW(..., CREATE_SUSPENDED, ...)`로 child를 생성하고 `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`가 설정된 Job Object에 `AssignProcessToJobObject`가 성공한 뒤에만 main thread를 `ResumeThread`한다. **Job 귀속 전 resume은 금지한다.** `CreateProcessW` 전에 pipe 또는 launcher 준비가 실패하면 생성한 handle만 회수한다. `CreateProcessW` 성공 뒤 `AssignProcessToJobObject`가 실패하거나 Job 귀속 전에 다른 오류가 발생하면 suspended process를 절대 resume하지 않고 `TerminateProcess`를 호출한다. 이어서 process handle로 종료를 기다리고 실제 종료를 확인한 뒤 thread·process·pipe handle을 닫는다. handle close만으로 suspended process 종료를 대신해서는 안 된다. Job 귀속 뒤 `ResumeThread` 또는 이후 시작 단계가 실패하면 `TerminateJobObject`를 호출하고 `QueryInformationJobObject`의 `ActiveProcesses == 0`을 확인한다. 일반 timeout·Ctrl-C·예외도 Job handle을 유지한 상태에서 `TerminateJobObject`를 호출하고 `ActiveProcesses == 0`이 될 때까지 제한 시간 안에서 확인한 뒤 handle을 닫는다. `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`는 handle 누수나 비정상 종료에 대비한 fail-safe이며 정상 cleanup 또는 orphan 0건 증거를 대신하지 않는다. Ubuntu child는 `setsid`로 새 process group/session에서 시작하고, timeout·Ctrl-C·예외 시 group 전체에 TERM을 보낸 뒤 유예 시간을 거쳐 KILL을 적용한다. PID와 process 시작 시각을 함께 기록하고 종료 뒤 job/group의 생존 process가 0건인지 확인한다. `Process.Kill(entireProcessTree)`는 정상 containment가 아니라 fallback으로만 사용한다. descendant가 남거나 정리 결과를 확인할 수 없으면 runner 전체를 실패로 기록하며, 이미 생성된 로그와 report는 삭제하지 않는다.

### 결과 판정과 보고서

unit 및 각 shard는 다음 조건을 모두 만족해야 성공이다.

1. child process exit code가 `0`이다.
2. 예상 test result directory에 XML이 하나 이상 존재한다.
3. XML 전체의 `tests` 합이 0보다 크다.
4. `failures`, `errors`, `skipped` 합이 모두 0이다.

exit code 또는 XML 중 하나라도 조건을 만족하지 않으면 해당 child는 실패다. malformed XML, 필수 attribute 누락과 빈 report도 실패다. runner는 모든 child가 끝난 뒤 선택값과 근거, child별 task·PID·시작/종료·elapsed·exit code·test 합계·log/report 경로를 출력하며 하나라도 실패하면 non-zero로 종료한다.

### 플랫폼과 CI 경계

첫 지원 범위는 Windows PowerShell 7과 GitHub Actions `ubuntu-24.04`의 `pwsh`다. Ubuntu Backend CI unit job은 실제 Gradle 전체 runner를 다시 실행하지 않고 플랫폼 공통·Ubuntu PowerShell contract fixture만 실행한다. Windows native launcher·contract test 구현과 같은 commit 또는 Pull Request 범위에서 `.github/workflows/backend-ci.yml`에 Gradle과 Testcontainers를 실행하지 않는 별도 Windows PowerShell contract job을 추가한다. 이 job은 runner의 실제 Windows native launcher를 사용해 suspend→Job 귀속→resume 순서, timeout·cleanup 뒤 orphan 0건, argv와 stdin·stdout·stderr 계약을 검증한다. 같은 변경에서 이름이 `backend-ci`인 기존 집계 job에 Windows contract job의 성공을 필수 `needs`로 연결한다. A~D integration matrix와 required check 이름 `backend-ci`는 유지한다.

Windows contract job을 모든 backend Pull Request의 `backend-ci` 필수 의존성으로 두면 backend 코드와 직접 관련 없는 변경도 Windows runner 가용성이나 이 계약의 실패로 차단될 수 있고, Linux·macOS 개발자는 실패를 로컬에서 그대로 재현하기 어렵다. 이 비용은 지원 대상으로 선언한 저장소 소유 Windows 검증 표면이 조용히 깨지는 것을 막기 위해 수용한다. job은 Gradle과 Testcontainers를 실행하지 않는 contract-only 검증으로 비용을 제한하고, GitHub Actions의 로그·artifact·rerun을 공통 진단 경로로 사용한다. path filter나 파일 존재 여부 skip은 집계 job의 `needs` 의미를 불명확하게 하거나 검증하지 않은 상태를 성공처럼 보이게 할 수 있으므로 적용하지 않는다.

설계 문서만 수정하는 현재 변경에서는 아직 존재하지 않는 runner·native launcher·contract test보다 workflow gate를 먼저 활성화하지 않는다. 구현 전 `.github/workflows/backend-ci.yml`은 변경하지 않으며, 파일 존재 여부 조건이나 `continue-on-error`로 Windows contract job을 skip·완화하는 임시 우회도 금지한다. 다음 계약은 구현과 CI 활성화를 같은 commit 또는 Pull Request 범위에서 원자적으로 적용할 때 검증한다.

- Auto 경계, 8GB 미만·탐지 실패의 최소 fallback 경고와 명시적 override
- integration 동시 실행 상한 1·2·4와 별도 unit child
- child별 build/cache/log/report 격리
- stdout·stderr 독립 비동기 drain, process와 두 stream 완료 뒤 실제 exit code 수집과 최종 non-zero 전파
- XML 누락·malformed·tests=0·failure/error/skipped 거부
- 한 child 실패와 timeout 뒤에도 남은 queue 실행
- Windows `CreateProcessW`의 `CREATE_SUSPENDED` 생성, Job 귀속 전 resume 금지, 귀속 후 resume 순서와 Ubuntu process group 시작
- Windows fixture가 `AssignProcessToJobObject` 실패를 주입해 resume 0회, `TerminateProcess` 호출, process 종료 확인 뒤 handle 회수와 orphan 0건을 검증하는 계약
- Windows `TerminateJobObject` 뒤 `ActiveProcesses == 0` 확인, Ubuntu group cleanup을 통한 Ctrl-C·timeout descendant 종료와 orphan 0건
- Windows native launcher가 세 child-side standard handle만 inheritable로 만들고 `STARTF_USESTDHANDLES`, 유효한 `hStdInput`·`hStdOutput`·`hStdError`, 정확히 세 handle의 `PROC_THREAD_ATTRIBUTE_HANDLE_LIST`, `bInheritHandles=TRUE`를 함께 적용하는 계약
- 일반 runner가 parent-side stdin write handle을 즉시 닫아 EOF를 제공하고, fixture가 stdin nonce를 child에 전달해 동일 값의 stdout 반환을 확인하는 stdin→child→stdout 연결 계약
- `CreateProcessW` 직후 parent의 세 child-side handle 복사본을 닫아 stdout·stderr write 복사본이 pump EOF를 막지 않게 하고, parent-side stdin write handle은 일반 실행의 즉시 EOF 또는 fixture nonce 기록 뒤 닫는 계약. thread→process→read→Job handle은 각 완료 조건까지 유지·회수한다.
- parent write handle 잔존 없이 stdout·stderr를 독립 drain해 pump가 EOF까지 완료되고 handle 누수가 없는 계약
- 공백·한글·`&`, `(`, `)`, `^`, `%`, `!`, 따옴표(`"`)가 포함된 경로·인자의 argv round-trip 보존. 따옴표 단독과 따옴표+각 입력 조합 모두 child가 실제 받은 Unicode argv와 원본의 정확한 일치를 확인한다.
- Windows Wrapper와 Ubuntu Wrapper 선택
- `projectsEvaluated` 이후 설정과 각 Test `doFirst`의 runner-local 1GB/fork 1 최종 assertion
- init script 없는 일반 Gradle configuration에서 저장소의 기존 Test JVM 설정이 유지되는 경계

Windows와 Ubuntu contract test의 실제 성공 증거가 생기기 전에는 해당 플랫폼 지원을 `CONFIGURED`라고 부르지 않는다. macOS는 이번 범위에 포함하지 않는다.

### 성능 목표와 검증 범위

2026-08-14의 외부 실험에서는 Test JVM 1GB, fork 1, Gradle daemon 1GB로 unit+assemble과 A~D 다섯 child를 실행해 23분 0.828초 wall clock, unit 2,093개와 integration 544개, failure/error/skipped 0을 관찰했다. Java peak working set은 약 7.1GB였고 Docker peak는 측정하지 못했다. 이 결과는 25분 목표와 초기 Auto 기준을 정하기 위한 잠정 sizing input일 뿐 인수 또는 완료 증거가 아니다. 실험 wrapper가 `Start-Process.ExitCode`를 안정적으로 수집하지 못했고 이후 테스트 소스도 변경됐다.

구현 후 최신 `dev`를 반영한 동일 commit을 현재 34GB Windows 기준 머신에서 정식 runner로 한 번 실행해 child별 ExitCode, unit+assemble+A~D XML 무결성과 25분 이하 wall clock을 확인한다. 이 실행만 25분 목표의 완료 증거로 인정하며, 해당 머신과 실행의 관찰 결과일 뿐 모든 PC에 대한 보장이 아니다. 같은 코드·설정으로 기존 43~46분 전체 build를 설계 단계에서 반복하지 않는다. 로컬 runner 성공은 해당 commit의 Backend CI 성공을 대신하지 않는다.

### shard 재배치 경계

현재 class-duration XML은 보존되지 않았고 benchmark 이후 통합 테스트 소스도 변경됐으므로 #330은 shard 태그를 수정하지 않는다. runner 완료 후 비교 가능한 최신 실행에서 같은 shard의 불균형이 최소 두 번 반복될 때만 별도 Issue를 연다.

재배치 Issue는 class-duration 중앙값과 실제 shard 총 시간을 함께 사용하고, 이동할 정확한 테스트 파일 allowlist를 변경 전에 확정한다. 클래스 수만으로 이동하지 않는다. context cache 재사용은 cache debug log 또는 동등한 실행 증거가 없으면 근거로 사용하지 않으며, 태그 이동 뒤 전체 runner로 실제 균형과 전체 통과를 다시 검증한다.

### 검토한 대안과 결과

- 단일 Gradle invocation의 `--parallel` 또는 재귀 custom task는 실제 Test JVM 병렬 실행과 child별 report·cleanup 격리가 입증되지 않아 선택하지 않는다.
- CI workflow만 유지하고 로컬 명령을 추가하지 않는 안은 43~46분 로컬 피드백 문제를 해결하지 않아 선택하지 않는다.
- runner 전용 init script가 Test heap/fork를 child invocation에만 적용하는 안을 선택한다. 저장소의 일반 Test JVM 정책을 변경하지 않으면서 기존 병렬 benchmark 조건을 재현한다.
- `backend/build.gradle.kts`에서 Test heap/fork를 바꾸는 안은 일반 로컬 실행과 CI까지 영향을 넓히므로 선택하지 않는다.
- 현재 저장소의 2GB/default fork를 runner가 그대로 소비하는 안은 기존 1GB/fork 1 benchmark와 Auto 기준을 재사용할 수 없어 선택하지 않는다.
- 실패 즉시 다른 child를 종료하는 안은 전체 진단을 잃고 CI의 `fail-fast=false` 정책과 달라 선택하지 않는다.

runner가 contract fixture, Windows 전체 실행과 Ubuntu CI 증거를 모두 통과한 뒤에만 backend 명령 레지스트리, `backend/ai/implementation-guardrails.md`와 verification 상태에서 선택적 Backend 로컬 full-verification runner를 `CONFIGURED`로 갱신한다. 범용 verification runner 전체 상태는 바꾸지 않는다. 그 전까지 계획, script 이름 또는 Issue만으로 활성 상태를 주장하지 않는다.
