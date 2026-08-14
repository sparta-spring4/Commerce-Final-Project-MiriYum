package com.miriyum.domain.auth.qrepoch;

import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenHash;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenKey;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
class ValkeyConsumerQrEpochStore implements ConsumerQrEpochStore {

    private static final Logger log = LoggerFactory.getLogger(ValkeyConsumerQrEpochStore.class);
    private static final String OK_PREFIX = "OK|";
    private static final String CORRUPT = "CORRUPT";
    private static final long ACTIVE_INDEX_MEMBER_MISSING = -2L;
    private static final long REVOKED_INDEX_MEMBER_PRESENT = -3L;
    private static final int SALT_BYTES = 16;
    private static final Pattern SALT_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{22}$");
    private static final Pattern COUNTER_PATTERN = Pattern.compile("^(0|[1-9][0-9]*)$");
    private static final String MAX_COUNTER = Long.toString(Long.MAX_VALUE);

    private static final RedisScript<String> CAPTURE_SCRIPT = new DefaultRedisScript<>("""
            local keyType = redis.call('TYPE', KEYS[1]).ok
            if keyType == 'none' then
                redis.call('HSET', KEYS[1], 'salt', ARGV[1], 'counter', '0')
                return 'OK|' .. ARGV[1] .. '|0'
            end
            if keyType ~= 'hash' then
                return 'CORRUPT'
            end
            if redis.call('PTTL', KEYS[1]) ~= -1 then
                return 'CORRUPT'
            end
            local salt = redis.call('HGET', KEYS[1], 'salt')
            local counter = redis.call('HGET', KEYS[1], 'counter')
            if salt == false or counter == false then
                return 'CORRUPT'
            end
            return 'OK|' .. salt .. '|' .. counter
            """, String.class);

    private static final RedisScript<String> READ_SCRIPT = new DefaultRedisScript<>("""
            local keyType = redis.call('TYPE', KEYS[1]).ok
            if keyType == 'none' then
                return 'MISSING'
            end
            if keyType ~= 'hash' then
                return 'CORRUPT'
            end
            if redis.call('PTTL', KEYS[1]) ~= -1 then
                return 'CORRUPT'
            end
            local salt = redis.call('HGET', KEYS[1], 'salt')
            local counter = redis.call('HGET', KEYS[1], 'counter')
            if salt == false or counter == false then
                return 'CORRUPT'
            end
            return 'OK|' .. salt .. '|' .. counter
            """, String.class);

