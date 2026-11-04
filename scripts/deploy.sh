#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f .envrc ]]; then echo 'Production configuration is missing: supply .envrc (see .envrc.example).' >&2; exit 2; fi
# .envrc is shell code supplied by the repository owner; never print it.
set -a
source ./.envrc
set +a
export FLY_API_TOKEN="${FLY_API_TOKEN:-${FLY_ORG_DEPLOY_TOKEN:-}}"
for key in FLY_API_TOKEN FLY_APP_NAME MOM_URL AUTH_BASE_URL GM_OWNER_ID MOM_GAME_TOKEN MOM_GM_OUTBOUND_TOKEN; do
  if [[ -z "${!key:-}" ]]; then echo "Missing production configuration: $key" >&2; exit 2; fi
done
export APP_BASE_URL="${APP_BASE_URL:-https://referron.coselling.ai}"
export GM_URL="${GM_URL:-$APP_BASE_URL}"
if [[ "${GAME_NAME:-referron-market}" == *local* ]]; then echo 'Refusing a local game identity in production.' >&2; exit 2; fi
# Whitelisted values only. Pipe directly to Fly; do not print secret values.
python3 - <<'PY' | fly secrets import --stage --app "$FLY_APP_NAME"
import os
keys=['MOM_URL','AUTH_BASE_URL','GM_OWNER_ID','MOM_GAME_TOKEN','MOM_GM_OUTBOUND_TOKEN','APP_BASE_URL','GM_URL','CLERK_PUBLISHABLE_KEY']
for key in keys:
    value=os.environ.get(key)
    if value:
        if '\n' in value or '\r' in value: raise SystemExit('Configuration contains a newline: '+key)
        print(key+'='+value)
PY
fly deploy --config fly.toml --app "$FLY_APP_NAME" --remote-only --strategy immediate --ha=false --yes
fly checks list --app "$FLY_APP_NAME"
curl --fail --silent --show-error --retry 12 --retry-delay 5 --retry-connrefused --max-time 20 "$APP_BASE_URL/health"
echo
