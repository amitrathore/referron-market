const {defineConfig}=require('@playwright/test');
const discoveryURL=process.env.DISCOVERY_VERIFY_URL||'http://127.0.0.1:4188';
process.env.DISCOVERY_VERIFY_URL=discoveryURL;
module.exports=defineConfig({
  testDir:'tests',workers:1,reporter:'list',
  use:{baseURL:'https://referron.test',trace:'retain-on-failure'},
  webServer:[
    {command:'npm run preview',url:'http://127.0.0.1:4173/health',reuseExistingServer:!process.env.CI},
    {command:'lein with-profile +verify run -m referron-market.discoverability-preview',url:discoveryURL+'/market',reuseExistingServer:!process.env.CI,timeout:180000}
  ]
});
