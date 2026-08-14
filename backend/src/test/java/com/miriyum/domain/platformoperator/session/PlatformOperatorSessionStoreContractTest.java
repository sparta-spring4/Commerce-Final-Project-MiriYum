package com.miriyum.domain.platformoperator.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlatformOperatorSessionStoreContractTest {
    private final InMemoryStore store = new InMemoryStore();
    private final Instant now = Instant.parse("2026-08-13T00:00:00Z");

    @Test
    void replacementLeavesExactlyOneValidSession() {
        store.replaceActiveSession(state("s1", "r1", now.plusSeconds(1800)));
        store.replaceActiveSession(state("s2", "r2", now.plusSeconds(1800)));

        assertThat(store.validateAndTouch(proof("s1", "r1"), now, now.plusSeconds(1800)).status())
                .isEqualTo(PlatformOperatorSessionResult.Status.INVALID);
        assertThat(store.validateAndTouch(proof("s2", "r2"), now, now.plusSeconds(1800)).status())
                .isEqualTo(PlatformOperatorSessionResult.Status.VALID);
    }

    @Test
    void exactExpirationAndSecondRotationAreRejected() {
        store.replaceActiveSession(state("s1", "r1", now));
        assertThat(store.validateAndTouch(proof("s1", "r1"), now, now.plusSeconds(10)).status())
                .isEqualTo(PlatformOperatorSessionResult.Status.EXPIRED);

        store.replaceActiveSession(state("s2", "r2", now.plusSeconds(1800)));
        assertThat(store.rotate(proof("s2", "r2"), "next", "nextHash", now, now.plusSeconds(1800)).status())
                .isEqualTo(PlatformOperatorSessionResult.Status.ROTATED);
        assertThat(store.rotate(proof("s2", "r2"), "again", "againHash", now, now.plusSeconds(1800)).status())
                .isEqualTo(PlatformOperatorSessionResult.Status.REUSED);
        store.revoke(1L, "s2");
        store.revoke(1L, "s2");
    }

    private PlatformOperatorSessionState state(String sessionHash, String refreshHash, Instant idleExpiry) {
        return new PlatformOperatorSessionState(1L, sessionHash, refreshHash, refreshHash, now, now,
                idleExpiry, now.plusSeconds(28800), 1L, 1L, true);
    }

    private PlatformOperatorSessionProof proof(String sessionHash, String refreshHash) {
        return new PlatformOperatorSessionProof(1L, sessionHash, refreshHash, refreshHash, 1L, 1L);
    }

    private static final class InMemoryStore implements PlatformOperatorSessionStore {
        private final Map<Long, PlatformOperatorSessionState> states = new HashMap<>();

        public PlatformOperatorSessionResult replaceActiveSession(PlatformOperatorSessionState state) {
            states.put(state.accountId(), state);
            return new PlatformOperatorSessionResult(PlatformOperatorSessionResult.Status.CREATED, state);
        }

        public PlatformOperatorSessionResult validateAndTouch(
                PlatformOperatorSessionProof proof, Instant now, Instant nextIdle) {
            PlatformOperatorSessionState state = states.get(proof.accountId());
            if (!matches(state, proof, false)) return PlatformOperatorSessionResult.of(PlatformOperatorSessionResult.Status.INVALID);
            if (!now.isBefore(state.idleExpiresAt()) || !now.isBefore(state.absoluteExpiresAt())) {
                states.remove(proof.accountId());
                return PlatformOperatorSessionResult.of(PlatformOperatorSessionResult.Status.EXPIRED);
            }
            PlatformOperatorSessionState touched = state.touch(now, min(nextIdle, state.absoluteExpiresAt()));
            states.put(proof.accountId(), touched);
            return new PlatformOperatorSessionResult(PlatformOperatorSessionResult.Status.VALID, touched);
        }

        public PlatformOperatorSessionResult rotate(
                PlatformOperatorSessionProof proof, String nextId, String nextHash, Instant now, Instant nextIdle) {
            PlatformOperatorSessionState state = states.get(proof.accountId());
            if (state != null && state.sessionHash().equals(proof.sessionHash())
                    && (!state.refreshTokenId().equals(proof.refreshTokenId())
                    || !state.refreshTokenHash().equals(proof.refreshTokenHash()))) {
                states.remove(proof.accountId());
                return PlatformOperatorSessionResult.of(PlatformOperatorSessionResult.Status.REUSED);
            }
            if (!matches(state, proof, true)) return PlatformOperatorSessionResult.of(PlatformOperatorSessionResult.Status.INVALID);
            PlatformOperatorSessionState rotated = state.rotate(nextId, nextHash, now, min(nextIdle, state.absoluteExpiresAt()));
            states.put(proof.accountId(), rotated);
            return new PlatformOperatorSessionResult(PlatformOperatorSessionResult.Status.ROTATED, rotated);
        }

        public void revoke(Long accountId, String sessionHash) {
            states.computeIfPresent(accountId, (id, state) -> state.sessionHash().equals(sessionHash) ? null : state);
        }

        public void revokeAll(Long accountId) { states.remove(accountId); }

        private static boolean matches(PlatformOperatorSessionState state, PlatformOperatorSessionProof proof, boolean refresh) {
            return state != null && state.sessionHash().equals(proof.sessionHash())
                    && state.authorityVersion() == proof.authorityVersion()
                    && state.sessionVersion() == proof.sessionVersion()
                    && (!refresh || (state.refreshTokenId().equals(proof.refreshTokenId())
                    && state.refreshTokenHash().equals(proof.refreshTokenHash())));
        }

        private static Instant min(Instant left, Instant right) { return left.isBefore(right) ? left : right; }
    }
}
