import { QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router'
import { createQueryClient } from '../shared/api/queryClient'
import { AppErrorBoundary } from './AppErrorBoundary'
import { AppRoutes } from './routes/index'

const queryClient = createQueryClient()

export default function App() {
  return (
    <AppErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <AppRoutes />
        </BrowserRouter>
      </QueryClientProvider>
    </AppErrorBoundary>
  )
}
