package com.miriyum.domain.store.core.dto;

import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.Region;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record StoreUpdateRequest(
        @Size(min = 1, max = 100)
        String name,

        @Size(max = 1000)
        String description,

        Region region,

        @Size(min = 1, max = 300)
        String address,

        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$")
        String storeCategoryCode,

        @Size(max = 20)
        List<
                @NotBlank
                @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$")
                String> tagCodes,

        @Valid
        StoreModesRequest modes,

        OperationStatus operationStatus
) {

    @AssertTrue(message = "수정할 필드가 하나 이상 필요합니다.")
    public boolean isAnyFieldPresent() {
        return name != null
                || description != null
                || region != null
                || address != null
                || storeCategoryCode != null
                || tagCodes != null
                || modes != null
                || operationStatus != null;
    }
}
