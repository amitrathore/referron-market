const {test,expect}=require('@playwright/test');
const path=require('path');
const fs=require('fs');
async function setup(page){
  let actor='operator',draft=null,schedule=null,receipt=null;
  const app={id:'application-1',status:'pending-operator-activation','player-name':'seller','seller-legal-name':'Force Equals Inc','signer-name':'Marc Chabot','signer-title':'CEO',version:'1.0',sha256:'general-hash','accepted-at':'2026-10-13T00:00:00Z','notice-email':'support@forceequals.ai','notice-address':'Seller address'};
  const initial={'commission-bps':4000,currency:'USD','payment-route':'seller-direct',...Object.fromEntries(['offerings','territories','fees','settlement','holds','reversals','termination-subscriptions','additional-terms'].map(key=>[key,'Agreed '+key]))};
  const html=terms=>`<article class="seller-terms"><h2>Commercial Schedule</h2><p>Force Equals Inc</p><h3>Commission: ${terms['commission-bps']/100}%</h3><p>Complete agreed commercial terms.</p></article>`;
  const posts=[];
  await page.route('https://referron.test/**',async route=>{
    const p=new URL(route.request().url()).pathname,raw=route.request().postDataJSON();
    if(p==='/session')return route.fulfill({json:{authenticated:true,selectedSeat:{playerName:actor==='operator'?'amit':'seller',playerRoles:actor==='operator'?['operator']:['audience']}}});
    if(p==='/ui/public-state')return route.fulfill({json:{gameState:{attention:{feed:[]},market:{capabilities:{}}}}});
    if(p==='/auth.json')return route.fulfill({json:{}});
    if(p==='/api/seller/agreement')return route.fulfill({json:{version:'1.0',sha256:'general-hash',html:fs.readFileSync(path.join(__dirname,'../resources/ui/assets/legal/seller-agreement-v1.html'),'utf8')}});
    if(p==='/api/seller/agreement/status')return route.fulfill({json:{acceptance:app}});
    if(p==='/api/operator/sellers')return route.fulfill({json:{applications:[{...app,commercial:{draft:draft||(!schedule?{suggested:true,terms:initial,html:html(initial)}:null),schedule,acceptance:receipt}}]}});
    if(p==='/api/operator/sellers/review'){posts.push(raw);app.status=raw.decision;return route.fulfill({json:{acceptance:app}});}
    if(p==='/api/operator/sellers/commercial-schedule'){posts.push(raw);draft={id:'draft-1',terms:raw.terms,html:html(raw.terms)};return route.fulfill({json:{draft}});}
    if(p==='/api/operator/sellers/commercial-schedule/publish'){posts.push(raw);schedule={id:'schedule-1',version:1,sha256:'schedule-digest','seller-legal-name':'Force Equals Inc',terms:draft.terms,html:draft.html};draft=null;return route.fulfill({json:{schedule}});}
    if(p==='/api/seller/commercial-schedule')return route.fulfill({json:{schedule,acceptance:receipt}});
    if(p==='/api/seller/commercial-schedule/accept'){posts.push(raw);receipt={id:'receipt-1',version:1,sha256:schedule.sha256,...raw,'seller-legal-name':'Force Equals Inc','accepted-at':'2026-10-13T01:00:00Z'};return route.fulfill({json:{acceptance:receipt}});}
    if(p.startsWith('/assets/'))return route.fulfill({path:path.join(__dirname,'../resources/ui',p)});
    return route.fulfill({path:path.join(__dirname,'../resources/ui/index.html'),contentType:'text/html'});
  });
  return {posts,setActor:value=>{actor=value;}};
}
test('operator publishes 40% and seller accepts before activation',async({page})=>{
  await page.setViewportSize({width:390,height:844});
  const fixture=await setup(page);
  await page.goto('/operator/sellers');
  await expect(page.getByLabel('Commission (%)')).toHaveValue('40');
  await page.getByRole('button',{name:'Save commercial draft'}).click();
  await expect(page.getByRole('button',{name:'Publish for acceptance'})).toBeDisabled();
  await page.getByRole('button',{name:'Approve for activation'}).click();
  await expect(page.getByRole('button',{name:'Mark activated'})).toBeDisabled();
  await page.getByLabel(/I am authorized to offer/).check();
  await page.getByRole('button',{name:'Publish for acceptance'}).click();
  await expect(page.getByText('Published revision 1 · Awaiting seller acceptance')).toBeVisible();
  fixture.setActor('seller');await page.goto('/seller-agreement#commercial-terms');
  await expect(page.getByRole('heading',{name:'Commission: 40%'})).toBeVisible();
  await expect(page.getByLabel(/I am authorized to bind/)).not.toBeChecked();
  await page.getByLabel(/I am authorized to bind/).check();
  await page.getByRole('button',{name:'I Accept these commercial terms'}).click();
  await expect(page.getByText('Waiting for Referron Market activation.')).toBeVisible();
  await expect(page.getByRole('button',{name:'I Accept these commercial terms'})).toHaveCount(0);
  await page.reload();await expect(page.getByText(/Accepted by/)).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.locator('#commercial-terms').screenshot({path:test.info().outputPath('referron-commercial-seller-panel.png')});
  fixture.setActor('operator');await page.goto('/operator/sellers');
  await page.getByLabel(/I verified payment/).check();
  await page.getByRole('button',{name:'Mark activated'}).click();
  await expect(page.locator('.operator-card-head')).toContainText('activated');
  expect(fixture.posts.find(p=>p.terms)?.terms['commission-bps']).toBe(4000);
  expect(fixture.posts.find(p=>p['schedule-id'])).toMatchObject({accepted:true,authorized:true,'schedule-id':'schedule-1',sha256:'schedule-digest','signer-name':'Marc Chabot'});
  expect(fixture.posts.at(-1)['configuration-confirmed']).toBe(true);
});
test('unpublished terms are hidden from seller and failed saves retain edits',async({page})=>{
  const fixture=await setup(page);fixture.setActor('seller');await page.goto('/seller-agreement');
  await expect(page.getByText('Waiting for Referron Market to prepare your terms.')).toBeVisible();
  await expect(page.getByRole('button',{name:'I Accept these commercial terms'})).toHaveCount(0);
  fixture.setActor('operator');await page.goto('/operator/sellers');
  await page.getByLabel('Fees and responsibilities').fill('Edited agreed fee terms.');
  await page.route('https://referron.test/api/operator/sellers/commercial-schedule',route=>route.fulfill({status:502,json:{error:{message:'Try again later'}}}));
  await page.getByRole('button',{name:'Save commercial draft'}).click();
  await expect(page.getByText('Try again later')).toBeVisible();
  await expect(page.getByLabel('Fees and responsibilities')).toHaveValue('Edited agreed fee terms.');
});
