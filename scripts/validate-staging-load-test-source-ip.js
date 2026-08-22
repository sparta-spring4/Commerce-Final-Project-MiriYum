#!/usr/bin/env node

const sourceIp = process.argv[2] ?? ''
const parts = sourceIp.split('.').map(Number)

const isIpv4 = parts.length === 4
  && parts.every((part) => Number.isInteger(part) && part >= 0 && part <= 255)
  && parts.join('.') === sourceIp

function isSpecialUseIpv4([first, second, third]) {
  return first === 0
    || first === 10
    || first === 127
    || first >= 224
    || (first === 100 && second >= 64 && second <= 127)
    || (first === 169 && second === 254)
    || (first === 172 && second >= 16 && second <= 31)
    || (first === 192 && second === 0)
    || (first === 192 && second === 2)
    || (first === 192 && second === 31 && third === 196)
    || (first === 192 && second === 52 && third === 193)
    || (first === 192 && second === 88 && third === 99)
    || (first === 192 && second === 168)
    || (first === 192 && second === 175 && third === 48)
    || (first === 198 && (second === 18 || second === 19))
    || (first === 198 && second === 51 && third === 100)
    || (first === 203 && second === 0 && third === 113)
}

if (!isIpv4 || isSpecialUseIpv4(parts)) {
  console.error('Use one globally routable IPv4 address.')
  process.exit(1)
}
