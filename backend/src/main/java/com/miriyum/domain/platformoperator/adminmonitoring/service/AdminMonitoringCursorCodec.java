package com.miriyum.domain.platformoperator.adminmonitoring.service;

import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringRequests.ListQuery;
import com.miriyum.domain.platformoperator.adminmonitoring.dto.AdminMonitoringResponses.CaseType;
import com.miriyum.domain.platformoperator.adminmonitoring.exception.AdminMonitoringErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnExpression("'${miriyum.platform-operator.enabled:false}' == 'true' and "
        + "'${miriyum.admin-monitoring.enabled:false}' == 'true'")
public class AdminMonitoringCursorCodec {

    private static final int CONTRACT_VERSION = 2;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String activeKeyId;
    private final byte[] activeSecret;
    private final Duration ttl;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AdminMonitoringCursorCodec(
            @Value("${miriyum.admin-monitoring.cursor-active-key-id:v1}") String activeKeyId,
            @Value("${miriyum.admin-monitoring.cursor-active-secret}") String activeSecret,
            @Value("${miriyum.admin-monitoring.cursor-ttl:PT30M}") Duration ttl,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        if (activeKeyId == null || !activeKeyId.matches("^[A-Za-z0-9_-]{1,32}$")) {
            throw new IllegalArgumentException("monitoring cursor key ID is invalid");
        }
        if (activeSecret == null || activeSecret.length() < 32) {
            throw new IllegalArgumentException("monitoring cursor secret must contain at least 32 characters");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("monitoring cursor TTL must be positive");
        }
        this.activeKeyId = activeKeyId;
        this.activeSecret = activeSecret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public String encode(CursorState state, ListQuery query) {
        Objects.requireNonNull(state, "cursor state must not be null");
        Objects.requireNonNull(query, "query must not be null");
        Instant issuedAt = clock.instant();
        if (state.asOf().isAfter(issuedAt)) {
            throw invalid();
        }
        try {
            Payload payload = new Payload(
                    CONTRACT_VERSION,
                    activeKeyId,
                    issuedAt.toString(),
                    state.asOf().toString(),
                    fingerprint(query),
                    seek(state.lastEvaluated()),
                    seek(state.reservationAfter()),
                    seek(state.waitingAfter()),
                    seek(state.menuHoldAfter()),
                    seek(state.paymentAfter()),
                    state.emittedCaseIds().stream().sorted().toList());
            String body = base64(objectMapper.writeValueAsBytes(payload));
            String signed = activeKeyId + "." + body;
            return signed + "." + base64(sign(signed));
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    public CursorState decode(String token, ListQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        try {
            if (token == null || token.isBlank()) {
                throw invalid();
            }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw invalid();
            }
            if (!MessageDigest.isEqual(
                    parts[0].getBytes(StandardCharsets.UTF_8),
                    activeKeyId.getBytes(StandardCharsets.UTF_8))) {
                throw expired();
            }
            String signed = parts[0] + "." + parts[1];
            byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(sign(signed), actual)) {
                throw invalid();
            }
            Payload payload = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(parts[1]), Payload.class);
            if (payload.contractVersion() != CONTRACT_VERSION
                    || !activeKeyId.equals(payload.keyId())) {
                throw expired();
            }
            Instant now = clock.instant();
            Instant issuedAt = Instant.parse(payload.issuedAt());
            Instant asOf = Instant.parse(payload.asOf());
            if (issuedAt.isAfter(now) || asOf.isAfter(now)) {
                throw invalid();
            }
            if (issuedAt.plus(ttl).isBefore(now)) {
                throw expired();
            }
            if (!MessageDigest.isEqual(
                    payload.filterFingerprint().getBytes(StandardCharsets.UTF_8),
                    fingerprint(query).getBytes(StandardCharsets.UTF_8))) {
                throw invalid();
            }
            return new CursorState(
                    asOf,
                    globalSeek(payload.lastEvaluated()),
                    sourceSeek(payload.reservationAfter()),
                    sourceSeek(payload.waitingAfter()),
                    sourceSeek(payload.menuHoldAfter()),
                    sourceSeek(payload.paymentAfter()),
                    Set.copyOf(payload.emittedCaseIds()));
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private String fingerprint(ListQuery query) {
        try {
            return base64(MessageDigest.getInstance("SHA-256")
                    .digest(query.canonicalFilter().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(activeSecret, HMAC_ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private static SeekPayload seek(GlobalSeek seek) {
        return seek == null ? null : new SeekPayload(
                seek.statusChangedAt().toString(), seek.caseType().name(), seek.caseId());
    }

    private static SeekPayload seek(SourceSeek seek) {
        return seek == null ? null : new SeekPayload(
                seek.statusChangedAt().toString(), null, seek.caseId());
    }

    private static GlobalSeek globalSeek(SeekPayload seek) {
        return seek == null ? null : new GlobalSeek(
                Instant.parse(seek.statusChangedAt()), CaseType.valueOf(seek.caseType()), seek.caseId());
    }

    private static SourceSeek sourceSeek(SeekPayload seek) {
        return seek == null ? null : new SourceSeek(Instant.parse(seek.statusChangedAt()), seek.caseId());
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static ServiceException invalid() {
        return new ServiceException(AdminMonitoringErrorCode.INVALID_CURSOR);
    }

    private static ServiceException expired() {
        return new ServiceException(AdminMonitoringErrorCode.EXPIRED_CURSOR);
    }

    public record CursorState(
            Instant asOf,
            GlobalSeek lastEvaluated,
            SourceSeek reservationAfter,
            SourceSeek waitingAfter,
            SourceSeek menuHoldAfter,
            SourceSeek paymentAfter,
            Set<String> emittedCaseIds
    ) {
        public CursorState {
            Objects.requireNonNull(asOf, "asOf must not be null");
            emittedCaseIds = emittedCaseIds == null ? Set.of() : Set.copyOf(emittedCaseIds);
            emittedCaseIds.forEach(AdminMonitoringCursorCodec::requireCaseId);
        }
    }

    public record GlobalSeek(Instant statusChangedAt, CaseType caseType, String caseId) {
        public GlobalSeek {
            Objects.requireNonNull(statusChangedAt, "statusChangedAt must not be null");
            Objects.requireNonNull(caseType, "caseType must not be null");
            requireCaseId(caseId);
        }
    }

    public record SourceSeek(Instant statusChangedAt, String caseId) {
        public SourceSeek {
            Objects.requireNonNull(statusChangedAt, "statusChangedAt must not be null");
            requireCaseId(caseId);
        }
    }

    private record Payload(
            int contractVersion,
            String keyId,
            String issuedAt,
            String asOf,
            String filterFingerprint,
            SeekPayload lastEvaluated,
            SeekPayload reservationAfter,
            SeekPayload waitingAfter,
            SeekPayload menuHoldAfter,
            SeekPayload paymentAfter,
            List<String> emittedCaseIds
    ) {
    }

    private record SeekPayload(String statusChangedAt, String caseType, String caseId) {
    }

    private static void requireCaseId(String caseId) {
        if (caseId == null || !caseId.matches("(?:reservation(?:-hold)?|waiting):[1-9][0-9]*")) {
            throw new IllegalArgumentException("invalid monitoring case ID");
        }
    }
}
