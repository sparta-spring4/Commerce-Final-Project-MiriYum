import type { components } from '../../../../shared/api/generated/menu-hold-pickup'

export type MenuInventoryBucket = components['schemas']['MenuInventoryBucket']
export type MenuInventoryPageData = components['schemas']['MenuInventoryPageData']
export type MenuInventoryCreateRequest = components['schemas']['MenuInventoryCreateRequest']
export type MenuInventoryUpdateRequest = components['schemas']['MenuInventoryUpdateRequest']
export type InventoryAvailabilityStatus = components['schemas']['InventoryAvailabilityStatus']

export const INVENTORY_STATUS_LABEL: Record<InventoryAvailabilityStatus, string> = {
  AVAILABLE: '판매 가능',
  SOLD_OUT: '품절',
}
