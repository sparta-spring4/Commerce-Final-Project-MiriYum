import { Outlet } from 'react-router'
import './partner.css'
import { CurrentStoreProvider } from './CurrentStoreProvider'
import { StoreOperatorAuthProvider } from './StoreOperatorAuthProvider'

export function StoreOperatorBoundary() {
  return (
    <StoreOperatorAuthProvider>
      <CurrentStoreProvider>
        <Outlet />
      </CurrentStoreProvider>
    </StoreOperatorAuthProvider>
  )
}
