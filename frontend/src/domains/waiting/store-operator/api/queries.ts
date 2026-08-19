import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { storeOperatorKeys } from '../../../../app/shells/store-operator/queryKeys'
import { useStoreOperatorAuth } from '../../../../app/shells/store-operator/StoreOperatorAuthProvider'
import type { WaitingCommand } from '../model/transitions'
import { isClosureJobSettled } from '../model/types'
import type {
  WaitingClosureJob,
  WaitingDisableImpact,
  WaitingSetting,
  WaitingSettingUpdateRequest,
  WaitingTeamDetail,
  WaitingTeamPage,
  WaitingTeamStatus,
} from '../model/types'

/**
 * 웨이팅 query·mutation.
 *
 * 키는 운영자 셸의 `storeOperatorKeys`를 그대로 쓴다. 매장이 바뀔 때 접두사
 * 하나로 이전 매장의 query가 함께 격리되어야 하므로 별도 트리를 만들지 않는다.
 *
 * 실시간 구독(#250)은 아직 계약이 없다. 목록·상세는 명령 성공 뒤 서버를 다시
 * 조회해 수렴시키며, 화면이 응답을 기다리지 않고 상태를 미리 바꾸지 않는다.
 */

const SETTINGS_PATH =
  '/api/v1/store-operators/stores/{storeId}/waiting-settings' as const
const IMPACT_PATH =
  '/api/v1/store-operators/stores/{storeId}/waiting-settings/deactivation-impact' as const
const JOB_PATH =
  '/api/v1/store-operators/stores/{storeId}/waiting-closure-jobs/{jobId}' as const
const TEAMS_PATH =
  '/api/v1/store-operators/stores/{storeId}/waiting-teams' as const
const TEAM_PATH =
  '/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}' as const

/** 명령 이름과 하위 리소스 경로를 한 곳에서 맞춘다. */
const COMMAND_PATH = {
  call: '/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/calls',
  arrive:
    '/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/arrivals',
  'check-in':
    '/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/check-ins',
  cancel:
    '/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/cancellations',
} as const

export function useWaitingSettings(storeId: string, enabled = true) {
  const { apiClient } = useStoreOperatorAuth()

  return useQuery({
    queryKey: storeOperatorKeys.waitingSettings(storeId),
    enabled: enabled && storeId.length > 0,
    queryFn: async ({ signal }): Promise<WaitingSetting> => {
      const response = await apiClient(SETTINGS_PATH, {
        method: 'get',
        pathParams: { storeId },
        signal,
      })
      return response.data
    },
  })
}

/**
 * 비활성화 영향 조회.
 *
 * 끄기 직전에만 필요하므로 기본으로 켜 두지 않는다. 화면이 확인 단계에 들어갈 때
 * `enabled`를 올린다.
 */
export function useWaitingDisableImpact(storeId: string, enabled: boolean) {
  const { apiClient } = useStoreOperatorAuth()

  return useQuery({
    queryKey: storeOperatorKeys.waitingDisableImpact(storeId),
    enabled: enabled && storeId.length > 0,
    // 확인 단계를 다시 열면 그동안 바뀐 활성 팀 수를 새로 읽는다.
    staleTime: 0,
    gcTime: 0,
    queryFn: async ({ signal }): Promise<WaitingDisableImpact> => {
      const response = await apiClient(IMPACT_PATH, {
        method: 'get',
        pathParams: { storeId },
        signal,
      })
      return response.data
    },
  })
}

/** 종결 작업을 다시 읽는 간격. */
export const CLOSURE_JOB_POLL_MS = 2_000

/**
 * 비동기 일괄 종결 작업의 현재 상태.
 *
 * 202는 "접수했다"까지만 말한다. 그 응답을 그대로 들고 있으면 화면이 영원히
 * 최초 PENDING·PROCESSING에 멈춘다. 그래서 202가 준 값을 첫 표시로만 쓰고,
 * 서버가 종결로 넘길 때까지 jobId로 다시 읽는다.
 *
 * 종결 상태에서는 폴링을 멈춘다. 백엔드가 그 뒤로 상태를 바꾸지 않으므로 계속
 * 읽어도 같은 값이고, 운영자가 화면을 열어 둔 동안 요청만 쌓인다.
 */
export function useWaitingClosureJob(
  storeId: string,
  jobId: string | null,
  initialJob?: WaitingClosureJob,
) {
  const { apiClient } = useStoreOperatorAuth()

  return useQuery({
    queryKey: storeOperatorKeys.waitingClosureJob(storeId, jobId ?? ''),
    enabled: jobId !== null && storeId.length > 0,
    // 202 응답을 첫 화면으로 쓴다. 첫 조회가 돌아오기 전 빈 자리를 만들지 않는다.
    initialData: initialJob,
    queryFn: async ({ signal }): Promise<WaitingClosureJob> => {
      const response = await apiClient(JOB_PATH, {
        method: 'get',
        pathParams: { storeId, jobId: jobId ?? '' },
        signal,
      })
      return response.data
    },
    refetchInterval: (query) => {
      // 실패한 조회를 자동으로 계속 두드리면 영구 오류에서도 폴링이 끝나지
      // 않는다. 화면에서 오류를 알리고 운영자가 명시적으로 다시 시도하게 한다.
      if (query.state.error !== null) {
        return false
      }
      const job = query.state.data
      return job !== undefined && isClosureJobSettled(job)
        ? false
        : CLOSURE_JOB_POLL_MS
    },
  })
}

