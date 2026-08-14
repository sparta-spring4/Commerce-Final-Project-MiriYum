package com.miriyum.domain.platformoperator.controller.membersupport;

import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.platformoperator.dto.membersupport.PublicMemberSupportRequests.AppealSubmissionRequest;
import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportSubmissionService;
import com.miriyum.global.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnExpression("'${miriyum.platform-operator.enabled:false}' == 'true' and "
        + "'${miriyum.member-support.enabled:false}' == 'true'")
public class MemberAppealController {
    private static final ApiResponse<Void> ACCEPTED = ApiResponse.success("요청을 접수했습니다.", null);
    private final MemberSupportSubmissionService submissions;

    public MemberAppealController(MemberSupportSubmissionService submissions) {
        this.submissions = submissions;
    }

    @PostMapping("/api/v1/consumers/account-sanction-appeals")
    public ResponseEntity<ApiResponse<Void>> submitConsumer(@Valid @RequestBody AppealSubmissionRequest request) {
        submissions.submitAppeal(MemberAccountType.CONSUMER,
                request.sanctionId(), request.contact(), request.statement());
        return ResponseEntity.accepted().body(ACCEPTED);
    }

    @PostMapping("/api/v1/store-operators/account-sanction-appeals")
    public ResponseEntity<ApiResponse<Void>> submitStoreOperator(@Valid @RequestBody AppealSubmissionRequest request) {
        submissions.submitAppeal(MemberAccountType.STORE_OPERATOR,
                request.sanctionId(), request.contact(), request.statement());
        return ResponseEntity.accepted().body(ACCEPTED);
    }
}
