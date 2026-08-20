import { fireEvent, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { authenticatedOperator } from '../test/handlers'
import { renderOperator } from '../test/renderOperator'
import { StoreImagePanel } from './StoreImagePanel'

const IMAGE_PATH = '/api/v1/store-operators/stores/7/images'

function renderPanel() {
  return renderOperator(<StoreImagePanel storeId="7" />, {
    route: '/store-operator/stores/7/info',
    path: '/store-operator/stores/7/info',
  })
}

describe('매장 이미지 패널', () => {
  it('등록된 매장 이미지 목록을 보여 준다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(IMAGE_PATH, () =>
        successResponse([
          { imageId: 'image-1', url: 'https://cdn.example/store-1.webp' },
        ]),
      ),
    )

    renderPanel()

    expect(await screen.findByAltText('매장 이미지 1')).toHaveAttribute(
      'src',
      'https://cdn.example/store-1.webp',
    )
    expect(screen.getByText('1/10장 등록됨 · JPG, PNG, WEBP · 최대 10MB')).toBeInTheDocument()
  })

  it('새 이미지를 multipart 요청으로 추가하고 목록에 반영한다', async () => {
    let contentType: string | null = null
    const images: { imageId: string; url: string }[] = []
    server.use(
      authenticatedOperator(),
      http.get(IMAGE_PATH, () => successResponse(images)),
      http.post(IMAGE_PATH, ({ request }) => {
        contentType = request.headers.get('content-type')
        images.push({ imageId: 'image-1', url: 'https://cdn.example/store-1.webp' })
        return successResponse(images[0])
      }),
    )

    renderPanel()
    expect(await screen.findByText('등록된 매장 이미지가 없습니다.')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('이미지 추가'), {
      target: {
        files: [new File(['image'], 'store.webp', { type: 'image/webp' })],
      },
    })

    expect(await screen.findByAltText('매장 이미지 1')).toHaveAttribute(
      'src',
      'https://cdn.example/store-1.webp',
    )
    expect(contentType).toMatch(/^multipart\/form-data; boundary=/)
  })

  it('기존 이미지를 교체하고 삭제한다', async () => {
    const images = [
      { imageId: 'image-1', url: 'https://cdn.example/store-before.webp' },
    ]
    let deleteCalled = false
    server.use(
      authenticatedOperator(),
      http.get(IMAGE_PATH, () => successResponse(images)),
      http.put(`${IMAGE_PATH}/image-1`, () => {
        images[0] = {
          imageId: 'image-1',
          url: 'https://cdn.example/store-after.webp',
        }
        return successResponse(images[0])
      }),
      http.delete(`${IMAGE_PATH}/image-1`, () => {
        deleteCalled = true
        images.splice(0, 1)
        return new HttpResponse(null, { status: 204 })
      }),
    )

    renderPanel()
    await screen.findByAltText('매장 이미지 1')
    fireEvent.change(screen.getByLabelText('매장 이미지 1 파일'), {
      target: {
        files: [new File(['image'], 'store.webp', { type: 'image/webp' })],
      },
    })

    expect(await screen.findByAltText('매장 이미지 1')).toHaveAttribute(
      'src',
      'https://cdn.example/store-after.webp',
    )
    fireEvent.click(screen.getByRole('button', { name: '이미지 삭제' }))

    await waitFor(() => expect(deleteCalled).toBe(true))
    expect(await screen.findByText('등록된 매장 이미지가 없습니다.')).toBeInTheDocument()
  })

  it('지원하지 않는 형식은 업로드하지 않는다', async () => {
    let uploadCalled = false
    server.use(
      authenticatedOperator(),
      http.get(IMAGE_PATH, () => successResponse([])),
      http.post(IMAGE_PATH, () => {
        uploadCalled = true
        return new HttpResponse(null, { status: 500 })
      }),
    )

    renderPanel()
    await screen.findByText('등록된 매장 이미지가 없습니다.')
    fireEvent.change(screen.getByLabelText('이미지 추가'), {
      target: { files: [new File(['text'], 'store.gif', { type: 'image/gif' })] },
    })

    expect(await screen.findByText('JPG, PNG, WEBP 이미지 파일만 등록할 수 있습니다.')).toBeInTheDocument()
    expect(uploadCalled).toBe(false)
  })
})
