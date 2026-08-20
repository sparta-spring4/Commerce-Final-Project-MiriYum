import { fireEvent, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../../../test/msw/server'
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

  it('서버의 이미지 형식·용량 오류를 구분해 안내한다', async () => {
    let status = 413
    server.use(
      authenticatedOperator(),
      http.put(IMAGE_PATH, () =>
        HttpResponse.json(
          { code: 'COMMON_009', message: '지원하지 않는 형식입니다.' },
          { status },
        ),
      ),
    )

    renderPanel()
    const input = screen.getByLabelText('이미지 등록')
    fireEvent.change(input, {
      target: { files: [new File(['image'], 'menu.webp', { type: 'image/webp' })] },
    })
    expect(await screen.findByText('이미지 파일은 10MB 이하만 등록할 수 있습니다.')).toBeInTheDocument()

    status = 415
    fireEvent.change(input, {
      target: { files: [new File(['image'], 'menu-2.webp', { type: 'image/webp' })] },
    })
    expect(await screen.findByText('JPG, PNG, WEBP 이미지 파일만 등록할 수 있습니다.')).toBeInTheDocument()
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

  it('업로드 실패 후 다시 업로드하면 같은 멱등 키로 재시도한다', async () => {
    const idempotencyKeys: string[] = []
    let attempts = 0
    server.use(
      authenticatedOperator(),
      http.put(IMAGE_PATH, ({ request }) => {
        idempotencyKeys.push(request.headers.get('idempotency-key') ?? '')
        attempts += 1
        if (attempts === 1) {
          return HttpResponse.json(
            { code: 'COMMON_012', message: '잠시 후 다시 시도해 주세요.' },
            { status: 503 },
          )
        }
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

    expect(await screen.findByRole('button', { name: '다시 업로드' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '다시 업로드' }))

    expect(await screen.findByAltText('메뉴 대표 이미지')).toHaveAttribute(
      'src',
      'https://cdn.example/menu-11.webp',
    )
    expect(idempotencyKeys).toHaveLength(2)
    expect(idempotencyKeys[0]).toBe(idempotencyKeys[1])
  })

  it('삭제를 시작하면 이전 업로드 재시도 action을 제거한다', async () => {
    server.use(
      authenticatedOperator(),
      http.put(IMAGE_PATH, () =>
        HttpResponse.json(
          { code: 'COMMON_012', message: '잠시 후 다시 시도해 주세요.' },
          { status: 503 },
        ),
      ),
      http.delete(IMAGE_PATH, () =>
        HttpResponse.json(
          { code: 'COMMON_012', message: '잠시 후 다시 시도해 주세요.' },
          { status: 503 },
        ),
      ),
    )

    renderPanel()
    fireEvent.change(screen.getByLabelText('이미지 등록'), {
      target: {
        files: [new File(['image'], 'menu.webp', { type: 'image/webp' })],
      },
    })
    await screen.findByRole('button', { name: '다시 업로드' })

    fireEvent.click(screen.getByRole('button', { name: '이미지 삭제' }))

    await waitFor(() => {
      expect(
        screen.getByText(
          '서비스를 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.',
        ),
      ).toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: '다시 업로드' })).not.toBeInTheDocument()
  })

  it('삭제·업로드 응답이 유실된 뒤 새 삭제 멱등 키를 발급한다', async () => {
    const deleteKeys: string[] = []
    let deleteAttempts = 0
    let uploadAttempts = 0
    server.use(
      authenticatedOperator(),
      http.delete(IMAGE_PATH, ({ request }) => {
        deleteKeys.push(request.headers.get('idempotency-key') ?? '')
        deleteAttempts += 1
        return deleteAttempts === 1
          ? HttpResponse.error()
          : new HttpResponse(null, { status: 204 })
      }),
      http.put(IMAGE_PATH, () => {
        uploadAttempts += 1
        return uploadAttempts === 1
          ? HttpResponse.error()
          : HttpResponse.json({
              code: 'SUCCESS',
              message: '업로드했습니다.',
              data: { url: 'https://cdn.example/menu-11.webp' },
            })
      }),
    )

    renderPanel()
    fireEvent.click(screen.getByRole('button', { name: '이미지 삭제' }))
    await screen.findByText(
      '서버에 연결하지 못했습니다. 처리 여부가 확정되지 않았으니 상태를 다시 확인해 주세요.',
    )

    const file = new File(['image'], 'menu.webp', { type: 'image/webp' })
    fireEvent.change(screen.getByLabelText('이미지 등록'), {
      target: { files: [file] },
    })
    await screen.findByRole('button', { name: '다시 업로드' })
    fireEvent.click(screen.getByRole('button', { name: '이미지 삭제' }))

    await waitFor(() => expect(deleteKeys).toHaveLength(2))
    expect(deleteKeys[0]).not.toBe(deleteKeys[1])
  })

  it('업로드 중에는 파일 입력과 삭제를 잠근다', async () => {
    let releaseUpload!: () => void
    server.use(
      authenticatedOperator(),
      http.put(
        IMAGE_PATH,
        () =>
          new Promise((resolve) => {
            releaseUpload = () =>
              resolve(
                HttpResponse.json({
                  code: 'SUCCESS',
                  message: '업로드했습니다.',
                  data: { url: 'https://cdn.example/menu-11.webp' },
                }),
              )
          }),
      ),
    )

    renderPanel()
    fireEvent.change(screen.getByLabelText('이미지 등록'), {
      target: {
        files: [new File(['image'], 'menu.webp', { type: 'image/webp' })],
      },
    })

    await waitFor(() => {
      expect(screen.getByLabelText('메뉴 대표 이미지 파일')).toBeDisabled()
      expect(screen.getByRole('button', { name: '이미지 삭제' })).toBeDisabled()
    })
    releaseUpload()
    await screen.findByAltText('메뉴 대표 이미지')
  })
})
