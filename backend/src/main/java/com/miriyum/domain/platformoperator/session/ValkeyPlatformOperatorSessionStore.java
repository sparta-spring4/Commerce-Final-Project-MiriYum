package com.miriyum.domain.platformoperator.session;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class ValkeyPlatformOperatorSessionStore implements PlatformOperatorSessionStore {
    private static final String PREFIX = "miriyum:auth:platform-operator:";

    private static final RedisScript<Long> REPLACE = new DefaultRedisScript<>("""
            local previous = redis.call('GET', KEYS[1])
            if previous ~= false then redis.call('DEL', previous) end
            redis.call('HSET', KEYS[2],
                'accountId', ARGV[1], 'sessionHash', ARGV[2],
                'refreshTokenId', ARGV[3], 'refreshTokenHash', ARGV[4],
                'loginAt', ARGV[5], 'lastActivityAt', ARGV[6],
                'idleExpiresAt', ARGV[7], 'absoluteExpiresAt', ARGV[8],
                'authorityVersion', ARGV[9], 'sessionVersion', ARGV[10],
                'passwordChangeRequired', ARGV[11])
            redis.call('EXPIREAT', KEYS[2], ARGV[12])
            redis.call('SET', KEYS[1], KEYS[2], 'EXAT', ARGV[12])
            return 1
            """, Long.class);

    private static final RedisScript<Long> VALIDATE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= KEYS[2] or redis.call('EXISTS', KEYS[2]) == 0 then return 0 end
            if redis.call('HGET', KEYS[2], 'authorityVersion') ~= ARGV[1]
                    or redis.call('HGET', KEYS[2], 'sessionVersion') ~= ARGV[2] then return 0 end
            if tonumber(ARGV[3]) >= tonumber(redis.call('HGET', KEYS[2], 'idleExpiresAt'))
                    or tonumber(ARGV[3]) >= tonumber(redis.call('HGET', KEYS[2], 'absoluteExpiresAt')) then
                redis.call('DEL', KEYS[2]); redis.call('DEL', KEYS[1]); return 2
            end
            local nextExpiry = math.min(tonumber(ARGV[4]), tonumber(redis.call('HGET', KEYS[2], 'absoluteExpiresAt')))
            redis.call('HSET', KEYS[2], 'lastActivityAt', ARGV[3], 'idleExpiresAt', nextExpiry)
            redis.call('EXPIREAT', KEYS[2], nextExpiry); redis.call('EXPIREAT', KEYS[1], nextExpiry)
            return 1
            """, Long.class);

    private static final RedisScript<Long> ROTATE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= KEYS[2] or redis.call('EXISTS', KEYS[2]) == 0 then return 0 end
            if redis.call('HGET', KEYS[2], 'authorityVersion') ~= ARGV[1]
                    or redis.call('HGET', KEYS[2], 'sessionVersion') ~= ARGV[2] then return 0 end
            if redis.call('HGET', KEYS[2], 'refreshTokenId') ~= ARGV[3]
                    or redis.call('HGET', KEYS[2], 'refreshTokenHash') ~= ARGV[4] then
                redis.call('DEL', KEYS[2]); redis.call('DEL', KEYS[1]); return 3
            end
            if tonumber(ARGV[5]) >= tonumber(redis.call('HGET', KEYS[2], 'idleExpiresAt'))
                    or tonumber(ARGV[5]) >= tonumber(redis.call('HGET', KEYS[2], 'absoluteExpiresAt')) then
                redis.call('DEL', KEYS[2]); redis.call('DEL', KEYS[1]); return 2
            end
            local nextExpiry = math.min(tonumber(ARGV[8]), tonumber(redis.call('HGET', KEYS[2], 'absoluteExpiresAt')))
            redis.call('HSET', KEYS[2], 'refreshTokenId', ARGV[6], 'refreshTokenHash', ARGV[7],
                'lastActivityAt', ARGV[5], 'idleExpiresAt', nextExpiry)
            redis.call('EXPIREAT', KEYS[2], nextExpiry); redis.call('EXPIREAT', KEYS[1], nextExpiry)
            return 1
            """, Long.class);

    private static final RedisScript<Long> REVOKE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == KEYS[2] then redis.call('DEL', KEYS[1]) end
            return redis.call('DEL', KEYS[2])
            """, Long.class);

    private static final RedisScript<Long> REVOKE_ALL = new DefaultRedisScript<>("""
            local session = redis.call('GET', KEYS[1])
            if session ~= false then redis.call('DEL', session) end
            return redis.call('DEL', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redis;

    public ValkeyPlatformOperatorSessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public PlatformOperatorSessionResult replaceActiveSession(PlatformOperatorSessionState state) {
        long expiry = Math.min(state.idleExpiresAt().getEpochSecond(), state.absoluteExpiresAt().getEpochSecond());
        execute(REPLACE, List.of(accountKey(state.accountId()), sessionKey(state.sessionHash())),
                state.accountId().toString(), state.sessionHash(), state.refreshTokenId(), state.refreshTokenHash(),
                epoch(state.loginAt()), epoch(state.lastActivityAt()), epoch(state.idleExpiresAt()),
                epoch(state.absoluteExpiresAt()), Long.toString(state.authorityVersion()),
                Long.toString(state.sessionVersion()), Boolean.toString(state.passwordChangeRequired()),
                Long.toString(expiry));
        return new PlatformOperatorSessionResult(PlatformOperatorSessionResult.Status.CREATED, state);
    }

    @Override
    public PlatformOperatorSessionResult validateAndTouch(
            PlatformOperatorSessionProof proof, Instant now, Instant nextIdleExpiresAt) {
        long result = execute(VALIDATE, keys(proof), Long.toString(proof.authorityVersion()),
                Long.toString(proof.sessionVersion()), epoch(now), epoch(nextIdleExpiresAt));
        PlatformOperatorSessionResult.Status status = switch ((int) result) {
            case 1 -> PlatformOperatorSessionResult.Status.VALID;
            case 2 -> PlatformOperatorSessionResult.Status.EXPIRED;
            default -> PlatformOperatorSessionResult.Status.INVALID;
        };
        return new PlatformOperatorSessionResult(status,
                status == PlatformOperatorSessionResult.Status.VALID ? readState(proof.sessionHash()) : null);
    }

    @Override
    public PlatformOperatorSessionResult rotate(
            PlatformOperatorSessionProof proof, String nextId, String nextHash, Instant now, Instant nextIdle) {
        long result = execute(ROTATE, keys(proof), Long.toString(proof.authorityVersion()),
                Long.toString(proof.sessionVersion()), proof.refreshTokenId(), proof.refreshTokenHash(), epoch(now),
                nextId, nextHash, epoch(nextIdle));
        PlatformOperatorSessionResult.Status status = switch ((int) result) {
            case 1 -> PlatformOperatorSessionResult.Status.ROTATED;
            case 2 -> PlatformOperatorSessionResult.Status.EXPIRED;
            case 3 -> PlatformOperatorSessionResult.Status.REUSED;
            default -> PlatformOperatorSessionResult.Status.INVALID;
        };
        return new PlatformOperatorSessionResult(status,
                status == PlatformOperatorSessionResult.Status.ROTATED ? readState(proof.sessionHash()) : null);
    }

    @Override public void revoke(Long accountId, String sessionHash) {
        execute(REVOKE, List.of(accountKey(accountId), sessionKey(sessionHash)));
    }

    @Override public void revokeAll(Long accountId) {
        execute(REVOKE_ALL, List.of(accountKey(accountId)));
    }

    private List<String> keys(PlatformOperatorSessionProof proof) {
        return List.of(accountKey(proof.accountId()), sessionKey(proof.sessionHash()));
    }

    private long execute(RedisScript<Long> script, List<String> keys, String... args) {
        try {
            Long result = redis.execute(script, keys, (Object[]) args);
            if (result == null) throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
            return result;
        } catch (DataAccessException exception) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private PlatformOperatorSessionState readState(String sessionHash) {
        try {
            var values = redis.<String, String>opsForHash().entries(sessionKey(sessionHash));
            if (values.isEmpty()) return null;
            return new PlatformOperatorSessionState(
                    Long.valueOf(values.get("accountId")), values.get("sessionHash"), values.get("refreshTokenId"),
                    values.get("refreshTokenHash"), instant(values.get("loginAt")), instant(values.get("lastActivityAt")),
                    instant(values.get("idleExpiresAt")), instant(values.get("absoluteExpiresAt")),
                    Long.parseLong(values.get("authorityVersion")), Long.parseLong(values.get("sessionVersion")),
                    Boolean.parseBoolean(values.get("passwordChangeRequired")));
        } catch (RuntimeException exception) {
            if (exception instanceof ServiceException serviceException) throw serviceException;
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static String accountKey(Long id) { return PREFIX + "account:" + id + ":active-session"; }
    private static String sessionKey(String hash) { return PREFIX + "session:" + hash; }
    private static String epoch(Instant instant) { return Long.toString(instant.getEpochSecond()); }
    private static Instant instant(String epoch) { return Instant.ofEpochSecond(Long.parseLong(epoch)); }
}
