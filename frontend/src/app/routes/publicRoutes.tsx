import { Route } from 'react-router'
import {
  HomePage,
  StoreDetailPage,
  StoreSearchPage,
} from '../../domains/store/public'
import { ForbiddenPage } from '../ForbiddenPage'
import { NotFoundPage } from '../NotFoundPage'
import { PUBLIC_PATHS } from './paths/publicPaths'

export const publicRoutes = [
  <Route key="home" path={PUBLIC_PATHS.home} element={<HomePage />} />,
  <Route key="stores" path={PUBLIC_PATHS.stores} element={<StoreSearchPage />} />,
  <Route
    key="store-detail"
    path={PUBLIC_PATHS.storeDetail}
    element={<StoreDetailPage />}
  />,
  <Route key="forbidden" path={PUBLIC_PATHS.forbidden} element={<ForbiddenPage />} />,
  <Route key="not-found" path="*" element={<NotFoundPage />} />,
]
