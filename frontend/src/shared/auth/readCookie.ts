/** 이름이 정확히 일치하는 쿠키 값만 읽는다. */
export function readCookie(name: string, cookieSource: string = document.cookie): string | null {
  for (const entry of cookieSource.split(';')) {
    const separator = entry.indexOf('=')
    if (separator < 0) continue
    if (entry.slice(0, separator).trim() !== name) continue
    return decodeURIComponent(entry.slice(separator + 1).trim())
  }
  return null
}
