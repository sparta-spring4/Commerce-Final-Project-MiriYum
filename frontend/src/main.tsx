import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

import App from './app/App'
import './shared/ui/theme.css'
import './shared/ui/primitives.css'
import './app/appLayout.css'

const root = document.getElementById('root')

if (root === null) {
  throw new Error('Application root element was not found')
}

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
