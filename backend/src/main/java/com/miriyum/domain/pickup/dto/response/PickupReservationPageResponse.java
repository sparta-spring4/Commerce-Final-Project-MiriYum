package com.miriyum.domain.pickup.dto.response;

import com.miriyum.global.response.PageMetadata;
import java.util.List;
import org.springframework.data.domain.Page;

public record PickupReservationPageResponse(
        List<PickupReservationResponse> items,
        PageMetadata page
) {
    public static PickupReservationPageResponse from(Page<PickupReservationResponse> source) {
        return new PickupReservationPageResponse(
                List.copyOf(source.getContent()),
                new PageMetadata(
                        source.getNumber(), source.getSize(), source.getTotalElements(),
                        source.getTotalPages(), source.hasNext()));
    }
}
