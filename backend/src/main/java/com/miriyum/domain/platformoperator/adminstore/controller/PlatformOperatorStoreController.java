package com.miriyum.domain.platformoperator.adminstore.controller;

import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreRequests.*;
import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreResponses.*;
import com.miriyum.domain.platformoperator.adminstore.service.*;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/platform-operators/stores")
@ConditionalOnProperty(prefix="miriyum.platform-operator",name="enabled",havingValue="true")
public class PlatformOperatorStoreController {
 private final AdminStoreQueryService queries; private final StoreSanctionCaseService cases;
 private final StoreSanctionImpactService impacts; private final StoreSanctionCommandService sanctions;
 public PlatformOperatorStoreController(AdminStoreQueryService queries,StoreSanctionCaseService cases,
  StoreSanctionImpactService impacts,StoreSanctionCommandService sanctions){this.queries=queries;this.cases=cases;this.impacts=impacts;this.sanctions=sanctions;}

 @GetMapping public ApiResponse<StorePage> search(@AuthenticationPrincipal PlatformOperatorPrincipal p,
  @RequestHeader("X-Admin-Reason-Code") String reason,@RequestParam(required=false)String keyword,
  @RequestParam(required=false)OperationStatus operationStatus,@RequestParam(defaultValue="0")int page,
  @RequestParam(defaultValue="20")int size){return ApiResponse.success("매장을 조회했습니다.",queries.search(p,keyword,operationStatus,page,size));}

 @GetMapping("/{storeId}") public ApiResponse<StoreDetail> get(@AuthenticationPrincipal PlatformOperatorPrincipal p,
  @PathVariable long storeId,@RequestHeader("X-Admin-Reason-Code")String reason){return ApiResponse.success("매장을 조회했습니다.",queries.get(p,storeId));}

 @PostMapping("/{storeId}/sanction-cases") @ResponseStatus(HttpStatus.CREATED)
 public ApiResponse<CaseData> createCase(@AuthenticationPrincipal PlatformOperatorPrincipal p,@PathVariable long storeId,
  @RequestHeader("Idempotency-Key")String key,@RequestHeader("X-Admin-Reason-Code")String reason,
  @Valid @RequestBody CaseCreate request){IdempotencyKey.parse(key);return ApiResponse.success("제재 사건을 생성했습니다.",cases.create(p,storeId,request));}

 @PostMapping("/{storeId}/sanction-cases/{caseId}/assignments")
 public ApiResponse<CaseData> assign(@AuthenticationPrincipal PlatformOperatorPrincipal p,@PathVariable long storeId,
  @PathVariable String caseId,@RequestHeader("Idempotency-Key")String key,
  @RequestHeader("X-Admin-Reason-Code")String reason,@Valid @RequestBody CaseAssignment request){IdempotencyKey.parse(key);return ApiResponse.success("제재 사건을 배정했습니다.",cases.assign(p,storeId,caseId,request.expectedCaseVersion()));}

 @GetMapping("/{storeId}/sanction-cases/{caseId}")
 public ApiResponse<CaseDetail> caseDetail(@AuthenticationPrincipal PlatformOperatorPrincipal p,@PathVariable long storeId,
  @PathVariable String caseId,@RequestHeader("X-Admin-Case-Id")String headerCaseId,
  @RequestHeader("X-Admin-Case-Version")long version,@RequestHeader("X-Admin-Reason-Code")String reason){sameCase(caseId,headerCaseId);return ApiResponse.success("제재 사건을 조회했습니다.",queries.caseDetail(p,storeId,caseId,version));}

 @PostMapping("/{storeId}/sanction-cases/{caseId}/impact-previews") @ResponseStatus(HttpStatus.CREATED)
 public ApiResponse<ImpactPreviewData> preview(@AuthenticationPrincipal PlatformOperatorPrincipal p,@PathVariable long storeId,
  @PathVariable String caseId,@RequestHeader("X-Admin-Case-Id")String headerCaseId,
  @RequestHeader("X-Admin-Case-Version")long version,@RequestHeader("X-Admin-Reason-Code")String reason,
  @Valid @RequestBody SanctionShape request){sameCase(caseId,headerCaseId);return ApiResponse.success("거래 영향을 확인했습니다.",impacts.create(p,storeId,caseId,version,request));}

 @PostMapping("/{storeId}/sanction-cases/{caseId}/sanctions") @ResponseStatus(HttpStatus.CREATED)
 public ApiResponse<SanctionData> createSanction(@AuthenticationPrincipal PlatformOperatorPrincipal p,@PathVariable long storeId,
  @PathVariable String caseId,@RequestHeader("X-Admin-Case-Id")String headerCaseId,
  @RequestHeader("X-Admin-Case-Version")long version,@RequestHeader("X-Admin-Reason-Code")String reason,
  @RequestHeader("Idempotency-Key")String key,@RequestHeader(value="X-Correlation-Id",required=false)String correlation,
  @Valid @RequestBody SanctionCreate request){sameCase(caseId,headerCaseId);return ApiResponse.success("매장 제재를 생성했습니다.",sanctions.create(p,storeId,caseId,version,request,IdempotencyKey.parse(key).value(),correlation));}

 @PostMapping("/{storeId}/sanction-cases/{caseId}/sanctions/{sanctionId}/approvals")
 public ApiResponse<SanctionData> approve(@AuthenticationPrincipal PlatformOperatorPrincipal p,@PathVariable long storeId,
  @PathVariable String caseId,@PathVariable long sanctionId,@RequestHeader("X-Admin-Case-Id")String headerCaseId,
  @RequestHeader("X-Admin-Case-Version")long version,@RequestHeader("X-Admin-Reason-Code")String reason,
  @RequestHeader("Idempotency-Key")String key,@RequestHeader("X-Admin-Reauthentication")String approval,
  @RequestHeader(value="X-Correlation-Id",required=false)String correlation,@Valid @RequestBody SanctionApproval request){sameCase(caseId,headerCaseId);String parsed=IdempotencyKey.parse(key).value();return ApiResponse.success("매장 제재를 승인했습니다.",sanctions.approve(p,storeId,caseId,version,sanctionId,request,approval,parsed,correlation(parsed,correlation)));}

 @PostMapping("/{storeId}/sanction-cases/{caseId}/sanctions/{sanctionId}/releases")
 public ApiResponse<SanctionData> release(@AuthenticationPrincipal PlatformOperatorPrincipal p,@PathVariable long storeId,
  @PathVariable String caseId,@PathVariable long sanctionId,@RequestHeader("X-Admin-Case-Id")String headerCaseId,
  @RequestHeader("X-Admin-Case-Version")long version,@RequestHeader("X-Admin-Reason-Code")String reason,
  @RequestHeader("Idempotency-Key")String key,@RequestHeader("X-Admin-Reauthentication")String approval,
  @RequestHeader(value="X-Correlation-Id",required=false)String correlation,@Valid @RequestBody SanctionRelease request){sameCase(caseId,headerCaseId);String parsed=IdempotencyKey.parse(key).value();return ApiResponse.success("매장 제재를 해제했습니다.",sanctions.release(p,storeId,caseId,version,sanctionId,request,approval,parsed,correlation(parsed,correlation)));}

 private static void sameCase(String path,String header){if(!path.equals(header))throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);}
 private static String correlation(String key,String value){return value==null||value.isBlank()?"platform-operator:"+key:value;}
}
