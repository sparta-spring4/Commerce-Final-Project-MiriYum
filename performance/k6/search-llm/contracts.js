function object(name, value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${name} is invalid`)
  return value
}
function success(response) {
  const value = object('response', response)
  if (value.code !== 'SUCCESS' || typeof value.message !== 'string') throw new Error('success envelope is invalid')
  return object('data', value.data)
}

function optionalStoreIds(name, value) {
  if (value === undefined) return null
  if (!Array.isArray(value) || value.length === 0
    || value.some((storeId) => !Number.isInteger(storeId) || storeId < 1)
    || new Set(value).size !== value.length) {
    throw new Error(`${name} is invalid`)
  }
  return value
}

function validateSearchExpectation(expected, minimumItems) {
  const included = optionalStoreIds('expectedStoreIds', expected.expectedStoreIds)
  const ordered = optionalStoreIds('expectedOrderedStoreIds', expected.expectedOrderedStoreIds)
  const excluded = optionalStoreIds('excludedStoreIds', expected.excludedStoreIds)
  const maximum = expected.maximumItems
  if (maximum !== undefined
    && (!Number.isInteger(maximum) || maximum < 0 || maximum < minimumItems)) {
    throw new Error('maximumItems is invalid')
  }
  const positive = new Set([...(included || []), ...(ordered || [])])
  if (excluded?.some((storeId) => positive.has(storeId))) {
    throw new Error('search store expectations conflict')
  }
  return included !== null || ordered !== null || excluded !== null || maximum !== undefined
}

export function validateIntegratedSearchResponse(response, expected = {}) {
  const data = success(response)
  if (!Array.isArray(data.items) || !Array.isArray(data.warnings)
    || object('normalizedCondition', data.normalizedCondition) === null
    || typeof data.ruleVersion !== 'string' || typeof data.vocabularyVersion !== 'string'
    || !(data.rankingRuleVersion === null || typeof data.rankingRuleVersion === 'string')
    || !(data.nextCursor === null || typeof data.nextCursor === 'string')) {
    throw new Error('integrated search response is invalid')
  }
  const storeIds = data.items.map((item) => {
    object('integrated search item', item)
    if (!Number.isInteger(item.storeId) || item.storeId < 1) {
      throw new Error('integrated search item storeId is invalid')
    }
    return item.storeId
  })
  if (new Set(storeIds).size !== storeIds.length) throw new Error('integrated search stores are duplicated')
  const included = expected.expectedStoreIds || []
  if (included.some((storeId) => !storeIds.includes(storeId))) {
    throw new Error('expected integrated search store is missing')
  }
  let previousIndex = -1
  for (const storeId of expected.expectedOrderedStoreIds || []) {
    const currentIndex = storeIds.indexOf(storeId)
    if (currentIndex < 0 || currentIndex <= previousIndex) {
      throw new Error('expected integrated search store order is invalid')
    }
    previousIndex = currentIndex
  }
  if ((expected.excludedStoreIds || []).some((storeId) => storeIds.includes(storeId))) {
    throw new Error('excluded integrated search store is present')
  }
  if (expected.maximumItems !== undefined && data.items.length > expected.maximumItems) {
    throw new Error('search result exceeds fixture maximum')
  }
  return Object.freeze({ itemCount: data.items.length })
}

function validateItem(item, mode, sourceStoreId, sourceUnitPrice) {
  object('alternative item', item)
  if (!Number.isInteger(item.unitPrice) || item.unitPrice < sourceUnitPrice * 0.8
    || item.unitPrice > sourceUnitPrice * 1.2) throw new Error('alternative price is outside 20 percent')
  if (!Number.isInteger(item.availableOnlineQuantity) || item.availableOnlineQuantity <= 0) throw new Error('alternative is not in stock')
  const breakdown = object('scoreBreakdown', item.scoreBreakdown)
  if (![breakdown.llmConcept, breakdown.secondaryCategory, breakdown.priceSimilarity]
    .every(Number.isInteger)
    || breakdown.llmConcept < 0 || breakdown.llmConcept > 50
    || breakdown.secondaryCategory < 0 || breakdown.secondaryCategory > 20
    || breakdown.priceSimilarity < 0 || breakdown.priceSimilarity > 30
    || item.alternativeScore !== breakdown.llmConcept + breakdown.secondaryCategory + breakdown.priceSimilarity) {
    throw new Error('alternative score breakdown is invalid')
  }
  if (mode === 'SAME_STORE' && item.storeId !== sourceStoreId) throw new Error('same-store candidate crossed stores')
  if (mode === 'NEARBY_STORE' && (item.storeId === sourceStoreId
    || typeof item.distanceMeters !== 'number' || item.distanceMeters < 0 || item.distanceMeters > 3000
    || item.coordinates === null)) throw new Error('nearby candidate is outside contract')
}

export function validateAlternativeResponse(response, expected) {
  const data = success(response)
  const modes = new Set(['SAME_STORE', 'NEARBY_STORE', 'NO_ALTERNATIVE', 'REGION_SELECTION_REQUIRED'])
  if (!modes.has(data.mode) || !Array.isArray(data.items)) throw new Error('alternative response is invalid')
  data.items.forEach((item) => validateItem(item, data.mode, expected.sourceStoreId, expected.sourceUnitPrice))
  for (let index = 1; index < data.items.length; index += 1) {
    const previous = data.items[index - 1]
    const current = data.items[index]
    if (previous.alternativeScore < current.alternativeScore
      || (data.mode === 'NEARBY_STORE' && previous.alternativeScore === current.alternativeScore
        && previous.distanceMeters > current.distanceMeters)) throw new Error('alternative ordering is invalid')
  }
  return Object.freeze({ mode: data.mode, itemCount: data.items.length })
}

export function validateSearchLlmFixture(fixture) {
  const value = object('fixture', fixture)
  if (!Array.isArray(value.cases) || value.cases.length === 0) throw new Error('fixture cases are required')
  const aliases = new Set()
  for (const entry of value.cases) {
    object('fixture case', entry)
    if (typeof entry.alias !== 'string' || !/^[A-Za-z0-9._-]+$/.test(entry.alias)
      || aliases.has(entry.alias) || !['exact', 'natural-language', 'same-store', 'nearby-store', 'fallback'].includes(entry.scenario)) {
      throw new Error('fixture case identity is invalid')
    }
    aliases.add(entry.alias)
    if (entry.scenario.includes('store') && (!Number.isInteger(entry.storeId)
      || !Number.isInteger(entry.menuId) || !Number.isInteger(entry.sourceUnitPrice)
      || object('request', entry.request) === null)) throw new Error('alternative fixture is invalid')
    if (entry.scenario === 'exact' || entry.scenario === 'natural-language') {
      if (typeof entry.searchInput !== 'string' || entry.searchInput.trim() === ''
        || !Number.isInteger(entry.minimumItems) || entry.minimumItems < 0) {
        throw new Error('search fixture is invalid')
      }
      const hasQualityExpectation = validateSearchExpectation(entry, entry.minimumItems)
      if (entry.scenario === 'natural-language' && !hasQualityExpectation) {
        throw new Error('natural-language quality expectation is required')
      }
    }
    if (entry.scenario === 'fallback') {
      if (!['disabled', 'timeout'].includes(entry.fallbackMode)
        || !['search', 'alternative'].includes(entry.fallbackTarget)) {
        throw new Error('fallback fixture control mode is invalid')
      }
      if (entry.fallbackTarget === 'search'
        && (typeof entry.searchInput !== 'string' || entry.searchInput.trim() === ''
          || !Number.isInteger(entry.minimumItems) || entry.minimumItems < 0)) {
        throw new Error('fallback search fixture is invalid')
      }
      if (entry.fallbackTarget === 'search') validateSearchExpectation(entry, entry.minimumItems)
      if (entry.fallbackTarget === 'alternative' && (!Number.isInteger(entry.storeId)
        || !Number.isInteger(entry.menuId) || !Number.isInteger(entry.sourceUnitPrice)
        || entry.request === null || typeof entry.request !== 'object' || Array.isArray(entry.request))) {
        throw new Error('fallback alternative fixture is invalid')
      }
    }
  }
  return Object.freeze({ cases: value.cases.map((entry) => Object.freeze({ ...entry })) })
}
