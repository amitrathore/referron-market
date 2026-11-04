// Isolated guest preview. Never fetch another game's production state.
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '../resources/ui');
const types = {'.html':'text/html; charset=utf-8','.css':'text/css','.js':'text/javascript','.png':'image/png','.svg':'image/svg+xml','.woff2':'font/woff2','.ico':'image/x-icon','.webmanifest':'application/manifest+json'};
async function start() {
  const snapshot=JSON.parse(await fs.readFile(path.join(__dirname,'../resources/catalog/yvatar.json'),'utf8'));
  const state = process.env.REFERRON_PREVIEW_STATE
    ? JSON.parse(await fs.readFile(process.env.REFERRON_PREVIEW_STATE,'utf8'))
    : {gameState:{market:{capabilities:snapshot.capabilities},attention:{feed:[]},storefront:{profiles:{yvatar:snapshot.profile}}},topics:[]};
  http.createServer(async(req,res)=>{
    const json=(value,status=200)=>{res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify(value));};
    try {
      const url=new URL(req.url,'http://localhost');
      if(url.pathname==='/essays'||url.pathname.startsWith('/essays/')) {
        res.writeHead(301,{'Location':url.pathname.replace(/^\/essays/,'/insights')+url.search});res.end();return;
      }
      if(req.method!=='GET') return json({error:{code:'preview-read-only'}},405);
      if(url.pathname==='/ui/public-state') return json(state);
      if(url.pathname==='/health') return json({ok:true,preview:true});
      if(url.pathname==='/session') return json({authenticated:false});
      if(url.pathname==='/auth.json') return json({app_name:'Referron Market'});
      if(url.pathname==='/api/market/payment-mode') return json({mode:'unavailable'});
      if(url.pathname==='/api/seller/agreement') {
        const html=await fs.readFile(path.join(root,'assets/legal/seller-agreement-v1.html'),'utf8');
        return json({'agreement-id':'referron-seller-agreement',version:'1.0',sha256:crypto.createHash('sha256').update(html).digest('hex'),html});
      }
      if(url.pathname.startsWith('/api/')) return json({error:{code:'authentication-required'}},401);
      const file=url.pathname==='/favicon.ico'?path.join(root,'assets/brand/favicon-v2.ico'):url.pathname==='/site.webmanifest'?path.join(root,'site.webmanifest'):url.pathname.startsWith('/assets/')?path.resolve(root,'.'+decodeURIComponent(url.pathname)):path.join(root,'index.html');
      if(!file.startsWith(root+path.sep)) return json({error:'Forbidden'},403);
      const body=await fs.readFile(file);res.writeHead(200,{'Content-Type':types[path.extname(file)]||'application/octet-stream'});res.end(body);
    } catch {json({error:'Not found'},404);}
  }).listen(Number(process.env.PORT||4173),'127.0.0.1',()=>console.log('Referron guest preview: http://127.0.0.1:'+(process.env.PORT||4173)));
}
start().catch(e=>{console.error(e.message);process.exitCode=1;});
