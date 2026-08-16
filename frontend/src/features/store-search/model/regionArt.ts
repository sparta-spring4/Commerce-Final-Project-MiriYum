import busan from '../../../assets/regions/busan.webp'
import daegu from '../../../assets/regions/daegu.webp'
import daejeon from '../../../assets/regions/daejeon.webp'
import gwangju from '../../../assets/regions/gwangju.webp'
import seoul from '../../../assets/regions/seoul.webp'
import type { Region } from './searchParams'

/**
 * 지역 랜드마크 일러스트.
 *
 * catalog와 달리 `Region`은 OpenAPI가 고정한 enum이라 값이 늘지 않는다.
 * 그래서 다섯 값을 모두 채운 Record로 두고, 빠지면 typecheck가 잡게 한다.
 *
 * 프론트 번들의 정적 장식이며 서버가 주는 데이터가 아니다.
 */
const REGION_ART: Record<Region, string> = {
  SEOUL: seoul,
  BUSAN: busan,
  DAEGU: daegu,
  DAEJEON: daejeon,
  GWANGJU: gwangju,
}

export function regionArt(region: Region): string {
  return REGION_ART[region]
}
