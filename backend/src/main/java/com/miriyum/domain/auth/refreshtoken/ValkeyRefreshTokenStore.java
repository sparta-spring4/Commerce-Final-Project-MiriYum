package com.miriyum.domain.auth.refreshtoken;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** Lua 원자 연산으로 Refresh Token family 상태를 Valkey에 저장한다. */
@Component
public class ValkeyRefreshTokenStore implements RefreshTokenStore {

    private static final RedisScript<Long> CREATE_SCRIPT = new DefaultRedisScript<>("""
            local currentSessionEpoch = redis.call('GET', KEYS[3])
            if currentSessionEpoch == false then
                currentSessionEpoch = '0'
            end
            if currentSessionEpoch ~= ARGV[8] then
                return 2
            end
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return 0
            end
            redis.call('HSET', KEYS[1],
                'namespace', ARGV[1],
                'accountId', ARGV[2],
                'familyId', ARGV[3],
                'currentTokenId', ARGV[4],
                'currentTokenHash', ARGV[5],
                'status', 'ACTIVE',
                'lastRotatedAt', ARGV[6])
            redis.call('EXPIREAT', KEYS[1], ARGV[7])
            redis.call('SADD', KEYS[2], KEYS[1])
            redis.call('EXPIREAT', KEYS[2], ARGV[7])
            return 1
            """, Long.class);

