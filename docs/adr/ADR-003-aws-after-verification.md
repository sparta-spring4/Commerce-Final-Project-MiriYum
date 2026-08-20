# ADR-003: 테스트 게이트 이후 AWS·Terraform 구현

- 상태: Accepted
- 결정일: 2026-07-24

## 배경

클라우드 인프라를 먼저 구성하면 애플리케이션 경계, 데이터베이스 마이그레이션, 비밀 관리와 복구 절차가 검증되기 전에 운영 비용과 선택지가 고정될 수 있다.

## 결정

AWS 배포와 Terraform 구현은 정의된 테스트 게이트를 통과한 뒤의 단계로 둔다. 게이트가 충족되기 전에는 AWS 인프라나 `infra/terraform/` 구성을 만들지 않는다.

## 검토한 대안

- 애플리케이션 구현과 동시에 AWS·Terraform을 구성: 검증되지 않은 배포 대상과 운영 요구를 먼저 고정한다.
- 수동 클라우드 구성 후 나중에 코드화: 환경 차이와 재현성 문제를 만든다.

## 긍정적 결과

- 검증된 애플리케이션·마이그레이션·운영 요구를 바탕으로 인프라를 선택할 수 있다.
- 배포 자동화의 범위와 비용을 근거 기반으로 결정할 수 있다.

## 부정적 결과

- 초기 단계에는 클라우드 배포 환경이 제공되지 않는다.
- 테스트 게이트와 운영 준비를 먼저 갖춰야 한다.

## 재검토 조건

품질·운영 규칙의 AWS 단계 전환 게이트를 모두 충족하고, 배포 대상과 관리형 인프라 요구가 확정되면 AWS·Terraform 구현 ADR을 추가로 검토한다.

## 관련 문서

- [시스템 아키텍처](../06-system-architecture.md)
- [품질·운영·규칙](../09-quality-operations-and-rules.md)
- [ADR 템플릿](ADR-000-template.md)

## 2026-07-27 날짜별 개정

### 개정 결정

- 최초 결정과 본문은 AWS 배포를 검증 뒤로 미룬 판단 기록으로 보존한다.
- S3 객체 저장은 전체 AWS 배포나 Terraform 전환과 분리해 **고도화** 기능으로 둔다. 1차 MVP와 2차 MVP에는 파일 업로드 API, 객체 키, bucket 설정, S3 SDK 런타임 의존성을 두지 않는다.
- 고도화에서 사업자 증빙·매장 이미지를 활성화할 때만 제공자 중립 파일 포트 뒤에 S3 어댑터를 연결한다. DB에는 권한·소유자·목적·상태·객체 키·무결성 참조를 기록하고 객체 자체를 거래 원장으로 사용하지 않는다.

### 활성화 게이트와 이행

- 개인정보 최소 수집·암호화·짧은 수명 접근 경로·감사·보유/삭제 정책, 객체와 DB 상태의 멱등 대사, 실패 격리·재처리, 테스트 환경 분리를 검증해야 한다.
- 로컬 또는 테스트 파일 어댑터와 S3 계약 테스트를 같은 포트에 적용하고, 업로드 성공 후 DB 확정 실패와 DB 상태 생성 후 업로드 실패를 모두 복구한다.
- 객체 저장·삭제 같은 외부 네트워크 호출은 업무 DB 트랜잭션과 Store·Menu 잠금 밖에서 실행한다. `PENDING` 메타데이터는 공개 전에 격리하고, 확정 실패 뒤 보상 삭제가 실패하면 `DELETED`로 남겨 별도 reconciliation이 재시도한다. 클라이언트의 같은 멱등 키 재생은 보조 경로일 뿐 고아 객체 회수의 유일한 수단이 아니다.
- S3 runtime 활성화 전에는 reconciliation의 멱등 회수, `PutObject` 뒤 HEAD 검증 실패와 안전한 보상 삭제 불가가 겹쳐도 `PENDING`으로 남아 회수되는 회귀, 동시 교체 뒤 `CONFIRMED` 공개본 하나로의 수렴, 성공·재시도 가능 실패·장기 체류의 식별자 없는 관측, staging 업로드·교체·삭제·비인가 접근 smoke를 함께 검증한다.
- S3 도입은 외부 의존성과 객체/DB 이중 상태의 운영 비용을 늘린다. 전체 AWS 전환은 기존 재검토 조건을 별도로 충족해야 하며 S3 활성화만으로 ECS, RDS, ElastiCache, Terraform 채택을 승인하지 않는다.

## 2026-08-17 날짜별 개정

### 운영 전환 결정

