const {test,expect}=require('@playwright/test');
const path=require('path');
async function setup(page,{seller=false,empty=false,fail=false,upcoming=false}={}) {
  const profile={'display-name':'Builder tools',tagline:'Small tools. Useful outcomes.',description:'Independent tools for everyday work.', 'website-url':'https://example.com','support-email':'support@example.com'};
  const posts=[];
  const profiles=upcoming?{
    'liam-seller':{'display-name':'Liam'},
    'outcome-machines':{'display-name':'Outcome Machines','website-url':'https://outcomemachines.com'},
    opentangle:{'display-name':'Opentangle','website-url':'https://opentangle.ai'}
  }:{builder:profile};
  await page.route('https://referron.test/**',async route=>{
    const p=new URL(route.request().url()).pathname;
    if(p==='/ui/public-state') return route.fulfill({json:{gameState:{attention:{feed:[]},storefront:{profiles},market:{capabilities:empty?{}:{sku:{id:'sku',seller:upcoming?'liam-seller':'builder',title:'Useful tool',status:'active',commerce:{'checkout-enabled':!upcoming},terms:{price:{amount:1000,currency:'USD'}}}}}}}});
    if(p==='/session') return route.fulfill({json:{authenticated:seller,selectedSeat:seller?{playerName:'builder',playerRoles:['seller']}:null}});
    if(p==='/events') return route.fulfill({json:{success:true,'gm-result':{success:true,data:{carts:[]}}}});
    if(p==='/api/seller/storefront') {
      posts.push(route.request().postDataJSON());
      if(!fail) profiles.builder=posts.at(-1);
      return route.fulfill({status:fail?502:200,json:fail?{error:{message:'Try again later'}}:{profile:posts.at(-1)}});
    }
    if(p==='/auth.json'||p.startsWith('/api/')) return route.fulfill({json:{checkouts:[],batches:[],orders:[]}});
    if(p.startsWith('/assets/')) return route.fulfill({path:path.join(__dirname,'../resources/ui',p)});
    return route.fulfill({path:path.join(__dirname,'../resources/ui/index.html'),contentType:'text/html'});
  });
  await page.goto('/market/builder');
  return posts;
}
test('storefront introduces seller before products and works on mobile',async({page})=>{
  await page.setViewportSize({width:390,height:844});
  await setup(page);
  await expect(page.getByRole('heading',{name:'Builder tools'})).toBeVisible();
  await expect(page.getByText('Independent tools for everyday work.')).toBeVisible();
  const header=await page.locator('.storefront-header').boundingBox(), products=await page.locator('#storefront-products').boundingBox();
  expect(header.y+header.height).toBeLessThanOrEqual(products.y);
  await expect(page.getByRole('button',{name:'Edit storefront'})).toHaveCount(0);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.screenshot({path:test.info().outputPath('referron-storefront-mobile.png'),fullPage:true});
  await page.setViewportSize({width:1440,height:1000});
  await page.getByRole('button',{name:'Switch to dark theme'}).click();
  await page.screenshot({path:test.info().outputPath('referron-storefront-desktop-dark.png'),fullPage:true});
});
test('coming-soon sellers are stamped in the directory and storefronts',async({page})=>{
  await page.setViewportSize({width:390,height:844});
  await setup(page,{upcoming:true});
  await page.goto('/market');
  for(const name of ['Liam','Outcome Machines','Opentangle']) {
    await expect(page.locator('.store-card').filter({hasText:name}).locator('.coming-soon-stamp')).toHaveText('Coming soon!');
  }
  for(const [id,name,website] of [['liam-seller','Liam',null],['outcome-machines','Outcome Machines','https://outcomemachines.com/'],['opentangle','Opentangle','https://opentangle.ai/']]) {
    await page.goto('/market/'+id);
    await expect(page.getByRole('heading',{name,exact:true})).toBeVisible();
    await expect(page.locator('.storefront-header .coming-soon-stamp')).toHaveText('Coming soon!');
    await expect(page.getByText('Purchasing on Referron Market is coming soon.')).toBeVisible();
    if(website) {
      await expect(page.getByRole('link',{name:'Visit website ↗'})).toHaveAttribute('href',website);
      await expect(page.getByRole('link',{name:'Browse products'})).toHaveCount(0);
      await expect(page.locator('.goods')).toHaveCount(0);
    }
    expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  }
  await page.screenshot({path:test.info().outputPath('referron-coming-soon-mobile.png'),fullPage:true});
});
test('seller publishes profile and directory reflects it',async({page})=>{
  const posts=await setup(page,{seller:true});
  await page.getByRole('button',{name:'Edit storefront'}).click();
  await page.getByLabel('Store name').fill('Builder studio');
  await page.getByRole('button',{name:'Publish changes'}).click();
  await expect(page.getByRole('heading',{name:'Builder studio'})).toBeVisible();
  expect(posts).toHaveLength(1);
  await page.getByRole('link',{name:'All stores'}).click();
  await expect(page.locator('.store-card')).toContainText('Builder studio');
});
test('failed save retains edits and empty profile remains readable',async({page})=>{
  await setup(page,{seller:true,empty:true,fail:true});
  await expect(page.getByText('This seller is preparing their first products. Check back soon.')).toBeVisible();
  await page.getByRole('button',{name:'Edit storefront'}).click();
  await page.getByLabel('Description').fill('New description');
  await page.getByRole('button',{name:'Publish changes'}).click();
  await expect(page.getByLabel('Description')).toHaveValue('New description');
  await expect(page.getByText('Try again later')).toBeVisible();
  await page.getByRole('button',{name:'Cancel',exact:true}).click();
  await expect(page.getByText('Independent tools for everyday work.')).toBeVisible();
});
test('seller colors survive publish and reload, style directory, and can be cleared',async({page})=>{
  await page.setViewportSize({width:390,height:844});
  const posts=await setup(page,{seller:true});
  await page.getByRole('button',{name:'Edit storefront'}).click();
  await page.getByLabel('Use brand colors').check();
  await page.screenshot({path:test.info().outputPath('referron-storefront-color-editor.png')});
  for(const [label,value] of [['Primary — border and accents','#ffffff'],['Secondary — gradient','#000000'],['Button color','#000000']]) {
    await page.getByLabel(label).evaluate((input,color)=>{input.value=color;input.dispatchEvent(new Event('input',{bubbles:true}));},value);
  }
  await page.getByRole('button',{name:'Publish changes'}).click();
  expect(posts[0]['brand-primary']).toBe('#ffffff');
  expect(posts[0]['brand-secondary']).toBe('#000000');
  expect(posts[0]['brand-button']).toBe('#000000');
  await expect(page.locator('.storefront-header')).toHaveClass(/seller-branded/);
  expect(await page.evaluate(()=>{
    const primary=readableBrandAccent('#ffffff','#000000');
    return (colorLuminance('#d3d1d9')+.05)/(colorLuminance(primary)+.05);
  })).toBeGreaterThanOrEqual(4.5);
  await expect(page.getByRole('link',{name:'Browse products'})).toHaveCSS('background-color','rgb(0, 0, 0)');
  await expect(page.getByRole('link',{name:'Browse products'})).toHaveCSS('color','rgb(255, 255, 255)');
  await page.reload();
  await expect(page.locator('.storefront-header')).toHaveClass(/seller-branded/);
  await page.getByRole('link',{name:'All stores'}).click();
  await expect(page.locator('.store-card')).toHaveCSS('border-top-color','rgb(255, 255, 255)');
  await page.locator('.store-card').click();
  await page.getByRole('button',{name:'Switch to dark theme'}).click();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.screenshot({path:test.info().outputPath('referron-custom-colors-dark-mobile.png')});
  await page.getByRole('button',{name:'Edit storefront'}).click();
  await expect(page.getByLabel('Primary — border and accents')).toHaveValue('#ffffff');
  await page.getByLabel('Use brand colors').uncheck();
  await page.getByRole('button',{name:'Publish changes'}).click();
  await expect(page.locator('.storefront-header')).not.toHaveClass(/seller-branded/);
  expect(posts[1]['brand-primary']).toBe('');
});
test('failed color save keeps the chosen palette',async({page})=>{
  await setup(page,{seller:true,fail:true});
  await page.getByRole('button',{name:'Edit storefront'}).click();
  await page.getByLabel('Use brand colors').check();
  await page.getByLabel('Button color').evaluate(input=>{input.value='#ff0000';});
  await page.getByRole('button',{name:'Publish changes'}).click();
  await expect(page.getByLabel('Use brand colors')).toBeChecked();
  await expect(page.getByLabel('Button color')).toHaveValue('#ff0000');
  await expect(page.getByText('Try again later')).toBeVisible();
});
