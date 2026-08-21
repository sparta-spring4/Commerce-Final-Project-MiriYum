import { screen } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import {
  STORE_ID,
  authenticatedOperator,
  managedStore,
  operatorStorePath,
} from '../../../domains/store/store-operator/test/handlers'
import { renderOperator } from '../../../domains/store/store-operator/test/renderOperator'
import { RequirePickupEnabled } from './RequirePickupEnabled'

describe('픽업 운영 화면 보호', () => {
  it('픽업 미사용 매장의 직접 접근을 막는다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(operatorStorePath(), () => successResponse(managedStore({
        modes: { reservationEnabled: true, menuHoldEnabled: false, pickupEnabled: false },
      }))),
    )

    renderOperator(<RequirePickupEnabled><p>픽업 본문</p></RequirePickupEnabled>, {
      route: `/store-operator/stores/${STORE_ID}/pickup-reservations`,
      path: '/store-operator/stores/:storeId/pickup-reservations',
    })

    expect(await screen.findByText('이 매장은 픽업을 사용하지 않습니다.')).toBeInTheDocument()
    expect(screen.queryByText('픽업 본문')).not.toBeInTheDocument()
  })
})
