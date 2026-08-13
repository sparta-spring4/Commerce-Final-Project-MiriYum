# CloudWatch staging 최소 관측

## 목적

staging EC2 한 대의 상태와 배포 결과를 최소 비용으로 확인한다. 이번 범위는 CloudWatch Agent, CloudWatch Logs, 기본 지표·알람·Dashboard, SNS 이메일 한 개로 제한한다.

Prometheus·Grafana 컨테이너, X-Ray·OpenTelemetry, production ECS·RDS·ElastiCache 관측은 포함하지 않는다. 애플리케이션 지표가 더 필요해질 때 별도 이슈에서 검토한다.

## 수집 범위

- AWS 기본 지표: `CPUUtilization`, `StatusCheckFailed`
- CloudWatch Agent 지표: 루트 디스크 사용률, 메모리 사용률
- CloudWatch Logs: Docker `awslogs` 드라이버로 서비스별 표준 출력 로그
- 사용자 지정 지표: 배포 후 health 결과 `DeploymentHealth`, 위험 사건 전달 정체 `RefreshTokenRiskEventDeliveryStalled`
- 로그 보존: 7일

CloudWatch Agent 설정은 [`cloudwatch-agent-config.json`](../../deploy/monitoring/cloudwatch-agent-config.json)에 있다. Agent는 메모리·디스크 지표를 수집하고, Docker 로그는 Compose의 `awslogs` 드라이버가 `/miriyum/staging/docker` 로그 그룹의 `mysql`, `backend`, `nginx`, `valkey` 스트림으로 직접 전송한다. CD가 배포 파일을 SSM으로 전송할 때 Agent 설정도 `/opt/miriyum/monitoring/cloudwatch-agent.json`에 복사한다.

디스크 원본 지표에는 `path`·`device`·`fstype` 차원이 붙을 수 있다. 설정의 `aggregation_dimensions: [["InstanceId"]]`가 InstanceId-only 집계 시계열을 만들고, `drop_original_metrics`가 원본 차원별 `used_percent` 시계열을 제외한다. CloudWatch에 게시되는 이름은 `disk_used_percent`이므로 알람과 Dashboard도 이 이름을 사용한다. 따라서 알람·Dashboard가 사용하는 `InstanceId` 차원과 정확히 일치하면서 불필요한 custom metric 수도 줄인다.

## EC2 역할 권한

EC2의 `miriyum-ec2-role`에 AWS 관리형 정책 `CloudWatchAgentServerPolicy`를 추가한다. 이 권한은 Agent 지표와 Docker `awslogs` 드라이버가 CloudWatch Logs에 기록할 때 사용한다. GitHub Actions OIDC 역할이나 애플리케이션 `.env`에 CloudWatch 자격 증명을 추가하지 않는다.

기존 `AmazonSSMManagedInstanceCore`, `AmazonEC2ContainerRegistryReadOnly`는 그대로 유지한다.

## Agent 최초 설치

Amazon Linux 2023의 staging EC2에서 한 번만 실행한다.

```bash
sudo dnf install -y amazon-cloudwatch-agent
sudo install -d -m 0755 /opt/miriyum/monitoring
sudo test -s /opt/miriyum/monitoring/cloudwatch-agent.json
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
  -a fetch-config \
  -m ec2 \
  -c file:/opt/miriyum/monitoring/cloudwatch-agent.json \
  -s
sudo systemctl status amazon-cloudwatch-agent --no-pager
```

Agent가 설치된 뒤 CD가 실행되면 최신 설정 파일을 다시 전송하고 `fetch-config`로 Agent를 재로드한다. Agent가 아직 설치되지 않은 첫 배포에서는 설정 파일만 전송하므로, 최초 설치 절차를 한 번 완료해야 한다.

## 알람·Dashboard·이메일

AWS CLI 권한이 있는 CloudShell 또는 관리자 PC에서 실행한다. 이 스크립트는 7일 보존 로그 그룹·SNS 주제·이메일 구독·알람 6개·Dashboard를 생성하거나 갱신한다.

```bash
chmod +x deploy/monitoring/create-cloudwatch-resources.sh
AWS_REGION=ap-northeast-2 \
EC2_INSTANCE_ID=i-xxxxxxxxxxxxxxxxx \
ALARM_EMAIL=팀에서확인할이메일@example.com \
deploy/monitoring/create-cloudwatch-resources.sh
```

생성되는 기준은 다음과 같다.