- 품질·운영 gate와 비용 승인 Issue #318의 선행 조건을 확인한 뒤, staging과 분리된 운영 배포 경로로 ECS Fargate, ALB, RDS MySQL Single-AZ, ElastiCache Valkey Primary/Replica를 채택한다.
- ECS task와 관리형 데이터 저장소는 private subnet에 두고, ALB만 public ingress로 둔다. GitHub Actions는 OIDC 역할과 `production` Environment 승인 경계를 거쳐 `main`의 성공한 Backend CI SHA만 immutable ECR image로 ECS rolling deployment 한다.
- Terraform 전체 전환, Blue/Green 배포, RDS Multi-AZ는 이번 결정에 포함하지 않는다. 실제 트래픽·가용성 요구가 확인되면 별도 Issue와 ADR 개정으로 재검토한다.

### 검증과 롤백 경계

- 운영 전환 전후에는 RDS 연결·Flyway 실행, ALB `/actuator/health`의 `UP`, Target Group `Healthy`, `main` SHA 기준 ECS rolling deployment를 확인한다.
- `production` Environment 보호 규칙, `PRODUCTION_ECS_DEPLOYMENT_ENABLED=true`, ECR `IMMUTABLE` 정책이 모두 준비되기 전에는 운영 deploy job을 실행하지 않는다.
- 배포 이상 시 활성화 변수를 `false`로 내려 후속 배포를 차단하고, ECS service를 직전 정상 task definition으로 되돌린다. RDS·Valkey는 데이터 보존을 위해 배포 롤백만으로 삭제하지 않으며, 비용 종료·삭제는 Issue #318의 승인된 체크리스트로 분리한다.

### 관련 정본

- 운영 토폴로지와 CD source 검증의 상세는 [시스템 아키텍처](../06-system-architecture.md)를 따른다.
- 승인 gate·비용 태그·종료 체크리스트의 상세는 [품질·운영·규칙](../09-quality-operations-and-rules.md)을 따른다.

## 2026-08-20 날짜별 개정

### 운영 OFF/ON 경계

- 운영 비용 절감 절차는 ECS `miriyum-prod-cluster`의 `miriyum-prod-backend-service` desired count를 `2 ↔ 0`으로 전환하고 RDS `miriyum-prod-mysql`을 `available ↔ stopped`로 전환하는 AWS CLI 절차로 한정한다.
- 이 절차는 예상 AWS 계정 `579750808837`과 리전 `ap-northeast-2`를 먼저 확인한다. 계정·리전·정확한 ECS task family `miriyum-production-backend`가 일치하지 않으면 중단한다.
- VPC, subnet, route table, NAT Gateway, Elastic IP, security group, ALB, listener, target group, Route 53, RDS 데이터·백업, ElastiCache Valkey, ECR, Secrets Manager, S3는 조회 전용 영속 리소스다. OFF/ON 절차와 Terraform state에서 수정·삭제·재생성 대상으로 삼지 않는다.
- Terraform 전체 전환, import, apply, Blue/Green, ECS service 재생성은 계속 제외 범위다. 실제 OFF/ON은 비용·중단 영향이 있으므로 별도 운영 승인 뒤 수동 실행하고, 실행 전후 service 안정화와 RDS 상태를 기록한다.

### Auto Scaling과 OFF/ON 공존 경계

- ECS Auto Scaling은 기존 service의 desired count만 `2..3` 범위에서 조절하며, VPC·ALB·DNS·RDS·Valkey 같은 영속 리소스를 Terraform state에 편입하거나 변경하지 않는다.
- Auto Scaling과 파일 저장 runtime Terraform은 `production/runtime-resources.tfstate`를 사용한다. 기존 전체 인프라 state(`production/terraform.tfstate`)와 분리해, 좁은 runtime 구성으로 영속 인프라 삭제 계획이 생성되지 않게 한다.
- runtime Terraform 적용 전에는 `terraform plan`에서 기존 인프라의 `destroy = 0`을 확인한다. 콘솔에서 먼저 만든 Auto Scaling target·policy는 import block으로 runtime state에 편입한다.
- 운영 OFF는 Auto Scaling의 동적·예약 scaling을 먼저 중지하고 최소 용량을 `0`으로 내린 다음 desired count를 `0`으로 전환한다.
- 운영 ON은 RDS가 `available`이 된 뒤 Auto Scaling 최소 용량을 `2`로 복구하고 desired count `2`를 확인한 다음 동적·예약 scaling을 재개한다.
- `scale_in_cooldown=300`은 첫 축소 전 관찰 시간이 아니라 축소 완료 뒤 다음 축소를 막는 시간이다.