    private static final RedisScript<Long> ADVANCE_FOR_LOGOUT_SCRIPT = new DefaultRedisScript<>("""
            local function validUnsignedLong(value)
                if value == '0' then
                    return true
                end
                if string.match(value, '^[1-9][0-9]*$') == nil then
                    return false
                end
                if string.len(value) < 19 then
                    return true
                end
                return string.len(value) == 19 and value <= '9223372036854775807'
            end

            local function validPositiveLong(value)
                return value ~= '0' and validUnsignedLong(value)
            end

            local function validIdentifier(value)
                return string.len(value) >= 1 and string.len(value) <= 128
                        and string.match(value, '^[A-Za-z0-9_-]+$') ~= nil
            end

            local function validSha256(value)
                return string.len(value) == 64 and string.match(value, '^[0-9a-f]+$') ~= nil
            end

            local function validGeneration(value)
                return string.len(value) >= 1 and string.len(value) <= 64
                        and string.match(value, '^[A-Za-z0-9._-]+$') ~= nil
            end

            local familyType = redis.call('TYPE', KEYS[1]).ok
            if familyType == 'none' then
                return 0
            end
            if familyType ~= 'hash' then
                return -1
            end

            local namespace = redis.call('HGET', KEYS[1], 'namespace')
            local accountId = redis.call('HGET', KEYS[1], 'accountId')
            local familyId = redis.call('HGET', KEYS[1], 'familyId')
            local currentTokenId = redis.call('HGET', KEYS[1], 'currentTokenId')
            local currentTokenHash = redis.call('HGET', KEYS[1], 'currentTokenHash')
            local status = redis.call('HGET', KEYS[1], 'status')
            local lastRotatedAt = redis.call('HGET', KEYS[1], 'lastRotatedAt')
            if namespace == false or accountId == false or familyId == false
                    or currentTokenId == false or currentTokenHash == false
                    or status == false or lastRotatedAt == false
                    or (namespace ~= 'consumer' and namespace ~= 'store-operator'
                        and namespace ~= 'platform-operator')
                    or not validPositiveLong(accountId)
                    or not validIdentifier(familyId)
                    or not validIdentifier(currentTokenId)
                    or not validSha256(currentTokenHash)
                    or (status ~= 'ACTIVE' and status ~= 'REVOKED')
                    or not validUnsignedLong(lastRotatedAt) then
                return -1
            end

            if namespace ~= ARGV[1] or accountId ~= ARGV[2] or familyId ~= ARGV[3] then
                return 0
            end

            local markerNamespace = redis.call('HGET', KEYS[1], 'qrLogoutNamespace')
            local markerAccountId = redis.call('HGET', KEYS[1], 'qrLogoutAccountId')
            local markerFamilyId = redis.call('HGET', KEYS[1], 'qrLogoutFamilyId')
            local markerGeneration = redis.call('HGET', KEYS[1], 'qrLogoutGeneration')
            local markerTokenId = redis.call('HGET', KEYS[1], 'qrLogoutTokenId')
            local markerTokenHash = redis.call('HGET', KEYS[1], 'qrLogoutTokenHash')
            local markerCount = 0
            if markerNamespace ~= false then markerCount = markerCount + 1 end
            if markerAccountId ~= false then markerCount = markerCount + 1 end
            if markerFamilyId ~= false then markerCount = markerCount + 1 end
            if markerGeneration ~= false then markerCount = markerCount + 1 end
            if markerTokenId ~= false then markerCount = markerCount + 1 end
            if markerTokenHash ~= false then markerCount = markerCount + 1 end
            if markerCount ~= 0 and markerCount ~= 6 then
                return -1
            end
            if markerCount == 6 then
                if not validPositiveLong(markerAccountId)
                        or not validIdentifier(markerFamilyId)
                        or not validGeneration(markerGeneration)
                        or not validIdentifier(markerTokenId)
                        or not validSha256(markerTokenHash)
                        or markerNamespace ~= namespace
                        or markerAccountId ~= accountId
                        or markerFamilyId ~= familyId
                        or markerTokenId ~= currentTokenId
                        or markerTokenHash ~= currentTokenHash
                        or status ~= 'REVOKED' then
                    return -1
                end
            end
            local markerExact = markerCount == 6
                    and markerNamespace == ARGV[1]
                    and markerAccountId == ARGV[2]
                    and markerFamilyId == ARGV[3]
                    and markerGeneration == ARGV[7]
                    and markerTokenId == ARGV[4]
                    and markerTokenHash == ARGV[5]

            if markerCount ~= 0 and not markerExact then
                return 0
            end
            if markerCount == 0 and (status ~= 'ACTIVE'
                    or currentTokenId ~= ARGV[4] or currentTokenHash ~= ARGV[5]) then
                return 0
            end
            if markerCount == 0 and ARGV[9] == '1' then
                return 3
            end
            if ARGV[10] ~= '1' then
                return -1
            end

            local familyExpireAt = redis.call('PEXPIRETIME', KEYS[1])
            if familyExpireAt < 0 then
                return -1
            end

            local epochType = redis.call('TYPE', KEYS[3]).ok
            if epochType ~= 'none' and epochType ~= 'hash' then
                return -1
            end
            if epochType == 'hash' then
                if redis.call('PTTL', KEYS[3]) ~= -1 then
                    return -1
                end
                local salt = redis.call('HGET', KEYS[3], 'salt')
                local counter = redis.call('HGET', KEYS[3], 'counter')
                if salt == false or string.len(salt) ~= 22
                        or string.match(salt, '^[A-Za-z0-9_-]+$') == nil
                        or counter == false or not validUnsignedLong(counter) then
                    return -1
                end
                if counter == '9223372036854775807' then
                    return -1
                end
            elseif markerExact then
                return -1
            end

            local indexType = redis.call('TYPE', KEYS[2]).ok
            if indexType ~= 'none' and indexType ~= 'set' then
                return -1
            end

            if markerExact then
                if indexType == 'set' and redis.call('SISMEMBER', KEYS[2], KEYS[1]) == 1 then
                    return -3
                end
                return 2
            end

            if indexType ~= 'set' or redis.call('SISMEMBER', KEYS[2], KEYS[1]) ~= 1 then
                return -2
            end
            local indexExpireAt = redis.call('PEXPIRETIME', KEYS[2])
            if indexExpireAt < 0 or indexExpireAt < familyExpireAt then
                return -1
            end
            if string.len(ARGV[8]) ~= 22 or string.match(ARGV[8], '^[A-Za-z0-9_-]+$') == nil
                    or not validUnsignedLong(ARGV[6]) then
                return -1
            end

            if epochType == 'none' then
                redis.call('HSET', KEYS[3], 'salt', ARGV[8], 'counter', '0')
            end
            redis.call('HINCRBY', KEYS[3], 'counter', 1)
            redis.call('HSET', KEYS[1],
                'status', 'REVOKED',
                'lastRotatedAt', ARGV[6],
                'qrLogoutNamespace', ARGV[1],
                'qrLogoutAccountId', ARGV[2],
                'qrLogoutFamilyId', ARGV[3],
                'qrLogoutGeneration', ARGV[7],
                'qrLogoutTokenId', ARGV[4],
                'qrLogoutTokenHash', ARGV[5])
            redis.call('SREM', KEYS[2], KEYS[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ConsumerQrEpochProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    ValkeyConsumerQrEpochStore(
            StringRedisTemplate redisTemplate,
            ConsumerQrEpochProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public ConsumerQrEpochSnapshot captureCurrent(Long accountId) {
        String generation = properties.requireStorageGeneration();
        String key = ConsumerQrEpochKey.forAccount(generation, accountId);
        String result = execute(CAPTURE_SCRIPT, "capture", key, newSalt());
        EpochState state = requireState(result, "capture");
        return snapshot(generation, accountId, state);
    }

    @Override
    public boolean isCurrent(Long accountId, String opaqueVersion) {
        String generation = properties.requireStorageGeneration();
        String key = ConsumerQrEpochKey.forAccount(generation, accountId);
        String result = execute(READ_SCRIPT, "read", key);
        if ("MISSING".equals(result)) {
            return false;
        }
        EpochState state = requireState(result, "read");
        return MessageDigest.isEqual(
                snapshot(generation, accountId, state).opaqueVersion().getBytes(StandardCharsets.US_ASCII),
                opaqueVersion.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public ConsumerQrEpochAdvanceResult advanceForLogout(
            TokenNamespace namespace,
            ParsedToken refreshToken,
            String rawRefreshToken,
            boolean corroboratingSubjectMismatch,
            Instant now
    ) {
        String configuredGeneration = properties.getStorageGeneration();
        boolean generationValid = ConsumerQrEpochProperties.isValidStorageGeneration(configuredGeneration);
        String generation = generationValid ? configuredGeneration : "";
        String familyKey = RefreshTokenKey.forFamily(namespace, refreshToken.familyId());
        String accountFamiliesKey = RefreshTokenKey.forAccountFamilies(namespace, refreshToken.accountId());
        String epochKey = generationValid
                ? ConsumerQrEpochKey.forAccount(generation, refreshToken.accountId())
                : "auth:qr-epoch:invalid-generation";
        Long result;
        try {
            result = redisTemplate.execute(
                    ADVANCE_FOR_LOGOUT_SCRIPT,
                    List.of(familyKey, accountFamiliesKey, epochKey),
                    namespace.value(),
                    refreshToken.accountId().toString(),
                    refreshToken.familyId(),
                    refreshToken.tokenId(),
                    RefreshTokenHash.sha256(rawRefreshToken),
                    Long.toString(now.getEpochSecond()),
                    generation,
                    newSalt(),
                    corroboratingSubjectMismatch ? "1" : "0",
                    generationValid ? "1" : "0");
        } catch (DataAccessException exception) {
            log.error("event=qr_epoch_store_unavailable operation=advanceForLogout namespace={}", namespace.value());
            throw unavailable();
        }
        if (result == null) {
            log.error("event=qr_epoch_script_no_response operation=advanceForLogout namespace={}", namespace.value());
            throw unavailable();
        }
        if (result.equals(1L)) {
            return new ConsumerQrEpochAdvanceResult(ConsumerQrEpochAdvanceResult.Status.APPLIED);
        }
        if (result.equals(2L)) {
            return new ConsumerQrEpochAdvanceResult(ConsumerQrEpochAdvanceResult.Status.ALREADY_APPLIED);
        }
        if (result.equals(0L)) {
            return new ConsumerQrEpochAdvanceResult(ConsumerQrEpochAdvanceResult.Status.NOT_AUTHORIZED);
        }
        if (result.equals(3L)) {
            return new ConsumerQrEpochAdvanceResult(ConsumerQrEpochAdvanceResult.Status.SUBJECT_MISMATCH);
        }
        if (result.equals(ACTIVE_INDEX_MEMBER_MISSING)) {
            log.error("event=qr_epoch_refresh_index_mismatch operation=advanceForLogout namespace={} expected=present",
                    namespace.value());
            throw unavailable();
        }
        if (result.equals(REVOKED_INDEX_MEMBER_PRESENT)) {
            log.error("event=qr_epoch_refresh_index_mismatch operation=advanceForLogout namespace={} expected=absent",
                    namespace.value());
            throw unavailable();
        }
        log.error("event=qr_epoch_script_unexpected_result operation=advanceForLogout namespace={} result={}",
                namespace.value(), result);
        throw unavailable();
    }

    ConsumerQrEpochAdvanceResult advanceForLogout(
            TokenNamespace namespace,
            ParsedToken refreshToken,
            String rawRefreshToken,
            Instant now
    ) {
        return advanceForLogout(namespace, refreshToken, rawRefreshToken, false, now);
    }

    private String execute(RedisScript<String> script, String operation, String key, String... args) {
        try {
            return redisTemplate.execute(script, List.of(key), (Object[]) args);
        } catch (DataAccessException exception) {
            log.error("event=qr_epoch_store_unavailable operation={}", operation);
            throw unavailable();
        }
    }

    private EpochState requireState(String result, String operation) {
        if (result == null || CORRUPT.equals(result) || !result.startsWith(OK_PREFIX)) {
            log.error("event=qr_epoch_script_invalid_response operation={}", operation);
            throw unavailable();
        }
        String[] parts = result.split("\\|", -1);
        if (parts.length != 3 || !SALT_PATTERN.matcher(parts[1]).matches() || !validCounter(parts[2])) {
            log.error("event=qr_epoch_script_invalid_state operation={}", operation);
            throw unavailable();
        }
        return new EpochState(parts[1], parts[2]);
    }

    private boolean validCounter(String value) {
        if (!COUNTER_PATTERN.matcher(value).matches()) {
            return false;
        }
        return value.length() < MAX_COUNTER.length()
                || (value.length() == MAX_COUNTER.length() && value.compareTo(MAX_COUNTER) <= 0);
    }

    private ConsumerQrEpochSnapshot snapshot(String generation, Long accountId, EpochState state) {
        String input = "v1\0" + generation + "\0" + accountId + "\0" + state.salt() + "\0" + state.counter();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return new ConsumerQrEpochSnapshot(
                    accountId,
                    "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private String newSalt() {
        byte[] bytes = new byte[SALT_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ServiceException unavailable() {
        return new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    private record EpochState(String salt, String counter) {
    }
}
