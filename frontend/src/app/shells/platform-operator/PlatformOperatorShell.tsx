import { PLATFORM_OPERATOR_PATHS } from '../../routes/paths/platformOperatorPaths'
import { Route, Routes } from 'react-router'
import { NotFoundPage } from '../../NotFoundPage'
import { PlatformOperatorInitialPasswordPage } from '../../../domains/account/platform-operator/auth/ui/PlatformOperatorInitialPasswordPage'
import { PlatformOperatorSignInPage } from '../../../domains/account/platform-operator/auth/ui/PlatformOperatorSignInPage'
import { PlatformOperatorAuthProvider } from './PlatformOperatorAuthProvider'
import { RequirePlatformOperatorAuth } from './RequirePlatformOperatorAuth'
import { AuditDetailPage } from '../../../domains/platform-operation/platform-operator/ui/AuditDetailPage'
import { MemberSanctionApprovalPage } from '../../../domains/platform-operation/platform-operator/ui/MemberSanctionApprovalPage'
import { StoreDetailPage } from '../../../domains/platform-operation/platform-operator/ui/StoreDetailPage'
import { StoreListPage } from '../../../domains/platform-operation/platform-operator/ui/StoreListPage'
import { StoreSanctionCasePage } from '../../../domains/platform-operation/platform-operator/ui/StoreSanctionCasePage'
import { OperatorCreatePage } from '../../../domains/platform-operation/platform-operator/ui/OperatorCreatePage'
import { OperatorDetailPage } from '../../../domains/platform-operation/platform-operator/ui/OperatorDetailPage'
import { OperatorListPage } from '../../../domains/platform-operation/platform-operator/ui/OperatorListPage'
import { AuditSearchPage } from '../../../domains/platform-operation/platform-operator/ui/AuditSearchPage'
import { ConsoleHomePage } from './ConsoleHomePage'
import { ConsoleLayout } from './ConsoleLayout'
import { MemberDetailPage } from '../../../domains/platform-operation/platform-operator/ui/MemberDetailPage'
import { MemberListPage } from '../../../domains/platform-operation/platform-operator/ui/MemberListPage'
import { SupportCaseDetailPage } from '../../../domains/platform-operation/platform-operator/ui/SupportCaseDetailPage'
import { SupportCaseListPage } from '../../../domains/platform-operation/platform-operator/ui/SupportCaseListPage'
import { PaymentRecoveryCaseListPage } from '../../../domains/platform-operation/platform-operator/ui/PaymentRecoveryCaseListPage'
import { PaymentRecoveryCaseDetailPage } from '../../../domains/platform-operation/platform-operator/ui/PaymentRecoveryCaseDetailPage'

/**
 * 운영 콘솔의 route 묶음.
 *
 * 이 파일이 lazy chunk의 진입점이다. `App.tsx`가 flag가 켜졌을 때만 이 모듈을
 * 동적으로 불러오므로, 꺼진 빌드에는 콘솔 코드와 그 아래 API client import
 * graph가 산출물에 남지 않는다.
 *
 * provider도 여기 있다. 앱 최상단에 두면 flag와 무관하게 항상 로드되고,
 * 일반 사용자 화면에서도 운영자 세션 복구 요청이 나간다.
 *
 * 계약이 있는 화면만 등록한다. 대시보드·입점 심사·매장·예약·웨이팅·결제
 * 복구는 route를 만들지 않았다. 없는 화면으로 가는 링크도 두지 않는다.
 */
export default function PlatformOperatorConsole() {
  return (
    <PlatformOperatorAuthProvider>
      <Routes>
        <Route
          path={relative(PLATFORM_OPERATOR_PATHS.signIn)}
          element={<PlatformOperatorSignInPage />}
        />
        <Route
          path={relative(PLATFORM_OPERATOR_PATHS.initialPassword)}
          element={<PlatformOperatorInitialPasswordPage />}
        />

        <Route element={<RequirePlatformOperatorAuth />}>
          <Route element={<ConsoleLayout />}>
            <Route
              path={relative(PLATFORM_OPERATOR_PATHS.paymentRecoveryCases)}
              element={<PaymentRecoveryCaseListPage />}
            />
            <Route
              path={relative(PLATFORM_OPERATOR_PATHS.paymentRecoveryDetail)}
              element={<PaymentRecoveryCaseDetailPage />}
            />
            <Route
              path={relative(PLATFORM_OPERATOR_PATHS.members)}
              element={<MemberListPage />}
            />
            <Route
              path={relative(PLATFORM_OPERATOR_PATHS.memberDetail)}
              element={<MemberDetailPage />}
            />
            <Route
              path={relative(PLATFORM_OPERATOR_PATHS.supportCases)}
              element={<SupportCaseListPage />}
            />
            <Route
              path={relative(PLATFORM_OPERATOR_PATHS.supportCaseDetail)}
              element={<SupportCaseDetailPage />}
            />
            {/*
              생성 route를 상세보다 먼저 둔다. `/operators/new`가
              `/operators/:operatorId`에도 맞으므로 순서가 뒤바뀌면
              "new"를 운영자 ID로 조회한다.
            */}
            <Route
              path={relative(PLATFORM_OPERATOR_PATHS.operators)}
              element={<OperatorListPage />}
            />
            <Route
              path={relative(PLATFORM_OPERATOR_PATHS.operatorCreate)}
              element={<OperatorCreatePage />}
            />
            <Route
              path={relative(PLATFORM_OPERATOR_PATHS.operatorDetail)}
              element={<OperatorDetailPage />}
            />
            <Route
              path={relative(PLATFORM_OPERATOR_PATHS.stores)}
              element={<StoreListPage />}
            />
            <Route
              path={relative(PLATFORM_OPERATOR_PATHS.storeDetail)}
              element={<StoreDetailPage />}
            />
            <Route
              path={relative(PLATFORM_OPERATOR_PATHS.storeSanctionCase)}
              element={<StoreSanctionCasePage />}
            />
            <Route
              path={relative(PLATFORM_OPERATOR_PATHS.memberSanctionApproval)}
              element={<MemberSanctionApprovalPage />}
            />
            <Route
              path={relative(PLATFORM_OPERATOR_PATHS.audit)}
              element={<AuditSearchPage />}
            />
            <Route
              path={relative(PLATFORM_OPERATOR_PATHS.auditDetail)}
              element={<AuditDetailPage />}
            />
            {/*
              진입점에서 업무 화면으로 자동 이동하지 않는다. 이동하면 콘솔에
              들어오는 것만으로 그 화면의 조회가 나가고, 권한 없는 운영자에게는
              403과 감사 기록이 남는다. 중립 화면에서 운영자가 직접 고른다.
            */}
            <Route index element={<ConsoleHomePage />} />
          </Route>
        </Route>

        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </PlatformOperatorAuthProvider>
  )
}

/**
 * `/admin/...` 전체 경로를 이 묶음 안의 상대 경로로 바꾼다.
 *
 * 절대 경로를 그대로 쓰면 부모 route가 이미 `/admin/*`를 소비한 뒤라 매칭되지
 * 않는다. 경로 문자열을 손으로 두 벌 적지 않으려고 `routes.ts`의 값을 자른다.
 */
function relative(fullPath: string): string {
  return fullPath.replace(/^\/admin\/?/, '')
}
