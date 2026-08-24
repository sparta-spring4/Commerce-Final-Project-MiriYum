# Production ECS 장애 대응 런북

## 현재 확인된 운영 기준

- ECS 서비스 `miriyum-prod-backend-service`의 desired count는 `2`다.
- ECS 서비스의 **production traffic**으로 지정된 ALB 대상 그룹에는 정상(Healthy) 대상이 `2`개, 비정상(Unhealthy) 대상이 `0`개여야 한다. 대기 대상 그룹에 대상이 없는 것만으로는 장애로 판단하지 않는다.
- 롤링 배포는 minimum healthy percent `100`, maximum percent `200`을 유지한다.
- 운영 health endpoint는 `https://api.miriyum.click/actuator/health`이며 응답의 `status`는 `UP`이어야 한다.

### 2026-08-23 확인값

- `miriyum-prod-backend-green-tg`가 production traffic을 수신했고, 대상 상태는 `Healthy 2`, `Unhealthy 0`이었다.
- `miriyum-prod-backend-tg`는 대기 대상 그룹으로 대상이 없었다. 다음 배포에서 production traffic 대상 그룹이 바뀔 수 있으므로 이 이름을 고정된 정상 판정 기준으로 사용하지 않는다.

이 문서는 운영 장애와 배포 실패를 안전하게 분류하고 복구하기 위한 실행 절차다. 민감값, 토큰, Secrets Manager 값, DB 비밀번호와 고객 식별자는 캡처·티켓·로그에 남기지 않는다.

## 배포 전 확인

1. AWS 콘솔에서 **ECS > 클러스터 > miriyum-prod-cluster > 서비스 > miriyum-prod-backend-service**를 연다.
2. 서비스 개요에서 `2 실행 중`, `0 보류 중`인지 확인한다.
3. **로드 밸런서 대상 상태**에서 production traffic으로 표시된 대상 그룹을 확인하고, 해당 그룹이 `Healthy 2`, `Unhealthy 0`인지 확인한다. 대기 대상 그룹의 대상 수는 이 정상 판정을 대신하지 않는다.
4. PowerShell에서 다음을 실행한다.

```powershell
curl.exe https://api.miriyum.click/actuator/health
```

5. `"status":"UP"` 응답을 배포 전 증적으로 기록한다. 실패 상태에서 새 배포를 시작하지 않는다.

## 배포 중 확인

1. ECS 서비스의 **배포** 탭에서 새 배포가 진행 중인지 확인한다.
2. 롤링 업데이트 중에는 기존 정상 task를 유지하면서 새 task가 추가될 수 있다. 이때 대상 그룹에는 일시적으로 `Draining` 대상이 보일 수 있다.
3. 새 task가 `Healthy`가 되기 전에는 기존 task가 모두 사라지면 안 된다. `minimum healthy 100%`이므로 정상 대상은 최소 2개를 유지하는 것이 목표다.
4. 배포가 완료되면 production traffic 대상 그룹에서 다시 `Healthy 2`, `Unhealthy 0`과 health endpoint `UP`을 확인한다.

## 장애 분류

| 증상 | 먼저 확인할 위치 | 즉시 조치 |
| --- | --- | --- |
| 새 task가 시작되지 않음 | ECS 서비스 > 이벤트, 중지된 task > 중지 사유 | 이미지 태그·ECR 존재 여부·task definition을 확인하고 배포를 진행하지 않는다. |
| task는 실행되지만 production traffic 대상이 Unhealthy | ECS 서비스 > 로드 밸런서 대상 상태, CloudWatch `/miriyum/production/backend` | `/actuator/health`, 포트 8080, 보안 그룹, 애플리케이션 시작 오류를 확인한다. |
| production traffic 대상의 Healthy 수가 1개 이하 | ECS 서비스 개요, 로드 밸런서 대상 상태 | 신규 배포를 중단하고 직전 정상 task definition으로 롤백을 준비한다. |
| API health가 UP이 아님 | `https://api.miriyum.click/actuator/health`, CloudWatch Logs | 신규 배포를 중단하고 최근 변경·의존성 오류를 분류한다. |
| RDS 또는 Valkey 연결 오류 | CloudWatch Logs, 각 서비스 상태 | 비밀값을 노출하지 않고 오류 코드·시각만 기록한다. |

## 롤백 절차

다음 중 하나면 롤백을 검토한다: 배포 회로 차단기 롤백, 새 task 반복 종료, production traffic 대상 그룹이 5분 이상 `Healthy 2`를 회복하지 못함, health endpoint가 `UP`이 아님.

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
5. desired count는 `2`로 유지한다.
6. 배포 설정의 minimum healthy `100`, maximum `200`이 유지되는지 확인한다.
7. **업데이트**를 눌러 롤백 배포를 시작한다.
8. production traffic 대상 그룹에서 `Healthy 2`, `Unhealthy 0`과 health endpoint `UP`을 확인한 뒤에만 복구 완료로 기록한다.