    private static final RedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 0
            end
            if redis.call('HGET', KEYS[1], 'accountId') ~= ARGV[1] then
                return 0
            end
            local status = redis.call('HGET', KEYS[1], 'status')
            if redis.call('HGET', KEYS[1], 'currentTokenId') ~= ARGV[2]
                    or redis.call('HGET', KEYS[1], 'currentTokenHash') ~= ARGV[3] then
                redis.call('HSET', KEYS[1], 'status', 'REVOKED', 'lastRotatedAt', ARGV[6])
                if redis.call('EXISTS', KEYS[3]) == 0 then
                    redis.call('HSET', KEYS[3],
                        'namespace', ARGV[8],
                        'accountId', ARGV[1],
                        'familyId', ARGV[9],
                        'tokenHash', ARGV[3],
                        'sourceEvent', 'REUSED_ROTATED_TOKEN',
                        'originEvent', 'ROTATION',
                        'policyVersion', 'AUTH-012-v1',
                        'occurredAt', ARGV[6])
                    redis.call('EXPIREAT', KEYS[3], ARGV[7])
                end
                return 3
            end
            if status ~= 'ACTIVE' then
                if redis.call('EXISTS', KEYS[3]) == 0 then
                    redis.call('HSET', KEYS[3],
                        'namespace', ARGV[8],
                        'accountId', ARGV[1],
                        'familyId', ARGV[9],
                        'tokenHash', ARGV[3],
                        'sourceEvent', 'REUSED_REVOKED_TOKEN',
                        'originEvent', 'REVOCATION',
                        'policyVersion', 'AUTH-012-v1',
                        'occurredAt', ARGV[6])
                    redis.call('EXPIREAT', KEYS[3], ARGV[7])
                end
                return 3
            end
            redis.call('SADD', KEYS[2], KEYS[1])
            redis.call('EXPIREAT', KEYS[2], ARGV[7])
            redis.call('HSET', KEYS[1],
                'currentTokenId', ARGV[4],
                'currentTokenHash', ARGV[5],
                'lastRotatedAt', ARGV[6])
            redis.call('EXPIREAT', KEYS[1], ARGV[7])
            redis.call('EXPIREAT', KEYS[2], ARGV[7])
            return 1
            """, Long.class);

    private static final RedisScript<Long> REVOKE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                redis.call('SREM', KEYS[2], KEYS[1])
                return 0
            end
            if redis.call('HGET', KEYS[1], 'accountId') ~= ARGV[1] then
                return 0
            end
            redis.call('HSET', KEYS[1], 'status', 'REVOKED', 'lastRotatedAt', ARGV[2])
            redis.call('SREM', KEYS[2], KEYS[1])
            return 1
            """, Long.class);

    private static final RedisScript<Long> REVOKE_ALL_SCRIPT = new DefaultRedisScript<>("""
            redis.call('INCR', KEYS[2])
            redis.call('EXPIREAT', KEYS[2], ARGV[3])
            local familyKeys = redis.call('SMEMBERS', KEYS[1])
            local revoked = 0
            for _, familyKey in ipairs(familyKeys) do
                if redis.call('EXISTS', familyKey) == 1
                        and redis.call('HGET', familyKey, 'accountId') == ARGV[1]
                        and redis.call('HGET', familyKey, 'status') == 'ACTIVE' then
                    redis.call('HSET', familyKey, 'status', 'REVOKED', 'lastRotatedAt', ARGV[2])
                    revoked = revoked + 1
                end
            end
            redis.call('DEL', KEYS[1])
            return revoked
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public ValkeyRefreshTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public RefreshTokenCreationResult create(RefreshTokenState state, long expectedSessionEpoch) {
        if (state.status() != RefreshTokenState.Status.ACTIVE) {
            throw new IllegalArgumentException("new Refresh Token family must be active");
        }
        String familyKey = RefreshTokenKey.forFamily(state.namespace(), state.familyId());
        String accountFamiliesKey = RefreshTokenKey.forAccountFamilies(state.namespace(), state.accountId());
        String sessionEpochKey = RefreshTokenKey.forAccountSessionEpoch(state.namespace(), state.accountId());
        Long result = execute(CREATE_SCRIPT, List.of(familyKey, accountFamiliesKey, sessionEpochKey),
                state.namespace().value(),
                state.accountId().toString(),
                state.familyId(),
                state.currentTokenId(),
                state.currentTokenHash(),
                epochSeconds(state.lastRotatedAt()),
                epochSeconds(state.familyExpiresAt()),
                Long.toString(expectedSessionEpoch));
        return switch (result == null ? 0 : result.intValue()) {
            case 1 -> new RefreshTokenCreationResult(RefreshTokenCreationResult.Status.CREATED);
            case 2 -> new RefreshTokenCreationResult(RefreshTokenCreationResult.Status.SESSION_EPOCH_CHANGED);
            default -> throw unavailable();
        };
    }

    @Override
    public long currentSessionEpoch(TokenNamespace namespace, Long accountId) {
        String sessionEpochKey = RefreshTokenKey.forAccountSessionEpoch(namespace, accountId);
        try {
            String value = redisTemplate.opsForValue().get(sessionEpochKey);
            return value == null ? 0L : Long.parseLong(value);
        } catch (DataAccessException | NumberFormatException exception) {
            throw unavailable();
        }
    }
    @Override

    public RefreshTokenRotationResult rotate(
            TokenNamespace namespace,
            String familyId,
            Long accountId,
            String expectedTokenId,
            String expectedTokenHash,
            String nextTokenId,
            String nextTokenHash,
            Instant now,
            Instant nextFamilyExpiresAt
    ) {
        String familyKey = RefreshTokenKey.forFamily(namespace, familyId);
        String accountFamiliesKey = RefreshTokenKey.forAccountFamilies(namespace, accountId);
        String riskEventKey = RefreshTokenRiskEventKey.forReuse(namespace, familyId, expectedTokenHash);
        Long result = execute(ROTATE_SCRIPT, List.of(familyKey, accountFamiliesKey, riskEventKey),
                accountId.toString(),
                expectedTokenId,
                expectedTokenHash,
                nextTokenId,
                nextTokenHash,
                epochSeconds(now),
                epochSeconds(nextFamilyExpiresAt),
                namespace.value(),
                familyId);
        return switch (result == null ? 0 : result.intValue()) {
            case 1 -> new RefreshTokenRotationResult(RefreshTokenRotationResult.Status.ROTATED);
            case 2 -> new RefreshTokenRotationResult(RefreshTokenRotationResult.Status.REVOKED);
            case 3 -> new RefreshTokenRotationResult(RefreshTokenRotationResult.Status.REUSED);
            default -> new RefreshTokenRotationResult(RefreshTokenRotationResult.Status.NOT_FOUND);
        };
    }

    @Override
    public void revoke(TokenNamespace namespace, String familyId, Long accountId, Instant now) {
        String familyKey = RefreshTokenKey.forFamily(namespace, familyId);
        String accountFamiliesKey = RefreshTokenKey.forAccountFamilies(namespace, accountId);
        execute(REVOKE_SCRIPT, List.of(familyKey, accountFamiliesKey), accountId.toString(), epochSeconds(now));
    }

    @Override
    public void revokeAll(
            TokenNamespace namespace,
            Long accountId,
            Instant now,
            Instant sessionEpochExpiresAt
    ) {
        String accountFamiliesKey = RefreshTokenKey.forAccountFamilies(namespace, accountId);
        String sessionEpochKey = RefreshTokenKey.forAccountSessionEpoch(namespace, accountId);
        execute(
                REVOKE_ALL_SCRIPT,
                List.of(accountFamiliesKey, sessionEpochKey),
                accountId.toString(),
                epochSeconds(now),
                epochSeconds(sessionEpochExpiresAt));
    }

    private Long execute(RedisScript<Long> script, List<String> keys, String... args) {
        try {
            return redisTemplate.execute(script, keys, (Object[]) args);
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private ServiceException unavailable() {
        return new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    private String epochSeconds(Instant instant) {
        return Long.toString(instant.getEpochSecond());
    }
}
