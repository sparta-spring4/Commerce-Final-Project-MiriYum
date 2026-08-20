import { useRef, useState } from 'react'
import { createIdempotencyKey } from '../../../../shared/api/idempotencyKey'
import { Button } from '../../../../shared/ui/Button'
import { Alert, Loading } from '../../../../shared/ui/Feedback'
import {
  useDeleteStoreImage,
  useReplaceStoreImage,
  useStoreImages,
  useUploadStoreImage,
} from '../api/queries'
import { storeErrorMessage } from '../model/storeErrors'
import { SectionCard } from '../../../../app/shells/store-operator/OperatorPage'

const MAX_IMAGE_BYTES = 10 * 1024 * 1024
const MAX_IMAGE_COUNT = 10
const ACCEPTED_IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp'])

type RetryRequest = {
  file: File
  imageId?: string
  idempotencyKey: string
}

/** 매장 공개 이미지 목록과 추가·교체·삭제를 매장 정보 PATCH와 분리해 관리한다. */
export function StoreImagePanel({ storeId }: { storeId: string }) {
  const images = useStoreImages(storeId)
  const upload = useUploadStoreImage(storeId)
  const replace = useReplaceStoreImage(storeId)
  const remove = useDeleteStoreImage(storeId)
  const [message, setMessage] = useState<string | null>(null)
  const [retry, setRetry] = useState<RetryRequest | null>(null)
  const deleteKeys = useRef(new Map<string, string>())

  const busy = upload.isPending || replace.isPending || remove.isPending

  function validateFile(file: File): boolean {
    if (!ACCEPTED_IMAGE_TYPES.has(file.type)) {
      setMessage('JPG, PNG, WEBP 이미지 파일만 등록할 수 있습니다.')
      return false
    }
    if (file.size > MAX_IMAGE_BYTES) {
      setMessage('이미지 파일은 10MB 이하만 등록할 수 있습니다.')
      return false
    }
    return true
  }

  async function submitFile(request: RetryRequest) {
    setMessage(null)
    try {
      if (request.imageId === undefined) {
        await upload.mutateAsync(request)
      } else {
        await replace.mutateAsync({
          imageId: request.imageId,
          file: request.file,
          idempotencyKey: request.idempotencyKey,
        })
      }
      setRetry(null)
    } catch (error) {
      setMessage(storeErrorMessage(error))
      setRetry(request)
    }
  }

  function handleFile(file: File | undefined, imageId?: string) {
    if (file === undefined || !validateFile(file)) return
    const request = { file, imageId, idempotencyKey: createIdempotencyKey() }
    void submitFile(request)
  }

  async function handleDelete(imageId: string) {
    setMessage(null)
    setRetry(null)
    try {
      const idempotencyKey =
        deleteKeys.current.get(imageId) ?? createDeleteKey(imageId, deleteKeys.current)
      await remove.mutateAsync({
        imageId,
        idempotencyKey,
      })
      deleteKeys.current.delete(imageId)
    } catch (error) {
      setMessage(storeErrorMessage(error))
    }
  }

  return (
    <SectionCard
      title="매장 이미지"
      icon="store"
      hint="고객 화면에 보이는 매장 사진을 최대 10장까지 관리합니다."
    >
      {message !== null && (
        <Alert
          tone="error"
          title={message}
          actions={
            retry !== null ? (
              <Button
                variant="ghost"
                size="sm"
                loading={upload.isPending || replace.isPending}
                onClick={() => void submitFile(retry)}
              >
                다시 업로드
              </Button>
            ) : undefined
          }
        />
      )}
      {images.isPending ? (
        <Loading label="매장 이미지를 불러오는 중입니다." />
      ) : images.isError ? (
        <Alert
          tone="error"
          title="매장 이미지를 불러오지 못했습니다."
          actions={
            <Button variant="ghost" size="sm" onClick={() => void images.refetch()}>
              다시 시도
            </Button>
          }
        />
      ) : (
        <div className="op-stack">
          <div className="op-store-images">
            {images.data.map((image, index) => (
              <article className="op-store-image" key={image.imageId}>
                <img
                  className="op-store-image__preview"
                  src={image.url}
                  alt={`매장 이미지 ${index + 1}`}
                />
                <div className="op-store-image__actions">
                  <label
                    className={`mi-button mi-button--secondary${busy ? ' mi-button--disabled' : ''}`}
                    htmlFor={`store-image-file-${image.imageId}`}
                    aria-disabled={busy}
                  >
                    {replace.isPending ? '교체 중...' : '이미지 교체'}
                  </label>
                  <input
                    id={`store-image-file-${image.imageId}`}
                    aria-label={`매장 이미지 ${index + 1} 파일`}
                    className="visually-hidden"
                    type="file"
                    disabled={busy}
                    accept="image/jpeg,image/png,image/webp"
                    onChange={(event) => {
                      const file = event.target.files?.[0]
                      event.target.value = ''
                      handleFile(file, image.imageId)
                    }}
                  />
                  <Button
                    variant="danger"
                    loading={remove.isPending}
                    disabled={busy}
                    onClick={() => void handleDelete(image.imageId)}
                  >
                    이미지 삭제
                  </Button>
                </div>
              </article>
            ))}
          </div>

          {images.data.length === 0 && (
            <p className="op-section__hint">등록된 매장 이미지가 없습니다.</p>
          )}

          {images.data.length < MAX_IMAGE_COUNT ? (
            <div className="op-store-image__actions">
              <label
                className={`mi-button mi-button--secondary${busy ? ' mi-button--disabled' : ''}`}
                htmlFor="store-image-file-new"
                aria-disabled={busy}
              >
                {upload.isPending ? '업로드 중...' : '이미지 추가'}
              </label>
              <input
                id="store-image-file-new"
                aria-label="새 매장 이미지 파일"
                className="visually-hidden"
                type="file"
                disabled={busy}
                accept="image/jpeg,image/png,image/webp"
                onChange={(event) => {
                  const file = event.target.files?.[0]
                  event.target.value = ''
                  handleFile(file)
                }}
              />
              <p className="op-section__hint">
                {images.data.length}/{MAX_IMAGE_COUNT}장 등록됨 · JPG, PNG, WEBP · 최대 10MB
              </p>
            </div>
          ) : (
            <p className="op-section__hint">매장 이미지는 최대 10장까지 등록할 수 있습니다.</p>
          )}
        </div>
      )}
    </SectionCard>
  )
}

function createDeleteKey(imageId: string, keys: Map<string, string>): string {
  const key = createIdempotencyKey()
  keys.set(imageId, key)
  return key
}
