import { useMemo, useState } from 'react'
import { createIdempotencyKeyCache } from '../../../shared/api/idempotencyKey'
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
  const uploadKeys = useMemo(createIdempotencyKeyCache, [])
  const [imageUrl, setImageUrl] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  async function handleFileChange(file: File | undefined) {
    if (file === undefined) return
    setMessage(null)

    if (!ACCEPTED_IMAGE_TYPES.has(file.type)) {
      setMessage('JPG, PNG, WEBP 이미지 파일만 등록할 수 있습니다.')
      return
    }
    if (file.size > MAX_IMAGE_BYTES) {
      setMessage('이미지 파일은 10MB 이하만 등록할 수 있습니다.')
      return
    }

    try {
      const url = await upload.mutateAsync({
        file,
        idempotencyKey: uploadKeys.keyFor(
          `${file.name}:${file.size}:${file.lastModified}`,
        ),
      })
      setImageUrl(url)
    } catch (error) {
      setMessage(storeErrorMessage(error))
    }
  }

  async function handleDelete() {
    setMessage(null)
    try {
      await remove.mutateAsync('menu-image-delete')
      setImageUrl(null)
    } catch (error) {
      setMessage(storeErrorMessage(error))
    }
  }

  return (
    <SectionCard
      title="대표 이미지"
      hint="메뉴 목록과 상세 화면에 표시할 이미지를 관리합니다."
    >
      {message !== null && <Alert tone="error" title={message} />}
      <div className="op-menu-image">
        {imageUrl !== null ? (
          <img className="op-menu-image__preview" src={imageUrl} alt="메뉴 대표 이미지" />
        ) : (
          <div className="op-menu-image__empty">등록된 대표 이미지가 없습니다.</div>
        )}
        <div className="op-menu-image__actions">
          <label className="mi-button mi-button--secondary" htmlFor="menu-image-file">
            {imageUrl === null ? '이미지 등록' : '이미지 교체'}
          </label>
          <input
            id="menu-image-file"
            className="visually-hidden"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            onChange={(event) => void handleFileChange(event.target.files?.[0])}
          />
          {imageUrl !== null && (
            <Button variant="danger" loading={remove.isPending} onClick={() => void handleDelete()}>
              이미지 삭제
            </Button>
          )}
        </div>
      </div>
    </SectionCard>
  )
}
