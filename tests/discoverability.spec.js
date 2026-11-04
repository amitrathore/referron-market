const {test,expect}=require('@playwright/test');
const origin=process.env.DISCOVERY_VERIFY_URL;
test.describe('Discoverability browser-to-protocol flow',()=>{
  test.skip(!origin,'Start the isolated Clojure verification server and set DISCOVERY_VERIFY_URL.');
  test('public content is readable without JavaScript',async({browser})=>{
    const context=await browser.newContext({javaScriptEnabled:false});
    const page=await context.newPage();
    await page.goto(origin+'/insights/useful?ref=example');
    await expect(page.getByRole('heading',{name:'Useful tools',exact:true})).toBeVisible();
    await expect(page.getByText('Real public content.')).toBeVisible();
    await expect(page.locator('main')).toHaveCount(1);
    await expect(page.locator('link[rel=canonical]')).toHaveAttribute('href','https://referron.coselling.ai/insights/useful');
    await expect(page).toHaveTitle(/Useful tools/);
    await context.close();
  });
  test('interactive rendering replaces initial content once',async({page})=>{
    await page.goto(origin+'/insights/useful');
    await expect(page.locator('#app')).not.toHaveAttribute('data-server-rendered','true');
    await expect(page.getByRole('heading',{name:'Useful tools',exact:true})).toHaveCount(1);
    await expect(page.getByText('Real public content.')).toHaveCount(1);
    await page.getByRole('navigation',{name:'Main navigation'}).getByRole('link',{name:'Market',exact:true}).click();
    await expect(page).toHaveURL(origin+'/market');
    await expect(page.getByRole('heading',{name:'Explore the market',exact:true})).toBeVisible();
  });
  test('operator draft stays private until exact revision approval',async({page,request})=>{
    await page.addInitScript(()=>localStorage.setItem('referron.token','preview-operator'));
    await page.goto(origin+'/operator/discoverability');
    await expect(page.getByRole('heading',{name:'Published artifacts'})).toBeVisible();
    const text='Browser verification '+Date.now()+': useful expertise builds productive networks.';
    await page.getByLabel('Narrative (Markdown)').fill(text);
    await page.getByRole('button',{name:'Submit narrative for review',exact:true}).click();
    await expect(page.locator('details[open] pre')).toHaveText(text);
    await expect(page.getByRole('button',{name:'Approve this revision',exact:true})).toBeVisible();
    expect(await (await request.get(origin+'/llms.txt')).text()).not.toContain(text);
    await page.getByRole('button',{name:'Approve this revision',exact:true}).click();
    await expect(page.getByRole('button',{name:'Approve this revision',exact:true})).toHaveCount(0);
    expect(await (await request.get(origin+'/llms.txt')).text()).toContain(text);
    await page.screenshot({path:test.info().outputPath('referron-discoverability-dashboard.png'),fullPage:true});
    await page.setViewportSize({width:390,height:844});
    expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  });
  test('guest cannot call operator API',async({request})=>{
    expect((await request.get(origin+'/api/operator/discoverability')).status()).toBe(403);
  });
});
