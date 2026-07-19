import { expect, it } from 'vitest'
import { matchesInitialRoute, readInitialPayload } from '@/services/initialPayload'
import { homeInitialPayload, projectInitialPayload } from '../fixtures/publicSnapshots'

it('reads inert bootstrap once and removes the boundary node', () => {
  document.body.innerHTML = `<template id="__PORTFOLIO_DATA__">${JSON.stringify(homeInitialPayload).replaceAll('<', '\\u003c')}</template>`
  expect(readInitialPayload()).toEqual(homeInitialPayload)
  expect(document.querySelector('#__PORTFOLIO_DATA__')).toBeNull()
  expect(readInitialPayload()).toBeNull()
})

it('rejects malformed and mismatched data without executing it', () => {
  document.body.innerHTML = '<template id="__PORTFOLIO_DATA__">{"kind":"home"}</template>'
  expect(readInitialPayload()).toBeNull()
  expect(matchesInitialRoute(homeInitialPayload, { kind: 'home', locale: 'en' })).toBe(false)
  expect(matchesInitialRoute(projectInitialPayload, { kind: 'project', locale: 'en', slug: 'other' })).toBe(false)
})
