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
     * 지금 이 계정이 지연 중인지 확인한다. 호출부는 지연 중이면 비밀번호를 검사하지 말고
     * 평소와 같은 자격 증명 오류로 응답해야 한다(실패 횟수·지연 상태·계정 존재 여부 비노출).
     */
    @Transactional(readOnly = true)
    public boolean isDelayed(TokenNamespace namespace, long accountId) {
        return loginFailureDelayRepository.find(namespace.value(), accountId)
                .orElseGet(LoginFailureDelay::none)
                .isDelayedAt(LocalDateTime.now(clock));
    }

    /**
     * 실패 한 번을 기록하고 필요하면 지연 단계를 올린다.
     *
     * <p>호출부는 이 직후 자격 증명 오류를 던지는데, 같은 트랜잭션이면 그 예외에 기록까지 함께
     * 롤백되어 실패가 영원히 누적되지 않는다. 그래서 {@code REQUIRES_NEW}로 별도 트랜잭션에서
     * 기록하고 즉시 커밋한다. 행을 잠그는 구간도 이 짧은 트랜잭션 안에서만 유지된다.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(TokenNamespace namespace, long accountId) {
        // 한 번 읽은 시각을 단계 계산과 감사 컬럼에 함께 넘겨, 같은 기록 안에서 시간 기준이 갈리지 않게 한다.
        LocalDateTime now = LocalDateTime.now(clock);
        LoginFailureDelay current = loginFailureDelayRepository.lock(namespace.value(), accountId, now);
        LoginFailureDelay updated = loginDelayPolicy.applyFailure(current, now);
        loginFailureDelayRepository.save(namespace.value(), accountId, updated, now);
    }

    /**
     * 로그인에 성공하면 실패 횟수와 지연 단계를 초기화한다.
     */
    @Transactional
    public void reset(TokenNamespace namespace, long accountId) {
        loginFailureDelayRepository.reset(namespace.value(), accountId);
    }
}
