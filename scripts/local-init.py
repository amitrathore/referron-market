#!/usr/bin/env python3
"""Generate isolated local credentials once; never consume production .envrc."""
from pathlib import Path
import secrets, uuid
p=Path('.env.local')
if p.exists():
    print('.env.local already exists; preserving credentials.')
else:
    values={'INTERGRAPH_ROOT':'../intergraph','LOCAL_PG_PASSWORD':secrets.token_hex(24),
      'LOCAL_JWT_SECRET':secrets.token_hex(32),'LOCAL_MCP_TOKEN':secrets.token_hex(24),
      'LOCAL_TREASURY_TOKEN':secrets.token_hex(24),'MOM_GM_OUTBOUND_TOKEN':secrets.token_hex(24),
      'GM_OWNER_ID':str(uuid.uuid4()),'MOM_DEV_EMAIL':'owner@referron.local',
      'MOM_DEV_PASSWORD':secrets.token_urlsafe(18),'MOM_GAME_TOKEN':''}
    p.touch(mode=0o600)
    p.write_text(''.join(f'{k}={v}\n' for k,v in values.items()))
    print('Created .env.local with isolated local credentials. Login details are in that ignored file.')
