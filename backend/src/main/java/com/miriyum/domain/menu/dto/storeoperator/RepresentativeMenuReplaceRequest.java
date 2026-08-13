package com.miriyum.domain.menu.dto.storeoperator;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

public record RepresentativeMenuReplaceRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull @Size(min = 3, max = 5)
        List<@Valid @Pattern(regexp = "^[1-9][0-9]{0,18}$") String> menuIds
) {
    public RepresentativeMenuReplaceRequest {
        if (menuIds != null) {
            menuIds = List.copyOf(menuIds);
        }
    }

    @AssertTrue
    public boolean isMenuIdsUnique() {
        return menuIds == null
                || menuIds.stream().distinct().count() == menuIds.size();
    }
}
