package com.miriyum.domain.menuhold.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.menuhold.inventory.dto.InventoryBucketCreateCommand;
import com.miriyum.domain.menuhold.inventory.dto.InventoryBucketView;
import com.miriyum.domain.menuhold.inventory.dto.InventoryPolicyChange;
import com.miriyum.domain.menuhold.inventory.model.InventoryAvailabilityStatus;
import com.miriyum.domain.menuhold.service.MenuInventoryAdminCommandService;
import com.miriyum.domain.menuhold.service.MenuInventoryAdminService;
import com.miriyum.domain.menuhold.service.MenuInventoryCommandResult;
import com.miriyum.domain.store.config.StoreManagementSecurityConfig;
import com.miriyum.global.exception.GlobalExceptionHandler;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MenuInventoryAdminController.class)
@Import({StoreManagementSecurityConfig.class, GlobalExceptionHandler.class})
class MenuInventoryAdminControllerTest {

    private static final String URL =
            "/api/v1/store-operator/stores/3/menu-inventory-buckets";
    private static final String KEY =
            "123e4567-e89b-12d3-a456-426614174000";

    @Autowired MockMvc mockMvc;
    @MockitoBean MenuInventoryAdminService queryService;
    @MockitoBean MenuInventoryAdminCommandService commandService;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    void listsCurrentInventoryPoliciesWithPageMetadata() throws Exception {
        authenticate();
        given(queryService.list(eq(7L), eq(3L), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(view())));

        mockMvc.perform(get(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].policyVersion").value(2))
                .andExpect(jsonPath("$.data.items[0].startTime").value("12:00"))
                .andExpect(jsonPath("$.data.items[0].endTime").value("13:00"))
                .andExpect(jsonPath("$.data.items[0].sharedOnlineAllowed").value(true))
                .andExpect(jsonPath("$.data.items[0].endDate").value("2026-08-10"))
                .andExpect(jsonPath("$.data.page.totalElements").value(1));
    }

    @Test
    void createsInventoryPolicyWithRequiredEndDate() throws Exception {
        authenticate();
        given(commandService.create(eq(7L), eq(3L), any(), any()))
                .willReturn(new MenuInventoryCommandResult(201, view()));

        mockMvc.perform(post(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.inventoryBucketId").value(41))
                .andExpect(jsonPath("$.data.sharedOnlineAllowed").value(true))
                .andExpect(jsonPath("$.data.pools.onlineHold").value(3));

        ArgumentCaptor<InventoryBucketCreateCommand> command =
                ArgumentCaptor.forClass(InventoryBucketCreateCommand.class);
        then(commandService).should().create(eq(7L), eq(3L), any(), command.capture());
        assertThat(command.getValue().sharedOnlineAllowed()).isFalse();
    }

    @Test
    void missingEndDateIsRejectedBeforeCreateService() throws Exception {
        authenticate();

        mockMvc.perform(post(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson().replace(
                                "\"endDate\":\"2026-08-10\",", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    void missingPoolQuantityIsRejectedBeforeCreateService() throws Exception {
        authenticate();

        mockMvc.perform(post(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson().replace(
                                "\"onsite\":1,\"shared\":1",
                                "\"onsite\":1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    void missingSharedOnlinePolicyIsRejectedBeforeCreateService() throws Exception {
        authenticate();

        mockMvc.perform(post(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson().replace(
                                "\"sharedOnlineAllowed\":false,", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    void secondPrecisionInventoryTimeIsRejectedBeforeCreateService() throws Exception {
        authenticate();

        mockMvc.perform(post(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson().replace("12:00", "12:00:30")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    void updatesInventoryPolicy() throws Exception {
        authenticate();
        given(commandService.update(eq(7L), eq(3L), eq(41L), any(), any()))
                .willReturn(new MenuInventoryCommandResult(200, view()));

        mockMvc.perform(patch(URL + "/41")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"totalSupply":5,
                                 "pools":{"onlineHold":3,"onsite":1,"shared":1},
                                 "sharedOnlineAllowed":false,
                                 "availabilityStatus":"AVAILABLE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policyVersion").value(2));

        ArgumentCaptor<InventoryPolicyChange> change =
                ArgumentCaptor.forClass(InventoryPolicyChange.class);
        then(commandService).should().update(
                eq(7L), eq(3L), eq(41L), any(), change.capture());
        assertThat(change.getValue().sharedOnlineAllowed()).isFalse();
    }

    private void authenticate() {
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 7L));
    }

    private static InventoryBucketView view() {
        return new InventoryBucketView(
                41L, 11L, LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                2L, 5, 3, 3, 1, 1, 1, 1, true, 4,
                InventoryAvailabilityStatus.AVAILABLE);
    }

    private static String createJson() {
        return """
                {"menuId":11,"serviceDate":"2026-08-10",
                 "startTime":"12:00","endDate":"2026-08-10","endTime":"13:00",
                 "totalSupply":5,
                 "pools":{"onlineHold":3,"onsite":1,"shared":1},
                 "sharedOnlineAllowed":false,
                 "availabilityStatus":"AVAILABLE"}
                """;
    }
}
