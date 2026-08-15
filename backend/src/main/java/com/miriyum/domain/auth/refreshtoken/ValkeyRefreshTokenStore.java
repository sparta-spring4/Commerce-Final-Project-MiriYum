package com.miriyum.domain.auth.refreshtoken;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** Lua 원자 연산으로 Refresh Token family 상태를 Valkey에 저장한다. */
@Component
public class ValkeyRefreshTokenStore implements RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(ValkeyRefreshTokenStore.class);
    private static final long RISK_EVENT_MARKER_RETENTION_SECONDS = 604_800L;

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
                'lastRotatedAt', ARGV[6],
                'familyCreatedAt', ARGV[9])
            redis.call('EXPIREAT', KEYS[1], ARGV[7])
            redis.call('SADD', KEYS[2], KEYS[1])
            local accountFamiliesExpiresAt = redis.call('EXPIRETIME', KEYS[2])
            if accountFamiliesExpiresAt < tonumber(ARGV[7]) then
                redis.call('EXPIREAT', KEYS[2], ARGV[7])
            end
            return 1
            """, Long.class);

    private static final RedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>("""
            local function recordRiskEvent(sourceEvent, originEvent)
                local totalOccurrenceCount = redis.call('INCR', KEYS[5])
                local counterExpiresAt = redis.call('EXPIRETIME', KEYS[5])
                if counterExpiresAt < tonumber(ARGV[7]) then
                    redis.call('EXPIREAT', KEYS[5], ARGV[7])
                end
                if redis.call('EXISTS', KEYS[3]) == 0 then
                    redis.call('HSET', KEYS[3],
                        'namespace', ARGV[8],
                        'accountId', ARGV[1],
                        'familyId', ARGV[9],
                        'tokenHash', ARGV[3],
                        'sourceEvent', sourceEvent,
                        'originEvent', originEvent,
                        'policyVersion', 'AUTH-012-v1',
                        'occurredAt', ARGV[6],
                        'occurrenceCount', totalOccurrenceCount,
                        'lastOccurredAt', ARGV[6],
                        'generation', ARGV[11])
                    redis.call('EXPIREAT', KEYS[3], ARGV[10])
                else
                    redis.call('HSET', KEYS[3],
                        'occurrenceCount', totalOccurrenceCount,
                        'lastOccurredAt', ARGV[6])
                end
                redis.call('SADD', KEYS[4], KEYS[3])
            end

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
                recordRiskEvent('REUSED_ROTATED_TOKEN', 'ROTATION')
                return 3
            end
            if status ~= 'ACTIVE' then
                recordRiskEvent('REUSED_REVOKED_TOKEN', 'REVOCATION')
                return 3
            end
            local familyCreatedAt = redis.call('HGET', KEYS[1], 'familyCreatedAt')
            redis.call('SADD', KEYS[2], KEYS[1])
            redis.call('HSET', KEYS[1],
                'currentTokenId', ARGV[4],
                'currentTokenHash', ARGV[5],
                'lastRotatedAt', ARGV[6])
            if familyCreatedAt ~= false and familyCreatedAt ~= nil then
                redis.call('EXPIREAT', KEYS[1], ARGV[7])
                local accountFamiliesExpiresAt = redis.call('EXPIRETIME', KEYS[2])
                if accountFamiliesExpiresAt < tonumber(ARGV[7]) then
                    redis.call('EXPIREAT', KEYS[2], ARGV[7])
                end
            end
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
                Long.toString(expectedSessionEpoch),
                epochSeconds(state.familyCreatedAt()));
        // CREATE_SCRIPT는 0(familyId 충돌)·1(생성)·2(session epoch 변경)만 반환한다.
        int createResult = requireScriptResult(result, "create", state.namespace());
        return switch (createResult) {
            case 1 -> new RefreshTokenCreationResult(RefreshTokenCreationResult.Status.CREATED);
            case 2 -> new RefreshTokenCreationResult(RefreshTokenCreationResult.Status.SESSION_EPOCH_CHANGED);
            // familyId는 256bit 난수라 충돌은 사실상 일어나지 않는다. 그래도 스크립트가 아예 실행되지
            // 않은 경우와 같은 COMMON_012로 묶이면 장애 때 원인을 좁힐 수 없어 따로 기록한다.
            case 0 -> {
                log.error("Refresh Token family key가 이미 존재합니다. namespace={}", state.namespace().value());
                throw unavailable();
            }
            default -> throw unexpectedScriptResult("create", createResult, state.namespace());
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
        String pendingRiskEventIndexKey = RefreshTokenRiskEventKey.pendingIndex();
        String riskEventOccurrenceCounterKey = RefreshTokenRiskEventKey.occurrenceCounter(
                namespace, familyId, expectedTokenHash);
        Long result = execute(ROTATE_SCRIPT, List.of(
                        familyKey,
                        accountFamiliesKey,
                        riskEventKey,
                        pendingRiskEventIndexKey,
                        riskEventOccurrenceCounterKey),
                accountId.toString(),
                expectedTokenId,
                expectedTokenHash,
                nextTokenId,
                nextTokenHash,
                epochSeconds(now),
                epochSeconds(nextFamilyExpiresAt),
                namespace.value(),
                familyId,
                epochSeconds(now.plusSeconds(RISK_EVENT_MARKER_RETENTION_SECONDS)),
                UUID.randomUUID().toString());
        // ROTATE_SCRIPT는 0(없음/불일치)·1(회전)·3(재사용)만 반환한다. 0만 정상 업무 결과이고,
        // null과 그 밖의 값은 Valkey 실행 이상이므로 인증 오류로 감추지 않고 COMMON_012로 실패시킨다.
        int rotateResult = requireScriptResult(result, "rotate", namespace);
        return switch (rotateResult) {
            case 1 -> new RefreshTokenRotationResult(RefreshTokenRotationResult.Status.ROTATED);
            case 3 -> new RefreshTokenRotationResult(RefreshTokenRotationResult.Status.REUSED);
            case 0 -> new RefreshTokenRotationResult(RefreshTokenRotationResult.Status.NOT_FOUND);
            default -> throw unexpectedScriptResult("rotate", rotateResult, namespace);
        };
    }

    @Override
    public void revoke(TokenNamespace namespace, String familyId, Long accountId, Instant now) {
        String familyKey = RefreshTokenKey.forFamily(namespace, familyId);
        String accountFamiliesKey = RefreshTokenKey.forAccountFamilies(namespace, accountId);
        Long result = execute(
                REVOKE_SCRIPT, List.of(familyKey, accountFamiliesKey), accountId.toString(), epochSeconds(now));
        // REVOKE_SCRIPT는 0(없음/불일치)·1(폐기)만 반환한다. 반환값을 확인하지 않으면
        // 실행 자체가 실패해도 폐기가 끝난 것처럼 종료된다.
        int revokeResult = requireScriptResult(result, "revoke", namespace);
        if (revokeResult != 0 && revokeResult != 1) {
            throw unexpectedScriptResult("revoke", revokeResult, namespace);
        }
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
        Long result = execute(
                REVOKE_ALL_SCRIPT,
                List.of(accountFamiliesKey, sessionEpochKey),
                accountId.toString(),
                epochSeconds(now),
                epochSeconds(sessionEpochExpiresAt));
        // REVOKE_ALL_SCRIPT는 폐기한 family 수를 반환한다. 0건도 정상이지만 음수는 있을 수 없다.
        int revokedCount = requireScriptResult(result, "revokeAll", namespace);
        if (revokedCount < 0) {
            throw unexpectedScriptResult("revokeAll", revokedCount, namespace);
        }
    }

    /**
     * Lua 실행이 결과를 돌려주지 않으면 업무 결과로 해석하지 않고 실패시킨다.
     *
     * <p>무응답을 "없음"이나 "성공"으로 접으면 Valkey 실행 이상이 인증 오류로 숨거나
     * 폐기가 적용되지 않은 채 성공으로 보인다.</p>
     *
     * <p>로그에는 연산 이름과 namespace만 남긴다. accountId·familyId·tokenId·tokenHash는
     * 계정을 특정하는 값이라 AUTH-012의 비식별 계약상 남기지 않는다. 원인 분류에는
     * "어느 연산이 어떤 값을 반환했는가"만 있으면 충분하다.</p>
     */
    private int requireScriptResult(Long result, String operation, TokenNamespace namespace) {
        if (result == null) {
            log.error("event=refresh_token_script_no_response operation={} namespace={}",
                    operation, namespace.value());
            throw unavailable();
        }
        return result.intValue();
    }

    private ServiceException unexpectedScriptResult(String operation, int result, TokenNamespace namespace) {
        log.error("event=refresh_token_script_unexpected_result operation={} result={} namespace={}",
                operation, result, namespace.value());
        return unavailable();
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
