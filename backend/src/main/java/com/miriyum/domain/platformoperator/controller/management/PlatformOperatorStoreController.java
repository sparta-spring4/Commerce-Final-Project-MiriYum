package com.miriyum.domain.platformoperator.controller.management;

import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreRequests.*;
import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreResponses.*;
import com.miriyum.domain.platformoperator.adminstore.service.*;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.domain.platformoperator.enums.PlatformOperatorAuditReason;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.idempotency.RequestFingerprint;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/platform-operators/stores")
@ConditionalOnProperty(prefix="miriyum.platform-operator",name="enabled",havingValue="true")
public class PlatformOperatorStoreController {
 private final AdminStoreQueryService queries; private final StoreSanctionCaseService cases;
 private final StoreSanctionImpactService impacts; private final StoreSanctionCommandService sanctions;
 public PlatformOperatorStoreController(AdminStoreQueryService queries,StoreSanctionCaseService cases,
  StoreSanctionImpactService impacts,StoreSanctionCommandService sanctions){this.queries=queries;this.cases=cases;this.impacts=impacts;this.sanctions=sanctions;}

 @GetMapping public ApiResponse<StorePage> search(@AuthenticationPrincipal PlatformOperatorPrincipal p,
  @RequestHeader("X-Admin-Reason-Code") PlatformOperatorAuditReason reason,@RequestParam(required=false)String keyword,
  @RequestParam(required=false)OperationStatus operationStatus,@RequestParam(defaultValue="0")int page,
  @RequestParam(defaultValue="20")int size,@RequestHeader(value="X-Correlation-Id",required=false)String correlation){return ApiResponse.success("매장을 조회했습니다.",queries.search(p,keyword,operationStatus,page,size,reason,auditCorrelation(correlation)));}

 @GetMapping("/{storeId}") public ApiResponse<StoreDetail> get(@AuthenticationPrincipal PlatformOperatorPrincipal p,
  @PathVariable long storeId,@RequestHeader("X-Admin-Reason-Code")PlatformOperatorAuditReason reason,
  @RequestHeader(value="X-Correlation-Id",required=false)String correlation){return ApiResponse.success("매장을 조회했습니다.",queries.get(p,storeId,reason,auditCorrelation(correlation)));}

 @PostMapping("/{storeId}/sanction-cases") @ResponseStatus(HttpStatus.CREATED)
 public ResponseEntity<ApiResponse<JsonNode>> createCase(@AuthenticationPrincipal PlatformOperatorPrincipal p,@PathVariable long storeId,
  @RequestHeader("Idempotency-Key")String key,@RequestHeader("X-Admin-Reason-Code")PlatformOperatorAuditReason reason,
  @RequestHeader(value="X-Correlation-Id",required=false)String correlation,@Valid @RequestBody CaseCreate request){IdempotencyKey parsed=IdempotencyKey.parse(key);return response(cases.create(command(p,"STORE_CASE_CREATE",parsed,
          "store="+storeId+"|reason="+reason+"|violation="+request.violationType()+"|evidence="+request.evidenceReferences().stream().sorted().toList()+"|policy="+request.policyVersion()),p,storeId,request,reason,correlation(parsed.value(),correlation)),"제재 사건을 생성했습니다.");}

 @PostMapping("/{storeId}/sanction-cases/{caseId}/assignments")
 public ResponseEntity<ApiResponse<JsonNode>> assign(@AuthenticationPrincipal PlatformOperatorPrincipal p,@PathVariable long storeId,
  @PathVariable String caseId,@RequestHeader("Idempotency-Key")String key,
  @RequestHeader("X-Admin-Reason-Code")PlatformOperatorAuditReason reason,@RequestHeader(value="X-Correlation-Id",required=false)String correlation,
  @Valid @RequestBody CaseAssignment request){IdempotencyKey parsed=IdempotencyKey.parse(key);return response(cases.assign(command(p,"STORE_CASE_ASSIGN",parsed,
          "store="+storeId+"|case="+caseId+"|reason="+reason+"|version="+request.expectedCaseVersion()),p,storeId,caseId,request.expectedCaseVersion(),reason,correlation(parsed.value(),correlation)),"제재 사건을 배정했습니다.");}

 @GetMapping("/{storeId}/sanction-cases/{caseId}")
 public ApiResponse<CaseDetail> caseDetail(@AuthenticationPrincipal PlatformOperatorPrincipal p,@PathVariable long storeId,
  @PathVariable String caseId,@RequestHeader("X-Admin-Case-Id")String headerCaseId,
  @RequestHeader("X-Admin-Case-Version")long version,@RequestHeader("X-Admin-Reason-Code")PlatformOperatorAuditReason reason,
  @RequestHeader(value="X-Correlation-Id",required=false)String correlation){sameCase(caseId,headerCaseId);return ApiResponse.success("제재 사건을 조회했습니다.",queries.caseDetail(p,storeId,caseId,version,reason,auditCorrelation(correlation)));}

 @PostMapping("/{storeId}/sanction-cases/{caseId}/impact-previews") @ResponseStatus(HttpStatus.CREATED)
 public ApiResponse<ImpactPreviewData> preview(@AuthenticationPrincipal PlatformOperatorPrincipal p,@PathVariable long storeId,
  @PathVariable String caseId,@RequestHeader("X-Admin-Case-Id")String headerCaseId,
  @RequestHeader("X-Admin-Case-Version")long version,@RequestHeader("X-Admin-Reason-Code")PlatformOperatorAuditReason reason,
  @RequestHeader(value="X-Correlation-Id",required=false)String correlation,
  @Valid @RequestBody SanctionShape request){sameCase(caseId,headerCaseId);return ApiResponse.success("거래 영향을 확인했습니다.",impacts.create(p,storeId,caseId,version,request,reason,auditCorrelation(correlation)));}

