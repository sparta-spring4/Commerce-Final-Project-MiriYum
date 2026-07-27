import '@testing-library/jest-dom/vitest'
import { render, screen } from '@testing-library/react'
import { expect, test } from 'vitest'

import App from './App'

test('renders the application name as a heading', () => {
  render(<App />)

  expect(
    screen.getByRole('heading', { level: 1, name: 'MiriYum' }),
  ).toBeInTheDocument()
})
