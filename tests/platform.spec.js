const {test,expect}=require('@playwright/test');
const path=require('node:path');
test('platform uses official branding and a browse-only Yvatar store',async({page})=>{
  await page.goto('http://127.0.0.1:4173/');
  await expect(page.getByRole('heading',{name:'Good things grow through people.'})).toBeVisible();
  await expect(page.locator('.store-card').first()).toContainText('Yvatar Store');
  await expect(page.locator('.store-card')).toHaveCount(1);
  await expect(page.locator('header .brand-mark')).toHaveJSProperty('naturalWidth',1153);
  for(const width of [320,390,768,1440]){
    await page.setViewportSize({width,height:900});
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  }
  await page.screenshot({path:test.info().outputPath('referron-home-desktop.png'),fullPage:true});
  await page.setViewportSize({width:390,height:844});
  await page.screenshot({path:test.info().outputPath('referron-home-mobile.png'),fullPage:true});
  await page.goto('http://127.0.0.1:4173/market');
  await expect(page.locator('.store-card')).toHaveCount(1);
});
test('guest earnings page asks for authentication',async({page})=>{
  await page.goto('http://127.0.0.1:4173/earnings');
  await expect(page.getByRole('heading',{name:'Your earnings'})).toBeVisible();
  await expect(page.locator('main').getByRole('link',{name:'Sign in',exact:true})).toHaveAttribute('href','/join');
});
test('member earnings come from wallet endpoints and disabled payouts stay unavailable',async({page})=>{
  await page.route('https://referron.test/**',async route=>{
    const url=new URL(route.request().url());
    const responses={
      '/ui/public-state':{gameState:{market:{capabilities:{}},attention:{feed:[]}}},
      '/session':{authenticated:true,selectedSeat:{playerName:'member',playerRoles:['audience','coseller']}},
      '/seats':{seats:[{playerName:'member',playerRoles:['audience','coseller']}]},
      '/auth.json':{},'/api/market/payment-mode':{mode:'unavailable'},
      '/wallet':{wallet:{balance:2500,availableNow:1500,pending:[{amount:1000}]}},
      '/api/connect/status':{status:'none',connectEnabled:false},
      '/api/wallet/withdrawals':{withdrawals:[]},
      '/api/coseller/program':{status:'active','ref-token':'test-ref',policy:{},opportunities:[]}
    };
    if(responses[url.pathname])return route.fulfill({json:responses[url.pathname]});
    if(url.pathname.startsWith('/assets/'))return route.fulfill({path:path.join(__dirname,'../resources/ui',url.pathname)});
    return route.fulfill({path:path.join(__dirname,'../resources/ui/index.html'),contentType:'text/html'});
  });
  await page.goto('/earnings');await expect(page.locator('#earnings-data')).toContainText('$25.00');
  await expect(page.locator('#earnings-data')).toContainText('$15.00');
  await expect(page.getByText('Withdrawals will open after payment setup is complete.')).toBeVisible();
  await expect(page.getByRole('button',{name:'Withdraw',exact:true})).toHaveCount(0);
});

test('social card and favicon exports are crisp and complete',async({page})=>{
  await page.goto('http://127.0.0.1:4173/');
  await expect(page.locator('meta[property="og:image"]')).toHaveAttribute('content','https://referron.coselling.ai/assets/brand/social-card-v2.png');
  await expect(page.locator('meta[property="og:image:width"]')).toHaveAttribute('content','1200');
  await expect(page.locator('meta[property="og:image:height"]')).toHaveAttribute('content','630');
  await expect(page.locator('link[rel="icon"][type="image/svg+xml"]')).toHaveAttribute('href','/assets/brand/favicon-v2.svg');
  const dimensions=await page.evaluate(async()=>{
    const files=['social-card-v2.png','favicon-v2.svg','favicon-v2-16.png','favicon-v2-32.png','favicon-v2-180.png','favicon-v2-192.png','favicon-v2-512.png'];
    return Promise.all(files.map(async name=>{const img=new Image();img.src='/assets/brand/'+name;await img.decode();return[name,img.naturalWidth,img.naturalHeight];}));
  });
  expect(dimensions).toEqual([['social-card-v2.png',1200,630],['favicon-v2.svg',512,512],['favicon-v2-16.png',16,16],['favicon-v2-32.png',32,32],['favicon-v2-180.png',180,180],['favicon-v2-192.png',192,192],['favicon-v2-512.png',512,512]]);
  const ico=await page.request.get('http://127.0.0.1:4173/favicon.ico');expect(ico.ok()).toBe(true);
  const bytes=await ico.body();expect(bytes.readUInt16LE(2)).toBe(1);expect(bytes.readUInt16LE(4)).toBe(3);
});
