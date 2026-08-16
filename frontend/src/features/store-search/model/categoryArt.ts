import asian from '../../../assets/categories/asian.webp'
import bar from '../../../assets/categories/bar.webp'
import cafeBakery from '../../../assets/categories/cafe-bakery.webp'
import chinese from '../../../assets/categories/chinese.webp'
import etc from '../../../assets/categories/etc.webp'
import japanese from '../../../assets/categories/japanese.webp'
import korean from '../../../assets/categories/korean.webp'
import western from '../../../assets/categories/western.webp'

/**
 * 매장 카테고리 일러스트.
 *
 * 프론트가 번들로 갖는 정적 자산이며 서버가 주는 데이터가 아니다. 1차 MVP
 * 계약에 이미지 필드가 없다는 제약은 **매장·메뉴 사진**에 대한 것이고(#157),
 * catalog code에 대응하는 장식 일러스트는 그 범위가 아니다.
 *
 * catalog는 서버가 소유하므로 코드가 늘어날 수 있다. 매핑에 없는 코드는
 * 그라디언트 타일로 떨어지며, 없는 이미지를 만들어 내지 않는다.
 * 표시명은 언제나 서버 `displayName`을 쓴다.
 */
const CATEGORY_ART: Readonly<Record<string, string>> = {
  KOREAN: korean,
  CHINESE: chinese,
  JAPANESE: japanese,
  WESTERN: western,
  ASIAN: asian,
  CAFE_BAKERY: cafeBakery,
  BAR: bar,
  ETC: etc,
}

export function categoryArt(code: string): string | null {
  return CATEGORY_ART[code] ?? null
}

/**
 * 일러스트가 없는 코드의 대체 색조.
 *
 * 코드 문자열에서 결정적으로 고른다. 순서(index)로 고르면 목록이 정렬되거나
 * 필터링될 때 같은 카테고리의 색이 바뀐다.
 */
export function categoryTint(code: string): { from: string; to: string } {
  let hash = 0
  for (let i = 0; i < code.length; i += 1) {
    hash = (hash * 31 + code.charCodeAt(i)) % 997
  }
  return TILE_TINTS[hash % TILE_TINTS.length]
}

const TILE_TINTS = [
  { from: '#ffdad2', to: '#ffb4a2' },
  { from: '#ffdea8', to: '#ffba20' },
  { from: '#ffb4a2', to: '#ff5f38' },
  { from: '#e9e1dc', to: '#cdc5c0' },
] as const
