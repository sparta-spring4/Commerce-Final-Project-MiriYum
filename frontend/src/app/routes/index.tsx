import { Route, Routes } from 'react-router'
import { ConsumerAuthBoundary } from '../shells/consumer/ConsumerAuthBoundary'
import { PublicShell } from '../shells/public/PublicShell'
import { consumerEntryRoutes, consumerRoutes } from './consumerRoutes'
import { platformOperatorRoutes } from './platformOperatorRoutes'
import { publicRoutes } from './publicRoutes'
import { storeOperatorRoutes } from './storeOperatorRoutes'

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<ConsumerAuthBoundary />}>
        <Route element={<PublicShell />}>
          {publicRoutes}
          {consumerEntryRoutes}
        </Route>
        {consumerRoutes}
      </Route>
      {storeOperatorRoutes}
      {platformOperatorRoutes}
    </Routes>
  )
}
