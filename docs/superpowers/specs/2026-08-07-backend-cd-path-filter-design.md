# Backend CD 배포 입력 경로 필터 설계

## 목적

문서나 프론트엔드 전용 변경이 `dev`에 병합될 때 Backend CI는 기존 Required check로 계속 실행하되, Backend CD가 불필요하게 ARM64 이미지를 빌드·ECR에 push하고 staging EC2에 배포하지 않도록 한다.

## 현재 문제

`Backend CD (Staging)`는 `Backend CI`가 `dev` push에서 성공하면 workflow_run으로 실행된다. 현재는 변경 파일을 확인하지 않으므로 `docs/**`만 바뀐 커밋도 ECR 이미지 빌드와 SSM 배포 대상이 될 수 있다.

Backend CI에 `paths` 필터를 바로 추가하면 문서 전용 PR에서 Required check가 실행되지 않아 병합이 pending 상태로 남을 수 있다. 따라서 CI 트리거는 유지하고 CD의 자동 배포 단계에서만 경로를 판별한다.

## 결정

### 자동 배포 대상 경로

다음 경로 중 하나라도 현재 `dev` push에 포함되면 자동 Backend CD를 실행한다.

- `backend/**`
- `deploy/**`
- `.github/workflows/backend-cd.yml`

그 외 문서, 프론트엔드, 저장소 관리 파일만 변경된 경우에는 자동 CD를 skip한다. 워크플로 자체가 변경되면 다음 실행에서 새 배포 계약이 적용될 수 있으므로 Backend CD workflow 파일을 배포 입력으로 포함한다.

### 변경 파일 비교 기준

`workflow_run`의 `head_sha`와 첫 번째 parent를 비교한다. `dev`에 PR merge commit이 push되는 경우에도 첫 번째 parent와 비교하면 해당 merge로 들어온 PR의 변경 경로를 판별할 수 있다.

### 실행 경로

```text
Backend CI 성공
  -> Backend CD verify-source
  -> stale revision 확인
  -> 현재 커밋과 첫 번째 parent의 변경 파일 확인
  -> backend/deploy/CD workflow 변경 있음: deployable=true
  -> 문서·프론트 전용 변경: deployable=false, 성공적으로 skip
```

수동 `workflow_dispatch`는 기존 동작을 유지한다. 사용자가 이미 ECR에 존재하는 40자리 immutable Git SHA를 지정하면 변경 경로와 무관하게 배포할 수 있어야 한다.

### 유지해야 하는 보호 장치

- stale CI revision이면 배포하지 않는다.
- 동일 staging의 배포는 `concurrency`로 하나씩 처리한다.
- OIDC 권한·ECR immutable tag·SSM 배포·health 확인을 변경하지 않는다.
- 문서 전용 자동 CD skip에는 AWS 인증, ECR push, SSM 명령을 수행하지 않는다.

## 구현 구조

- 변경 파일 목록에서 배포 대상 여부를 판별하는 작은 Python 스크립트를 둔다.
- `verify-source` job은 workflow_run에서 성공한 revision을 checkout하고, 첫 번째 parent와의 변경 파일을 스크립트에 전달한다.
- 수동 실행은 checkout이나 경로 판별 없이 `deployable=true`로 둔다.
- 기존 `verify-backend-cd-workflow.ps1`에 immutable image와 OIDC 보호 장치뿐 아니라 경로 판별 계약도 검증한다.
- Python 스크립트는 backend, deploy, Backend CD workflow 변경과 docs/frontend 전용 변경을 각각 테스트한다.

## 검증 계획

1. 배포 대상 경로만 입력하면 `deployable=true`인지 확인한다.
2. 문서와 프론트 경로만 입력하면 `deployable=false`인지 확인한다.
3. 빈 변경 목록을 안전하게 skip하는지 확인한다.
4. Backend CD workflow 파일 변경은 배포 대상으로 판정하는지 확인한다.
5. 기존 workflow 정적 계약 검증이 통과하는지 확인한다.
6. YAML 변경에 대해 `git diff --check`와 가능한 범위의 workflow 계약 검증을 실행한다.

## 제외 범위

- Backend CI Required check 범위 변경
- Frontend CI 또는 Frontend CD 구현
- production Environment·ECS·ALB 변경
- 수동 workflow_dispatch의 입력 계약 변경
- staging IAM·ECR·EC2 인프라 변경

## 롤백

경로 판별 단계만 되돌리면 기존처럼 Backend CI 성공 후 자동 CD가 실행된다. ECR, SSM, EC2 구성은 변경하지 않으므로 인프라 롤백은 필요하지 않다.
