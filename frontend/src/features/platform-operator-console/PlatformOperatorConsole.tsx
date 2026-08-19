import { Route, Routes } from 'react-router'
import { ROUTES } from '../../app/routes'
import { NotFoundPage } from '../../app/NotFoundPage'
import {
  PlatformOperatorAuthProvider,
  PlatformOperatorInitialPasswordPage,
  PlatformOperatorSignInPage,
  RequirePlatformOperatorAuth,
} from '../platform-operator-auth'
import { AuditDetailPage } from './ui/AuditDetailPage'
import { MemberSanctionApprovalPage } from './ui/MemberSanctionApprovalPage'
import { StoreDetailPage } from './ui/StoreDetailPage'
import { StoreListPage } from './ui/StoreListPage'
import { StoreSanctionCasePage } from './ui/StoreSanctionCasePage'
import { OperatorCreatePage } from './ui/OperatorCreatePage'
import { OperatorDetailPage } from './ui/OperatorDetailPage'
import { OperatorListPage } from './ui/OperatorListPage'
import { AuditSearchPage } from './ui/AuditSearchPage'
import { ConsoleHomePage } from './ui/ConsoleHomePage'
import { ConsoleLayout } from './ui/ConsoleLayout'
import { MemberDetailPage } from './ui/MemberDetailPage'
import { MemberListPage } from './ui/MemberListPage'
import { SupportCaseDetailPage } from './ui/SupportCaseDetailPage'
import { SupportCaseListPage } from './ui/SupportCaseListPage'

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
          path={relative(ROUTES.platformOperatorSignIn)}
          element={<PlatformOperatorSignInPage />}
        />
        <Route
          path={relative(ROUTES.platformOperatorInitialPassword)}
          element={<PlatformOperatorInitialPasswordPage />}
        />

        <Route element={<RequirePlatformOperatorAuth />}>
          <Route element={<ConsoleLayout />}>
            <Route
              path={relative(ROUTES.platformOperatorMembers)}
              element={<MemberListPage />}
            />
            <Route
              path={relative(ROUTES.platformOperatorMemberDetail)}
              element={<MemberDetailPage />}
            />
            <Route
              path={relative(ROUTES.platformOperatorSupportCases)}
              element={<SupportCaseListPage />}
            />
            <Route
              path={relative(ROUTES.platformOperatorSupportCaseDetail)}
              element={<SupportCaseDetailPage />}
            />
            {/*
              생성 route를 상세보다 먼저 둔다. `/operators/new`가
              `/operators/:operatorId`에도 맞으므로 순서가 뒤바뀌면
              "new"를 운영자 ID로 조회한다.
            */}
            <Route
              path={relative(ROUTES.platformOperatorOperators)}
              element={<OperatorListPage />}
            />
            <Route
              path={relative(ROUTES.platformOperatorCreate)}
              element={<OperatorCreatePage />}
            />
            <Route
              path={relative(ROUTES.platformOperatorDetail)}
              element={<OperatorDetailPage />}
            />
            <Route
              path={relative(ROUTES.platformOperatorStores)}
              element={<StoreListPage />}
            />
            <Route
              path={relative(ROUTES.platformOperatorStoreDetail)}
              element={<StoreDetailPage />}
            />
            <Route
              path={relative(ROUTES.platformOperatorStoreSanctionCase)}
              element={<StoreSanctionCasePage />}
            />
            <Route
              path={relative(ROUTES.platformOperatorMemberSanctionApproval)}
              element={<MemberSanctionApprovalPage />}
            />
            <Route
              path={relative(ROUTES.platformOperatorAudit)}
              element={<AuditSearchPage />}
            />
            <Route
              path={relative(ROUTES.platformOperatorAuditDetail)}
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
