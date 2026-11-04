const {test,expect}=require('@playwright/test');
const snapshot=require('../resources/catalog/yvatar.json');
const origin='http://127.0.0.1:4173';

test('Insights is discoverable and legacy links preserve referral attribution',async({page})=>{
  await page.goto(origin+'/');
  await page.getByRole('navigation',{name:'Main navigation'}).getByRole('link',{name:'Insights',exact:true}).click();
  await expect(page).toHaveURL(origin+'/insights');
  await expect(page.getByRole('heading',{name:'Insights',exact:true})).toBeVisible();
  await expect(page.getByText('No insights yet')).toBeVisible();
  await page.goto(origin+'/essays?ref=existing&campaign=launch');
  await expect(page).toHaveURL(origin+'/insights?ref=existing&campaign=launch');
  for(const width of [320,390,768,1440]){
    await page.setViewportSize({width,height:900});
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  }
});

test('Yvatar catalog matches the reviewed snapshot and cannot enter checkout',async({page})=>{
  await page.goto(origin+'/market');
  await expect(page.locator('.store-card').first()).toContainText('Yvatar Store');
  await page.locator('.store-card').first().click();
  await expect(page).toHaveURL(origin+'/market/yvatar');
  await expect(page.getByRole('heading',{name:'Yvatar Store',exact:true})).toBeVisible();
  await expect(page.locator('.storefront-header img')).toHaveJSProperty('complete',true);
  await expect(page.locator('.storefront-header img')).not.toHaveJSProperty('naturalWidth',0);
  const products=Object.values(snapshot.capabilities).sort((a,b)=>a['display-order']-b['display-order']);
  await expect(page.locator('.product-card h3')).toHaveText(products.map(p=>p.title));
  await expect(page.getByText('Purchasing on Referron Market is coming soon.')).toBeVisible();
  for(const product of products){
    await page.goto(origin+'/market/yvatar/products/'+product.id);
    await expect(page.getByRole('heading',{name:product.title,exact:true})).toBeVisible();
    await expect(page.getByRole('button',{name:'Coming soon',exact:true})).toBeDisabled();
    await expect(page.getByRole('button',{name:'Add to cart'})).toHaveCount(0);
  }
  for(const width of [320,390,768,1440]){
    await page.setViewportSize({width,height:900});
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  }
  await page.goto(origin+'/market/yvatar');
  await page.locator('.product-card img').evaluateAll(images=>Promise.all(images.map(image=>{
    image.loading='eager';return image.decode();
  })));
  await page.screenshot({path:test.info().outputPath('yvatar-store.png'),fullPage:true});
});

test('an explicitly empty fixture remains an empty market',async({page})=>{
  await page.route(origin+'/ui/public-state',route=>route.fulfill({json:require('./fixtures/empty-market.json')}));
  await page.goto(origin+'/market');
  await expect(page.locator('.store-card')).toHaveCount(0);
});
