# 품질 운영 및 규칙

## 변경 유형별 최소 검증

| 변경 유형 | 최소 검증 |
|---|---|
| 문서 전용 | 링크, 중복 소유권, 형식, diff 범위 검사 |
| 백엔드 도메인 로직 | 컴파일과 단위 테스트 |
| DB·API | 단위·통합·마이그레이션·실제 API smoke 검사 중 현재 구성된 항목 |
| 프론트엔드 | typecheck·lint·단위 또는 컴포넌트 테스트 중 현재 구성된 항목 |
| 핵심 사용자 흐름 | 해당 흐름이 실행 가능해진 뒤 E2E |

각 검증 기록에는 다음을 함께 남긴다.

- 실제로 실행 가능한 경우에만 정확한 명령을 적고, 결과 상태를 `PASS`, `FAIL`, `BLOCKED`, `NOT RUN`, `NOT CONFIGURED`, `NOT APPLICABLE` 중 하나로 기록한다.
- 결과의 이유 또는 증거 링크를 남긴다.
- 실행 파일이 없는 명령을 미리 문서화하지 않는다. mock이나 코드 검사만으로 실행 중인 API 성공을 주장하지 않는다.
- 문서 전용 변경에는 존재하지 않는 제품 테스트를 억지로 통과 표시하지 않는다. 예: `NOT RUN - 제품 실행 파일 없음`.

Docker Compose와 GitHub Actions는 선택된 후속 tooling이며, 이 단계에서는 미구성이다. GitHub Actions를 active CI라고 부르려면 깨끗한 checkout의 Pull Request에서 실제 workflow가 성공해야 한다. required check라고 부르려면 repository의 required-check settings도 별도로 확인해야 한다.

## 검증 단계

다음 순서를 고정한다.

```text
단위 테스트
-> MySQL 통합 테스트
-> 주요 API 흐름 테스트
-> 프론트엔드 연동 테스트
-> 예약·결제 핵심 동시성 테스트
-> Docker 이미지 실행 검증
-> AWS·Terraform 배포 계획 승인
```

## AWS 단계 전환 게이트

AWS 단계에 진입하려면 다음 조건을 모두 확인한다.

- 핵심 사용자 흐름 통과
- Flyway 마이그레이션 검증
- 환경변수·비밀정보 분리
- 상태 확인 엔드포인트
- Docker 실행 검증
- 배포 비용 상한 합의

위 조건이 충족되기 전에는 `infra/terraform/`을 만들지 않는다.
