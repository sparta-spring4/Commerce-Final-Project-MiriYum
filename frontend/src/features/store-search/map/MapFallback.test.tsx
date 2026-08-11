import '@testing-library/jest-dom/vitest'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { MapFallback } from './MapFallback'

describe('MapFallback', () => {
  it('explains that stores remain available in the list when the map cannot load', () => {
    render(<MapFallback reason="지도를 불러오지 못했습니다." />)

    expect(screen.getByRole('status')).toHaveTextContent(
      '지도를 불러오지 못했습니다.',
    )
    expect(screen.getByText('매장 목록에서 계속 확인할 수 있습니다.')).toBeVisible()
  })
})
