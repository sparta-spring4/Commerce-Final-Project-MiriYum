import { Suspense, lazy } from 'react'
import { Route } from 'react-router'
import { Loading } from '../../shared/ui/Feedback'

const PlatformOperatorShell =
  import.meta.env.VITE_PLATFORM_OPERATOR_ENABLED === 'true'
    ? lazy(
        () =>
          import(
            '../shells/platform-operator/PlatformOperatorShell'
          ),
      )
    : null

export const platformOperatorRoutes =
  PlatformOperatorShell === null ? null : (
    <Route
      path="/admin/*"
      element={
        <Suspense fallback={<Loading label="운영 콘솔을 여는 중입니다." />}>
          <PlatformOperatorShell />
        </Suspense>
      }
    />
  )
