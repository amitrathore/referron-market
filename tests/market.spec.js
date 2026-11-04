const { test, expect } = require("@playwright/test");
const path = require("path");
async function setup(page, { signed = true, seated = true, multiSeats = false, payment = "stripe", preview = false, free = false, coseller = false, ordering = false, upgrade = false, sellerSeat = false, connectStatus = "none", essays = [], tokenFailure = false, tokenLifetime = 3600000 } = {}) {
  const items = Object.fromEntries(
    Array.from({ length: 9 }, (_, i) => [
      "sku" + i,
      {
        id: "sku" + i,
        seller: "yvatar",
        title: "Yvatar credits " + i,
        description: "Credits for creative work",
        status: "active",
        terms: { price: { amount: 500 + i * 100, currency: "USD" } },
        commerce: { "checkout-owner": "seller", adapter: "yvatar" },
        images: [],
      },
    ]),
  );
  if (ordering) {
    items.sku0["display-order"] = 30;
    items.sku1["display-order"] = 10;
    items.sku2["display-order"] = 20;
  }
  items.tool = {
    id: "tool",
    seller: "builder",
    title: "Independent tool",
    status: "active",
    terms: { price: { amount: 1200, currency: "USD" } },
  };
  if (preview) {
    items["liam-plus"] = {
      id: "liam-plus",
      seller: "liam-seller",
      title: "Liam Plus",
      description: "5,000 Liam credits each month.",
      status: "active",
      terms: { price: { amount: 4900, currency: "USD" } },
      commerce: {
        "checkout-owner": "seller",
        adapter: "liam",
        "checkout-enabled": false,
      },
      images: [],
    };
  }
  if (free) {
    items["liam-pre"] = {
      id: "liam-pre",
      seller: "liam-seller",
      title: "Liam Pre",
      status: "active",
      terms: { price: { amount: 0, currency: "USD" } },
      commerce: { "checkout-owner": "seller", adapter: "liam" },
    };
  }
  if (upgrade) {
    for (const [id, amount, title] of [["liam-plus", 4900, "Liam Plus"],
                                       ["liam-pro", 7900, "Liam Pro"]]) {
      items[id] = { id, seller: "liam-seller", title, status: "active",
        terms: { price: { amount, currency: "USD" } },
        commerce: { "checkout-owner": "seller", adapter: "liam",
          "subscription-group": "liam-credits" } };
    }
  }
  items.hidden = {
    id: "hidden",
    seller: "hidden-seller",
    status: "withdrawn",
    title: "Hidden",
  };
  const state = {
    gameState: { market: { capabilities: items }, attention: { feed: essays }, storefront: {profiles:{...(preview||free||upgrade?{'liam-seller':{'display-name':'Liam'}}:{}),yvatar:{'display-name':'Yvatar','logo-url':'https://referron.test/assets/brand/favicon.png','website-url':'https://yvatar.com/','brand-primary':'#7657ff','brand-secondary':'#2ab7ff','brand-button':'#ffd95a'}}} },
  };
  let cart = null,
    offers = [],
    checkouts = [],
    batches = [],
    orders = [];
  let cosellerActive = coseller;
  let contributorActive = false;
  let hasSeat = seated;
  const events = [];
  const sessions = [];
  const issuedSeatTokens = [];
  const ownedSeats = multiSeats
    ? [
        { playerName: "amit", playerRole: "audience", playerRoles: ["audience", "coseller"] },
        { playerName: "liam-seller", playerRole: "seller", playerRoles: ["seller"] },
        { playerName: "yvatar", playerRole: "seller", playerRoles: ["seller"] },
      ]
    : seated ? [sellerSeat
      ? { playerName: "yvatar", playerRole: "seller", playerRoles: ["seller"] }
      : { playerName: "buyer", playerRole: "audience", playerRoles: ["audience"] }] : [];
  await page.route("https://referron.test/**", async (route) => {
    const req = route.request(),
      u = new URL(req.url());
    if (u.pathname === "/session")
      return route.fulfill({
        json: signed
          ? { authenticated: true, seatCount: multiSeats ? ownedSeats.length : hasSeat ? 1 : 0,
              selectedSeat: multiSeats
                ? ownedSeats.find((seat) => seat.playerName === req.headers()["x-selected-seat"]) || null
                : hasSeat ? (sellerSeat ? ownedSeats[0] : { ...ownedSeats[0], playerRoles: ["audience", ...(cosellerActive ? ["coseller"] : []), ...(contributorActive ? ["contributor"] : [])] }) : null }
          : { authenticated: false },
      });
    if (u.pathname === "/seats")
      return route.fulfill({ json: { seats: multiSeats ? ownedSeats : hasSeat ? ownedSeats : [] } });
    if (u.pathname === "/api/player-token") {
      const selected = multiSeats
        ? ownedSeats.find((seat) => seat.playerName === req.headers()["x-selected-seat"])
        : hasSeat ? ownedSeats[0] : null;
      if (!signed) return route.fulfill({ status: 401, json: { error: { message: "Authorization required" } } });
      if (!selected) return route.fulfill({ status: 400, json: { error: { message: "A selected seat is required" } } });
      if (tokenFailure) return route.fulfill({ status: 502, json: { error: { message: "Could not create a seat token right now. Try again." } } });
      issuedSeatTokens.push({ playerName: selected.playerName, headers: req.headers() });
      return route.fulfill({ json: { token: `seat-token-${selected.playerName}`, playerName: selected.playerName,
        expiresAt: Date.now() + tokenLifetime, expiresIn: Math.ceil(tokenLifetime / 1000) } });
    }
    if (u.pathname === "/join/claim") {
      hasSeat = true;
      const { "player-name": name, roles } = req.postDataJSON();
      const existing = ownedSeats.find((seat) => seat.playerName === name);
      if (existing) {
        existing.playerRoles = [...new Set([...existing.playerRoles, ...roles])];
      } else {
        ownedSeats.push({ playerName: name, playerRole: roles[0], playerRoles: roles });
      }
      if (roles.includes("contributor")) contributorActive = true;
      return route.fulfill({ json: { selectedSeat: { playerName: name } } });
    }
    if (u.pathname === "/ui/public-state")
      return route.fulfill({ json: state });
    if (u.pathname === "/auth.json")
      return route.fulfill({
        json: { auth_base_url: "https://auth.example.test" },
      });
    if (u.pathname === "/coseller/claim-visitor")
      return route.fulfill({ json: {} });
    if (u.pathname === "/api/coseller/activate") {
      cosellerActive = true;
      return route.fulfill({ json: { status: "active", "ref-token": "ref-buyer" } });
    }
    if (u.pathname === "/api/coseller/program")
      return route.fulfill({
        status: cosellerActive ? 200 : 403,
        json: cosellerActive
          ? {
              status: "active",
              "player-name": "buyer",
              "ref-token": "ref-buyer",
              "share-url": "https://referron.test/?ref=ref-buyer",
              "market-status": "preview",
              "earnings-enabled": false,
              "commission-range": { "minimum-bps": 3000, wording: "Start around 30% and can be higher" },
              policy: {
                "window-days": 180,
                "pool-share-bps": 8000,
                "referral-override-bps": 500,
                "payout-hold-days": 3,
                "seller-direct-payout-hold-days": 1,
                "withdrawal-minimum": null,
                "subscription-chain": "locked-at-start",
              },
              opportunities: [
                { "capability-id": "sku0", title: "Yvatar credits 0", seller: "yvatar", "commission-bps": 3500 },
                { "capability-id": "liam-plus", title: "Liam Plus", seller: "liam-seller", "commission-bps": 3000 },
              ],
            }
          : { error: { code: "coseller-role-required" } },
      });
    if (u.pathname === "/api/market/payment-mode")
      return route.fulfill({
        json: { mode: payment, "publishable-key": "pk_test" },
      });
    if (u.pathname === "/api/connect/seller/status")
      return route.fulfill({ json: { status: connectStatus,
        accountType: connectStatus === "enabled" ? "standard" : null,
        paymentsEnabled: connectStatus === "enabled",
        payoutsEnabled: connectStatus === "enabled" } });
    if (u.pathname === "/api/connect/seller/start")
      return route.fulfill({ json: { url: "https://referron.test/?connect=return" } });
    if (u.pathname === "/api/market/purchases")
      return route.fulfill({ json: { checkouts, batches, orders } });
    if (u.pathname === "/api/market/subscriptions")
      return route.fulfill({ json: { subscriptions: upgrade ? [{
        id: "market-sub", seller: "liam-seller", "subscription-group": "liam-credits",
        "capability-id": "liam-plus", "provider-subscription-id": "sub_existing",
        status: "active",
      }] : [] } });
    if (u.pathname === "/api/market/subscriptions/market-sub/portal")
      return route.fulfill({ json: { url: "https://billing.stripe.com/test-upgrade" } });
    if (u.pathname.endsWith("/session")) {
      sessions.push(req.postDataJSON());
      if (free) {
        checkouts[0].status = "paid";
        orders[0].status = "paid";
        orders[0].fulfillment = { status: "fulfilled" };
        return route.fulfill({ json: { "ui-mode": "none", status: "activated" } });
      }
      return route.fulfill({
        json: { "client-secret": "secret", "connected-account": "acct_test" },
      });
    }
    if (u.pathname === "/api/market/checkout-session")
      return route.fulfill({ json: { url: "https://referron.test/market/orders" } });
    if (u.pathname === "/events") {
      const e = req.postDataJSON();
      events.push(e);
      const d = e["event/data"];
      let data = {};
      switch (e["event/type"]) {
        case "market/query-carts":
          data = { carts: cart ? [cart] : [] };
          break;
        case "market/query-cart":
          data = { cart, offers };
          break;
        case "market/create-cart":
          cart = { id: "cart1", buyer: "buyer", status: "open" };
          data = { cart };
          break;
        case "market/add-to-cart": {
          const c = items[d["capability-id"]];
          offers.push({
            id: "offer" + offers.length,
            seller: c.seller,
            "capability-id": c.id,
            status: "proposed",
            terms: {
              quantity: d.quantity,
              price: {
                ...c.terms.price,
                amount: c.terms.price.amount * d.quantity,
              },
            },
          });
          break;
        }
        case "market/remove-from-cart":
          offers = offers.filter((o) => o.id !== d["offer-id"]);
          break;
        case "market/accept-cart-offers":
          if (free) {
            checkouts = [{
              id: "liam-free-checkout",
              status: "pending-payment",
              "funds-flow": "seller-direct",
              total: { amount: 0, currency: "USD" },
              orders: ["liam-free-order"],
            }];
            orders = [{ id: "liam-free-order", "capability-id": "liam-pre", status: "pending-payment" }];
            batches = [{ id: "liam-free-batch", checkouts: ["liam-free-checkout"] }];
            cart = null;
            offers = [];
            data = { "checkout-batch-id": "liam-free-batch", "payment-groups": checkouts };
            break;
          }
          checkouts = [
            {
              id: "yvatar-checkout",
              status: "pending-payment",
              "funds-flow": "seller-direct",
              total: { amount: 500, currency: "USD" },
              orders: ["yvatar-order"],
            },
            {
              id: "native-checkout",
              status: "pending-payment",
              total: { amount: 1200, currency: "USD" },
              orders: [],
            },
          ];
          orders = [{ id: "yvatar-order", "capability-id": "sku0", status: "paid", fulfillment: { status: "fulfilled" } }];
          batches = [{ id: "batch", checkouts: checkouts.map((c) => c.id) }];
          cart = null;
          offers = [];
          data = { "checkout-batch-id": "batch", "payment-groups": checkouts };
          break;
      }
      return route.fulfill({ json: { "gm-result": { success: true, data } } });
    }
    if (u.pathname.startsWith("/assets/"))
      return route.fulfill({
        path: path.join(__dirname, "../resources/ui", u.pathname),
      });
    return route.fulfill({
      path: path.join(__dirname, "../resources/ui/index.html"),
      contentType: "text/html",
    });
  });
  return {
    events,
    sessions,
    issuedSeatTokens,
    paid: () => {
      checkouts[0].status = "paid";
    },
    state,
  };
}
test("seller-first directory, nine Yvatar products, direct product links and mobile layout", async ({
  page,
}) => {
  await setup(page);
  await page.goto("/market");
  await expect(page.locator(".store-card")).toHaveCount(2);
  await expect(page.locator(".store-card").first()).toContainText("Yvatar");
  await expect(
    page.locator(".store-card").filter({ hasText: "Yvatar" }),
  ).toContainText("9 products");
  await expect(page.locator('.store-card.seller-branded img')).toHaveAttribute('src','https://referron.test/assets/brand/favicon.png');
  await page.locator(".store-card").filter({ hasText: "Yvatar" }).click();
  await expect(page.locator('.storefront-header.seller-branded img')).toHaveJSProperty('naturalWidth',125);
  await expect(page.getByRole('link',{name:'Visit website ↗'})).toHaveAttribute('href','https://yvatar.com/');
  await expect(page.locator(".product-card")).toHaveCount(9);
  await page.screenshot({
    path: "test-results/yvatar-store-desktop.png",
    fullPage: true,
  });
  await page.setViewportSize({width:390,height:844});
  await page.getByRole('button',{name:'Switch to dark theme'}).click();
  await expect(page.locator('.storefront-header h1')).toHaveCSS('color','rgb(23, 20, 37)');
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.screenshot({path:test.info().outputPath('referron-yvatar-brand-mobile-dark.png'),fullPage:true});
  await page.locator(".product-card").first().click();
  await expect(page.locator(".product-detail")).toContainText("$5.00");
  await page.reload();
  await expect(page.locator(".product-detail h1")).toHaveText(
    "Yvatar credits 0",
  );
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.goto("/");
  await expect(page.locator(".store-card")).toHaveCount(2);
});

