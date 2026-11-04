# Referron Market

A standalone marketplace for products, services, and trusted recommendations.
Built on existing Intergraph protocols with Referron's official branding.
The application starts with no sellers or inventory. Yvatar and Daemons are
subsequent seller-onboarding work.

## Architecture

The Clojure/Ring Game Master consumes published `ai.intergraph/game-master`
0.1.34. MoM owns authentication, durable game state, membership, and ledger
execution. This application owns the web UI, HTTP boundaries, storefronts,
seller agreements, and payment orchestration. Market, Coseller, Withdrawal,
Attention, Avatar, Introspection, Shoptype, and Discoverability use existing
protocols. No shared Intergraph repository is modified.

`src/referron_market/spec.clj` declares protocols, roles, and empty initial
state. HTTP handlers forward authenticated operations to MoM; the browser
cannot select another player's identity or mark its own payment as received.
`resources/ui/` contains the framework-free browser application.

The implementation adapts Agents of Mind's marketplace and the-js-game's wallet
orchestration. Provenance: sibling repositories under `../intergraph/`.
See [DESIGN.md](DESIGN.md) for brand assets and design rules.
The dedicated share card and icon family can be rebuilt with `npm run build:brand`.
The 1200 × 630 PNG is served in server-rendered OG/Twitter metadata; SVG, PNG and
ICO favicons use the unchanged official Referron mark.

## Local Compose stack

Prerequisites: Docker Compose, Java 21, Leiningen, Python 3, and the existing
`../intergraph/mom` checkout (including `auth-ui`). Change `INTERGRAPH_ROOT` in
`.env.local` if that checkout is elsewhere. Sibling sources are build inputs;
these scripts do not edit them.

```sh
./scripts/local.sh init
./scripts/local.sh up
./scripts/local.sh ps
```

Startup builds MoM, auth, Portal and Referron, seeds a local owner, creates
`referron-market-local`, and issues a game-scoped credential. It is safe to
rerun. Generated credentials live in ignored, mode-600 `.env.local`; local
startup never sources production `.envrc`. The Compose wrapper overrides any
inherited production exports with values from `.env.local`.

| Service | URL |
| --- | --- |
| Referron Market | http://localhost:8891 |
| Auth UI | http://localhost:15176 |
| MoM | http://localhost:18080 |
| Portal | http://localhost:18081 |

Sign in using `MOM_DEV_EMAIL` and `MOM_DEV_PASSWORD` from `.env.local`. The
initial owner has local platform administration privileges. Choose a handle
in the market. Local nREPL binds host port 17888 to loopback for owner bootstrap;
Postgres is not exposed to the host. Other local Intergraph stacks use different
ports and separate volumes.

```sh
./scripts/local.sh logs --tail=100 referron
./scripts/local.sh restart referron
./scripts/local.sh down
```

`down` preserves the Postgres volume. Game re-registration recovers existing
state. Do not delete the volume unless intentionally resetting local data.
The local file store is in-memory; uploaded media persistence is not provided
by this development configuration.

## Guest preview and checks

```sh
npm ci
npm run preview                    # http://127.0.0.1:4173
lein test
npx playwright install chromium
npm run test:ui
lein uberjar
```

The guest preview uses empty state and cannot sign in or accept payments.
`REFERRON_PREVIEW_STATE=/absolute/path/to/fixture.json npm run preview` loads an
explicit local public-state fixture. It never reads production inventory.
The browser suite starts both the guest preview and an isolated Clojure discovery
verification server automatically. Browser tests use isolated fixtures for paid scenarios, selected identities,
cart recovery, sharing, and seller application/terms. Backend tests cover
protocol dispatch and HTTP trust boundaries. CI runs tests and a Docker build.

## Fly.io production

Supply the new app and production connection values in root `.envrc`, following
`.envrc.example`. The file is owner-supplied shell code; the deployment script
sources it without printing credentials. It passes only an explicit allowlist
to Fly secrets. Local commands use `.env.local` instead.

To verify the actual local login and ledger integration after startup:

```sh
python3 scripts/smoke-local.py
node scripts/check-local.cjs
```

These use only `.env.local` and create/recover the test owner's `local-owner`
handle. They do not add sellers, inventory, or funded balances.

Before deploying, create `referron-market` in the existing production MoM with
the configured owner and issue its own game-scoped registry credential. Export
the registration payload using:

