#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-up}" in
  init)
    python3 scripts/local-init.py
    ;;
  up)
    test -f .env.local || python3 scripts/local-init.py
    python3 scripts/local-compose.py up -d --build postgres mom auth-ui portal
    python3 scripts/local-bootstrap.py
    python3 scripts/local-compose.py up -d --build referron
    ;;
  down|ps|logs|restart)
    action="$1"; shift
    python3 scripts/local-compose.py "$action" "$@"
    ;;
  *) echo 'Usage: scripts/local.sh {init|up|down|ps|logs|restart}' >&2; exit 2 ;;
esac
