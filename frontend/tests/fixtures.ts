import { expect, test as base } from '@playwright/test'

export { expect }
export type { Page } from '@playwright/test'

// Every test fails if the browser reports a Content Security Policy violation, so a CSP change that breaks part of
// the SPA can't pass unnoticed.
export const test = base.extend<{ cspViolations: string[] }>({
  cspViolations: [
    async ({ page }, use) => {
      const violations: string[] = []
      page.on('console', (message) => {
        if (message.text().includes('Content Security Policy')) violations.push(message.text())
      })
      await use(violations)
      expect(violations, 'Content Security Policy violations').toEqual([])
    },
    { auto: true },
  ],
})
