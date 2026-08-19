import { Outlet } from 'react-router'
import { ConsumerAuthProvider } from './ConsumerAuthProvider'

export function ConsumerAuthBoundary() {
  return (
    <ConsumerAuthProvider>
      <Outlet />
    </ConsumerAuthProvider>
  )
}
