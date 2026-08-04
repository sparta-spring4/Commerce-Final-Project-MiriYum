package com.miriyum.domain.reservation.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class ReservationTimePolicyPublicationRequest {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "publicationMode",
            "effectiveAt",
            "changeReason"
    );

    @NotNull
    private final PublicationMode publicationMode;

    private final OffsetDateTime effectiveAt;

    @NotBlank
    @Size(max = 500)
    private final String changeReason;

    private final boolean effectiveAtPresent;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ReservationTimePolicyPublicationRequest(JsonNode input) {
        if (input == null || !input.isObject()
                || !ALLOWED_FIELDS.containsAll(input.propertyNames())) {
            throw new IllegalArgumentException("invalid publication request object");
        }
        this.publicationMode = parsePublicationMode(input.get("publicationMode"));
        this.effectiveAtPresent = input.has("effectiveAt");
        this.effectiveAt = parseEffectiveAt(input.get("effectiveAt"));
        this.changeReason = nullableString(input.get("changeReason"), "changeReason");
    }

    public ReservationTimePolicyPublicationRequest(
            PublicationMode publicationMode,
            OffsetDateTime effectiveAt,
            String changeReason
    ) {
        this.publicationMode = publicationMode;
        this.effectiveAt = effectiveAt;
        this.effectiveAtPresent = effectiveAt != null;
        this.changeReason = changeReason;
    }

    public PublicationMode publicationMode() {
        return publicationMode;
    }

    public OffsetDateTime effectiveAt() {
        return effectiveAt;
    }

    public String changeReason() {
        return changeReason;
    }

    @AssertTrue(message = "게시 방식과 적용 시각이 일치해야 합니다.")
    public boolean isEffectiveAtValid() {
        return publicationMode == null
                || (publicationMode == PublicationMode.IMMEDIATE
                        ? !effectiveAtPresent
                        : effectiveAtPresent && effectiveAt != null);
    }

    private static PublicationMode parsePublicationMode(JsonNode value) {
        String text = nullableString(value, "publicationMode");
        return text == null ? null : PublicationMode.valueOf(text);
    }

    private static OffsetDateTime parseEffectiveAt(JsonNode value) {
        String text = nullableString(value, "effectiveAt");
        return text == null ? null : OffsetDateTime.parse(text);
    }

    private static String nullableString(JsonNode value, String fieldName) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new IllegalArgumentException(fieldName + " must be a string");
        }
        return value.asString();
    }

    public enum PublicationMode {
        IMMEDIATE,
        SCHEDULED
    }
}
