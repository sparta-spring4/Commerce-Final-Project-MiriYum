import { createApiClient } from './client'

/**
 * 인증이 필요 없는 공개 조회 전용 client.
 *
 * 토큰 공급자를 주지 않는다. 공개 경로에 Authorization 헤더가 붙으면
 * 비회원 흐름이 계정 shell 상태에 얽히고, 만료 토큰이 401을 만들 수 있다.
 * 계정 shell별 client는 각 shell이 자기 토큰 공급자와 함께 따로 만든다.
 */
export const publicApiClient = createApiClient()
