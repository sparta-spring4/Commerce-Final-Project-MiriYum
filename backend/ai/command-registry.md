# Backend command registry

Contract status: ACTIVE

Only commands whose executable files exist and whose success output and exit code were observed may be `CONFIGURED`. Run every command from `backend/`.

| Command ID | State | Exact command | Required inputs | Expected output | Evidence | Failure meaning |
|---|---|---|---|---|---|---|
| `backend.wrapper.version` | `CONFIGURED` | `.\gradlew.bat --version` | Committed Wrapper scripts, Wrapper JAR, Wrapper properties, network or a verified local distribution | Gradle `9.6.1` and exit code `0` | Preserve command output, exit code, and Wrapper hashes with the current change evidence | Nonzero exit, wrong version, download failure, or checksum rejection means `FAIL` |
| `backend.test` | `CONFIGURED` | `.\gradlew.bat test` | Java 21 toolchain, resolved dependencies, application and test sources | Context test succeeds and the command exits `0` | Preserve Gradle test output, exit code, and test report with the current change evidence | Compilation, context startup, assertion, dependency, or toolchain failure means `FAIL` |
| `backend.build` | `CONFIGURED` | `.\gradlew.bat build` | The same inputs as `backend.test` | Compilation, tests, and packaging succeed with exit code `0` | Preserve Gradle build output, exit code, and produced reports with the current change evidence | Any compile, test, packaging, dependency, or toolchain failure means `FAIL` |

The three commands above were activated only after their files existed and each command returned exit code `0` during scaffold verification.

DB integration, API smoke, Docker, CI, and deployment commands have no stable command ID because their execution surfaces are `NOT CONFIGURED`.
