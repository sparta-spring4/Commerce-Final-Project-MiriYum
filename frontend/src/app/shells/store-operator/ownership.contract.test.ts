import { describe, expect, test } from 'vitest'

const STORE_OPERATOR_DOMAIN_MODULES = import.meta.glob(
  '../../../domains/{reservation,waiting}/store-operator/**/*.{ts,tsx}',
  { query: '?raw', import: 'default', eager: true },
) as Record<string, string>

function importSpecifiers(source: string): string[] {
  return [...source.matchAll(/(?:from\s*|import\s*)['"]([^'"]+)['"]/g)].map(
    (match) => match[1],
  )
}

describe('매장 운영자 도메인 소유권', () => {
  test('reservation·waiting은 store barrel이나 store 전용 UI를 소비하지 않는다', () => {
    const violations = Object.entries(STORE_OPERATOR_DOMAIN_MODULES).flatMap(
      ([modulePath, source]) =>
        modulePath.includes('.test.')
          ? []
          : importSpecifiers(source).flatMap((specifier) =>
              /(?:^|\/)store\/store-operator(?:$|\/ui\/)/.test(specifier)
                ? [`${modulePath} -> ${specifier}`]
                : [],
            ),
    )

    expect(violations).toEqual([])
  })
})
