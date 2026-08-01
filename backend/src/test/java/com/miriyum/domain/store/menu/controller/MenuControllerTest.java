package com.miriyum.domain.store.menu.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.store.core.config.StoreManagementSecurityConfig;
import com.miriyum.domain.store.menu.dto.ManagedMenuResponse;
import com.miriyum.domain.store.menu.dto.MenuVersionResponse;
import com.miriyum.domain.store.menu.enums.MenuSellingStatus;
import com.miriyum.domain.store.menu.enums.MenuVersionStatus;
import com.miriyum.domain.store.menu.enums.MenuVisibility;
import com.miriyum.domain.store.menu.model.AllergenDisclosure;
import com.miriyum.domain.store.menu.model.AllergenDisclosureStatus;
import com.miriyum.domain.store.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.store.menu.model.OriginDisclosure;
import com.miriyum.domain.store.menu.service.MenuCommandResult;
import com.miriyum.domain.store.menu.service.MenuCommandService;
import com.miriyum.domain.store.menu.service.MenuQueryService;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.idempotency.IdempotencyKey;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MenuController.class)
@Import({StoreManagementSecurityConfig.class, GlobalExceptionHandler.class})
class MenuControllerTest {

    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MenuCommandService commandService;

    @MockitoBean
    private MenuQueryService queryService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void authenticate() {
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 11L));
    }

    @Test
    void createSavesDraftAndReturnsCreated() throws Exception {
        given(commandService.create(eq(11L), eq(7L), any(IdempotencyKey.class), any()))
                .willReturn(new MenuCommandResult(201, menu()));

        mockMvc.perform(post("/api/v1/store-operator/stores/7/menus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contentJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.menuId").value(21))
                .andExpect(jsonPath("$.data.draft.versionNumber").value(1))
                .andExpect(jsonPath("$.data.visibility").value("HIDDEN"))
                .andExpect(jsonPath("$.data.sellingStatus").value("PAUSED"));
    }

    @Test
    void putIsIndependentOptionalUpdateEndpoint() throws Exception {
        given(commandService.update(eq(11L), eq(7L), eq(21L), any(), any()))
                .willReturn(new MenuCommandResult(200, menu()));

        mockMvc.perform(put("/api/v1/store-operator/stores/7/menus/21")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contentJson()))
                .andExpect(status().isOk());
    }

    @Test
    void publicationAndControlsHaveSeparateRoutes() throws Exception {
        given(commandService.publish(eq(11L), eq(7L), eq(21L), any(), any()))
                .willReturn(new MenuCommandResult(200, menu()));
        given(commandService.cancelPublication(eq(11L), eq(7L), eq(21L), any(), any()))
                .willReturn(new MenuCommandResult(200, menu()));
        given(commandService.changeVisibility(eq(11L), eq(7L), eq(21L), any(), any()))
                .willReturn(new MenuCommandResult(200, menu()));
        given(commandService.changeSellingStatus(eq(11L), eq(7L), eq(21L), any(), any()))
                .willReturn(new MenuCommandResult(200, menu()));
        given(commandService.retire(eq(11L), eq(7L), eq(21L), any(), any()))
                .willReturn(new MenuCommandResult(200, menu()));

        perform(post("/api/v1/store-operator/stores/7/menus/21/publication"),
                "{\"mode\":\"IMMEDIATE\",\"changeReason\":\"가격 확정\"}")
                .andExpect(status().isOk());
        perform(post("/api/v1/store-operator/stores/7/menus/21/publication-cancellation"),
                "{\"changeReason\":\"게시 일정 변경\"}")
                .andExpect(status().isOk());
        perform(patch("/api/v1/store-operator/stores/7/menus/21/visibility"),
                "{\"visibility\":\"VISIBLE\",\"changeReason\":\"메뉴 공개\"}")
                .andExpect(status().isOk());
        perform(patch("/api/v1/store-operator/stores/7/menus/21/selling-status"),
                "{\"sellingStatus\":\"SELLING\",\"changeReason\":\"판매 재개\"}")
                .andExpect(status().isOk());
        perform(post("/api/v1/store-operator/stores/7/menus/21/retirement"),
                "{\"changeReason\":\"메뉴 종료\"}")
                .andExpect(status().isOk());
    }

    @Test
    void publicationWithoutChangeReasonReturnsBadRequest() throws Exception {
        perform(post("/api/v1/store-operator/stores/7/menus/21/publication"),
                "{\"mode\":\"IMMEDIATE\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    void soldOutIsAcceptedAsIndependentSellingStatus() throws Exception {
        given(commandService.changeSellingStatus(eq(11L), eq(7L), eq(21L), any(), any()))
                .willReturn(new MenuCommandResult(200, menu()));

        perform(patch("/api/v1/store-operator/stores/7/menus/21/selling-status"),
                "{\"sellingStatus\":\"SOLD_OUT\",\"changeReason\":\"당일 소진\"}")
                .andExpect(status().isOk());
    }

    @Test
    void managementReadsDoNotRequireIdempotencyKey() throws Exception {
        given(queryService.list(11L, 7L)).willReturn(List.of(menu()));
        given(queryService.get(11L, 7L, 21L)).willReturn(menu());

        mockMvc.perform(get("/api/v1/store-operator/stores/7/menus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].menuId").value(21));
        mockMvc.perform(get("/api/v1/store-operator/stores/7/menus/21")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.menuId").value(21));
    }

    @Test
    void writeWithoutIdempotencyKeyReturnsCommon003() throws Exception {
        mockMvc.perform(post("/api/v1/store-operator/stores/7/menus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contentJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }

    private org.springframework.test.web.servlet.ResultActions perform(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String content
    ) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                .header("Idempotency-Key", KEY);
        if (content != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(content);
        }
        return mockMvc.perform(request);
    }

    private String contentJson() {
        return """
                {
                  "name": "Americano",
                  "description": "",
                  "price": 5000,
                  "representative": true,
                  "primaryCategoryCode": "COFFEE",
                  "secondaryCategoryCodes": [],
                  "localTags": ["signature"],
                  "holdSelectionAllowed": true,
                  "pickupSelectionAllowed": true,
                  "allergenInformationStatus": "REGISTERED",
                  "allergenDisclosures": [
                    {"ingredient": "우유", "status": "CONTAINS"}
                  ],
                  "originInformationStatus": "REGISTERED",
                  "originDisclosures": [
                    {"ingredient": "원두", "origin": "콜롬비아"}
                  ],
                  "alcoholic": false
                }
                """;
    }

    private ManagedMenuResponse menu() {
        return new ManagedMenuResponse(
                21L, 7L, MenuVisibility.HIDDEN, MenuSellingStatus.PAUSED, false,
                new MenuVersionResponse(
                        1, MenuVersionStatus.DRAFT, "Americano", "", 5_000,
                        true, "COFFEE", List.of(), List.of("signature"),
                        true, true,
                        DisclosureRegistrationStatus.REGISTERED,
                        List.of(new AllergenDisclosure(
                                "우유", AllergenDisclosureStatus.CONTAINS)),
                        DisclosureRegistrationStatus.REGISTERED,
                        List.of(new OriginDisclosure("원두", "콜롬비아")),
                        false,
                        null),
                null, null);
    }
}
