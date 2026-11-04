#!/usr/bin/env python3
"""Compose with local credentials overriding any inherited production exports."""
import os,subprocess,sys
from pathlib import Path
values={}
for line in Path('.env.local').read_text().splitlines():
    if line and not line.startswith('#') and '=' in line:
        key,value=line.split('=',1);values[key]=value
result=subprocess.run(['docker','compose','--env-file','.env.local',*sys.argv[1:]],env={**os.environ,**values})
raise SystemExit(result.returncode)
