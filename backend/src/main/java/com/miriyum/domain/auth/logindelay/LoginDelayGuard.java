package com.miriyum.domain.auth.logindelay;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
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

    private static final long ACTIVE_ATTEMPT_LEASE_SECONDS = 30;

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
     * 이번 시도를 진행해도 되는지 판정하고, 진행을 허용하면 비밀번호 비교 슬롯을 잠시 점유한다.
     *
     * <p>같은 계정의 비밀번호 비교는 하나씩만 수행한다. 이전 구현처럼 "허용 요청을 먼저 실패로 적립"
     * 하면 올바른 비밀번호의 동시 요청도 실패 5회를 채워 지연을 만들어 버릴 수 있다. 그래서
     * <b>진행 중 시도</b>와 <b>확정 실패</b>를 분리해, 비교가 끝나기 전에는 실패 횟수에 반영하지 않는다.</p>
     *
     * <p>반환값이 {@link AttemptDecision#ACQUIRED}면 호출부가 비밀번호를 비교하고, 성공·실패·예외
     * 어느 쪽으로 끝났는지에 따라 {@link #reset}, {@link #recordFailure}, {@link #release} 중
     * 하나를 반드시 호출해야 한다. {@link AttemptDecision#BUSY}는 같은 계정의 비교가 이미 진행
     * 중이므로 잠시 뒤 다시 시도하라는 뜻이고, {@link AttemptDecision#DELAYED}는 정책상 지연
     * 중이라 비교 자체를 거절해야 한다는 뜻이다.</p>
     *
     * <p><b>왜 비교를 여기서 하지 않는가.</b> 비밀번호 해시 비교(BCrypt)를 이 트랜잭션 안에서
     * 실행하면 해시 계산이 끝날 때까지 DB 커넥션과 행 잠금을 함께 쥔다. 서로 다른 계정을 동시에
     * 시도하면 각 요청이 다른 행을 잠그고 나란히 해시 계산에 들어가므로, 커넥션 풀 전체를 점유해
     * 인증과 무관한 DB 요청까지 지연시킨다. 그래서 이 트랜잭션은 짧은 DB 연산만 하고 즉시 커밋하며,
     * 해시 비교는 호출부가 트랜잭션 밖에서 수행한다.</p>
     *
     * <p>진행 중 시도 표시에는 만료 시각을 둔다. 호출부가 죽어 {@link #release}가 오지 않아도
     * 영구 점유 상태에 빠지지 않게 하기 위해서다.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AttemptPermit tryAcquireAttempt(TokenNamespace namespace, long accountId) {
        LocalDateTime now = LocalDateTime.now(clock);
        LoginFailureDelay current = loginFailureDelayRepository.lock(namespace.value(), accountId, now);

        if (current.isDelayedAt(now)) {
            return AttemptPermit.delayed();
        }
        if (current.hasActiveAttemptAt(now)) {
            return AttemptPermit.busy();
        }

        String attemptToken = UUID.randomUUID().toString();
        LoginFailureDelay reserved = current.reserveAttempt(
                attemptToken, now.plusSeconds(ACTIVE_ATTEMPT_LEASE_SECONDS));
        loginFailureDelayRepository.save(namespace.value(), accountId, reserved, now);
        return AttemptPermit.acquired(attemptToken);
    }

    /**
     * 비밀번호가 틀렸을 때 확정 실패 한 건을 반영한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(TokenNamespace namespace, long accountId, String attemptToken) {
        LocalDateTime now = LocalDateTime.now(clock);
        LoginFailureDelay current = loginFailureDelayRepository.lock(namespace.value(), accountId, now);
        if (!current.isOwnedBy(attemptToken)) {
            return;
        }

        LoginFailureDelay updated = loginDelayPolicy.applyFailure(current.clearActiveAttempt(), now);
        loginFailureDelayRepository.save(namespace.value(), accountId, updated, now);
    }

    /**
     * 비밀번호가 맞았을 때 실패 횟수와 지연 단계를 초기화한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reset(TokenNamespace namespace, long accountId, String attemptToken) {
        LocalDateTime now = LocalDateTime.now(clock);
        LoginFailureDelay current = loginFailureDelayRepository.lock(namespace.value(), accountId, now);
        if (!current.isOwnedBy(attemptToken)) {
            return;
        }
        loginFailureDelayRepository.reset(namespace.value(), accountId);
    }

    /**
     * 비밀번호 비교가 예외로 끝났을 때 진행 중 표시만 제거한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(TokenNamespace namespace, long accountId, String attemptToken) {
        LocalDateTime now = LocalDateTime.now(clock);
        LoginFailureDelay current = loginFailureDelayRepository.lock(namespace.value(), accountId, now);
        if (!current.isOwnedBy(attemptToken)) {
            return;
        }
        LoginFailureDelay cleared = current.clearActiveAttempt();
        if (cleared.isEmpty()) {
            loginFailureDelayRepository.reset(namespace.value(), accountId);
            return;
        }
        loginFailureDelayRepository.save(namespace.value(), accountId, cleared, now);
    }

    public enum AttemptDecision {
        ACQUIRED,
        BUSY,
        DELAYED
    }

    public record AttemptPermit(AttemptDecision decision, String attemptToken) {

        public static AttemptPermit acquired(String attemptToken) {
            return new AttemptPermit(AttemptDecision.ACQUIRED, attemptToken);
        }

        public static AttemptPermit busy() {
            return new AttemptPermit(AttemptDecision.BUSY, null);
        }

        public static AttemptPermit delayed() {
            return new AttemptPermit(AttemptDecision.DELAYED, null);
        }
    }
}
