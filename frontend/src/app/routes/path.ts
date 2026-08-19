export interface NavigationItem {
  label: string
  path: string
}

export function fillPath(
  pattern: string,
  params: Readonly<Record<string, string | number>>,
): string {
  let path = pattern
  for (const [name, value] of Object.entries(params)) {
    path = path.replace(`:${name}`, encodeURIComponent(String(value)))
  }
  return path
}
