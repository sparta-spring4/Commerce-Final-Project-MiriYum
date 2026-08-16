package com.miriyum.domain.platformoperator.adminstore.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.miriyum.domain.platformoperator.adminstore.service.*;
import com.miriyum.domain.platformoperator.controller.management.PlatformOperatorStoreController;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
@Tag("integration-shard-a")
class AdminStoreHttpIT {
    @Test
    void replayedCaseCreationUsesStoredStatusAndPayload() throws Exception {
        StoreSanctionCaseService cases=mock(StoreSanctionCaseService.class);
        given(cases.create(any(),any(),eq(10L),any())).willReturn(new IdempotentOutcome(true,201,"SUCCESS",
                "STORE_SANCTION_CASE","case-1",new ObjectMapper().readTree("{\"caseId\":\"case-1\",\"storeId\":10}")));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new PlatformOperatorStoreController(mock(AdminStoreQueryService.class),
                cases,mock(StoreSanctionImpactService.class),mock(StoreSanctionCommandService.class)))
                .setCustomArgumentResolvers(new PrincipalResolver()).build();

        mvc.perform(post("/api/v1/platform-operators/stores/10/sanction-cases")
                        .header("Idempotency-Key","123e4567-e89b-12d3-a456-426614174000")
                        .header("X-Admin-Reason-Code","STORE_ENFORCEMENT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"violationType\":\"FRAUD\",\"evidenceReferences\":[\"evidence://1\"],\"policyVersion\":\"ADMIN-007-v1\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.caseId").value("case-1"))
                .andExpect(jsonPath("$.data.storeId").value(10));
    }
    private static final class PrincipalResolver implements HandlerMethodArgumentResolver {
        public boolean supportsParameter(MethodParameter parameter){return parameter.getParameterType()==PlatformOperatorPrincipal.class;}
        public Object resolveArgument(MethodParameter p,ModelAndViewContainer m,NativeWebRequest r,
                org.springframework.web.bind.support.WebDataBinderFactory b){return new PlatformOperatorPrincipal(17L,"a@b.com","sid",1,1,false);}
    }
}
