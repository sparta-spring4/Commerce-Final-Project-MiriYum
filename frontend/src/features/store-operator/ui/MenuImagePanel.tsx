import { useRef, useState } from 'react'
import { createIdempotencyKey } from '../../../shared/api/idempotencyKey'
import { Button } from '../../../shared/ui/Button'
import { Alert } from '../../../shared/ui/Feedback'
import { useDeleteMenuImage, usePutMenuImage } from '../api/menuQueries'
import { storeErrorMessage } from '../model/storeErrors'
import { SectionCard } from './PageHeader'

const MAX_IMAGE_BYTES = 10 * 1024 * 1024
const ACCEPTED_IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp'])

export function MenuImagePanel({
  storeId,
  menuId,
}: {
  storeId: string
  menuId: string
}) {
  const upload = usePutMenuImage(storeId, menuId)
  const remove = useDeleteMenuImage(storeId, menuId)
  const uploadKeys = useRef(new WeakMap<File, string>())
  const deleteKey = useRef<string | null>(null)
  const [imageUrl, setImageUrl] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [retryFile, setRetryFile] = useState<File | null>(null)

  const busy = upload.isPending || remove.isPending

  async function handleFileChange(file: File | undefined) {
    if (file === undefined) return
    setMessage(null)
    setRetryFile(null)

    if (!ACCEPTED_IMAGE_TYPES.has(file.type)) {
      setMessage('JPG, PNG, WEBP 이미지 파일만 등록할 수 있습니다.')
      return
    }
    if (file.size > MAX_IMAGE_BYTES) {
      setMessage('이미지 파일은 10MB 이하만 등록할 수 있습니다.')
      return
    }

    await uploadFile(file)
  }

  async function uploadFile(file: File) {
    // 새 업로드는 이전 삭제 시도의 결과를 재사용하면 안 된다.
    deleteKey.current = null
    try {
      const url = await upload.mutateAsync({
        file,
        idempotencyKey:
          uploadKeys.current.get(file) ??
          createUploadKey(file, uploadKeys.current),
      })
      setImageUrl(url)
      setMessage(null)
      deleteKey.current = null
      setRetryFile(null)
    } catch (error) {
      setMessage(storeErrorMessage(error))
      setRetryFile(file)
    }
  }

  async function handleDelete() {
    setMessage(null)
    setRetryFile(null)
    try {
      const key =
        deleteKey.current ?? (deleteKey.current = createIdempotencyKey())
      await remove.mutateAsync(key)
      setImageUrl(null)
      deleteKey.current = null
    } catch (error) {
      setMessage(storeErrorMessage(error))
    }
  }

  return (
    <SectionCard
      title="대표 이미지"
      hint="메뉴 목록과 상세 화면에 표시할 이미지를 관리합니다."
    >
      {message !== null && (
        <Alert
          tone="error"
          title={message}
          actions={
            retryFile !== null ? (
              <Button
                variant="ghost"
                size="sm"
                loading={upload.isPending}
                onClick={() => void uploadFile(retryFile)}
              >
                다시 업로드
              </Button>
            ) : undefined
          }
        />
      )}
      {upload.isPending && (
        <Alert tone="info" title="이미지를 업로드하는 중입니다." />
      )}
      <div className="op-menu-image">
        {imageUrl !== null ? (
          <img className="op-menu-image__preview" src={imageUrl} alt="메뉴 대표 이미지" />
        ) : (
          <div className="op-menu-image__empty">등록된 대표 이미지가 없습니다.</div>
        )}
        <div className="op-menu-image__actions">
          <label
            className={`mi-button mi-button--secondary${busy ? ' mi-button--disabled' : ''}`}
            htmlFor="menu-image-file"
            aria-disabled={busy}
          >
            {upload.isPending
              ? '업로드 중...'
              : imageUrl === null
                ? '이미지 등록'
                : '이미지 교체'}
          </label>
          <input
            id="menu-image-file"
            aria-label="메뉴 대표 이미지 파일"
            className="visually-hidden"
            type="file"
            disabled={busy}
            accept="image/jpeg,image/png,image/webp"
            onChange={(event) => {
              const file = event.target.files?.[0]
              event.target.value = ''
              void handleFileChange(file)
            }}
          />
          <Button
            variant="danger"
            loading={remove.isPending}
            disabled={busy}
            onClick={() => void handleDelete()}
          >
            이미지 삭제
          </Button>
        </div>
      </div>
    </SectionCard>
  )
}

function createUploadKey(file: File, keys: WeakMap<File, string>): string {
  const key = createIdempotencyKey()
  keys.set(file, key)
  return key
}