 @PostMapping("/{storeId}/sanction-cases/{caseId}/sanctions") @ResponseStatus(HttpStatus.CREATED)
 public ResponseEntity<ApiResponse<JsonNode>> createSanction(@AuthenticationPrincipal PlatformOperatorPrincipal p,@PathVariable long storeId,
  @PathVariable String caseId,@RequestHeader("X-Admin-Case-Id")String headerCaseId,
  @RequestHeader("X-Admin-Case-Version")long version,@RequestHeader("X-Admin-Reason-Code")PlatformOperatorAuditReason reason,
  @RequestHeader("Idempotency-Key")String key,@RequestHeader(value="X-Correlation-Id",required=false)String correlation,
  @Valid @RequestBody SanctionCreate request){sameCase(caseId,headerCaseId);IdempotencyKey parsed=IdempotencyKey.parse(key);return response(sanctions.create(command(p,"STORE_SANCTION_CREATE",parsed,
          "store="+storeId+"|case="+caseId+"|version="+version+"|reason="+reason+"|request="+canonical(request)),p,storeId,caseId,version,request,reason,correlation(parsed.value(),correlation)),"매장 제재를 생성했습니다.");}

 @PostMapping("/{storeId}/sanction-cases/{caseId}/sanctions/{sanctionId}/approvals")
 public ResponseEntity<ApiResponse<JsonNode>> approve(@AuthenticationPrincipal PlatformOperatorPrincipal p,@PathVariable long storeId,
  @PathVariable String caseId,@PathVariable long sanctionId,@RequestHeader("X-Admin-Case-Id")String headerCaseId,
  @RequestHeader("X-Admin-Case-Version")long version,@RequestHeader("X-Admin-Reason-Code")PlatformOperatorAuditReason reason,
  @RequestHeader("Idempotency-Key")String key,@RequestHeader("X-Admin-Reauthentication")String approval,
  @RequestHeader(value="X-Correlation-Id",required=false)String correlation,@Valid @RequestBody SanctionApproval request){sameCase(caseId,headerCaseId);IdempotencyKey parsed=IdempotencyKey.parse(key);return response(sanctions.approve(command(p,"STORE_SANCTION_APPROVE",parsed,
          "store="+storeId+"|case="+caseId+"|version="+version+"|reason="+reason+"|sanction="+sanctionId+"|request="+canonical(request)),p,storeId,caseId,version,sanctionId,request,approval,reason,correlation(parsed.value(),correlation)),"매장 제재를 승인했습니다.");}

 @PostMapping("/{storeId}/sanction-cases/{caseId}/sanctions/{sanctionId}/releases")
 public ResponseEntity<ApiResponse<JsonNode>> release(@AuthenticationPrincipal PlatformOperatorPrincipal p,@PathVariable long storeId,
  @PathVariable String caseId,@PathVariable long sanctionId,@RequestHeader("X-Admin-Case-Id")String headerCaseId,
  @RequestHeader("X-Admin-Case-Version")long version,@RequestHeader("X-Admin-Reason-Code")PlatformOperatorAuditReason reason,
  @RequestHeader("Idempotency-Key")String key,@RequestHeader("X-Admin-Reauthentication")String approval,
  @RequestHeader(value="X-Correlation-Id",required=false)String correlation,@Valid @RequestBody SanctionRelease request){sameCase(caseId,headerCaseId);IdempotencyKey parsed=IdempotencyKey.parse(key);return response(sanctions.release(command(p,"STORE_SANCTION_RELEASE",parsed,
          "store="+storeId+"|case="+caseId+"|version="+version+"|reason="+reason+"|sanction="+sanctionId+"|request="+canonical(request)),p,storeId,caseId,version,sanctionId,request,approval,reason,correlation(parsed.value(),correlation)),"매장 제재를 해제했습니다.");}

 private static void sameCase(String path,String header){if(!path.equals(header))throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);}
 private static String correlation(String key,String value){return value==null||value.isBlank()?"platform-operator:"+key:value;}
 private static String auditCorrelation(String value){return value==null||value.isBlank()?"platform-operator:"+java.util.UUID.randomUUID():value;}
 private static IdempotencyCommand command(PlatformOperatorPrincipal p,String type,IdempotencyKey key,String input){return new IdempotencyCommand(
         "platform-operator",p.accountId(),type,key.value(),RequestFingerprint.of(input));}
 private static ResponseEntity<ApiResponse<JsonNode>> response(IdempotentOutcome value,String message){return ResponseEntity.status(value.httpStatus())
         .body(ApiResponse.success(message,value.data()));}
 private static String canonical(SanctionCreate r){return r.type()+"|"+r.restrictedFeatures().stream().map(Enum::name).sorted().toList()
         +"|"+r.startsAt()+"|"+r.endsAt()+"|"+r.reason()+"|"+canonical(r.impactConfirmation());}
 private static String canonical(SanctionApproval r){return r.expectedSanctionVersion()+"|"+canonical(r.impactConfirmation())+"|"+r.note();}
 private static String canonical(SanctionRelease r){return r.expectedSanctionVersion()+"|"+r.reason();}
 private static String canonical(ImpactConfirmation c){return c==null?"null":c.previewId()+"|"+c.previewDigest()+"|"+c.caseVersion()+"|"+c.storeEnforcementVersion();}
}
