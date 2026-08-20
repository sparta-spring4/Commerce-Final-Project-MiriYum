/**
 * 페이지 번호만 달라진 재조회인지 판정한다.
 *
 * 공개 매장, 일반 사용자, 플랫폼 운영자의 목록 query가 함께 사용한다.
 * query key의 마지막 칸에 조건 객체를 두는 목록 query 전용이다.
 */
export function keepsSameListConditions(
  previousKey: unknown,
  next: object,
): boolean {
  if (!Array.isArray(previousKey)) {
    return false
  }
  const previous: unknown = previousKey[previousKey.length - 1]
  if (typeof previous !== 'object' || previous === null) {
    return false
  }
  return conditionSignature(previous) === conditionSignature(next)
}

function conditionSignature(query: object): string {
  return JSON.stringify(
    Object.entries(query)
      .filter(([field]) => field !== 'page')
      .sort(([a], [b]) => a.localeCompare(b)),
  )
}
