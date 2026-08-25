package com.miriyum.domain.store.dto.storeoperator;

import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.Region;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.ZoneId;
import java.time.LocalDate;
import java.util.List;

public record StoreCreateRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9]{10}$")
        String businessRegistrationNumber,

        BusinessType businessType,

        @NotBlank
        @Size(max = 100)
        String name,

        @NotNull
        @Size(max = 1000)
        String description,

        @NotNull
        Region region,

        @NotBlank
        @Size(max = 300)
        String address,

        @NotBlank
        @Size(max = 64)
        String timeZoneId,

        @NotBlank
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$")
        String storeCategoryCode,

        @NotNull
        @Size(max = 20)
        List<
                @NotBlank
                @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$")
                String> tagCodes,

        @NotNull
        @Valid
        StoreModesRequest modes,

        @NotBlank @Size(max = 200)
        String legalBusinessName,

        @NotBlank @Size(max = 100)
        String representativeName,

        @NotNull
        LocalDate openingDate,

        @NotBlank @Size(max = 100)
        String primaryBusinessCategory,

        @NotBlank @Size(max = 100)
        String primaryBusinessItem,

        @NotNull
        @AssertTrue
        Boolean applicantSelfAttested,

        @NotNull
        @AssertTrue
        Boolean requiredTermsAgreed
) {
    /** 신규 HTTP 신청처럼 확장 입점 필드는 보내되 전환용 businessType은 생략하는 생성자다. */
    public StoreCreateRequest(
            String businessRegistrationNumber,
            String name,
            String description,
            Region region,
            String address,
            String timeZoneId,
            String storeCategoryCode,
            List<String> tagCodes,
            StoreModesRequest modes,
            String legalBusinessName,
            String representativeName,
            LocalDate openingDate,
            String primaryBusinessCategory,
            String primaryBusinessItem,
            Boolean applicantSelfAttested,
            Boolean requiredTermsAgreed
    ) {
        this(
                businessRegistrationNumber, null, name, description, region, address,
                timeZoneId, storeCategoryCode, tagCodes, modes, legalBusinessName,
                representativeName, openingDate, primaryBusinessCategory, primaryBusinessItem,
                applicantSelfAttested, requiredTermsAgreed);
    }

    /** 신규 클라이언트와 기존 내부 호출자를 위한 생성자다. 전환용 businessType은 생략한다. */
    public StoreCreateRequest(
            String businessRegistrationNumber,
            String name,
            String description,
            Region region,
            String address,
            String timeZoneId,
            String storeCategoryCode,
            List<String> tagCodes,
            StoreModesRequest modes,
            Boolean applicantSelfAttested,
            Boolean requiredTermsAgreed
    ) {
        this(
                businessRegistrationNumber, null, name, description, region, address,
                timeZoneId, storeCategoryCode, tagCodes, modes,
                "LEGACY", "LEGACY", LocalDate.of(1970, 1, 1), "LEGACY", "LEGACY",
                applicantSelfAttested, requiredTermsAgreed);
    }

    /** 구 task와 테스트가 사용하던 생성자이며 contract 단계까지 유지한다. */
    public StoreCreateRequest(
            String businessRegistrationNumber,
            BusinessType businessType,
            String name,
            String description,
            Region region,
            String address,
            String timeZoneId,
            String storeCategoryCode,
            List<String> tagCodes,
            StoreModesRequest modes,
            Boolean applicantSelfAttested,
            Boolean requiredTermsAgreed
    ) {
        this(
                businessRegistrationNumber, businessType, name, description, region, address,
                timeZoneId, storeCategoryCode, tagCodes, modes,
                "LEGACY", "LEGACY", LocalDate.of(1970, 1, 1), "LEGACY", "LEGACY",
                applicantSelfAttested, requiredTermsAgreed);
    }

    /** 구 task가 읽을 수 있는 비-null 저장값을 반환한다. */
    public BusinessType compatibilityBusinessType() {
        return businessType == null ? BusinessType.OTHER : businessType;
    }

    @AssertTrue(message = "유효한 IANA 시간대여야 합니다.")
    public boolean isTimeZoneIdValid() {
        if (timeZoneId == null || timeZoneId.isBlank()) {
            return true;
        }
        return ZoneId.getAvailableZoneIds().contains(timeZoneId);
    }
}
