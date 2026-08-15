import { check } from 'k6'

import { COOKIE_LIFETIME_OPTIONS } from '../lib/runtime-options.js'

const mainSource = open('/scripts/main.js')

export const options = {
  thresholds: {
    checks: ['rate==1'],
  },
}

export default function () {
  check(null, {
    'VU cookie jars persist across iterations for the CSRF token cache': () =>
      COOKIE_LIFETIME_OPTIONS.noCookiesReset === true,
    'main.js applies the cookie lifetime options': () =>
      /\.\.\.COOKIE_LIFETIME_OPTIONS/.test(mainSource),
  })
}
