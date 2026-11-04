#!/usr/bin/env python3
"""Read-only production checks. Source owner-supplied .envrc before running."""
import json,os,subprocess

base=os.environ.get('APP_BASE_URL','https://referron.coselling.ai').rstrip('/')
mom=os.environ.get('MOM_URL','https://mom.intergraph.ai').rstrip('/')

def request(origin,path,token=None,method='GET',body=None):
    cfg='header = "Content-Type: application/json"\n'
    if token: cfg+='header = "Authorization: Bearer '+token+'"\n'
    args=['curl','--silent','--show-error','--max-time','30','--config','-','--request',method,'--write-out','\n%{http_code}',origin+path]
    if body is not None:args+=['--data-binary',json.dumps(body)]
    r=subprocess.run(args,input=cfg,capture_output=True,text=True)
    if r.returncode:raise SystemExit('Network check failed: '+origin+path)
    raw,status=r.stdout.rsplit('\n',1)
    try:value=json.loads(raw)
    except ValueError:value=raw
    return int(status),value

def check(ok,label):
    if not ok:raise SystemExit('FAIL: '+label)
    print('PASS: '+label)

status,health=request(base,'/health')
check(status==200 and health.get('game')=='referron-market','production game health')
status,config=request(base,'/auth.json')
check(status==200 and config.get('app_name')=='Referron Market','branded authentication')
check(config.get('success_url','').startswith(base+'/'),'canonical auth return URL')
check(config.get('logo_url')==base+'/assets/brand/referron-logo.png','absolute official logo URL')
status,public=request(base,'/ui/public-state')
check(status==200,'public state loads through MoM')
state=public['gameState']
check(not state.get('market',{}).get('capabilities'),'catalog is empty')
check(not state.get('storefront',{}).get('profiles'),'no seeded sellers')
check('coseller' not in state and 'seller-agreement' not in state,'private state excluded')
status,mode=request(base,'/api/market/payment-mode')
check(status==200 and mode.get('mode')=='unavailable','live payments disabled')
status,_=request(base,'/v1/gm/handle',method='POST',body={'event/type':'gm/query-state','event/data':{}})
check(status==401,'unsigned MoM webhook is rejected')
for path in ['/','/market','/coselling','/seller-agreement','/llms.txt','/sitemap.xml']:
    status,body=request(base,path);check(status==200,'public route '+path)
    if path=='/sitemap.xml':check(base in body,'canonical sitemap origin')
bootstrap=os.environ.get('MOM_GAME_TOKEN')
if not bootstrap:raise SystemExit('MOM_GAME_TOKEN required for live routing verification.')
status,auth=request(mom,'/auth/service-token',bootstrap,'POST',{})
check(status==200 and auth.get('service-id')=='referron-market' and bool(auth.get('token')),'Referron-specific service credential')
status,event=request(mom,'/api/v1/action',auth['token'],'POST',{
 'game-name':'referron-market','event/type':'introspection/query-capabilities',
 'event/data':{'player-name':'game-master','roles':['game-master']}})
result=event.get('gm-result',event) if isinstance(event,dict) else {}
check(status==200 and result.get('success'),'authenticated event routes through production MoM to Referron')
