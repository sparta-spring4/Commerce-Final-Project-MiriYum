import type { NavigationItem } from '../../routes/path'
import { PUBLIC_PATHS } from '../../routes/paths/publicPaths'
import { CONSUMER_PATHS } from '../../routes/paths/consumerPaths'

export const CONSUMER_NAVIGATION: readonly NavigationItem[] = [
  { label: '매장 찾기', path: PUBLIC_PATHS.stores },
  { label: '내 예약', path: CONSUMER_PATHS.myReservations },
  { label: '마이페이지', path: CONSUMER_PATHS.myPage },
]
