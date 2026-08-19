/**
 * 소비자 웨이팅 등록 화면의 UI 전용 뷰 모델.
 *
 * 이 파일은 API를 호출하지 않는다. 화면이 표현해야 하는 상태의 이름과 각
 * 상태의 사용자 안내 문구만 소유하고, 실제 값은 화면을 감싸는 컨테이너가
 * availability·위치 증빙·등록 응답에서 옮겨 담아 넘긴다.
 *
 * 상태 이름은 활성 Waiting OpenAPI가 선언한 판정 결과와 1:1로 맞춘다. 화면이
 * 자기 이름을 새로 만들면 계약이 바뀔 때 어긋난 안내를 하게 된다.
 */

/** 접수 가능 여부. `checking`은 아직 서버 판정을 받지 못한 상태다. */
export type WaitingReceptionState = 'checking' | 'accepting' | 'closed'

/**
 * 등록 진행 단계.
 *
 * `locating`은 현재 위치를 확인하는 중, `registering`은 위치 증빙을 받은 뒤
 * 웨이팅을 등록하는 중이다. 두 단계를 하나로 합치지 않는다. 사용자가 기다리는
 * 이유가 서로 다르고, 실패했을 때 되돌아갈 지점도 다르다.
 */
export type WaitingRegistrationProgress =
  | 'idle'
  | 'locating'
  | 'registering'
  | 'succeeded'

/**
 * 등록을 막은 사유.
 *
 * 앞의 여섯 개는 위치 증빙 응답의 `resultCategory`에서, `LOCATION_PROOF_INVALID`는
 * 등록 요청의 `409 WAITING_013`에서 온다. `REQUEST_FAILED`는 그 밖의 API 실패다.
 * 어떤 사유도 성공으로 우회하지 않는다.
 */
export type WaitingRegistrationNoticeCode =
  | 'PERMISSION_DENIED'
  | 'POSITION_UNAVAILABLE'
  | 'OUTSIDE_RADIUS'
  | 'ACCURACY_INSUFFICIENT'
  | 'MEASUREMENT_STALE'
  | 'MANIPULATION_SUSPECTED'
  | 'LOCATION_PROOF_INVALID'
  | 'REQUEST_FAILED'

export interface WaitingRegistrationNotice {
  code: WaitingRegistrationNoticeCode
  /**
   * 서버가 준 문구. 있으면 설명 자리에 그대로 쓴다.
   * 없으면 아래 표의 기본 설명을 쓴다. 화면이 사유를 지어내지 않는다.
   */
  message?: string
}

/** 사유별 복구 경로. 위치를 다시 확인할지, 같은 요청을 다시 보낼지 정한다. */
export type WaitingRegistrationRetry = 'location' | 'submit'

export interface WaitingRegistrationGuidance {
  title: string
  description: string
  retry: WaitingRegistrationRetry
}

/**
 * 사유별 안내 문구와 복구 경로.
 *
 * 위치 판정에서 온 사유는 모두 위치를 다시 확인해야 풀린다. 같은 요청을 다시
 * 보내도 판정 결과가 그대로이기 때문이다. 증빙 만료도 마찬가지로 재측정이지
 * 재전송이 아니다.
 */
export const WAITING_REGISTRATION_GUIDANCE: Record<
  WaitingRegistrationNoticeCode,
  WaitingRegistrationGuidance
> = {
  PERMISSION_DENIED: {
    title: '위치 권한이 거부되었습니다.',
    description:
      '브라우저 설정에서 이 사이트의 위치 권한을 허용한 뒤 다시 확인해 주세요.',
    retry: 'location',
  },
  POSITION_UNAVAILABLE: {
    title: '현재 위치를 확인하지 못했습니다.',
    description:
      '기기가 위치를 받지 못했습니다. 잠시 후 다시 확인해 주세요.',
    retry: 'location',
  },
  OUTSIDE_RADIUS: {
    title: '매장에서 3km 넘게 떨어져 있습니다.',
    description: '매장 근처에서 다시 확인해 주세요.',
    retry: 'location',
  },
  ACCURACY_INSUFFICIENT: {
    title: '위치 정확도가 부족합니다.',
    description:
      '실내에서는 위치가 정확하지 않을 수 있습니다. 창가나 실외로 이동한 뒤 다시 확인해 주세요.',
    retry: 'location',
  },
  MEASUREMENT_STALE: {
    title: '확인한 위치가 오래되었습니다.',
    description: '현재 위치를 다시 확인해 주세요.',
    retry: 'location',
  },
  MANIPULATION_SUSPECTED: {
    title: '위치 정보를 신뢰할 수 없습니다.',
    description:
      '위치를 바꾸는 앱이나 확장 프로그램을 끈 뒤 다시 확인해 주세요.',
    retry: 'location',
  },
  LOCATION_PROOF_INVALID: {
    title: '위치 확인이 만료되었습니다.',
    description:
      '위치 확인은 잠시 동안만 유효합니다. 현재 위치를 다시 확인한 뒤 등록해 주세요.',
    retry: 'location',
  },
  REQUEST_FAILED: {
    title: '웨이팅을 등록하지 못했습니다.',
    description: '잠시 후 다시 시도해 주세요.',
    retry: 'submit',
  },
}

/** 등록 성공 결과. 화면이 보여 주는 값만 받는다. */
export interface WaitingRegistrationResult {
  queueSequence: number
  teamsAhead: number
}

/**
 * 계약의 인원 하한. 상한은 웨이팅 등록 계약에 없으므로 화면이 만들지 않는다.
 * 예약의 인원 상한을 여기로 가져오면 계약에 없는 거절을 화면이 하게 된다.
 */
export const MIN_WAITING_PARTY_SIZE = 1

/**
 * 인원수 입력을 검증한다. 통과하면 `null`을 준다.
 *
 * 하한만 검사한다. 서버가 거절할 값을 미리 막는 것이 목적이고, 서버가 받는
 * 값을 화면이 대신 거절하지 않는다.
 */
export function validatePartySize(value: number): string | null {
  if (!Number.isInteger(value)) {
    return '인원수는 정수로 입력해 주세요.'
  }
  if (value < MIN_WAITING_PARTY_SIZE) {
    return '방문 인원을 한 명 이상 입력해 주세요.'
  }
  return null
}

/**
 * 인원 입력의 문자열을 숫자로 읽는다.
 *
 * 빈 입력은 0으로 읽어 검증 문구가 뜨게 한다. 빈 값을 마지막 유효값으로
 * 되돌리면 사용자가 지운 사실이 화면에서 사라진다.
 */
export function readPartySize(text: string): number {
  const digits = text.replace(/[^0-9]/g, '')
  if (digits.length === 0) {
    return 0
  }
  return Number.parseInt(digits, 10)
}