```sh
GAME_NAME=referron-market GM_OWNER_ID=your-owner-uuid \
  lein run -m clojure.main scripts/game-spec.clj
```

Use the resulting protocol and role specification in MoM/Portal. Set `MOM_URL`,
`AUTH_BASE_URL`, `GM_OWNER_ID`, `MOM_GAME_TOKEN`, and `MOM_GM_OUTBOUND_TOKEN` in
`.envrc`, plus `FLY_APP_NAME` and a suitable `FLY_API_TOKEN` or `FLY_ORG_DEPLOY_TOKEN`. APP_BASE_URL and
GM_URL default to `https://referron.coselling.ai`. This app must be reachable from MoM
at GM_URL for event routing.

```sh
./scripts/deploy.sh
```

The deployment builds the same Dockerfile as local, deploys one continuously
running 1GB machine, and probes `/health`. It uses the immediate deployment
strategy to avoid running overlapping Game Masters for the same seat.
The production game name and player group are distinct from local.

### Production domain

The canonical hostname is **referron.coselling.ai**. The Fly app is
`referron-market` in the token-accessible `personal` organization. Cloudflare
DNS is configured with these DNS-only records:

| Type | Name | Target |
| --- | --- | --- |
| CNAME | referron.coselling.ai | rkj8ddm.referron-market.fly.dev |
| TXT | _fly-ownership.referron.coselling.ai | app-rkj8ddm |
| CNAME | _acme-challenge.referron.coselling.ai | referron.coselling.ai.rkj8ddm.flydns.net |

Inspect certificate status with:

```sh
fly certs add referron.coselling.ai --app "$FLY_APP_NAME"
fly certs show referron.coselling.ai --app "$FLY_APP_NAME"
```

Configure the `referron` DNS record under `coselling.ai` using the exact routing
and ownership-verification targets Fly returns. Wait for certificate readiness
before the canonical-domain smoke check. For a first deploy before DNS is ready,
set `APP_BASE_URL` and `GM_URL` explicitly to the Fly hostname, then restore both
to the canonical hostname and redeploy after DNS and TLS are ready. Register authentication callbacks and,
when payment activation follows, Stripe callbacks against this hostname.
`APP_BASE_URL` controls public URLs; `GM_URL` must be reachable by MoM and can be
explicitly set to the app's Fly hostname during DNS provisioning. No DNS changes
are made by the deployment script.

Production is deployed at **https://referron.coselling.ai** on Fly app
`referron-market`, connected to `https://mom.intergraph.ai` and
`https://auth.intergraph.ai`. The production game has its own scoped credential;
all production credentials remain in ignored `.envrc` and Fly secrets.

For repeatable read-only production verification:

```sh
set -a; source ./.envrc; set +a
python3 scripts/smoke-production.py
node scripts/check-production.cjs
```

These checks do not claim handles, seed sellers, or make purchases. They include
an authenticated introspection event through the game's own MoM credential.

After deploy, verify `/health`, `/auth.json`, `/ui/public-state`, `/market`, and
`/api/market/payment-mode`, then complete sign-in and claim a handle. Verify
an actual query event routes through MoM. A green health endpoint alone does
not establish that registration, auth, or commerce works. Recover by redeploying
the prior image with the same identity and credentials; never create another
game merely to work around a restart issue.

## Payments and first sellers

Production and local default to `MARKET_PAYMENT_PROVIDER=stub`,
`STRIPE_CONNECT_ENABLED=false`, and `SHOPTYPE_ENABLED=false`. Checkout presents
an unavailable state. No real seller or product is published by bootstrap.

Later activation requires the game's merchant onboarding, seller agreement and
commercial acceptance, activated seller seat, provider configuration, signed
webhooks, and payment/payout readiness. Use existing Seller Commerce or Shoptype
contracts; adding Daemons must not require modifying Daemons in this milestone.
Yvatar already has a reference seller integration; its credentials and seat
must be specific to this game.

Configure durable Market/Coseller policies through existing operations before
activation. Values in the game specification are UI defaults, not proof of
persisted policy. Audit return windows against payout holds. Seller commission
rates are supplied through commercial onboarding, not inherited from another
market. Live payments, real withdrawals, chargebacks and seller fulfillment
remain disabled until that separate activation is tested.

The copied seller terms and commercial workflow are application drafts.
Confirm the legal operator and accepted commercial terms before activating
real sellers. No identity/contact integration with the existing Referron app
is included.
