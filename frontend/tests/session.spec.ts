import { expect, test, type Page } from './fixtures.ts'

const PASSWORD = process.env.E2E_PASSWORD ?? 'demo-password'
const ENDED = 'Your session ended. Sign in again to continue.'
// The seeded HR document (hr-compensation-2026) has "compensation" in its title.
const HR_TITLE = /compensation/i

async function signIn(page: Page, username: string, password = PASSWORD) {
  await page.goto('/')
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
}

test('a 401 mid-session returns to login with a notice and clears the session', async ({ page }) => {
  await signIn(page, 'engineer')
  await expect(page.getByRole('banner')).toContainText('engineer')

  await page.route('**/api/documents', (route) => route.fulfill({ status: 401, json: { detail: 'Expired' } }))
  // All views stay mounted, so the documents query already ran at sign-in; reload to fetch it again.
  await page.getByRole('link', { name: 'Documents' }).click()
  await page.reload()

  await expect(page.getByRole('status').filter({ hasText: ENDED })).toBeVisible()
  expect(await page.evaluate(() => sessionStorage.getItem('erp.session'))).toBeNull()
})

test('a wrong password shows the incorrect message, not the session-ended notice', async ({ page }) => {
  await signIn(page, 'engineer', 'definitely-wrong')

  await expect(page.getByText('The username or password is incorrect.')).toBeVisible()
  await expect(page.getByText(ENDED)).toHaveCount(0)
})

test('a 429 on search shows the retry delay from Retry-After', async ({ page }) => {
  await signIn(page, 'engineer')
  await expect(page.getByRole('banner')).toContainText('engineer')

  await page.route('**/api/search', (route) =>
    route.fulfill({ status: 429, headers: { 'Retry-After': '30' }, json: { detail: 'rate limited' } }),
  )
  await page.getByRole('link', { name: 'Search' }).click()
  await page.getByLabel('Search query').fill('rollback')
  await page.getByRole('button', { name: 'Search', exact: true }).click()

  await expect(page.getByText('Too many requests. Try again in 30 seconds.')).toBeVisible()
})

// Signs in through the form already on screen. No page load, so only clearing the query cache can drop the
// previous user's data.
async function signInWithoutReload(page: Page, username: string) {
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('banner')).toContainText(username)
}

async function seeHrDocument(page: Page) {
  await signIn(page, 'hr.manager')
  await page.getByRole('link', { name: 'Documents' }).click()
  await expect(page.getByRole('rowheader', { name: HR_TITLE }).first()).toBeVisible()
}

// Holds the next documents request open, so a table could only be filled from a leftover cache.
async function expectNoLeftoverDocuments(page: Page) {
  await page.route('**/api/documents', () => {})
  await signInWithoutReload(page, 'engineer')
  await page.getByRole('link', { name: 'Documents' }).click()
  await expect(page.getByText('Loading documents…')).toBeVisible()
  await expect(page.getByRole('rowheader', { name: HR_TITLE })).toHaveCount(0)
}

test('signing out clears cached data, so the next user never sees it', async ({ page }) => {
  await seeHrDocument(page)
  await page.getByRole('button', { name: 'Sign out' }).click()
  expect(await page.evaluate(() => sessionStorage.getItem('erp.session'))).toBeNull()

  await expectNoLeftoverDocuments(page)
})

test('a 401 clears cached data as well as the session', async ({ page }) => {
  await seeHrDocument(page)
  await page.route('**/api/search', (route) => route.fulfill({ status: 401, json: { detail: 'Expired' } }))
  await page.getByRole('link', { name: 'Search' }).click()
  await page.getByLabel('Search query').fill('salary bands')
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: ENDED })).toBeVisible()

  await expectNoLeftoverDocuments(page)
})
