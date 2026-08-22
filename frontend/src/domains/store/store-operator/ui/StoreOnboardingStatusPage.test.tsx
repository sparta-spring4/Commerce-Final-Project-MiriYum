import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fireEvent, screen } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { authenticatedOperator, onboardingApplication } from '../test/handlers'
import { renderOperator } from '../test/renderOperator'
import { StoreOnboardingStatusPage } from './StoreOnboardingStatusPage'

const APPLICATION_PATH =
  '/api/v1/store-operators/onboarding-applications/:applicationId'

function renderStatus(applicationId = '41') {
  return renderOperator(<StoreOnboardingStatusPage />, {
    route: `/store-operator/onboarding-applications/${applicationId}`,
    path: STORE_OPERATOR_PATHS.onboardingApplication,
    probePaths: [STORE_OPERATOR_PATHS.store],
  })
}

describe('입점 신청 상태 화면', () => {
  it('승인 전에는 신청 상태와 다음 할 일을 보여 주고 매장 진입을 막는다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(APPLICATION_PATH, () =>
        successResponse(
          onboardingApplication({
            applicationId: '41',
            status: 'AUTO_CHECKING',
            nextAction: '사업자 정보 자동 확인을 기다려 주세요.',
          }),
        ),
      ),
    )

    renderStatus()

    expect(await screen.findByText('자동 확인 중')).toBeInTheDocument()
    expect(
      screen.getByText('사업자 정보 자동 확인을 기다려 주세요.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '매장 관리로 이동' }),
    ).not.toBeInTheDocument()
  })

  it('승인되어 storeId가 생긴 뒤에만 명시적으로 매장을 선택해 이동한다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(APPLICATION_PATH, () =>
        successResponse(
          onboardingApplication({
            applicationId: '41',
            status: 'AUTO_APPROVED',
            nextAction: '매장 관리를 시작할 수 있습니다.',
            storeId: '7',
          }),
        ),
      ),
    )

    renderStatus()
    fireEvent.click(
      await screen.findByRole('button', { name: '매장 관리로 이동' }),
    )

    expect(screen.getByTestId('location')).toHaveTextContent(
      '/store-operator/stores/7',
    )
  })
})
