package com.miriyum.domain.platformoperator.adminstore.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreResponses.StorePage;
import com.miriyum.domain.platformoperator.adminstore.service.*;
import com.miriyum.domain.platformoperator.controller.management.PlatformOperatorStoreController;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import org.springframework.core.MethodParameter;
import org.springframework.web.method.support.*;
import org.springframework.web.context.request.NativeWebRequest;

class PlatformOperatorStoreControllerTest {
 private AdminStoreQueryService queries; private MockMvc mvc;
 @BeforeEach void setUp(){queries=mock(AdminStoreQueryService.class);mvc=MockMvcBuilders.standaloneSetup(new PlatformOperatorStoreController(queries,mock(StoreSanctionCaseService.class),mock(StoreSanctionImpactService.class),mock(StoreSanctionCommandService.class))).setCustomArgumentResolvers(new HandlerMethodArgumentResolver(){public boolean supportsParameter(MethodParameter p){return p.getParameterType()==PlatformOperatorPrincipal.class;}public Object resolveArgument(MethodParameter p,org.springframework.web.method.support.ModelAndViewContainer m,NativeWebRequest r,org.springframework.web.bind.support.WebDataBinderFactory b){return new PlatformOperatorPrincipal(1L,"admin@example.com","sid",1,1,false);}}).build();}
 @Test void platformStoreListUsesDedicatedAdminEndpointAndRequiresReasonHeader() throws Exception {
  when(queries.search(any(),isNull(),isNull(),eq(0),eq(20))).thenReturn(new StorePage(List.of(),0,20,0,0));
  mvc.perform(get("/api/v1/platform-operators/stores").header("X-Admin-Reason-Code","STORE_ENFORCEMENT"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("SUCCESS")).andExpect(jsonPath("$.data.content").isArray());
  mvc.perform(get("/api/v1/platform-operators/stores")).andExpect(status().isBadRequest());
 }
}