| 이름 | 조건 |
|---|---|
| CPU | 5분 평균 80% 초과가 2회 |
| 메모리 | 5분 평균 85% 초과가 2회 |
| 디스크 | 5분 평균 85% 초과가 2회 |
| 인스턴스 상태 | EC2 status check 실패 1회 |
| 배포 health | 최근 배포 결과가 0 |
| Refresh Token 위험 사건 전달 | 30초 주기 전달이 10회 연속 실패해 정체 로그가 발생하면 경보 |

SNS 이메일은 명령 실행 후 확인 메일의 `Confirm subscription` 링크를 눌러야 실제 알림을 받는다. 이메일 주소와 AWS 계정 ID는 블로그 캡처에 노출하지 않는다.

## 배포 health 지표

`deploy.sh`는 `http://127.0.0.1:8080/actuator/health`와 Valkey의 `healthy`, 무인증 `NOAUTH`, 인증 `PONG`, host port 미공개를 모두 확인한다. 어느 하나라도 실패하면 `DeploymentHealth=0`을 기록하고 배포를 실패 처리한다.

위 확인 뒤 실행하는 pending marker Set 인덱스 backfill은 #304 전까지는 경고·Valkey 상태·로그만 남기고 배포를 계속한다. 현재 위험 사건 전달이 기존 marker `SCAN`을 사용하므로 backfill 실패가 전달을 막지 않기 때문이다. #304에서 전달이 Set-only 읽기로 바뀌면 backfill 실패를 `DeploymentHealth=0`과 배포 실패로 승격한다.

- health 성공: `DeploymentHealth=1`
- health timeout: `DeploymentHealth=0`

CloudWatch 전송 실패가 배포 자체를 실패시키지는 않는다. 배포 성공·실패의 최종 기준은 기존 SSM 결과와 loopback health check이며, CloudWatch는 이를 보조하는 관측 수단이다.

## 위험 사건 전달 정체 지표

Refresh Token 재사용 위험 사건은 Valkey pending marker에서 MySQL 중앙 위험 사건으로 전달된다. Valkey 조회 또는 MySQL 저장이 한두 번 실패하면 marker를 보존하고 다음 주기에 재시도한다. 30초 주기 전달이 기본 10회 연속 실패한 경우에만 backend가 다음 제한 로그를 남긴다.

```text
event=refresh_token_risk_event_delivery_stalled consecutive_failures=10 failure_stage=mysql_write
```

로그에는 계정 ID, family ID, token ID, token hash와 원문 토큰을 넣지 않는다. CloudWatch Logs metric filter는 이 이벤트 이름만 `RefreshTokenRiskEventDeliveryStalled=1`로 변환하고, 5분 합계가 0보다 크면 SNS 알람을 보낸다. 정상 전달이 한 번 완료되면 애플리케이션의 연속 실패 횟수는 0으로 초기화된다.

## 로그 보안

CloudWatch Logs에는 Docker `awslogs` 드라이버가 표준 출력을 전송하므로 애플리케이션 로그에 다음 값을 기록하지 않는다.

- `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`
- `MIRIYUM_JWT_SECRET`
- Access·Refresh Token 원문
- 전화번호·이메일 원문

CloudWatch Agent는 임의의 비밀값을 자동으로 마스킹해 주는 기능으로 간주하지 않는다. 따라서 원천 로그에서 비밀값을 출력하지 않는 것을 우선하고, 로그에는 오류 코드·요청 ID·파생 식별자만 남긴다.

## 비용·삭제

이번 구성은 CloudWatch Agent와 Docker `awslogs` 드라이버, 기본 알람만 사용한다. 로그 보존을 7일로 제한하고, 테스트 종료 후 Dashboard·알람·SNS 주제·로그 그룹을 확인해 삭제한다. NAT Gateway, ALB, Prometheus/Grafana, Managed Service for Apache Kafka는 추가하지 않는다.

## 검증 순서

1. EC2 역할에 `CloudWatchAgentServerPolicy`를 추가한다.
2. Agent를 설치하고 `systemctl status`가 `active (running)`인지 확인한다.
3. CloudWatch Logs에서 `/miriyum/staging/docker` 로그 그룹의 `backend`, `mysql`, `nginx`, `valkey` 스트림을 확인한다.
4. CloudWatch Metrics에서 `MiriYum/Staging`의 `mem_used_percent`, `disk_used_percent`를 `InstanceId` 차원으로 확인한다.
5. 알람 생성 스크립트를 실행하고 SNS 이메일을 승인한다.
6. 테스트 임계치를 임시로 낮춰 이메일 수신을 확인한 뒤 원래 임계치로 되돌린다.
7. 위험 사건 전달 실패 테스트에서 민감값 없는 정체 로그와 `RefreshTokenRiskEventDeliveryStalled` 알람 구성을 확인한다.
8. 테스트 후 생성한 AWS 리소스와 알람 상태를 정리한다.
