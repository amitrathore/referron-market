# Referron Market

Keep all changes inside this repository. Shared Intergraph source, gm-lib,
Agents of Mind, Yvatar, Daemons and infrastructure repositories are read-only
references. Consume published gm-lib; do not locally install an override.

Brand source: https://www.referron.com/ and DESIGN.md. Preserve official assets.
No real sellers, inventory, or fabricated metrics in initial production state.

Checks: `lein test`, `npm run test:ui`, `docker build -t referron-market:local .`.
Local stack: `./scripts/local.sh up`; guest-only preview: `npm run preview`.

Production deploy: `./scripts/deploy.sh`, using owner-supplied root `.envrc`.
Keep credentials out of logs, git, images, and public state. Local startup must
never source production configuration. Do not turn on live payments or seller
integrations as part of platform-only deployment.

## Deploy Configuration

- Platform: Fly.io; app `referron-market`; region `sjc`; one Game Master machine.
- Production URL: https://referron.coselling.ai
- Deploy: `./scripts/deploy.sh` (accepts FLY_API_TOKEN or FLY_ORG_DEPLOY_TOKEN).
- Health: `/health`; confirm `game` equals `referron-market` after startup.
- Verify: source `.envrc`, run `python3 scripts/smoke-production.py`, then
  `node scripts/check-production.cjs` for public browser checks.
- DNS and TLS: Cloudflare CNAME routing and Fly certificate are configured.
- Payments and seller integrations remain disabled. No user handle is claimed
  by production verification.
