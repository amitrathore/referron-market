const { test, expect } = require('@playwright/test');
const path = require('path');
const fs = require('fs');

async function openAgreement(page, { signed = false, seated = false } = {}) {
  const posts = [];
  let acceptance = null;
  await page.route('https://referron.test/**', async route => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    if (pathname === '/ui/public-state') return route.fulfill({ json: { gameState: { attention: { feed: [] }, market: { capabilities: {} } } } });
    if (pathname === '/session') return route.fulfill({ json: { authenticated: signed, selectedSeat: seated ? { playerName: 'applicant', playerRoles: ['audience'] } : null } });
    if (pathname === '/auth.json') return route.fulfill({ json: { auth_base_url: 'https://auth.example.test' } });
    if (pathname === '/api/seller/agreement') return route.fulfill({ json: { version: '1.0', sha256: 'test-digest', html: fs.readFileSync(path.join(__dirname, '../resources/ui/assets/legal/seller-agreement-v1.html'), 'utf8') } });
    if (pathname === '/api/seller/agreement/status') return route.fulfill({ json: { acceptance } });
    if (pathname === '/api/seller/agreement/accept') {
      posts.push(request.postDataJSON());
      acceptance = { id: 'accept-1', version: '1.0', sha256: 'test-digest', status: 'pending-operator-activation', 'accepted-at': '2026-10-12T19:00:00Z', 'seller-legal-name': 'Example Inc', 'signer-name': 'Alex Example' };
      return route.fulfill({ json: { acceptance } });
    }
    if (pathname.startsWith('/assets/')) {
      return route.fulfill({ path: path.join(__dirname, '../resources/ui', pathname) });
    }
    return route.fulfill({ path: path.join(__dirname, '../resources/ui/index.html'), contentType: 'text/html' });
  });
  await page.goto('/seller-agreement');
  return posts;
}

test('public agreement is shareable from footer before sign in', async ({ page }) => {
  await openAgreement(page);
  await expect(page.getByRole('heading', { name: 'Seller Agreement', exact: true })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Seller Agreement sections' }).getByRole('link')).toHaveCount(12);
  await expect(page.getByRole('navigation', { name: 'Seller Agreement sections' }).getByRole('link', { name: '2. Commercial schedule' })).toHaveAttribute('href', '#seller-section-2');
  await expect(page.locator('footer').getByRole('link', { name: 'Seller Agreement' })).toHaveAttribute('href', '/seller-agreement');
  await expect(page.getByRole('link', { name: 'Sign in to apply' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'I Accept' })).toHaveCount(0);
});

test('signed in seller records acceptance and waits for activation', async ({ page }) => {
  const posts = await openAgreement(page, { signed: true, seated: true });
  await page.getByLabel('Seller legal name').fill('Example Inc');
  await page.getByLabel('Your full legal name').fill('Alex Example');
  await page.getByLabel('Your title or authority (for a business)').fill('CEO');
  await page.getByLabel('Notice email').fill('alex@example.com');
  await page.getByLabel('Notice mailing address').fill('1 Market St, Ohio');
  await page.getByLabel(/I am the Seller or am authorized/).check();
  await page.getByRole('button', { name: 'I Accept' }).click();
  await expect(page.getByText(/Status: Pending Referron Market review/)).toBeVisible();
  expect(posts).toHaveLength(1);
  expect(posts[0]).toMatchObject({ version: '1.0', sha256: 'test-digest', accepted: true, 'seller-legal-name': 'Example Inc', 'signer-name': 'Alex Example' });
});

test('operator can review a pending seller from a direct link', async ({ page }) => {
  const decisions = [];
  let status = 'pending-operator-activation';
  await page.route('https://referron.test/**', async route => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    if (pathname === '/ui/public-state') return route.fulfill({ json: { gameState: { attention: { feed: [] }, market: { capabilities: {} } } } });
    if (pathname === '/session') return route.fulfill({ json: { authenticated: true, selectedSeat: { playerName: 'reviewer', playerRoles: ['audience', 'operator'] } } });
    if (pathname === '/auth.json') return route.fulfill({ json: {} });
    if (pathname === '/api/operator/sellers') return route.fulfill({ json: { applications: [{ id: 'accept-1', status, 'seller-legal-name': 'Example Inc', 'player-name': 'applicant', 'signer-name': 'Alex Example', 'signer-title': 'CEO', 'seller-type': 'business', 'accepted-at': '2026-10-12T19:00:00Z', version: '1.0', sha256: 'test-digest', 'notice-email': 'alex@example.com', 'notice-address': '1 Market St' }] } });
    if (pathname === '/api/operator/sellers/review') {
      decisions.push(request.postDataJSON());
      status = 'approved';
      return route.fulfill({ json: { acceptance: { id: 'accept-1', status } } });
    }
    if (pathname.startsWith('/assets/')) return route.fulfill({ path: path.join(__dirname, '../resources/ui', pathname) });
    return route.fulfill({ path: path.join(__dirname, '../resources/ui/index.html'), contentType: 'text/html' });
  });
  await page.goto('/operator/sellers');
  await expect(page.getByRole('heading', { name: 'Seller reviews' })).toBeVisible();
  await page.getByLabel('Review note').fill('Authority verified');
  await page.getByRole('button', { name: 'Approve for activation' }).click();
  await expect(page.getByRole('button', { name: 'Mark activated' })).toBeVisible();
  expect(decisions).toEqual([{ 'acceptance-id': 'accept-1', decision: 'approved', note: 'Authority verified' }]);
});

test('phone layout keeps the agreement visible before the contents list', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openAgreement(page);
  const contents = page.locator('.seller-agreement-toc');
  await expect(contents).not.toHaveAttribute('open');
  await expect(page.getByRole('heading', { name: 'Seller Agreement', exact: true })).toBeVisible();
  await contents.locator('summary').click();
  await expect(contents.getByRole('link', { name: '12. Electronic acceptance and records' })).toBeVisible();
});

test('seller application appears above the agreement with a contextual reading link',async({page})=>{
  await page.setViewportSize({width:390,height:844});
  await openAgreement(page,{signed:true,seated:true});
  const panel=await page.locator('.seller-accept-panel').boundingBox();
  const terms=await page.locator('#seller-agreement-document').boundingBox();
  expect(panel.y+panel.height).toBeLessThanOrEqual(terms.y);
  expect(await page.getByLabel('Seller type').boundingBox()).toMatchObject({width:expect.any(Number)});
  expect((await page.getByLabel('Seller type').boundingBox()).y).toBeLessThan(844);
  await expect(page.getByRole('link',{name:'Read the agreement before accepting.'})).toHaveAttribute('href','#seller-agreement-document');
  await page.getByRole('link',{name:'Read the agreement before accepting.'}).click();
  await expect(page).toHaveURL(/#seller-agreement-document$/);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});
