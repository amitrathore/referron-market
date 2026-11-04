// Real local browser smoke. Reads only the isolated development credentials.
const {chromium,expect}=require('@playwright/test');
const fs=require('node:fs');
const env=Object.fromEntries(fs.readFileSync('.env.local','utf8').split('\n').filter(x=>x&&!x.startsWith('#')).map(x=>{const n=x.indexOf('=');return[x.slice(0,n),x.slice(n+1)];}));
(async()=>{
  const browser=await chromium.launch();
  try{
    const page=await browser.newPage();
    await page.goto('http://localhost:8891/join');
    await page.locator('main').getByRole('link',{name:'Sign in',exact:true}).click();
    await page.getByLabel('Email',{exact:true}).fill(env.MOM_DEV_EMAIL);
    await page.getByLabel('Password',{exact:true}).fill(env.MOM_DEV_PASSWORD);
    await page.getByRole('button',{name:'Sign in',exact:true}).click();
    await page.waitForURL(url=>url.origin==='http://localhost:8891'&&!url.pathname.startsWith('/auth/'),{timeout:30000});
    await expect(page.locator('nav').getByRole('link',{name:'@local-owner',exact:true})).toBeVisible();
    await page.goto('http://localhost:8891/earnings');
    await expect(page.locator('#earnings-data')).toContainText('$0.00');
    await expect(page.getByText('Withdrawals will open after payment setup is complete.')).toBeVisible();
    await page.goto('http://localhost:8891/market');
    await expect(page.locator('.store-card')).toHaveCount(0);
    await expect(page.locator('header .brand-mark')).toHaveJSProperty('naturalWidth',1153);
    fs.mkdirSync('test-results/local',{recursive:true});
    await page.screenshot({path:'test-results/local/market-desktop.png',fullPage:true});
    console.log('PASS: real browser sign-in, return, selected seat, ledger earnings, empty market, and official logo');
  }finally{await browser.close();}
})().catch(error=>{console.error(error.message.replace(/token=[^&\s]+/g,'token=[redacted]'));process.exitCode=1;});
