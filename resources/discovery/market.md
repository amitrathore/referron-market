- [Public state projection](http://localhost:8891/ui/public-state): JSON containing published feed content, public catalog fields, and public storefront profiles. Useful for clients that cannot execute the site's JavaScript. Private agreements, orders, carts, and coseller program economics are excluded.
- Seller storefronts use `/market/<seller-player-name>`; product pages use `/market/<seller-player-name>/products/<capability-id>`.
- Community profiles use `/@<player-name>`; essays use `/essays/<slug-or-id>`.
- The authenticated `GET /api/coseller/program` returns the selected coseller's program information. This is a member endpoint, not a public source of private rates.

Read current descriptions, prices, currency, billing mode, availability, and checkout eligibility before recommending an offer. Market supports commerce with native and integrated seller payment paths; the actual payment and fulfillment state is authoritative. Do not mark an order paid, promise delivery, claim refunds, or initiate purchases without the appropriate authority and supported flow.

Prospective sellers should review the Seller Agreement before applying. After Referron Market approval, Step 2 on the Seller Agreement page presents a private, seller-specific Commercial Schedule for explicit acceptance. That schedule states the actual commission, calculation base, payment route, fees, and settlement terms. Acceptance records the exact revision and does not grant Seller privileges or enable payments. Referron Market separately completes roles, payment readiness, and integration before activation. Sellers can maintain public storefront presentation with their authorized Seller seat. Private operator review is at `/operator/sellers` and is not a public seller self-service approval mechanism.

For editorial work, use Studio and only the publishing capabilities actually granted to the caller. Do not assume that a Contributor role alone permits directly publishing or modifying existing essays.
