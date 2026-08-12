import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { CurrentStoreProvider, useCurrentStore } from './CurrentStoreProvider'
import { storeOperatorKeys } from './api/queries'

function Probe() {
  const { storeId, selectStore, clearStore } = useCurrentStore()

  return (
    <div>
      <p data-testid="store">{storeId ?? '없음'}</p>
      <button type="button" onClick={() => selectStore('7')}>
        7번 선택
      </button>
      <button type="button" onClick={() => selectStore('9')}>
        9번 선택
      </button>
      <button type="button" onClick={clearStore}>
        선택 해제
      </button>
    </div>
  )
}

function renderProvider(queryClient = new QueryClient()) {
  const result = render(
    <QueryClientProvider client={queryClient}>
      <CurrentStoreProvider>
        <Probe />
      </CurrentStoreProvider>
    </QueryClientProvider>,
  )
  return { ...result, queryClient }
}

describe('현재 매장 선택', () => {
  it('아는 매장이 없으면 목록을 조회하지 않고 없음으로 둔다', () => {
    renderProvider()

    expect(screen.getByTestId('store')).toHaveTextContent('없음')
  })

  it('선택한 매장을 sessionStorage에 보존해 새로고침 뒤에도 유지한다', () => {
    const { unmount } = renderProvider()

    fireEvent.click(screen.getByRole('button', { name: '7번 선택' }))
    expect(screen.getByTestId('store')).toHaveTextContent('7')

    unmount()
    renderProvider()

    expect(screen.getByTestId('store')).toHaveTextContent('7')
  })

  it('매장이 바뀌면 이전 매장의 query 캐시를 격리한다', () => {
    const queryClient = new QueryClient()
    queryClient.setQueryData(storeOperatorKeys.managedStore('7'), {
      name: '이전 매장',
    })
    queryClient.setQueryData(storeOperatorKeys.menus('7'), [])

    renderProvider(queryClient)

    fireEvent.click(screen.getByRole('button', { name: '7번 선택' }))
    fireEvent.click(screen.getByRole('button', { name: '9번 선택' }))

    // 이전 매장 데이터가 남아 있으면 다른 매장 화면에 잠깐 그 값이 보인다.
    expect(
      queryClient.getQueryData(storeOperatorKeys.managedStore('7')),
    ).toBeUndefined()
    expect(queryClient.getQueryData(storeOperatorKeys.menus('7'))).toBeUndefined()
    expect(screen.getByTestId('store')).toHaveTextContent('9')
  })

  it('같은 매장을 다시 선택하면 캐시를 버리지 않는다', () => {
    const queryClient = new QueryClient()
    renderProvider(queryClient)

    fireEvent.click(screen.getByRole('button', { name: '7번 선택' }))
    queryClient.setQueryData(storeOperatorKeys.managedStore('7'), {
      name: '유지되어야 함',
    })
    fireEvent.click(screen.getByRole('button', { name: '7번 선택' }))

    expect(
      queryClient.getQueryData(storeOperatorKeys.managedStore('7')),
    ).toEqual({ name: '유지되어야 함' })
  })

  it('선택을 해제하면 보존값과 캐시를 함께 지운다', () => {
    const queryClient = new QueryClient()
    renderProvider(queryClient)

    fireEvent.click(screen.getByRole('button', { name: '7번 선택' }))
    queryClient.setQueryData(storeOperatorKeys.managedStore('7'), { name: 'x' })
    fireEvent.click(screen.getByRole('button', { name: '선택 해제' }))

    expect(screen.getByTestId('store')).toHaveTextContent('없음')
    expect(
      queryClient.getQueryData(storeOperatorKeys.managedStore('7')),
    ).toBeUndefined()
  })
})
