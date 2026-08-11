import { setupServer } from 'msw/node'

/**
 * 기본 핸들러는 두지 않는다. 각 테스트가 필요한 계약만 명시적으로 등록한다.
 * setup의 onUnhandledRequest: 'error'가 등록하지 않은 호출을 실패로 잡는다.
 *
 * backend에 구현되지 않은 경로(POST /api/v1/reservations, 픽업 전체 등)는
 * 여기서만 다룬다. production 코드에서 성공을 가정하지 않는다.
 */
export const server = setupServer()
