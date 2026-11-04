// Public production browser checks: no login, seat claims, inventory or purchases.
const {chromium,expect}=require('@playwright/test');
const fs=require('node:fs');
(async()=>{
  const browser=await chromium.launch();
  const errors=[];
  try{
    const page=await browser.newPage({viewport:{width:1440,height:1000}});
    page.on('pageerror',error=>errors.push(error.message));
    page.on('console',message=>{if(message.type()==='error')errors.push(message.text());});
    fs.mkdirSync('test-results/production',{recursive:true});
    const began=Date.now();
    const response=await page.goto('https://referron.coselling.ai/');
    if(response.status()!==200)throw new Error('Homepage HTTP '+response.status());
    await expect(page.getByRole('heading',{name:'Good things grow through people.'})).toBeVisible();
    await expect(page.locator('header .brand-mark')).toHaveJSProperty('naturalWidth',1153);
    const source=await response.text();
    if(!source.includes('https://referron.coselling.ai/assets/brand/social-card-v2.png')||!source.includes('og:image:width')||!source.includes('Make trusted recommendations'))throw new Error('Server-rendered sharing metadata is incomplete');
    await expect(page.locator('meta[property="og:image"]')).toHaveAttribute('content','https://referron.coselling.ai/assets/brand/social-card-v2.png');
    await expect(page.locator('meta[property="og:image:width"]')).toHaveAttribute('content','1200');
    await expect(page.locator('meta[property="og:image:height"]')).toHaveAttribute('content','630');
    await expect(page.locator('link[rel="icon"][type="image/svg+xml"]')).toHaveAttribute('href','/assets/brand/favicon-v2.svg');
    const imageSize=await page.evaluate(async()=>{const img=new Image();img.src='/assets/brand/social-card-v2.png';await img.decode();return[img.naturalWidth,img.naturalHeight];});
    if(imageSize[0]!==1200||imageSize[1]!==630)throw new Error('Social card dimensions are incorrect');
    const favicon=await page.request.get('https://referron.coselling.ai/favicon.ico');
    if(!favicon.ok())throw new Error('Favicon is unavailable');

    await expect(page.locator('.store-card').first()).toContainText('Yvatar Store');
    const elapsed=Date.now()-began;
    await page.screenshot({path:'test-results/production/home-desktop.png',fullPage:true});
    await page.setViewportSize({width:390,height:844});
    if(!await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth))throw new Error('Mobile overflow');
    await page.screenshot({path:'test-results/production/home-mobile.png',fullPage:true});
    await page.goto('https://referron.coselling.ai/market');
    await expect(page.getByRole('heading',{name:'Explore the market',exact:true})).toBeVisible();
    await expect(page.locator('.store-card').first()).toContainText('Yvatar Store');
    await page.goto('https://referron.coselling.ai/insights');
    await expect(page.getByRole('heading',{name:'Insights',exact:true})).toBeVisible();
    await page.goto('https://referron.coselling.ai/market/yvatar');
    await expect(page.getByRole('heading',{name:'Yvatar Store',exact:true})).toBeVisible();
    await expect(page.locator('.product-card')).not.toHaveCount(0);
    await expect(page.getByText('Purchasing on Referron Market is coming soon.')).toBeVisible();
    await page.goto('https://referron.coselling.ai/join');
    await page.locator('main').getByRole('link',{name:'Sign in',exact:true}).click();
    await page.waitForURL(url=>url.hostname==='auth.intergraph.ai');
    await expect(page.getByText('Referron Market',{exact:false}).first()).toBeVisible({timeout:20000});
    await page.screenshot({path:'test-results/production/auth.png',fullPage:true});
    if(errors.length)throw new Error('Browser errors: '+errors.join('; '));
    console.log('PASS: production homepage, official assets, Insights, browse-only Yvatar catalog, mobile layout, and auth handoff');
    console.log('Homepage ready in '+elapsed+'ms. No console or page errors.');
  }finally{await browser.close();}
})().catch(error=>{console.error(error.message);process.exitCode=1;});
