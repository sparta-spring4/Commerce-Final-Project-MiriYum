import { useEffect, useMemo, useState } from 'react'
import { useParams } from 'react-router'
import { useAdoptStoreFromRoute } from '../../../../app/shells/store-operator/CurrentStoreProvider'
import { PageHeader, SectionCard } from '../../../../app/shells/store-operator/OperatorPage'
import { createIdempotencyKeyCache } from '../../../../shared/api/idempotencyKey'
import { Alert, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { Badge } from '../../../../shared/ui/Badge'
import { Button } from '../../../../shared/ui/Button'
import {
  useManagedMenus,
  useReplaceRepresentativeMenus,
  useRepresentativeMenus,
} from '../api/menuQueries'
import { primaryMenuVersion } from '../model/menuFilters'
import type { ManagedMenu } from '../model/types'

const MINIMUM = 3
const MAXIMUM = 5

function unavailableReason(menu: ManagedMenu): string | null {
  if (menu.retired) return '운영 종료 메뉴'
  if (menu.visibility === 'HIDDEN') return '비공개 메뉴'
  if (menu.sellingStatus === 'PAUSED') return '판매 중지 메뉴'
  if (menu.published?.status !== 'PUBLISHED') return '미게시 메뉴'
  return null
}

export function RepresentativeMenusPage() {
  const { storeId = '' } = useParams<{ storeId: string }>()
  useAdoptStoreFromRoute(storeId)
  const menus = useManagedMenus(storeId)
  const setting = useRepresentativeMenus(storeId)
  const replace = useReplaceRepresentativeMenus(storeId)
  const keys = useMemo(createIdempotencyKeyCache, [])
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [loadedVersion, setLoadedVersion] = useState<number | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (setting.data && setting.data.version !== loadedVersion) {
      setSelectedIds([...setting.data.items]
        .sort((left, right) => left.displayOrder - right.displayOrder)
        .map((item) => item.menuId))
      setLoadedVersion(setting.data.version)
    }
  }, [loadedVersion, setting.data])

  const menuById = new Map((menus.data ?? []).map((menu) => [menu.menuId, menu]))
  const selectedMenus = selectedIds
    .map((id) => menuById.get(id))
    .filter((menu): menu is ManagedMenu => menu !== undefined)

  function toggle(menuId: string, checked: boolean) {
    setError(null)
    setMessage(null)
    if (checked) {
      if (selectedIds.length >= MAXIMUM) {
        setError('대표 메뉴는 최대 5개까지 선택할 수 있습니다.')
        return
      }
      setSelectedIds((current) => [...current, menuId])
    } else {
      setSelectedIds((current) => current.filter((id) => id !== menuId))
    }
  }

  function move(menuId: string, offset: -1 | 1) {
    setSelectedIds((current) => {
      const index = current.indexOf(menuId)
      const nextIndex = index + offset
      if (index < 0 || nextIndex < 0 || nextIndex >= current.length) return current
      const next = [...current]
      ;[next[index], next[nextIndex]] = [next[nextIndex], next[index]]
      return next
    })
  }

  async function save() {
    setError(null)
    setMessage(null)
    if (selectedIds.length < MINIMUM) {
      setError('대표 메뉴는 3개 이상 선택해 주세요.')
      return
    }
    if (selectedIds.length > MAXIMUM || setting.data === undefined) return
    const body = { expectedVersion: setting.data.version, menuIds: selectedIds }
    try {
      const result = await replace.mutateAsync({
        body,
        idempotencyKey: keys.keyFor(JSON.stringify(body)),
      })
      setLoadedVersion(result.version)
      setSelectedIds(result.items.length > 0
        ? [...result.items].sort((left, right) => left.displayOrder - right.displayOrder).map((item) => item.menuId)
        : selectedIds)
      setMessage('대표 메뉴를 저장했습니다.')
    } catch {
      setError('대표 메뉴를 저장하지 못했습니다. 최신 상태를 다시 확인해 주세요.')
    }
  }

  const pending = menus.isPending || setting.isPending
  const failed = menus.isError || setting.isError

  return <>
    <PageHeader title="대표 메뉴" description="매장 상세에 노출할 메뉴 3~5개와 표시 순서를 관리합니다." />
    {setting.data?.status === 'REQUIRES_ATTENTION' && <Alert tone="warning" title="대표 메뉴 구성을 확인해 주세요.">선택한 메뉴 중 현재 공개할 수 없는 메뉴가 있거나 최소 개수를 충족하지 못했습니다.</Alert>}
    {message && <Alert tone="info" title={message} />}
    {error && <Alert tone="error" title={error} />}
    {pending && <Loading label="대표 메뉴를 불러오는 중입니다." />}
    {failed && <ErrorState error={menus.error ?? setting.error} message="대표 메뉴 정보를 불러오지 못했습니다." onRetry={() => { void menus.refetch(); void setting.refetch() }} />}
    {!pending && !failed && <>
      <SectionCard title={`현재 ${selectedIds.length}개 선택`} hint="체크한 순서로 목록 끝에 추가됩니다. 선택 목록에서 노출 순서를 조정할 수 있습니다.">
        <ol className="op-stack">
          {selectedMenus.map((menu, index) => {
            const version = primaryMenuVersion(menu)
            return <li key={menu.menuId} className="op-list-item">
              <span><strong>{index + 1}. {version?.name ?? `메뉴 #${menu.menuId}`}</strong>{menu.sellingStatus === 'SOLD_OUT' && <Badge tone="attention">품절</Badge>}</span>
              <span className="op-actions">
                <Button size="sm" variant="ghost" aria-label={`${version?.name ?? menu.menuId} 위로`} disabled={index === 0} onClick={() => move(menu.menuId, -1)}>위로</Button>
                <Button size="sm" variant="ghost" aria-label={`${version?.name ?? menu.menuId} 아래로`} disabled={index === selectedMenus.length - 1} onClick={() => move(menu.menuId, 1)}>아래로</Button>
              </span>
            </li>
          })}
        </ol>
      </SectionCard>
      <SectionCard title="메뉴 선택">
        <ul className="op-stack">
          {(menus.data ?? []).map((menu) => {
            const version = primaryMenuVersion(menu)
            const reason = unavailableReason(menu)
            return <li key={menu.menuId} className="op-list-item">
              <label className="mi-check"><input type="checkbox" aria-label={`${version?.name ?? menu.menuId} 선택`} checked={selectedIds.includes(menu.menuId)} disabled={reason !== null} onChange={(event) => toggle(menu.menuId, event.target.checked)} /><span>{version?.name ?? `메뉴 #${menu.menuId}`}</span></label>
              {reason && <Badge tone="negative">{reason}</Badge>}
            </li>
          })}
        </ul>
        <div className="op-actions"><Button loading={replace.isPending} onClick={() => void save()}>대표 메뉴 저장</Button></div>
      </SectionCard>
    </>}
  </>
}
