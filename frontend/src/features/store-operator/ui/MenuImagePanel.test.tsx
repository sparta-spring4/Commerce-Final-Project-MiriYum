import { fireEvent, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../../test/msw/server'
import { authenticatedOperator } from '../test/handlers'
import { renderOperator } from '../test/renderOperator'
import { MenuImagePanel } from './MenuImagePanel'

const IMAGE_PATH =
  '/api/v1/store-operators/stores/7/menus/11/images'

function renderPanel() {
  return renderOperator(
    <MenuImagePanel storeId="7" menuId="11" />,
    {
      route: '/store-operator/stores/7/menus/11',
      path: '/store-operator/stores/7/menus/11',
    },
  )
}

describe('메뉴 대표 이미지 패널', () => {
  it('multipart 업로드와 응답 URL 표시를 처리한다', async () => {
    let contentType: string | null = null
    server.use(
      authenticatedOperator(),
      http.put(IMAGE_PATH, ({ request }) => {
        contentType = request.headers.get('content-type')
        return HttpResponse.json({
          code: 'SUCCESS',
          message: '업로드했습니다.',
          data: { url: 'https://cdn.example/menu-11.webp' },
        })
      }),
    )

    renderPanel()
    const file = new File(['image'], 'menu.webp', { type: 'image/webp' })
    fireEvent.change(screen.getByLabelText('이미지 등록'), {
      target: { files: [file] },
    })

    expect(await screen.findByAltText('메뉴 대표 이미지')).toHaveAttribute(
      'src',
      'https://cdn.example/menu-11.webp',
    )
    expect(contentType).toMatch(/^multipart\/form-data; boundary=/)
  })

  it('204 삭제 응답을 성공으로 처리한다', async () => {
    let deleteCalled = false
    server.use(
      authenticatedOperator(),
      http.put(IMAGE_PATH, () =>
        HttpResponse.json({
          code: 'SUCCESS',
          message: '업로드했습니다.',
          data: { url: 'https://cdn.example/menu-11.webp' },
        }),
      ),
      http.delete(IMAGE_PATH, () => {
        deleteCalled = true
        return new HttpResponse(null, { status: 204 })
      }),
    )

    renderPanel()
    fireEvent.change(screen.getByLabelText('이미지 등록'), {
      target: {
        files: [new File(['image'], 'menu.webp', { type: 'image/webp' })],
      },
    })
    await screen.findByAltText('메뉴 대표 이미지')
    fireEvent.click(screen.getByRole('button', { name: '이미지 삭제' }))

    await waitFor(() => expect(deleteCalled).toBe(true))
    expect(await screen.findByText('등록된 대표 이미지가 없습니다.')).toBeInTheDocument()
  })

  it('지원하지 않는 형식과 10MB 초과 파일을 업로드하지 않는다', async () => {
    let called = false
    server.use(
      authenticatedOperator(),
      http.put(IMAGE_PATH, () => {
        called = true
        return new HttpResponse(null, { status: 500 })
      }),
    )

    renderPanel()
    fireEvent.change(screen.getByLabelText('이미지 등록'), {
      target: { files: [new File(['text'], 'menu.gif', { type: 'image/gif' })] },
    })
    expect(await screen.findByText('JPG, PNG, WEBP 이미지 파일만 등록할 수 있습니다.')).toBeInTheDocument()

    const largeFile = new File(['x'], 'large.png', { type: 'image/png' })
    Object.defineProperty(largeFile, 'size', { value: 10 * 1024 * 1024 + 1 })
    fireEvent.change(screen.getByLabelText('이미지 등록'), {
      target: { files: [largeFile] },
    })
    expect(await screen.findByText('이미지 파일은 10MB 이하만 등록할 수 있습니다.')).toBeInTheDocument()
    expect(called).toBe(false)
  })
})