test("existing Liam Plus buyer sees a single-subscription Pro upgrade", async ({ page }) => {
  await setup(page, { upgrade: true });
  await page.goto("/market/liam-seller/products/liam-pro");
  await expect(page.getByRole("button", { name: "Review upgrade in Stripe" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Add to cart" })).toHaveCount(0);
  await page.goto("/market/liam-seller/products/liam-plus");
  await expect(page.getByRole("button", { name: "Current plan" })).toBeDisabled();
});
test("Yvatar seller can start Stripe authorization from the storefront", async ({ page }) => {
  await setup(page, { sellerSeat: true, payment: "unavailable" });
  await page.goto("/market/yvatar");
  const button = page.getByRole("button", { name: "Connect seller Stripe account" });
  await expect(button).toBeVisible();
  await button.click();
  await expect(page).toHaveURL("/?connect=return");
});
test("Stripe connection failure shows a safe reason on return", async ({ page }) => {
  await setup(page, { sellerSeat: true, payment: "unavailable" });
  await page.goto("/?connect=error&reason=oauth-invalid-grant");
  await expect(page.getByRole("alert")).toContainText("Stripe rejected the authorization code");
  await expect(page.getByRole("alert")).toContainText("Code: oauth-invalid-grant");
  await page.goto("/?connect=error&reason=%3Cscript%3E");
  await expect(page.getByRole("alert")).toHaveText("Stripe account connection failed.");
});
test("Stripe connection success is distinct from an error", async ({ page }) => {
  await setup(page, { sellerSeat: true, connectStatus: "enabled" });
  await page.goto("/?connect=return");
  const notice = page.getByRole("status").filter({ hasText: "Stripe account connected" });
  await expect(notice.getByRole("link", { name: "Return to the market" })).toHaveAttribute("href", "/market");
  await expect(notice).toHaveCSS("background-color", "rgb(214, 245, 232)");
});
test("owner with multiple seats chooses Yvatar before Stripe authorization", async ({ page }) => {
  await setup(page, { multiSeats: true, payment: "unavailable" });
  await page.addInitScript(() => localStorage.setItem("referron.token", "test-token"));
  await page.goto("/join");
  await expect(page.getByRole("heading", { name: "Choose a player seat" })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Main navigation" })
    .getByRole("link", { name: "Choose a seat" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "@amit" })).toBeVisible();
  await expect(page.getByRole("button", { name: "@liam-seller" })).toBeVisible();
  await page.getByRole("button", { name: "@amit" }).click();
  await page.goto("/market/yvatar");
  await expect(page.getByRole("button", { name: "Connect seller Stripe account" })).toHaveCount(0);
  await expect(page.getByRole("navigation", { name: "Main navigation" }).getByLabel("Player seat")).toHaveCount(0);
  const seatPicker = page.locator("footer").getByLabel("Player seat");
  await expect(seatPicker).toBeVisible();
  await seatPicker.selectOption("yvatar");
  await expect(seatPicker).toHaveValue("yvatar");
  await expect(page.getByRole("button", { name: "Connect seller Stripe account" })).toBeVisible();
  await page.reload();
  await expect(page.locator("footer").getByLabel("Player seat")).toHaveValue("yvatar");
  await page.setViewportSize({ width: 320, height: 900 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  const request = page.waitForRequest((r) => r.url().endsWith("/api/connect/seller/start"));
  await page.getByRole("button", { name: "Connect seller Stripe account" }).click();
  expect((await request).headers()["x-selected-seat"]).toBe("yvatar");
});
test("ready Yvatar seller account hides the authorization button", async ({ page }) => {
  await setup(page, { sellerSeat: true, connectStatus: "enabled" });
  await page.goto("/market/yvatar");
  await expect(page.getByText("Your Stripe seller account is connected and ready.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Connect seller Stripe account" })).toHaveCount(0);
});
test("home focuses on discovery and referrals on mobile", async ({page})=>{
  await setup(page);await page.setViewportSize({width:390,height:844});await page.goto('/');
  await expect(page.getByRole('heading',{name:'Good things grow through people.'})).toBeVisible();
  await expect(page.locator('.referron-hero').getByRole('link',{name:'Explore the market'})).toHaveAttribute('href','/market');
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
test("public Coseller page explains no-selling influence without exposing rates", async ({ page }) => {
  await setup(page, { signed: false });
  await page.goto("/coselling");
  await expect(page.getByRole("heading", { name: /Share what matters/ })).toBeVisible();
  await expect(page.getByText("#Coselling is #NoSelling.")).toBeVisible();
  await expect(page.getByText(/article, video, manifesto, profile, store, product/)).toBeVisible();
  await expect(page.getByText(/not every sale across the marketplace/)).toBeVisible();
  await expect(page.locator("main")).not.toContainText(/30%|35%|80%|5%/);
  await expect(page.getByRole("link", { name: "Become a referrer", exact: true })).toHaveAttribute("href", "/coselling");
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
test("member can activate Coselling and then see private program terms", async ({ page }) => {
  await setup(page, { signed: true, preview: true });
  await page.goto("/coselling");
  await page.getByRole("button", { name: "Activate Coselling" }).click();
  await expect(page.getByRole("heading", { name: "Your Coseller program" })).toBeVisible();
  await expect(page.locator("main")).toContainText("Commission opportunities start around 30% and can be higher");
  await expect(page.locator(".program-terms")).toContainText("180 days");
  await expect(page.locator(".program-terms")).toContainText("80% of eligible commission");
  await expect(page.locator(".program-terms")).toContainText("5% per eligible level; no fixed depth cap");
  await expect(page.locator(".program-terms")).toContainText("1 day after fulfillment");
  await expect(page.locator(".opportunity")).toHaveCount(2);
  await expect(page.locator(".opportunity").first()).toContainText(/30%|35%/);
  await page.goto("/market/yvatar/products/sku0");
  await expect(page.locator(".product-detail")).toContainText("A member may receive a reward");
  await expect(page.locator(".product-detail")).not.toContainText(/30%|35%|80%/);
});
test("preview subscriptions are visible but cannot enter checkout", async ({ page }) => {
  const fixture = await setup(page, { preview: true });
  await page.goto("/market");
  await expect(
    page.locator(".store-card").filter({ hasText: "Liam" }),
  ).toContainText("1 product");
  await page.goto("/market/liam-seller/products/liam-plus");
  await expect(page.getByRole("button", { name: "Coming soon" })).toBeDisabled();
  await expect(page.locator("[data-market=add]")).toHaveCount(0);
  expect(fixture.events.some((e) => e["event/type"] === "market/add-to-cart")).toBe(false);
});
test("seller display order wins, then unnumbered products follow", async ({ page }) => {
  await setup(page, { ordering: true });
  await page.goto("/market/yvatar");
  await expect(page.locator(".product-card h3")).toHaveCount(9);
  const names = await page.locator(".product-card h3").allTextContents();
  expect(names.slice(0, 3)).toEqual([
    "Yvatar credits 1", "Yvatar credits 2", "Yvatar credits 0",
  ]);
  expect(names.slice(3)).toEqual([
    "Yvatar credits 3", "Yvatar credits 4", "Yvatar credits 5",
    "Yvatar credits 6", "Yvatar credits 7", "Yvatar credits 8",
  ]);
});
test("mixed cart, quantity, remove, acceptance, recovery and no browser payment attestation", async ({
  page,
}) => {
  const fixture = await setup(page);
  await page.goto("/market/yvatar/products/sku0");
  await page.locator("[data-market=add]").click();
  await expect(page.locator(".cart-line").first()).toContainText(
    "Yvatar credits 0",
  );
  await page.goto("/market/builder/products/tool");
  await page.locator("#product-quantity").fill("2");
  await page.locator("[data-market=add]").click();
  await expect(page.locator(".cart-line")).toHaveCount(2);
  await expect(page.locator(".cart-line").nth(1)).toContainText("$24.00");
  await page.reload();
  await expect(page.locator(".cart-line")).toHaveCount(2);
  await page.locator("#market-recipient").fill("buyer@example.com");
  await page.locator("[data-market=checkout]").click();
  await expect(page.locator("[data-market=pay]")).toHaveCount(2);
  fixture.paid();
  await page.reload();
  await expect(page.getByText("Yvatar credits 0: Fulfilled")).toBeVisible();
  await expect(page.locator("[data-market=pay]")).toHaveCount(1);
  await expect(page.locator("[data-market=pay]")).toHaveAttribute(
    "data-id",
    "native-checkout",
  );
  expect(
    fixture.events.some((e) =>
      /mark-checkout-paid|record-seller-checkout-paid/.test(e["event/type"]),
    ),
  ).toBe(false);
});
test("guest return path, unavailable product and missing payment configuration", async ({
  page,
}) => {
  await setup(page, { signed: false });
  await page.goto("/market/yvatar/products/sku0");
  await expect(page.locator("[data-market=add]")).toHaveText("Sign in to buy");
  await page.goto("/market/yvatar/products/missing");
  await expect(page.getByText("Product unavailable")).toBeVisible();
});
test("signed-in buyer without a handle returns to product after claiming one", async ({ page }) => {
  await setup(page, { seated: false });
  await page.addInitScript(() =>
    sessionStorage.setItem("referron.market.return", "/market/yvatar/products/sku0"),
  );
  await page.goto("/");
  await expect(page).toHaveURL("https://referron.test/join");
  await expect(page.getByRole("heading", { name: "Pick your handle" })).toBeVisible();
  await page.locator("#handle").fill("buyer");
  const claim = page.waitForRequest((request) => request.url().endsWith("/join/claim"));
  await page.getByRole("button", { name: "Claim it" }).click();
  expect((await claim).postDataJSON().roles).toEqual(["audience"]);
  await expect(page).toHaveURL("https://referron.test/market/yvatar/products/sku0");
  await expect(page.locator("[data-market=add]")).toHaveText("Add to cart");
});
test("player can become a Contributor from the content feed", async ({ page }) => {
  await setup(page);
  await page.goto("/studio");
  await expect(page.getByRole("button", { name: "Become a Contributor" })).toBeVisible();
  await page.goto("/insights");
  const claim = page.waitForRequest((request) => request.url().endsWith("/join/claim"));
  await page.getByRole("button", { name: "Become a Contributor" }).click();
  expect((await claim).postDataJSON()).toMatchObject({
    "player-name": "buyer",
    roles: ["contributor"],
  });
  await expect(page.getByText("Contributor access is active")).toBeVisible();
  await expect(page.getByRole("button", { name: "Become a Contributor" })).toHaveCount(0);
  await page.goto("/studio");
  await expect(page.getByText("Contributor access is active")).toBeVisible();
});
test("Studio issues and copies a token for the selected seat", async ({ page }) => {
  const fixture = await setup(page, { multiSeats: true });
  await page.addInitScript(() => localStorage.setItem("referron.token", "user-token"));
  await page.context().grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.goto("/join");
  await page.getByRole("button", { name: "@amit" }).click();
  await page.goto("/studio");
  await expect(page.getByRole("heading", { name: "Connect your AI" })).toBeVisible();
  await expect(page.locator("#seat-token-value")).toHaveCount(0);
  await page.getByRole("button", { name: "Generate seat token" }).click();
  await expect(page.locator("#seat-token-value")).toHaveValue("seat-token-amit");
  await expect(page.locator("#seat-token-value")).toHaveAttribute("type", "password");
  expect(fixture.issuedSeatTokens).toMatchObject([{ playerName: "amit", headers: {
    authorization: "Bearer user-token", "x-selected-seat": "amit" } }]);
  await page.getByRole("button", { name: "Copy token" }).click();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe("seat-token-amit");
  await page.locator("footer").getByLabel("Player seat").selectOption("yvatar");
  await expect(page.locator("#seat-token-value")).toHaveCount(0);
  await page.getByRole("button", { name: "Generate seat token" }).click();
  await expect(page.locator("#seat-token-value")).toHaveValue("seat-token-yvatar");
  await page.setViewportSize({ width: 320, height: 700 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole("link", { name: "Insights" }).first().click();
  await page.goto("/studio");
  await expect(page.locator("#seat-token-value")).toHaveCount(0);
});
test("Studio handles token service errors and expiry", async ({ page }) => {
  await setup(page, { tokenFailure: true });
  await page.goto("/studio");
  await page.getByRole("button", { name: "Generate seat token" }).click();
  await expect(page.getByRole("alert")).toContainText("Could not create a seat token");
  await expect(page.locator("#seat-token-value")).toHaveCount(0);
});
test("Studio offers seat selection before token generation", async ({ page }) => {
  await setup(page, { seated: false });
  await page.goto("/studio");
  await expect(page.getByRole("button", { name: "Generate seat token" })).toHaveCount(0);
  await expect(page.locator(".connect-ai").getByRole("link", { name: "Pick a handle" })).toBeVisible();
});
test("Studio removes an expired seat token", async ({ page }) => {
  await setup(page, { tokenLifetime: 1500 });
  await page.goto("/studio");
  await page.getByRole("button", { name: "Generate seat token" }).click();
  await expect(page.locator("#seat-token-value")).toBeVisible();
  await expect(page.locator("#seat-token-value")).toHaveCount(0, { timeout: 4000 });
});
test("home presents the market and content remains available in Insights",async({page})=>{
 const essays=Array.from({length:5},(_,i)=>({id:'essay'+i,slug:'essay'+i,payload:{title:'Perspective '+i}}));
 await setup(page,{essays});await page.goto('/');await expect(page.locator('.store-card')).toHaveCount(2);
 await page.goto('/insights');await expect(page.locator('.essay')).toHaveCount(5);
});

test("Insights has an empty state before publication", async ({ page }) => {
  await setup(page, { signed: false });
  await page.goto("/insights");
  await expect(page.getByText("No insights yet")).toBeVisible();
  await expect(page.getByRole("link", { name: "Join to contribute" })).toBeVisible();
});
test("product button directs a signed-in buyer without a handle to join", async ({ page }) => {
  await setup(page, { seated: false });
  await page.goto("/market/yvatar/products/sku0");
  await expect(page.locator("[data-market=add]")).toHaveText("Pick a handle to buy");
  await page.locator("[data-market=add]").click();
  await expect(page).toHaveURL("https://referron.test/join");
  await expect(page.getByRole("heading", { name: "Pick your handle" })).toBeVisible();
});
test("checkout disabled without configured payment provider", async ({
  page,
}) => {
  await setup(page, { payment: "unavailable" });
  await page.goto("/market/yvatar/products/sku0");
  await page.locator("[data-market=add]").click();
  await expect(page.locator("[data-market=checkout]")).toBeDisabled();
  await expect(
    page.getByText("Payments are temporarily unavailable. Your cart is saved."),
  ).toBeVisible();
});
test("free seller subscription activates without Stripe", async ({ page }) => {
  const fixture = await setup(page, { free: true, payment: "unavailable" });
  await page.goto("/market/liam-seller/products/liam-pre");
  await page.locator("[data-market=add]").click();
  await expect(page.locator("[data-market=checkout]")).toBeEnabled();
  await expect(page.locator("[data-market=checkout]")).toHaveText("Review free subscription");
  await page.locator("#market-recipient").fill("buyer@example.com");
  await page.locator("[data-market=checkout]").click();
  await expect(page.locator("[data-market=pay]")).toBeEnabled();
  await expect(page.locator("[data-market=pay]")).toHaveText("Activate free subscription");
  await page.locator("[data-market=pay]").click();
  await expect(page.getByText("Liam Pre: Fulfilled")).toBeVisible();
  await expect(page.locator("[data-market=pay]")).toHaveCount(0);
  expect(fixture.sessions).toHaveLength(1);
  expect(fixture.sessions[0]["recipient-email"]).toBe("buyer@example.com");
});
test("mixed free and paid cart remains blocked without Stripe", async ({ page }) => {
  await setup(page, { free: true, payment: "unavailable" });
  await page.goto("/market/liam-seller/products/liam-pre");
  await page.locator("[data-market=add]").click();
  await expect(page.locator(".cart-line")).toHaveCount(1);
  await page.goto("/market/builder/products/tool");
  await page.locator("[data-market=add]").click();
  await expect(page.locator(".cart-line")).toHaveCount(2);
  await expect(page.locator("[data-market=checkout]")).toBeDisabled();
  await expect(page.getByText("Payments are temporarily unavailable. Your cart is saved.")).toBeVisible();
});

test("resumed Yvatar payment collects recipient, embeds Stripe and waits for verified status", async ({
  page,
}) => {
  const fixture = await setup(page);
  await page.addInitScript(() => {
    window.Stripe = (key, options) => ({
      initEmbeddedCheckout: async (config) => {
        window.stripeTest = {
          key,
          options,
          secret: await config.fetchClientSecret(),
          complete: config.onComplete,
        };
        return {
          mount: (selector) => {
            document.querySelector(selector).textContent =
              "Secure payment form";
          },
          destroy: () => {
            const node = document.querySelector("#market-payment");
            if (node) node.textContent = "";
          },
        };
      },
    });
  });
  await page.goto("/market/yvatar/products/sku0");
  await page.locator("[data-market=add]").click();
  await page.locator("#market-recipient").fill("buyer@example.com");
  await page.locator("[data-market=checkout]").click();
  await page.goto("/market/orders");
  await page.locator('[data-market=pay][data-id="yvatar-checkout"]').click();
  await expect(page).toHaveURL(/market\/cart$/);
  await expect(page.locator("#market-recipient")).toBeVisible();
  await page.locator("#market-recipient").fill("buyer@example.com");
  await page.locator('[data-market=pay][data-id="yvatar-checkout"]').click();
  await expect(page.locator("#market-payment")).toHaveText(
    "Secure payment form",
  );
  await page.getByRole('button', { name: 'Switch to dark theme' }).click();
  await expect(page.locator('#market-payment')).toHaveText('Secure payment form');
  expect(
    await page.evaluate(() => window.stripeTest.options.stripeAccount),
  ).toBe("acct_test");
  await page.evaluate(() => window.stripeTest.complete());
  await expect(page.locator("[data-market=pay]")).toHaveCount(2);
  await page.locator('[data-market=pay][data-id="yvatar-checkout"]').click();
  await expect(page.locator("#market-payment")).toHaveText(
    "Secure payment form",
  );
  expect(fixture.sessions).toHaveLength(2);
  expect(fixture.sessions[0]["attempt-id"]).toBe(
    fixture.sessions[1]["attempt-id"],
  );
  fixture.paid();
  await page.evaluate(() => window.stripeTest.complete());
  await expect(page.locator("[data-market=pay]")).toHaveCount(1);
  expect(
    fixture.events.some((e) =>
      /mark-checkout-paid|record-seller-checkout-paid/.test(e["event/type"]),
    ),
  ).toBe(false);
});

test("removing a cart line survives reload", async ({ page }) => {
  await setup(page);
  await page.goto("/market/yvatar/products/sku0");
  await page.locator("[data-market=add]").click();
  await page.locator("[data-market=remove]").click();
  await page.reload();
  await expect(page.locator("[data-market=remove]")).toHaveCount(0);
  await expect(
    page.getByText("Your cart is empty.", { exact: false }),
  ).toBeVisible();
});

test("stale catalog rejection preserves cart and displays the provider error", async ({
  page,
}) => {
  await setup(page);
  await page.goto("/market/yvatar/products/sku0");
  await page.locator("[data-market=add]").click();
  await page.route("**/events", async (route) => {
    if (
      route.request().postDataJSON()["event/type"] !==
      "market/accept-cart-offers"
    )
      return route.fallback();
    return route.fulfill({
      status: 409,
      json: {
        "gm-result": {
          success: false,
          error: { message: "Catalog changed. Review current prices." },
        },
      },
    });
  });
  await page.locator("#market-recipient").fill("buyer@example.com");
  await page.locator("[data-market=checkout]").click();
  await expect(page.getByRole("alert")).toHaveText(
    "Catalog changed. Review current prices.",
  );
  await expect(page.getByRole("alert")).toBeFocused();
  await expect(page.locator("[data-market=remove]")).toHaveCount(1);
  await expect(page.locator("[data-market=pay]")).toHaveCount(0);
});

 test("nonlocal pages reject loopback and script image URLs", async ({ page }) => {
  await setup(page);
  await page.goto("/market");
  expect(await page.evaluate(() => safeMarketImage("http://localhost:3000/img/test.png"))).toBe("");
  expect(await page.evaluate(() => safeMarketImage("javascript:alert(1)"))).toBe("");
 });

test('theme persists without resetting product quantity or keyboard focus', async ({ page }) => {
  await setup(page);
  await page.goto('/market/yvatar/products/sku0');
  await page.locator('#product-quantity').fill('7');
  const toggle = page.getByRole('button', { name: 'Switch to dark theme' });
  await toggle.focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await expect(page.locator('#product-quantity')).toHaveValue('7');
  await expect(page.getByRole('button', { name: 'Switch to light theme' })).toBeFocused();
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.getByRole('button', { name: 'Switch to light theme' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#f5f5f7');
});

test('light theme is the default even for dark system preference', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'dark' });
  await setup(page);
  await page.goto('/');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await expect(page.locator('html')).toHaveCSS('color-scheme', 'light');
  await expect(page.getByRole('button', { name: 'Switch to dark theme' })).toBeVisible();
});

test('mobile navigation, reading and long product names fit both themes', async ({ page }) => {
  const fixture = await setup(page);
  fixture.state.gameState.market.capabilities.sku0.title = 'Averylongunbrokenproductnameforindependentcreators';
  fixture.state.gameState.attention.feed.push({
    id: 'reading', slug: 'reading', author: 'builder',
    payload: { title: 'A new perspective on personal AI', summary: 'Make something that stays yours.',
      featured_image: '/assets/brand/referron-logo.png',
      body: '<p>Ideas worth keeping.</p><figure><img src="/assets/brand/referron-logo.png" alt="Brand artwork"><figcaption>A new perspective.</figcaption></figure>' }
  });
  for (const width of [320, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    for (const route of ['/', '/insights/reading', '/market/yvatar/products/sku0', '/market/cart']) {
      await page.goto(route);
      await expect(page.locator('header')).toBeVisible();
      const toggle = page.locator('.theme-toggle');
      await expect(toggle).toBeVisible();
      for (const theme of ['light', 'dark']) {
        await page.evaluate(theme => { if (document.documentElement.dataset.theme !== theme) toggleTheme(); }, theme);
        await expect(toggle).toBeVisible();
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        await expect.poll(async () => (await toggle.boundingBox())?.width || 0).toBeGreaterThanOrEqual(44);
      }
    }
  }
});

test('sharing metadata and icons use the refreshed identity', async ({ page }) => {
  await setup(page, { signed: false });
  await page.goto('/');
  await expect(page).toHaveTitle('Referron Market — Connect. Refer. Grow.');
  const description = await page.locator('meta[name="description"]').getAttribute('content');
  await expect(page.locator('meta[property="og:description"]')).toHaveAttribute('content', description);
  await expect(page.locator('meta[name="twitter:description"]')).toHaveAttribute('content', description);
  expect(await page.evaluate(async () => {
    const img = new Image(); img.src = '/assets/brand/referron-logo.png'; await img.decode();
    return [img.naturalWidth, img.naturalHeight];
  })).toEqual([1153, 340]);
  await expect(page.locator('main h1')).toHaveText('Good things grow through people.');
});

for (const scenario of [
  { name: 'guest', options: { signed: false }, action: 'Join Referron Market', destination: '/join' },
  { name: 'signed in without a handle', options: { seated: false }, action: 'Choose your handle', destination: '/join' },
  { name: 'signed in with no selected account', options: { multiSeats: true }, action: 'Choose your account', destination: '/join' },
  { name: 'selected account without coselling', options: {}, action: 'Share-to-Earn', destination: '/coselling' },
  { name: 'active coseller', options: { coseller: true }, earning: true },
]) {
  test(`essay actions reflect ${scenario.name} and copy the correct URL`, async ({ page }) => {
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
    await setup(page, { ...scenario.options, essays: [{ id: 'reading', slug: 'reading', author: 'builder',
      payload: { title: 'An essay worth sharing', body: '<p>Ideas worth keeping.</p>' } }] });
    await page.goto('/insights/reading?ref=someone-else');
    const actions = page.locator('article.reader > .foot-cta');
    await expect(actions).toBeVisible();
    if (scenario.action) await expect(actions.getByRole('link', { name: scenario.action, exact: true })).toHaveAttribute('href', scenario.destination);
    if (scenario.options.signed !== false) await expect(actions.getByRole('link', { name: 'Join Referron Market', exact: true })).toHaveCount(0);
    const copy = actions.getByRole('button', { name: scenario.earning ? 'Copy your earning link' : 'Copy essay link', exact: true });
    await copy.click();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe('https://referron.test/insights/reading' + (scenario.earning ? '?ref=ref-buyer' : ''));
    if (scenario.earning) {
      await expect(actions.getByText('Includes your sharing attribution')).toBeVisible();
      await expect(actions.getByRole('link')).toHaveCount(0);
    } else await expect(actions.getByText('Includes your sharing attribution')).toHaveCount(0);
  });
}

test('coselling steps keep text beside their numbers without vertical stacking', async ({ page }) => {
  await setup(page, { signed: false });
  await page.goto('/coselling');
  const steps = page.locator('.coseller-steps li');
  await expect(steps).toHaveCount(5);
  for (const width of [320, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    for (const step of await steps.all()) {
      const geometry = await step.evaluate(el => {
        const label = el.querySelector('b');
        const text = [...el.childNodes].find(node => node.nodeType === Node.TEXT_NODE && node.textContent.trim());
        const range = document.createRange(); range.selectNodeContents(text);
        const labelBox = label.getBoundingClientRect();
        const lines = [...range.getClientRects()];
        return { height: el.getBoundingClientRect().height, labelLeft: labelBox.left,
          textLeft: Math.min(...lines.map(rect => rect.left)), lineCount: lines.length };
      });
      expect(geometry.textLeft).toBeGreaterThanOrEqual(geometry.labelLeft - 1);
      expect(geometry.height).toBeLessThan(240);
      expect(geometry.lineCount).toBeLessThan(9);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
});

async function sharingEssay(page, options = {}) {
  await page.context().grantPermissions(['clipboard-read','clipboard-write']);
  await setup(page, { coseller: true, ...options, essays: [{id:'share-test',slug:'share-test',author:'builder',payload:{title:'A useful perspective',body:'<p>Ideas worth sharing.</p>'}}] });
  await page.goto('/insights/share-test?ref=someone-else&token=not-a-real-secret#section');
  await expect(page.getByRole('heading',{name:'A useful perspective',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Share-to-Earn',exact:true}).click();
  return page.getByRole('dialog',{name:'Share-to-Earn'});
}
test('share panel prepares the current essay link and restores keyboard focus',async({page})=>{
  const panel=await sharingEssay(page);
  await expect(panel.getByText('A useful perspective',{exact:true})).toBeVisible();
  await expect(panel.getByLabel('Your personal link')).toHaveValue('https://referron.test/insights/share-test?ref=ref-buyer');
  await panel.getByRole('button',{name:'Copy link',exact:true}).click();
  await expect(panel.getByRole('status')).toHaveText('Personal link copied.');
  expect(await page.evaluate(()=>navigator.clipboard.readText())).toBe('https://referron.test/insights/share-test?ref=ref-buyer');
  await page.keyboard.press('Escape');
  await expect(panel).not.toBeVisible();
  await expect(page.getByRole('button',{name:'Share-to-Earn',exact:true})).toBeFocused();
  await expect(page.locator('footer').getByRole('link',{name:'Become a referrer',exact:true})).toHaveAttribute('href','/coselling');
});
test('inactive coseller explicitly activates inside the panel without leaving the essay',async({page})=>{
  const panel=await sharingEssay(page,{coseller:false});
  await expect(panel.getByRole('button',{name:'Activate Share-to-Earn'})).toBeVisible();
  await expect(panel.getByLabel('Your personal link')).toHaveCount(0);
  await panel.getByRole('button',{name:'Activate Share-to-Earn'}).click();
  await expect(panel.getByLabel('Your personal link')).toHaveValue('https://referron.test/insights/share-test?ref=ref-buyer');
  await expect(page).toHaveURL(/\/insights\/share-test/);
});
test('signed-out share panel preserves a safe return target without showing an earning link',async({page})=>{
  const panel=await sharingEssay(page,{signed:false});
  await expect(panel.getByRole('button',{name:'Sign in to Share-to-Earn'})).toBeVisible();
  await expect(panel.getByLabel('Your personal link')).toHaveCount(0);
  await page.route('https://auth.example.test/**',route=>route.fulfill({body:'Sign-in handoff'}));
  await panel.getByRole('button',{name:'Sign in to Share-to-Earn'}).click();
  await expect(page).toHaveURL(/auth.example.test/);
});
for(const scenario of [{seated:false,label:'Choose your handle'},{multiSeats:true,label:'Choose your account'}]) {
  test(`share panel resumes the original essay after ${scenario.label}`,async({page})=>{
    const panel=await sharingEssay(page,{...scenario,coseller:false});
    await panel.getByRole('button',{name:scenario.label}).click();
    await expect(page).toHaveURL('https://referron.test/join');
    if(scenario.multiSeats) await page.getByRole('button',{name:'@yvatar',exact:true}).click();
    else {await page.locator('#handle').fill('buyer');await page.getByRole('button',{name:/Claim/}).click();}
    await expect(page).toHaveURL('https://referron.test/insights/share-test');
    await expect(panel.getByRole('button',{name:'Activate Share-to-Earn'})).toBeVisible();
    await expect(panel.getByLabel('Your personal link')).toHaveCount(0);
  });
}
test('share panel handles verification failure without showing stale attribution',async({page})=>{
  await setup(page,{coseller:true});
  await page.goto('/');
  await page.route('**/api/coseller/program',route=>route.fulfill({status:401,json:{error:{message:'Sign-in expired'}}}));
  await page.getByRole('button',{name:'Share-to-Earn',exact:true}).click();
  const panel=page.getByRole('dialog',{name:'Share-to-Earn'});
  await expect(panel.getByRole('alert')).toContainText('Sign-in expired');
  await expect(panel.getByLabel('Your personal link')).toHaveCount(0);
  await expect(panel.getByRole('button',{name:'Refresh sign-in'})).toBeVisible();
});
test('private pages share the public homepage and preserve the Studio draft',async({page})=>{
  await setup(page,{coseller:true});
  await page.goto('/studio');
  await page.getByRole('button',{name:'Generate seat token',exact:true}).click();
  const input=page.locator('#seat-token-value');
  await expect(input).toHaveValue('seat-token-buyer');
  await page.getByRole('button',{name:'Share-to-Earn',exact:true}).click();
  const panel=page.getByRole('dialog',{name:'Share-to-Earn'});
  await expect(panel.getByText(/Share the Referron Market homepage instead/)).toBeVisible();
  await expect(panel.getByLabel('Your personal link')).toHaveValue('https://referron.test/?ref=ref-buyer');
  await panel.getByRole('button',{name:'Close share panel'}).click();
  await expect(input).toHaveValue('seat-token-buyer');
});
test('share panel supports native sharing, cancellation, and manual copy fallback',async({page})=>{
  await page.addInitScript(()=>{window.nativeShareCalls=[];navigator.share=async data=>{window.nativeShareCalls.push(data);throw new DOMException('Cancelled','AbortError');};});
  const panel=await sharingEssay(page);
  await panel.getByRole('button',{name:'Share',exact:true}).click();
  expect(await page.evaluate(()=>window.nativeShareCalls)).toEqual([{title:'A useful perspective',url:'https://referron.test/insights/share-test?ref=ref-buyer'}]);
  await expect(panel.getByRole('status')).toHaveText('');
  await page.evaluate(()=>{navigator.clipboard.writeText=async()=>{throw new Error('Unavailable');};});
  await panel.getByRole('button',{name:'Copy link',exact:true}).click();
  await expect(panel.getByRole('status')).toContainText('copy it manually');
  await expect(panel.getByLabel('Your personal link')).toBeVisible();
  await page.setViewportSize({width:320,height:640});
  expect(await panel.evaluate(el=>el.getBoundingClientRect().width)).toBeLessThanOrEqual(320);
});
test('sign-in return reopens the original page and still requires explicit activation',async({page})=>{
  await page.addInitScript(()=>sessionStorage.setItem('referron.share-intent.v1',JSON.stringify({path:'/essays/share-test?ref=other',title:'A useful perspective',fallback:false})));
  await setup(page,{essays:[{id:'share-test',slug:'share-test',payload:{title:'A useful perspective',body:'<p>Read this.</p>'}}]});
  await page.goto('/');
  await expect(page).toHaveURL('https://referron.test/insights/share-test');
  await expect(page.getByRole('dialog',{name:'Share-to-Earn'}).getByRole('button',{name:'Activate Share-to-Earn'})).toBeVisible();
});
test('activation failure offers recovery without presenting an earning link',async({page})=>{
  const panel=await sharingEssay(page,{coseller:false});
  await page.route('**/api/coseller/activate',route=>route.fulfill({status:503,json:{error:{message:'Please retry later'}}}));
  await panel.getByRole('button',{name:'Activate Share-to-Earn'}).click();
  await expect(panel.getByRole('alert')).toContainText('Please retry later');
  await expect(panel.getByLabel('Your personal link')).toHaveCount(0);
  await expect(panel.getByRole('button',{name:'Retry',exact:true})).toBeVisible();
});
test('switching accounts clears the prepared sharing token',async({page})=>{
  await page.addInitScript(()=>localStorage.setItem('referron.token','user-token'));
  await setup(page,{multiSeats:true,coseller:true});
  await page.route('**/api/coseller/program',route=>{
    const name=route.request().headers()['x-selected-seat'];
    return route.fulfill({json:{status:'active','player-name':name,'ref-token':'ref-'+name}});
  });
  await page.goto('/join');
  await page.getByRole('button',{name:'@amit',exact:true}).click();
  await page.goto('/');
  await page.getByRole('button',{name:'Share-to-Earn',exact:true}).click();
  const panel=page.getByRole('dialog',{name:'Share-to-Earn'});
  await expect(panel.getByLabel('Your personal link')).toHaveValue('https://referron.test/?ref=ref-amit');
  await panel.getByRole('button',{name:'Close share panel'}).click();
  await page.locator('footer').getByLabel('Player seat').selectOption('yvatar');
  await page.getByRole('button',{name:'Share-to-Earn',exact:true}).click();
  await expect(panel.getByLabel('Your personal link')).toHaveCount(0);
  await expect(panel.getByRole('button',{name:'Activate Share-to-Earn'})).toBeVisible();
});
