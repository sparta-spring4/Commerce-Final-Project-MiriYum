# Production ECS 장애 대응 런북

## 현재 운영 기준

- ECS 서비스 `miriyum-prod-backend-service`의 desired count는 Terraform의
  `production_backend_desired_count` 입력이 소유한다. 이 런북에 고정 수를
  복제하지 않는다.
- ALB는 blue/green target group을 사용한다. active listener rule의 가중치
  전환은 ECS deployment controller가 소유하며 Terraform은 임시 가중치만
  무시한다.
- 운영 health endpoint는 `https://api.miriyum.click/actuator/health`이며 응답의
  `status`는 `UP`이어야 한다.
- delivery resource가 비용 절감 lifecycle로 중지돼 있으면 서비스가
  `INACTIVE`인 것은 장애가 아니다. 먼저
  [Production Terraform lifecycle runbook](production-terraform-lifecycle.md)을
  따라 복구한 뒤 이 런북을 사용한다.

이 문서는 운영 장애와 배포 실패를 안전하게 분류하고 복구하기 위한 실행 절차다. 민감값, 토큰, Secrets Manager 값, DB 비밀번호와 고객 식별자는 캡처·티켓·로그에 남기지 않는다.

## 배포 전 확인

1. AWS 콘솔에서 **ECS > 클러스터 > miriyum-prod-cluster > 서비스 > miriyum-prod-backend-service**를 연다.
2. 서비스 개요에서 `runningCount = desiredCount`, `pendingCount = 0`,
   `status = ACTIVE`인지 확인한다.
3. 현재 트래픽을 받는 target group의 모든 등록 대상이 `Healthy`인지 확인한다.
4. PowerShell에서 다음을 실행한다.

```powershell
curl.exe https://api.miriyum.click/actuator/health
```

5. `"status":"UP"` 응답을 배포 전 증적으로 기록한다. 실패 상태에서 새 배포를 시작하지 않는다.

## 배포 중 확인

1. ECS 서비스의 **배포** 탭에서 새 배포가 진행 중인지 확인한다.
2. Blue/Green 전환 중에는 test rule과 production rule의 target group 가중치가
   일시적으로 달라질 수 있다. 이 값만 보고 Terraform apply를 실행하지 않는다.
3. 새 task가 현재 production target group에서 `Healthy`가 되기 전에는 전환을
   성공으로 선언하지 않는다. `Draining` task는 전환 중에 보일 수 있다.
4. 배포가 완료되면 `runningCount = desiredCount`, active target group의
   `Healthy`, health endpoint `UP`을 함께 확인한다.

## 장애 분류

| 증상 | 먼저 확인할 위치 | 즉시 조치 |
| --- | --- | --- |
| 새 task가 시작되지 않음 | ECS 서비스 > 이벤트, 중지된 task > 중지 사유 | 이미지 태그·ECR 존재 여부·task definition을 확인하고 배포를 진행하지 않는다. |
| task는 실행되지만 대상이 Unhealthy | 대상 그룹 > 대상, CloudWatch `/miriyum/production/backend` | `/actuator/health`, 포트 8080, 보안 그룹, 애플리케이션 시작 오류를 확인한다. |
| active target group에 Healthy 대상이 없음 | ECS 서비스 개요, 대상 그룹 | 신규 전환을 중단하고 직전 정상 task definition으로 롤백을 준비한다. |
| API health가 UP이 아님 | `https://api.miriyum.click/actuator/health`, CloudWatch Logs | 신규 배포를 중단하고 최근 변경·의존성 오류를 분류한다. |
| RDS 또는 Valkey 연결 오류 | CloudWatch Logs, 각 서비스 상태 | 비밀값을 노출하지 않고 오류 코드·시각만 기록한다. |

## 롤백 절차

다음 중 하나면 롤백을 검토한다: 배포 회로 차단기 롤백, 새 task 반복 종료,
5분 이상 active target group의 healthy 대상을 회복하지 못함, health endpoint가
`UP`이 아님.

### 롤백 호환성 확인

직전 task definition이 이전에는 정상이어도, 현재 배포가 Flyway 스키마 변경이나 secret·환경 설정 변경을 이미 적용했을 수 있다. 이전 revision 선택 전 다음을 확인한다.

1. 이번 release의 Flyway 변경이 expand → migrate → contract 순서를 지켜 이전 애플리케이션과 현재 스키마가 하위 호환되는지 확인한다.
2. 이전 task definition과 이전 애플리케이션이 요구한 모든 Secrets Manager JSON key·환경 변수가 현재 실행 환경에 남아 있고 유효한지 확인한다. 제거·이름 변경된 key와 조건부 feature flag의 현재 값도 이전 revision의 기동 조건과 대조한다.
3. 해당 release에서 이전 revision으로의 rollback 검증 증거가 있으면 링크·실행 시각·결과만 확인한다. 비밀값과 고객 데이터는 기록하지 않는다.

셋 중 하나라도 확인할 수 없으면 task definition을 자동으로 되돌리지 않는다. 배포·DB 소유자가 현재 schema와 설정에서의 수동 복구 방법을 결정한 뒤에만 후속 조치를 수행한다.

### 호환성이 확인된 롤백 실행

1. **ECS > 클러스터 > miriyum-prod-cluster > 서비스 > miriyum-prod-backend-service**로 이동한다.
2. **배포** 탭에서 마지막으로 성공한 배포의 task definition revision을 확인한다.
3. 오른쪽 위 **서비스 업데이트**를 누른다.
4. **task definition**에서 호환성 확인을 통과한 직전 정상 revision을 선택한다.
5. desired count는 Terraform의 현재 승인값과 같게 유지한다.
6. blue/green production rule과 alternate target group 연결을 유지하는지 확인한다.
7. **업데이트**를 눌러 롤백 배포를 시작한다.
8. 현재 production target group의 `Healthy`와 health endpoint `UP`을 확인한 뒤에만 복구 완료로 기록한다.
