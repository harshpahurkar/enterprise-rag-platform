import { expect, test, type Page } from './fixtures.ts'

const PASSWORD = process.env.E2E_PASSWORD ?? 'demo-password'
// The seeded HR document (hr-compensation-2026) has "compensation" in its title.
const HR_TITLE = /compensation/i

async function signIn(page: Page, username: string) {
  await page.goto('/')
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('banner')).toContainText(username)
}

async function search(page: Page, query: string) {
  await page.getByRole('link', { name: 'Search' }).click()
  await page.getByLabel('Search query').fill(query)
  await page.getByRole('button', { name: 'Search', exact: true }).click()
  // The summary echoes the query, so this waits for this search rather than an earlier one.
  await expect(page.getByTestId('search-summary')).toContainText(query)
}

const resultTitles = (page: Page) =>
  page.getByRole('list', { name: 'Search results' }).getByRole('heading').allTextContents()

test('engineer searches the runbook and sees ranked results with latency', async ({ page }) => {
  await signIn(page, 'engineer')
  await search(page, 'how do we roll back a deploy')

  await expect(page.getByRole('list', { name: 'Search results' }).getByRole('listitem').first()).toBeVisible()
  await expect(page.getByTestId('retrieval-latency')).toContainText(/Retrieved in \d+\s*ms/)
})

test('engineer never sees the HR compensation document in search results', async ({ page }) => {
  await signIn(page, 'engineer')
  await search(page, 'salary bands')

  const titles = await resultTitles(page)
  expect(titles.filter((title) => HR_TITLE.test(title))).toEqual([])
})

test('hr.manager sees the HR compensation document in Documents', async ({ page }) => {
  await signIn(page, 'hr.manager')
  await page.getByRole('link', { name: 'Documents' }).click()

  await expect(page.getByRole('rowheader', { name: HR_TITLE }).first()).toBeVisible()
})
