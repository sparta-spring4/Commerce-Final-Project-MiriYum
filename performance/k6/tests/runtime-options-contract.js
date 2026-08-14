import { check } from 'k6'

const mainSource = open('/scripts/main.js')

export const options = {
  thresholds: {
    checks: ['rate==1'],
  },
}

export default function () {
  check(null, {
    'VU cookie jars persist across iterations for the CSRF token cache': () =>
      /noCookiesReset\s*:\s*true/.test(mainSource),
  })
}
