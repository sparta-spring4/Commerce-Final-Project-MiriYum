import { describe, expect, it } from 'vitest'
import { storeOperatorNavigation } from './navigation'

describe('매장 운영자 기능별 내비게이션', () => {
  it('픽업이 비활성화된 매장에서는 픽업 목록을 숨기고 재고·추천 메뉴는 유지한다', () => {
    const items = storeOperatorNavigation('7', { pickupEnabled: false })

    expect(items.map((item) => item.label)).not.toContain('픽업 목록')
    expect(items.map((item) => item.label)).toContain('메뉴 재고')
    expect(items.map((item) => item.label)).toContain('추천 메뉴')
  })

  it('픽업이 활성화된 매장에서는 픽업 목록을 노출한다', () => {
    const items = storeOperatorNavigation('7', { pickupEnabled: true })

    expect(items.map((item) => item.label)).toContain('픽업 목록')
  })
})
