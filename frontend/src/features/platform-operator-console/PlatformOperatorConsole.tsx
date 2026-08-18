import { Navigate, Route, Routes } from 'react-router'
import { ROUTES } from '../../app/routes'
import { NotFoundPage } from '../../app/NotFoundPage'
import {
  PlatformOperatorAuthProvider,
  PlatformOperatorInitialPasswordPage,
  PlatformOperatorSignInPage,
  RequirePlatformOperatorAuth,
} from '../platform-operator-auth'
import { AuditDetailPage } from './ui/AuditDetailPage'
import { AuditSearchPage } from './ui/AuditSearchPage'
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
 * 계약이 있는 화면만 등록한다. 대시보드·입점 심사·매장·예약·웨이팅·결제 복구와
 * 운영자 목록·권한 관리는 route를 만들지 않았다. 없는 화면으로 가는 링크가
 * 없으므로 자리표시자도 필요 없다.
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
            <Route
              path={relative(ROUTES.platformOperatorAudit)}
              element={<AuditSearchPage />}
            />
            <Route
              path={relative(ROUTES.platformOperatorAuditDetail)}
              element={<AuditDetailPage />}
            />
            {/* 콘솔 진입점은 회원 관리다. 대시보드 계약이 없다. */}
            <Route
              index
              element={
                <Navigate to={ROUTES.platformOperatorMembers} replace />
              }
            />
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
