/**
 * 일반 사용자 인증 shell의 공개 진입점.
 *
 * 매장 운영자 shell은 별도 provider·가드·화면을 갖는다. 여기서 함께 내보내지 않는다.
 */
import './ui/auth.css'

export {
  ConsumerAuthProvider,
  useConsumerAuth,
  type ConsumerAuthStatus,
} from './ConsumerAuthProvider'
export { RequireConsumerAuth } from './RequireConsumerAuth'
export { ConsumerSignInPage } from './ui/ConsumerSignInPage'
export { ConsumerSignUpPage } from './ui/ConsumerSignUpPage'
export { ConsumerAccountMenu } from './ui/ConsumerAccountMenu'
