package com.miriyum.domain.menu.controller.storeoperator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.menu.dto.storeoperator.RepresentativeMenuItemResponse;
import com.miriyum.domain.menu.dto.storeoperator.RepresentativeMenuSettingResponse;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus;
import com.miriyum.domain.menu.service.RepresentativeMenuCommandResult;
import com.miriyum.domain.menu.service.RepresentativeMenuService;
import com.miriyum.domain.store.config.StoreManagementSecurityConfig;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.GlobalExceptionHandler;
import com.miriyum.global.exception.ServiceException;
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

@WebMvcTest(RepresentativeMenuController.class)
@Import({StoreManagementSecurityConfig.class, GlobalExceptionHandler.class})
class RepresentativeMenuControllerTest {

    private static final String PATH =
            "/api/v1/store-operators/stores/7/representative-menus";
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RepresentativeMenuService service;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void authenticate() {
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, 11L));
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, 22L));
    }

    @Test
    void getsCurrentSettingWithoutIdempotencyKey() throws Exception {
        given(service.get(11L, 7L)).willReturn(setting());

        mockMvc.perform(get(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.version").value(4))
                .andExpect(jsonPath("$.data.items[0].menuId").value("13"))
                .andExpect(jsonPath("$.data.items[0].sellingStatus").value("SOLD_OUT"));
    }

    @Test
    void replacesOrderedSettingWithIdempotencyKey() throws Exception {
        given(service.replace(eq(11L), eq(7L), any(IdempotencyKey.class), any()))
                .willReturn(new RepresentativeMenuCommandResult(200, setting()));

        mockMvc.perform(put(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":3,"menuIds":["13","11","12"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].menuId").value("13"));
    }

    @Test
    void rejectsUnauthenticatedAndWrongAudienceTokens() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).get(any(Long.class), any(Long.class));
    }

    @Test
    void rejectsInvalidSizeDuplicateAndNonCanonicalIds() throws Exception {
        assertBadRequest("{\"expectedVersion\":0,\"menuIds\":[\"1\",\"2\"]}");
        assertBadRequest("""
                {"expectedVersion":0,"menuIds":["1","2","3","4","5","6"]}
                """);
        assertBadRequest("""
                {"expectedVersion":0,"menuIds":["1","1","2"]}
                """);
        assertBadRequest("""
                {"expectedVersion":0,"menuIds":["01","2","3"]}
                """);
        verify(service, never()).replace(any(Long.class), any(Long.class), any(), any());
    }

    @Test
    void requiresIdempotencyKeyForReplacement() throws Exception {
        mockMvc.perform(put(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }

    @Test
    void mapsOwnershipMissingMenuAndVersionConflicts() throws Exception {
        assertServiceError(StoreErrorCode.ACCESS_DENIED, 403, "STORE_003");
        assertServiceError(StoreErrorCode.MENU_NOT_FOUND, 404, "STORE_009");
        assertServiceError(CommonErrorCode.CONCURRENT_MODIFICATION, 409, "COMMON_008");
    }

    private void assertBadRequest(String body) throws Exception {
        mockMvc.perform(put(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    private void assertServiceError(
            com.miriyum.global.exception.ErrorCode errorCode,
            int status,
            String code
    ) throws Exception {
        given(service.replace(eq(11L), eq(7L), any(), any()))
                .willThrow(new ServiceException(errorCode));
        mockMvc.perform(put(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    private RepresentativeMenuSettingResponse setting() {
        return new RepresentativeMenuSettingResponse(
                4L,
                RepresentativeMenuSettingStatus.CONFIGURED,
                List.of(
                        new RepresentativeMenuItemResponse(
                                "13", 1, 2, "latte", 6_000,
                                MenuSellingStatus.SOLD_OUT),
                        new RepresentativeMenuItemResponse(
                                "11", 2, 1, "americano", 5_000,
                                MenuSellingStatus.SELLING),
                        new RepresentativeMenuItemResponse(
                                "12", 3, 1, "tea", 4_000,
                                MenuSellingStatus.SELLING)));
    }

    private String validBody() {
        return "{\"expectedVersion\":3,\"menuIds\":[\"13\",\"11\",\"12\"]}";
    }
}
