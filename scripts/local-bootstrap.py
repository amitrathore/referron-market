#!/usr/bin/env python3
"""Create only referron-market-local and its scoped credential. Safe to rerun."""
import importlib.util,json,subprocess,time,urllib.request,urllib.error
from pathlib import Path
spec=importlib.util.spec_from_file_location('seed',Path(__file__).with_name('seed-mom-user.py'))
seed=importlib.util.module_from_spec(spec);spec.loader.exec_module(seed)
p=Path('.env.local');env=seed.load_env(p)
base='http://localhost:18080';token=None

def call(method,path,body=None,credential=None):
    headers={'Content-Type':'application/json'}
    if credential: headers['Authorization']='Bearer '+credential
    req=urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=20) as r: 
            raw=r.read().decode()
            try: body=json.loads(raw)
            except json.JSONDecodeError: body={}
            return r.status,body
    except urllib.error.HTTPError as e:
        return e.code,json.loads(e.read() or '{}')

for attempt in range(90):
    try:
        code,_=call('GET','/ui/games')
        if code==200: break
    except OSError: pass
    time.sleep(2)
else: raise SystemExit('Local MoM did not become healthy.')
if seed.main()!=0: raise SystemExit('Could not seed local owner.')
code,login=call('POST','/auth/login',{'email':env['MOM_DEV_EMAIL'],'password':env['MOM_DEV_PASSWORD']})
token=login.get('token') or login.get('access_token')
if code!=200 or not token: raise SystemExit('Local owner login failed.')
game='referron-market-local'
code,games=call('GET','/admin/games',credential=token)
if code!=200: raise SystemExit('Could not inspect local games.')
if isinstance(games,dict): games=games.get('games') or games.get('data') or []
existing=next((g for g in games if (g.get('game/game-name') or g.get('game-name'))==game),None)
if not existing:
    # Export the authoritative local specification through the published library.
    import os
    specenv={**os.environ,'GAME_NAME':game,'GM_OWNER_ID':env['GM_OWNER_ID']}
    raw=subprocess.check_output(['lein','run','-m','clojure.main','scripts/game-spec.clj'],env=specenv,text=True)
    body=json.loads(raw.strip().splitlines()[-1])
    code,result=call('POST','/admin/games',body,token)
    if code!=201: raise SystemExit(f'Local game creation failed (HTTP {code}).')
if env.get('MOM_GAME_TOKEN'):
    req=urllib.request.Request(base+'/auth/service-token',data=b'{}',headers={'x-api-token':env['MOM_GAME_TOKEN'],'Content-Type':'application/json'},method='POST')
    try:
        with urllib.request.urlopen(req,timeout=15) as r:
            if r.status==200:
                print('Local game and credential recovered.');raise SystemExit(0)
    except urllib.error.HTTPError: pass
code,result=call('POST',f'/admin/games/{game}/credentials',{'label':'referron-compose-local'},token)
if code!=201 or not result.get('token'): raise SystemExit(f'Local credential issuance failed (HTTP {code}).')
env['MOM_GAME_TOKEN']=result['token']
p.write_text(''.join(f'{k}={v}\n' for k,v in env.items()))
print('Local Referron game provisioned. No sellers or inventory were seeded.')
