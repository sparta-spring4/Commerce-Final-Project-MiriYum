import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fillPath } from '../../../../app/routes/path'
import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { createIdempotencyKeyCache } from '../../../../shared/api/idempotencyKey'
import { Button } from '../../../../shared/ui/Button'
import { Alert, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { useAdoptStoreFromRoute } from '../../../../app/shells/store-operator/CurrentStoreProvider'
import { useCreateMenu, useManagedMenu, useUpdateMenuDraft } from '../api/menuQueries'
import { useManagedStore, useOperatorCatalog } from '../api/queries'
import {
  createMenuDraftForm,
  menuFormFromVersion,
  toMenuWriteRequest,
  validateMenuDraft,
  type MenuDraftForm,
} from '../model/menuDraft'
import { editableMenuVersion } from '../model/menuFilters'
import { storeErrorMessage } from '../model/storeErrors'
import type { ManagedMenu } from '../model/types'
import { MenuCommandPanel } from './MenuCommandPanel'
import { MenuContentForm } from './MenuContentForm'
import { MenuImagePanel } from './MenuImagePanel'
import { PageHeader } from '../../../../app/shells/store-operator/OperatorPage'

/**
 * 메뉴 등록·수정.
 *
 * 등록은 `POST`로 초안을 만들고, 수정은 `PUT`으로 초안 내용을 대체한다. 어느
 * 쪽도 게시하지 않는다. 게시는 별도 명령이며 오른쪽 패널이 담당한다.
 */
export function MenuEditorPage() {
  const { storeId = '', menuId } = useParams<{
    storeId: string
    menuId?: string
  }>()
  useAdoptStoreFromRoute(storeId)

  const storeQuery = useManagedStore(storeId)
  const menuQuery = useManagedMenu(storeId, menuId ?? '', menuId !== undefined)

  if (storeQuery.isPending || (menuId !== undefined && menuQuery.isPending)) {
    return <Loading label="메뉴 정보를 불러오는 중입니다." />
  }
  if (storeQuery.isError) {
    return (
      <ErrorState
        error={storeQuery.error}
        message={storeErrorMessage(storeQuery.error)}
        onRetry={() => void storeQuery.refetch()}
      />
    )
  }
  if (menuId !== undefined && menuQuery.isError) {
    return (
      <ErrorState
        error={menuQuery.error}
        message={storeErrorMessage(menuQuery.error)}
        onRetry={() => void menuQuery.refetch()}
      />
    )
  }

  return (
    <MenuEditor
      storeId={storeId}
      timeZoneId={storeQuery.data.timeZoneId}
      menu={menuId === undefined ? null : (menuQuery.data ?? null)}
    />
  )
}

function MenuEditor({
  storeId,
  timeZoneId,
  menu,
}: {
  storeId: string
  timeZoneId: string
  menu: ManagedMenu | null
}) {
  const navigate = useNavigate()
  const categories = useOperatorCatalog('menu-categories')
  const createMenu = useCreateMenu(storeId)
  const updateDraft = useUpdateMenuDraft(storeId, menu?.menuId ?? '')
  const draftKeys = useMemo(createIdempotencyKeyCache, [])

  const [form, setForm] = useState<MenuDraftForm>(() => {
    if (menu === null) {
      return createMenuDraftForm()
    }
    const version = editableMenuVersion(menu)
    return version === null ? createMenuDraftForm() : menuFormFromVersion(version)
  })
  const [errors, setErrors] = useState<Readonly<Record<string, string>>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  async function handleSave(event: React.FormEvent) {
    event.preventDefault()

    const nextErrors = validateMenuDraft(form)
    setErrors(nextErrors)
    setFormError(null)
    setSaved(false)
    if (Object.keys(nextErrors).length > 0) {
      return
    }

    const body = toMenuWriteRequest(form)
    if (body === null) {
      setFormError('알레르기와 원산지 표시 여부를 선택해 주세요.')
      return
    }

    try {
      if (menu === null) {
        const created = await createMenu.mutateAsync({
          body,
          idempotencyKey: draftKeys.keyFor(JSON.stringify(body)),
        })
        void navigate(
          fillPath(STORE_OPERATOR_PATHS.menu, {
            storeId,
            menuId: created.menuId,
          }),
          { replace: true },
        )
        return
      }
      await updateDraft.mutateAsync({
        body,
        idempotencyKey: draftKeys.keyFor(JSON.stringify(body)),
      })
      setSaved(true)
    } catch (error) {
      setFormError(storeErrorMessage(error))
    }
  }

  const saving = createMenu.isPending || updateDraft.isPending

  return (
    <>
      <PageHeader
        title={menu === null ? '새 메뉴' : '메뉴 수정'}
        description="저장하면 초안으로 보관되고, 게시 시점은 따로 정합니다."
        actions={
          <Link
            className="mi-button mi-button--ghost"
            to={fillPath(STORE_OPERATOR_PATHS.menus, { storeId })}
          >
            메뉴 목록
          </Link>
        }
      />

      <div className="op-grid op-grid--aside">
        <form onSubmit={handleSave} aria-label="메뉴 내용" noValidate>
          <div className="op-stack">
            {formError !== null && <Alert tone="error" title={formError} />}
            {saved && (
              <Alert tone="info" title="초안을 저장했습니다.">
                <p>
                  아직 고객에게 보이지 않습니다. 오른쪽에서 게시 시점을 정해 주세요.
                </p>
              </Alert>
            )}

            <MenuContentForm
              form={form}
              errors={errors}
              categories={categories.data}
              categoriesLoading={categories.isPending}
              onChange={(next) => {
                setForm(next)
                setSaved(false)
              }}
            />

            <div className="op-actions">
              <Button type="submit" variant="primary" loading={saving}>
                {menu === null ? '메뉴 초안 만들기' : '초안 저장'}
              </Button>
            </div>
          </div>
        </form>

        <div className="op-stack">
          {menu === null ? (
            <Alert tone="info" title="먼저 초안을 만들어 주세요.">
              <p>
                메뉴를 만들면 게시·노출·판매 상태 명령이 이 자리에 열립니다.
              </p>
            </Alert>
          ) : (
            <>
              <MenuImagePanel storeId={storeId} menuId={menu.menuId} />
              <MenuCommandPanel
                storeId={storeId}
                menu={menu}
                timeZoneId={timeZoneId}
              />
            </>
          )}
        </div>
      </div>
    </>
  )
}
