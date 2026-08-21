import { useMemo, useState } from 'react'
import { useParams } from 'react-router'
import { useAdoptStoreFromRoute } from '../../../../app/shells/store-operator/CurrentStoreProvider'
import { PageHeader, SectionCard } from '../../../../app/shells/store-operator/OperatorPage'
import { createIdempotencyKeyCache } from '../../../../shared/api/idempotencyKey'
import { Badge } from '../../../../shared/ui/Badge'
import { Button } from '../../../../shared/ui/Button'
import { SelectField, TextField } from '../../../../shared/ui/Field'
import { Alert, EmptyState, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { Pagination } from '../../../../shared/ui/Pagination'
import { useManagedMenus } from '../../../store/store-operator/api/menuQueries'
import { primaryMenuVersion } from '../../../store/store-operator/model/menuFilters'
import type { ManagedMenu } from '../../../store/store-operator/model/types'
import { useCreateMenuInventoryBucket, useMenuInventoryBuckets, useUpdateMenuInventoryBucket } from '../api/queries'
import { inventoryErrorMessage } from '../model/errors'
import { toInventoryCreate, toInventoryUpdate, validateInventoryDraft, type InventoryDraft } from '../model/inventoryDraft'
import { INVENTORY_STATUS_LABEL, type MenuInventoryBucket } from '../model/types'

const EMPTY_DRAFT: InventoryDraft = { menuId: '', serviceDate: '', startTime: '', endDate: '', endTime: '', totalSupply: '0', onlineHold: '0', onsite: '0', shared: '0', sharedOnlineAllowed: false, availabilityStatus: 'AVAILABLE' }

export function MenuInventoryPage() {
  const { storeId = '' } = useParams<{ storeId: string }>()
  useAdoptStoreFromRoute(storeId)
  const menus = useManagedMenus(storeId)
  const [serviceDate, setServiceDate] = useState('')
  const [menuId, setMenuId] = useState('')
  const [page, setPage] = useState(0)
  const [creating, setCreating] = useState(false)
  const [editingId, setEditingId] = useState<string | null>(null)
  const buckets = useMenuInventoryBuckets(storeId, { serviceDate: serviceDate || undefined, menuId: menuId || undefined, page, size: 20 })
  const menuName = (id: string) => {
    const menu = menus.data?.find((item) => item.menuId === id)
    return menu ? primaryMenuVersion(menu)?.name ?? `메뉴 #${id}` : `메뉴 #${id}`
  }

  return <>
    <PageHeader title="메뉴 재고" description="메뉴 제공 구간별 공급과 온라인·현장·공유 수량을 관리합니다." actions={<Button onClick={() => { setEditingId(null); setCreating(true) }}>새 재고 버킷</Button>} />
    <Alert tone="info" title="온라인 수량은 함께 사용됩니다.">일반 예약 메뉴 홀드와 픽업이 온라인 홀드·허용된 공유 수량을 함께 사용할 수 있습니다.</Alert>
    {creating && <InventoryPolicyForm storeId={storeId} menus={menus.data ?? []} initial={EMPTY_DRAFT} creating onClose={() => setCreating(false)} />}
    <SectionCard title="재고 버킷">
      <div className="op-form-grid"><TextField label="서비스 날짜 필터" type="date" value={serviceDate} onChange={(event) => { setServiceDate(event.target.value); setPage(0) }} /><SelectField label="메뉴 필터" value={menuId} onChange={(event) => { setMenuId(event.target.value); setPage(0) }}><option value="">전체</option>{(menus.data ?? []).map((menu) => <option key={menu.menuId} value={menu.menuId}>{primaryMenuVersion(menu)?.name ?? `메뉴 #${menu.menuId}`}</option>)}</SelectField></div>
      {(buckets.isPending || menus.isPending) && <Loading label="재고 버킷을 불러오는 중입니다." />}
      {(buckets.isError || menus.isError) && <ErrorState error={buckets.error ?? menus.error} message={inventoryErrorMessage(buckets.error ?? menus.error)} onRetry={() => { void buckets.refetch(); void menus.refetch() }} />}
      {buckets.isSuccess && buckets.data.items.length === 0 && <EmptyState title="등록된 재고 버킷이 없습니다." />}
      {buckets.isSuccess && buckets.data.items.length > 0 && <>
        <div className="op-table-scroll"><table className="op-table"><caption className="visually-hidden">메뉴 재고 버킷 목록</caption><thead><tr><th>메뉴·구간</th><th>공급</th><th>온라인 잔여</th><th>상태</th><th>관리</th></tr></thead><tbody>{buckets.data.items.map((bucket) => <tr key={bucket.inventoryBucketId}><th>{menuName(bucket.menuId)}<br /><span>{bucket.serviceDate} {bucket.startTime.slice(0, 5)}–{bucket.endDate} {bucket.endTime.slice(0, 5)}</span></th><td>{`온라인 ${bucket.pools.onlineHold} · 현장 ${bucket.pools.onsite} · 공유 ${bucket.pools.shared}`}<br /><Badge tone="neutral">v{bucket.policyVersion}</Badge></td><td>온라인 사용 가능 {bucket.availableOnlineQuantity}</td><td><Badge tone={bucket.availabilityStatus === 'AVAILABLE' ? 'positive' : 'negative'}>{INVENTORY_STATUS_LABEL[bucket.availabilityStatus]}</Badge></td><td><Button variant="ghost" size="sm" aria-label={`재고 ${bucket.inventoryBucketId} 수정`} onClick={() => { setCreating(false); setEditingId(bucket.inventoryBucketId) }}>수정</Button></td></tr>)}</tbody></table></div>
        <Pagination {...buckets.data.page} unitLabel="개" onChange={setPage} />
      </>}
    </SectionCard>
    {editingId !== null && buckets.data && (() => { const bucket = buckets.data.items.find((item) => item.inventoryBucketId === editingId); return bucket ? <InventoryPolicyForm key={`${bucket.inventoryBucketId}-${bucket.policyVersion}`} storeId={storeId} menus={menus.data ?? []} bucket={bucket} initial={draftFromBucket(bucket)} creating={false} onClose={() => setEditingId(null)} /> : null })()}
  </>
}

function draftFromBucket(bucket: MenuInventoryBucket): InventoryDraft {
  return { menuId: bucket.menuId, serviceDate: bucket.serviceDate, startTime: bucket.startTime.slice(0, 5), endDate: bucket.endDate, endTime: bucket.endTime.slice(0, 5), totalSupply: String(bucket.totalSupply), onlineHold: String(bucket.pools.onlineHold), onsite: String(bucket.pools.onsite), shared: String(bucket.pools.shared), sharedOnlineAllowed: bucket.sharedOnlineAllowed, availabilityStatus: bucket.availabilityStatus }
}

function InventoryPolicyForm({ storeId, menus, initial, creating, bucket, onClose }: { storeId: string; menus: readonly ManagedMenu[]; initial: InventoryDraft; creating: boolean; bucket?: MenuInventoryBucket; onClose: () => void }) {
  const [draft, setDraft] = useState(initial)
  const [error, setError] = useState<string | null>(null)
  const keys = useMemo(createIdempotencyKeyCache, [])
  const create = useCreateMenuInventoryBucket(storeId)
  const update = useUpdateMenuInventoryBucket(storeId, bucket?.inventoryBucketId ?? '')
  const pending = create.isPending || update.isPending
  const set = <K extends keyof InventoryDraft>(key: K, value: InventoryDraft[K]) => setDraft((current) => ({ ...current, [key]: value }))
  async function save() {
    const validation = validateInventoryDraft(draft, creating)
    setError(validation)
    if (validation) return
    try {
      if (creating) { const body = toInventoryCreate(draft); await create.mutateAsync({ body, idempotencyKey: keys.keyFor(JSON.stringify(body)) }) }
      else { const body = toInventoryUpdate(draft); await update.mutateAsync({ body, idempotencyKey: keys.keyFor(JSON.stringify({ id: bucket?.inventoryBucketId, body })) }) }
      onClose()
    } catch (caught) { setError(inventoryErrorMessage(caught)) }
  }
  return <SectionCard title={creating ? '새 재고 버킷' : `재고 버킷 #${bucket?.inventoryBucketId} 수정`} actions={<Button variant="ghost" onClick={onClose}>닫기</Button>}>
    {error && <Alert tone="error" title={error} />}
    {creating && <><SelectField label="메뉴" required value={draft.menuId} onChange={(event) => set('menuId', event.target.value)}><option value="">선택</option>{menus.map((menu) => <option key={menu.menuId} value={menu.menuId}>{primaryMenuVersion(menu)?.name ?? `메뉴 #${menu.menuId}`}</option>)}</SelectField><div className="op-form-grid"><TextField label="제공 시작 날짜" type="date" value={draft.serviceDate} onChange={(event) => set('serviceDate', event.target.value)} /><TextField label="제공 시작 시각" type="time" value={draft.startTime} onChange={(event) => set('startTime', event.target.value)} /><TextField label="제공 종료 날짜" type="date" value={draft.endDate} onChange={(event) => set('endDate', event.target.value)} /><TextField label="제공 종료 시각" type="time" value={draft.endTime} onChange={(event) => set('endTime', event.target.value)} /></div></>}
    {!creating && <p>{`${bucket?.serviceDate} ${bucket?.startTime.slice(0, 5)}–${bucket?.endDate} ${bucket?.endTime.slice(0, 5)} · 제공 구간은 수정할 수 없습니다.`}</p>}
    <div className="op-form-grid"><TextField label="총 공급" type="number" min="0" value={draft.totalSupply} onChange={(event) => set('totalSupply', event.target.value)} /><TextField label="온라인 홀드" type="number" min="0" value={draft.onlineHold} onChange={(event) => set('onlineHold', event.target.value)} /><TextField label="현장" type="number" min="0" value={draft.onsite} onChange={(event) => set('onsite', event.target.value)} /><TextField label="공유" type="number" min="0" value={draft.shared} onChange={(event) => set('shared', event.target.value)} /><SelectField label="판매 상태" value={draft.availabilityStatus} onChange={(event) => set('availabilityStatus', event.target.value as InventoryDraft['availabilityStatus'])}><option value="AVAILABLE">판매 가능</option><option value="SOLD_OUT">품절</option></SelectField></div>
    <label className="mi-check"><input type="checkbox" checked={draft.sharedOnlineAllowed} onChange={(event) => set('sharedOnlineAllowed', event.target.checked)} />공유 수량을 온라인에서도 사용</label>
    <div className="op-actions"><Button loading={pending} onClick={() => void save()}>{creating ? '재고 버킷 저장' : '재고 변경 저장'}</Button></div>
  </SectionCard>
}
