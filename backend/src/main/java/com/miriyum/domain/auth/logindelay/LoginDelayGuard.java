package com.miriyum.domain.auth.logindelay;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.function.BooleanSupplier;
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
     * 계정별 지연 판정 → 비밀번호 비교 → 실패 반영을 하나의 트랜잭션과 하나의 행 잠금 안에서
     * 수행한다. 비밀번호가 맞으면 {@code true}, 지연 중이거나 비밀번호가 틀리면 {@code false}다.
     *
     * <p>지연 판정을 잠금 밖에서 따로 읽으면, 동시 요청이 모두 "지연 아님"을 보고 통과한 뒤
     * 비밀번호 비교까지 마친다. 그러면 5번째 실패가 지연을 만든 뒤에도 이미 통과한 요청들이
     * 추측을 계속 수행해, 한 계정을 여러 IP에서 노리는 공격을 늦추려는 목적이 무력해진다.
     * 그래서 잠금을 먼저 잡고 그 안에서 판정하며, 지연 중이면 {@code passwordMatches}를 아예
     * 호출하지 않는다.</p>
     *
     * <p>실패 기록은 호출부가 자격 증명 오류를 던지기 전에 커밋돼야 한다. 이 메서드는 예외를
     * 던지지 않고 결과만 반환하므로 기록이 롤백되지 않으며, 별도 트랜잭션({@code REQUIRES_NEW})이라
     * 호출부가 나중에 예외를 던져도 영향을 받지 않는다.</p>
     *
     * <p>비용: 비밀번호 해시 비교가 행 잠금을 쥔 채 실행되므로 <b>같은 계정</b>의 동시 로그인
     * 시도는 차례로 처리된다. 다른 계정은 서로 다른 행이라 영향이 없고, 한 사용자가 동시에 여러 번
     * 로그인하는 경우는 드물다. 오히려 이 직렬화가 계정 단위 추측 속도 제한의 핵심이다.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean isPasswordAcceptedWithinDelay(
            TokenNamespace namespace,
            long accountId,
            BooleanSupplier passwordMatches
    ) {
        // 한 번 읽은 시각을 지연 판정·단계 계산·감사 컬럼에 함께 써, 같은 판정 안에서 기준이 갈리지 않게 한다.
        LocalDateTime now = LocalDateTime.now(clock);
        LoginFailureDelay current = loginFailureDelayRepository.lock(namespace.value(), accountId, now);

        if (current.isDelayedAt(now)) {
            return false;
        }
        if (passwordMatches.getAsBoolean()) {
            return true;
        }

        LoginFailureDelay updated = loginDelayPolicy.applyFailure(current, now);
        loginFailureDelayRepository.save(namespace.value(), accountId, updated, now);
        return false;
    }

    /**
     * 로그인에 성공하면 실패 횟수와 지연 단계를 초기화한다.
     */
    @Transactional
    public void reset(TokenNamespace namespace, long accountId) {
        loginFailureDelayRepository.reset(namespace.value(), accountId);
    }
}
