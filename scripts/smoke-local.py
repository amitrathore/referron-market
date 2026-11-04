#!/usr/bin/env python3
"""Exercise only the isolated local stack. Never reads production .envrc."""
import json,urllib.request,urllib.error
from pathlib import Path
values={}
for line in Path('.env.local').read_text().splitlines():
    if line and not line.startswith('#') and '=' in line:
        k,v=line.split('=',1);values[k]=v
base='http://localhost:8891';token=None

def call(path,body=None,origin=base):
    headers={'Content-Type':'application/json'}
    if token:
        headers['Authorization']='Bearer '+token
        if path!='/join/claim': headers['x-selected-seat']='local-owner'
    req=urllib.request.Request(origin+path,data=json.dumps(body).encode() if body is not None else None,headers=headers)
    try:
        with urllib.request.urlopen(req,timeout=30) as r:
            raw=r.read().decode()
            return r.status,json.loads(raw) if 'application/json' in r.headers.get('Content-Type','') else raw
    except urllib.error.HTTPError as e:
        return e.code,{}

def check(condition,label):
    if not condition: raise SystemExit('FAIL: '+label)
    print('PASS: '+label)

status,health=call('/health');check(status==200 and health.get('game')=='referron-market-local','local game health')
status,cfg=call('/auth.json');check(status==200 and cfg['app_name']=='Referron Market','branded auth configuration')
status,login=call('/auth/login',{'email':values['MOM_DEV_EMAIL'],'password':values['MOM_DEV_PASSWORD']},'http://localhost:18080')
token=login.get('token');check(status==200 and bool(token),'native owner authentication')
status,session=call('/session');check(status==200 and session.get('authenticated'),'authenticated Referron session')
status,claim=call('/join/claim',{'player-name':'local-owner','roles':['audience']});check(status==200,'claim or recover local owner handle')
status,session=call('/session');check(status==200 and session.get('selectedSeat',{}).get('playerName')=='local-owner','selected seat recovery')
status,event=call('/events',{'event/type':'market/query-capabilities','event/data':{}})
result=event.get('gm-result',event);check(status==200 and result.get('success'),'authenticated event routed through MoM')
status,state=call('/ui/public-state');check(status==200 and not state['gameState']['market']['capabilities'],'empty production-shaped catalog')
check('coseller' not in state['gameState'] and 'seller-agreement' not in state['gameState'],'private state excluded')
status,payment=call('/api/market/payment-mode');check(status==200 and payment.get('mode')=='unavailable','payments remain disabled')
status,wallet=call('/wallet');check(status==200 and wallet['wallet']['balance']==0,'real ledger wallet starts empty')
status,connect=call('/api/connect/status');check(status==200 and connect.get('connectEnabled') is False,'withdrawals remain disabled')
status,withdraw=call('/api/wallet/withdraw',{'amount':100});check(status==404,'real-money withdrawal rejected while disabled')
for path in ['/','/market','/llms.txt','/sitemap.xml']:
    status,_=call(path);check(status==200,'public route '+path)
