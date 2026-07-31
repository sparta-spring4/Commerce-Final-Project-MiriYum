-- 계정별 연속 로그인 실패 횟수와 지연 단계를 담는 중앙 저장소다(AUTH-006).
-- 일반 사용자·매장 운영자가 계정 테이블을 공유하지 않으므로 namespace를 키에 포함해 한 테이블로 관리한다.
-- 여러 인증 인스턴스가 동시에 갱신해도 5회 기준과 1분·5분·15분 단계가 우회되지 않아야 하므로,
-- 애플리케이션은 이 행을 SELECT ... FOR UPDATE로 잠근 뒤 갱신한다.
-- 번호는 다른 팀원이 먼저 쓰기로 한 앞 번호와 겹치지 않게 V12로 배정했다.
CREATE TABLE login_failure_delays (
    account_namespace VARCHAR(30) NOT NULL,
    account_id BIGINT NOT NULL,
    -- 마지막 지연이 끝난 뒤 누적된 연속 실패 횟수다. 로그인 성공 시 행을 지워 초기화한다.
    consecutive_failures INT NOT NULL,
    -- 0 = 지연 없음, 1 = 1분, 2 = 5분, 3 = 15분(이후 추가 실패에도 3을 유지해 15분을 반복한다).
    delay_stage INT NOT NULL,
    -- 이 시각 전까지는 비밀번호를 검사하지 않고 거절한다. 지연이 없으면 NULL이다.
    next_attempt_allowed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (account_namespace, account_id),
    CONSTRAINT ck_login_failure_delays_stage CHECK (delay_stage BETWEEN 0 AND 3),
    CONSTRAINT ck_login_failure_delays_failures CHECK (consecutive_failures >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
