package com.miriyum.domain.auth.logindelay;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정 단위 로그인 지연(AUTH-006)을 로그인 흐름에 적용한다.
 *
 * <p>이 지연은 {@code SCALE-005}의 IP 기준 요청 제한과 별개로 중첩 적용된다. IP 제한은 한 출발지의
 * 요청량을, 이 지연은 한 계정을 겨냥한 추측 시도를 늦춘다.</p>
 */
@Component
public class LoginDelayGuard {

    private final LoginFailureDelayRepository loginFailureDelayRepository;
    private final LoginDelayPolicy loginDelayPolicy;
    private final Clock clock;

    public LoginDelayGuard(
            LoginFailureDelayRepository loginFailureDelayRepository,
            LoginDelayPolicy loginDelayPolicy,
            Clock clock
    ) {
        this.loginFailureDelayRepository = loginFailureDelayRepository;
        this.loginDelayPolicy = loginDelayPolicy;
        this.clock = clock;
    }

    /**
     * 이번 시도를 진행해도 되는지 판정하고, 진행을 허용하면 <b>실패를 미리 기록해 슬롯을 예약</b>한다.
     * 허용되면 {@code true}, 지연 중이면 {@code false}다.
     *
     * <p>호출부는 {@code true}일 때만 비밀번호를 비교하고, 비교가 성공하면 {@link #reset}으로
     * 예약한 실패를 되돌려야 한다. 비교가 실패하면 이미 기록돼 있으므로 추가 작업이 없다.</p>
     *
     * <p><b>왜 미리 기록하는가.</b> 지연 판정과 실패 기록을 비밀번호 비교 앞뒤로 나누면, 동시 요청이
     * 모두 "지연 아님"을 보고 통과한 뒤 비교까지 마친다. 5번째 실패가 지연을 만든 뒤에도 이미 통과한
     * 요청들이 추측을 계속 수행하므로, 한 계정을 여러 IP에서 노리는 공격을 늦추려는 목적이
     * 무력해진다. 판정과 기록을 이 짧은 트랜잭션 하나로 합쳐 행 잠금 안에서 처리하면, 뒤에 온 요청은
     * 앞선 요청이 만든 지연을 반드시 보게 되어 비교 단계로 들어가지 못한다.</p>
     *
     * <p><b>왜 비교를 여기서 하지 않는가.</b> 비밀번호 해시 비교(BCrypt)를 이 트랜잭션 안에서
     * 실행하면 해시 계산이 끝날 때까지 DB 커넥션과 행 잠금을 함께 쥔다. 서로 다른 계정을 동시에
     * 시도하면 각 요청이 다른 행을 잠그고 나란히 해시 계산에 들어가므로, 커넥션 풀 전체를 점유해
     * 인증과 무관한 DB 요청까지 지연시킨다. 그래서 이 트랜잭션은 짧은 DB 연산만 하고 즉시 커밋하며,
     * 해시 비교는 호출부가 트랜잭션 밖에서 수행한다.</p>
     *
     * <p>이 메서드는 예외를 던지지 않고 결과만 반환하므로 예약한 기록이 롤백되지 않는다. 별도
     * 트랜잭션({@code REQUIRES_NEW})이라 호출부가 나중에 자격 증명 오류를 던져도 영향이 없다.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryReserveAttempt(TokenNamespace namespace, long accountId) {
        // 한 번 읽은 시각을 지연 판정·단계 계산·감사 컬럼에 함께 써, 같은 판정 안에서 기준이 갈리지 않게 한다.
        LocalDateTime now = LocalDateTime.now(clock);
        LoginFailureDelay current = loginFailureDelayRepository.lock(namespace.value(), accountId, now);

        if (current.isDelayedAt(now)) {
            return false;
        }

        LoginFailureDelay reserved = loginDelayPolicy.applyFailure(current, now);
        loginFailureDelayRepository.save(namespace.value(), accountId, reserved, now);
        return true;
    }

    /**
     * 비밀번호가 맞았을 때 실패 횟수와 지연 단계를 초기화한다.
     * {@link #tryReserveAttempt}가 미리 기록해 둔 실패도 이 시점에 함께 사라진다.
     *
     * <p>호출부가 이 뒤에 계정 상태 등으로 예외를 던져도 초기화가 유지되도록 별도 트랜잭션에서
     * 커밋한다. 비밀번호가 맞았다면 무차별 대입 신호가 아니므로 실패를 남기지 않는다.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reset(TokenNamespace namespace, long accountId) {
        loginFailureDelayRepository.reset(namespace.value(), accountId);
    }
}