export interface WaitingTeamQuery {
  status?: WaitingTeamStatus
  /** 직전 페이지가 준 opaque cursor. 첫 페이지는 보내지 않는다. */
  cursor?: string
  size: number
}

export function useWaitingTeams(storeId: string, query: WaitingTeamQuery) {
  const { apiClient } = useStoreOperatorAuth()

  return useQuery({
    queryKey: storeOperatorKeys.waitingTeamPage(storeId, query),
    enabled: storeId.length > 0,
    queryFn: async ({ signal }): Promise<WaitingTeamPage> => {
      const response = await apiClient(TEAMS_PATH, {
        method: 'get',
        pathParams: { storeId },
        query: {
          status: query.status,
          cursor: query.cursor,
          size: query.size,
        },
        signal,
      })
      return response.data
    },
    // 페이지를 넘길 때 목록이 빈 화면으로 깜빡이지 않게 이전 결과를 유지한다.
    placeholderData: (previous) => previous,
  })
}

export function useWaitingTeam(storeId: string, waitingTeamId: string) {
  const { apiClient } = useStoreOperatorAuth()

  return useQuery({
    queryKey: storeOperatorKeys.waitingTeam(storeId, waitingTeamId),
    enabled: storeId.length > 0 && waitingTeamId.length > 0,
    queryFn: async ({ signal }): Promise<WaitingTeamDetail> => {
      const response = await apiClient(TEAM_PATH, {
        method: 'get',
        pathParams: { storeId, waitingTeamId },
        signal,
      })
      return response.data
    },
  })
}

/**
 * 설정 전체 교체.
 *
 * 성공 응답은 두 모양이다. 200이면 새 설정, 202면 활성 팀 일괄 종결 작업이다.
 * client가 status를 노출하지 않으므로 호출부가 `isClosureJob`으로 가른다.
 */
export function useUpdateWaitingSettings(storeId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (input: {
      body: WaitingSettingUpdateRequest
      idempotencyKey: string
    }): Promise<WaitingSetting | WaitingClosureJob> => {
      const response = await apiClient(SETTINGS_PATH, {
        method: 'put',
        pathParams: { storeId },
        body: input.body,
        idempotencyKey: input.idempotencyKey,
      })
      /*
       * client의 성공 타입은 200 본문만 좁혀 준다(`paths.ts`의 SuccessStatus).
       * 이 operation은 202로 종결 작업도 반환하므로 여기서만 합집합으로 넓힌다.
       * 넓히는 자리를 한 곳으로 묶어 두어야 호출부가 두 모양을 반드시 가르게 된다.
       */
      return response.data as WaitingSetting | WaitingClosureJob
    },
    onSuccess: () => {
      invalidateWaitingSettings(queryClient, storeId)
    },
    /*
     * 실패해도 설정을 다시 읽는다. version 충돌이면 최신 값이 필요하고, 다른
     * 실패여도 서버가 이미 바뀌었을 수 있다. 화면은 입력을 지우지 않는다.
     */
    onError: () => {
      invalidateWaitingSettings(queryClient, storeId)
    },
  })
}

/**
 * 팀 상태 전이 명령.
 *
 * 성공하면 상세와 목록을 모두 다시 조회한다. 한쪽만 갱신하면 목록의 순번·상태와
 * 상세가 서로 다른 시점을 가리킨다.
 */
export function useWaitingTeamCommand(storeId: string, waitingTeamId: string) {
  const { apiClient } = useStoreOperatorAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (input: {
      command: WaitingCommand
      expectedVersion: number
      idempotencyKey: string
    }): Promise<WaitingTeamDetail> => {
      const response = await apiClient(COMMAND_PATH[input.command], {
        method: 'post',
        pathParams: { storeId, waitingTeamId },
        body: { expectedVersion: input.expectedVersion },
        idempotencyKey: input.idempotencyKey,
      })
      return response.data
    },
    onSettled: () => {
      // 성공·실패 모두 서버를 정본으로 다시 읽는다. 낙관 확정하지 않는다.
      void queryClient.invalidateQueries({
        queryKey: storeOperatorKeys.waitingTeam(storeId, waitingTeamId),
      })
      void queryClient.invalidateQueries({
        queryKey: storeOperatorKeys.waitingTeamPages(storeId),
      })
    },
  })
}

function invalidateWaitingSettings(
  queryClient: QueryClient,
  storeId: string,
): void {
  void queryClient.invalidateQueries({
    queryKey: storeOperatorKeys.waitingSettings(storeId),
  })
  /*
   * 일괄 종결은 원장을 바꾼다. 설정만 갱신하면 목록이 종결 전 상태로 남는다.
   */
  void queryClient.invalidateQueries({
    queryKey: storeOperatorKeys.waitingTeams(storeId),
  })
}
