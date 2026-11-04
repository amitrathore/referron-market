/* Storefront identity is presentation only; every transaction retains seller IDs. */
const Market = {
  busy: false,
  error: "",
  cart: null,
  offers: [],
  purchases: { checkouts: [], batches: [], orders: [] },
  subscriptions: [],
  payment: null,
  recipient: "",
  embedded: null,
  sellerConnect: null,
  storefrontDraft: null,
  storefrontSaving: false,
  storefrontError: "",
};
const yvatarSellerSeat = () =>
  S.session?.selectedSeat?.playerName === "yvatar" && hasRole("seller");
const sellerPath = (id) => "/market/" + encodeURIComponent(id);
const productPath = (c) =>
  sellerPath(c.seller) + "/products/" + encodeURIComponent(c.id);
const activeGoods = () =>
  caps().filter((c) => (c.status || "active") === "active" && c.seller);
const sellerName = id => sellerProfile(id)["display-name"] || id;
const yvatarBrand = () => false;
const sellerProfile = id => S.state?.gameState?.storefront?.profiles?.[id] || {};
const brandColorFields = ["brand-primary", "brand-secondary", "brand-button"];
const defaultBrandColors = ["#0294db", "#edf8fe", "#0172b5"];
const safeBrandColor = (value) => /^#[0-9a-f]{6}$/i.test(value || "") ? value.toLowerCase() : "";
function colorLuminance(color) {
  const rgb = color.slice(1).match(/../g).map(x => parseInt(x,16)/255)
    .map(x => x <= .04045 ? x/12.92 : ((x+.055)/1.055)**2.4);
  return rgb[0]*.2126+rgb[1]*.7152+rgb[2]*.0722;
}
function readableBrandAccent(color, secondary) {
  const tint=secondary.slice(1).match(/../g).map(x=>parseInt(x,16));
  const surface=[248,246,255].map((x,i)=>Math.round(x*(1-38/255)+tint[i]*38/255));
  const background="#"+surface.map(x=>x.toString(16).padStart(2,"0")).join("");
  const luminance=Math.min(colorLuminance(background),colorLuminance("#f8f6ff"));
  let rgb=color.slice(1).match(/../g).map(x=>parseInt(x,16));
  const hex=()=>"#"+rgb.map(x=>x.toString(16).padStart(2,"0")).join("");
  while ((luminance+.05)/(colorLuminance(hex())+.05)<4.5)
    rgb=rgb.map(x=>Math.floor(x*.9));
  return hex();
}
function sellerBrandAttributes(id) {
  const p=sellerProfile(id), primary=safeBrandColor(p["brand-primary"]);
  if(!primary) return "";
  const secondary=safeBrandColor(p["brand-secondary"])||primary;
  const button=safeBrandColor(p["brand-button"])||primary;
  const buttonInk=colorLuminance(button)>.179?"#000000":"#ffffff";
  return ` style="--store-primary:${primary};--store-gradient:${secondary}26;--store-button:${button};--store-button-ink:${buttonInk};--store-link:${readableBrandAccent(primary,secondary)}"`;
}
const sellerBrandClass = (id) => safeBrandColor(sellerProfile(id)["brand-primary"]) ? " seller-branded" : "";
const ownsStorefront = (id) => hasRole("seller") && S.session?.selectedSeat?.playerName === id;
function compareMarketProducts(a, b) {
  const orderA = Number.isInteger(a?.["display-order"]) ? a["display-order"] : Number.MAX_SAFE_INTEGER;
  const orderB = Number.isInteger(b?.["display-order"]) ? b["display-order"] : Number.MAX_SAFE_INTEGER;
  return orderA - orderB ||
    String(a?.title || "").localeCompare(String(b?.title || "")) ||
    String(a?.id || a?.["external-ref"] || "").localeCompare(
      String(b?.id || b?.["external-ref"] || ""),
    );
}
function storefronts() {
  const groups = new Map();
  for (const id of Object.keys(S.state?.gameState?.storefront?.profiles || {})) groups.set(id, []);
  for (const c of activeGoods()) {
    if (!groups.has(c.seller)) groups.set(c.seller, []);
    groups.get(c.seller).push(c);
  }
  return [...groups]
    .sort(([a], [b]) => sellerName(a).localeCompare(sellerName(b)))
    .map(([id, products]) => ({ id, products: products.sort(compareMarketProducts) }));
}
function safeMarketImage(value) {
  if (!value) return "";
  try {
    const u = new URL(value, location.origin);
    const loopback = (host) => ["localhost", "127.0.0.1", "[::1]"].includes(host);
    return u.protocol === "https:" ||
      (u.protocol === "http:" &&
        (u.origin === location.origin ||
          (loopback(location.hostname) && loopback(u.hostname))))
      ? u.href
      : "";
  } catch {
    return "";
  }
}
function productImage(c) {
  return safeMarketImage(c.images?.[0]?.url || "");
}
function sellerCard(s) {
  const profile=sellerProfile(s.id);
  const description = profile.tagline || profile.description || "Products and services from " + sellerName(s.id) + ".";
  return `<a class="store-card${sellerBrandClass(s.id)}"${sellerBrandAttributes(s.id)} href="${sellerPath(s.id)}" data-nav>${sellerLogo(s.id)}<h3>${esc(sellerName(s.id))}</h3>${comingSoonStamp(s)}<p>${esc(description)}</p><span class="store-meta">${s.products.length ? `${s.products.length} product${s.products.length === 1 ? "" : "s"} · ` : ""}Visit store</span></a>`;
}
const storeComingSoon = (store) => !store.products.some(marketCheckoutEnabled);
const comingSoonStamp = (store) => storeComingSoon(store) ? '<span class="coming-soon-stamp">Coming soon!</span>' : '';
function sellerLogo(id) {
  const logo = safeMarketImage(sellerProfile(id)["logo-url"]);
  return `<span class="store-monogram" aria-hidden="true">${esc(sellerName(id).slice(0,2))}${logo?`<img src="${esc(logo)}" alt="" onerror="this.remove()">`:""}</span>`;
}
function storefrontHeader(store) {
  const p=sellerProfile(store.id), website=safeMarketImage(p["website-url"]);
  return `<header class="storefront-header${sellerBrandClass(store.id)}"${sellerBrandAttributes(store.id)}>${sellerLogo(store.id)}<div><p class="eyebrow">Seller storefront</p><h1>${esc(sellerName(store.id))}</h1>${comingSoonStamp(store)}
    ${p.tagline?`<p class="storefront-tagline">${esc(p.tagline)}</p>`:""}
    <p class="storefront-description">${esc(p.description||"Explore products from "+sellerName(store.id)+".")}</p>
    ${storeComingSoon(store)?'<p class="storefront-launch-note">This seller is not active on Referron Market yet. Check back for the launch.</p>':''}
    <div class="storefront-links">${store.products.length?'<a class="btn" href="#storefront-products">Browse products</a>':''}${website?`<a href="${esc(website)}" target="_blank" rel="noopener noreferrer">Visit website ↗</a>`:""}
    ${storeComingSoon(store)&&!store.products.length?'':`<a href="/@${encodeURIComponent(store.id)}" data-nav>Community profile</a>`}${p["support-email"]?`<a href="mailto:${esc(p["support-email"])}">Contact support</a>`:""}
    ${ownsStorefront(store.id)?`<button class="btn" data-market="edit-storefront">Edit storefront</button>`:""}</div></div></header>${storefrontEditor(store.id)}`;
}
function storefrontEditor(id) {
  const draft=Market.storefrontDraft;
  if(!ownsStorefront(id)||draft?.seller!==id) return "";
  const fields=[["display-name","Store name",100],["tagline","Tagline",160],["logo-url","Logo URL",2048],["website-url","Website URL",2048],["support-email","Support email",254]];
  const branded=!!safeBrandColor(draft["brand-primary"]);
  return `<form class="storefront-editor" onsubmit="saveStorefront(event)"><h2>Edit storefront</h2><p>Your changes publish immediately.</p>
    ${fields.map(([key,label,max])=>`<label>${label}<input name="${key}" value="${esc(draft[key]||"")}" maxlength="${max}" ${key==="display-name"?"required":""} type="${key.endsWith("url")?"url":key==="support-email"?"email":"text"}"></label>`).join("")}
    <label>Description<textarea name="description" maxlength="2000" rows="5">${esc(draft.description||"")}</textarea></label>
    <label class="storefront-brand-toggle"><input type="checkbox" name="use-brand-colors" ${branded?"checked":""} onchange="toggleStorefrontColors(this.checked)"> Use brand colors</label>
    <fieldset class="storefront-colors" ${branded?"":"hidden disabled"}><legend>Brand colors</legend>
      <p>Keep the storefront style and choose three colors. Text contrast is adjusted automatically.</p>
      ${brandColorFields.map((key,i)=>`<label>${["Primary — border and accents","Secondary — gradient","Button color"][i]}<input type="color" name="${key}" value="${safeBrandColor(draft[key])||defaultBrandColors[i]}" oninput="this.nextElementSibling.textContent=this.value"><output>${safeBrandColor(draft[key])||defaultBrandColors[i]}</output></label>`).join("")}
    </fieldset>
    <p role="alert">${esc(Market.storefrontError)}</p><button class="btn fill" ${Market.storefrontSaving?"disabled":""}>${Market.storefrontSaving?"Publishing…":"Publish changes"}</button>
    <button class="btn" type="button" data-market="cancel-storefront" ${Market.storefrontSaving?"disabled":""}>Cancel</button></form>`;
}
function toggleStorefrontColors(enabled) {
  const fields=document.querySelector('.storefront-colors');
  if(fields) {fields.hidden=!enabled;fields.disabled=!enabled;}
}
async function saveStorefront(event) {
  event.preventDefault();
  const form=event.currentTarget, seller=route().seller;
  if(!ownsStorefront(seller)||!form.reportValidity()||Market.storefrontSaving) return;
  const data=Object.fromEntries(new FormData(form));
  const branded=data["use-brand-colors"]==="on";
  delete data["use-brand-colors"];
  for(const key of brandColorFields) data[key]=branded?data[key]:"";
  Market.storefrontDraft={seller,...data}; Market.storefrontSaving=true; Market.storefrontError=""; render();
  try {
    const result=await marketRequest("/api/seller/storefront",data);
    S.state.gameState.storefront ||= {profiles:{}};
    S.state.gameState.storefront.profiles ||= {};
    S.state.gameState.storefront.profiles[seller]=result.profile;
    Market.storefrontDraft=null; toast("Storefront published");
  } catch(error) {Market.storefrontError=error.message;}
  finally {Market.storefrontSaving=false; render();}
}
function marketProductCard(c) {
  const image = productImage(c);
  return `<a class="good product-card" href="${productPath(c)}" data-nav>${image ? `<img src="${esc(image)}" alt="${esc(c.images?.[0]?.alt || c.title)}" loading="lazy">` : productPlaceholder(c)}<span class="kind">${esc(productKind(c))}</span><h3>${esc(c.title)}</h3><p>${esc(c.description || "")}</p><div class="price">${marketPrice(c.terms?.price)}</div>${marketCheckoutEnabled(c) ? "" : '<span class="kind">Preview · Coming soon</span>'}</a>`;
}
function marketCheckoutEnabled(c) {
  return c?.commerce?.["checkout-enabled"] !== false;
}
function currentMarketSubscription(c) {
  const group = c?.commerce?.["subscription-group"];
  if (!group) return null;
  return Market.subscriptions.find((s) =>
    s.seller === c.seller && s["subscription-group"] === group &&
    ["active", "pending-activation", "past-due", "cancel-at-period-end", "paused"]
      .includes(String(s.status).replace(/^:/, ""))) || null;
}
function marketProductAction(c) {
  if (!marketCheckoutEnabled(c))
    return '<button class="btn fill" disabled>Coming soon</button><p>This preview cannot be added to a cart yet.</p>';
  const sub = seated() ? currentMarketSubscription(c) : null;
  if (sub) {
    const current = caps().find((p) => p.id === sub["capability-id"]);
    const paid = sub["provider-subscription-id"] &&
      !String(sub["provider-subscription-id"]).startsWith("free:");
    if (paid && c.terms?.price?.amount > current?.terms?.price?.amount)
      return `<button class="btn fill" data-market="upgrade" data-id="${esc(sub.id)}" data-target="${esc(c.id)}" ${Market.busy ? "disabled" : ""}>Review upgrade in Stripe</button><p>Stripe will show the prorated amount before you confirm. Your existing subscription stays in place.</p>`;
    return `<button class="btn fill" disabled>${c.id === sub["capability-id"] ? "Current plan" : "Subscription already active"}</button><p>Only one plan in this family can be active at a time.</p>`;
  }
  return `<label>Quantity <input id="product-quantity" type="number" min="1" max="100" value="1"></label><button class="btn fill" data-market="add" data-id="${esc(c.id)}" ${Market.busy ? "disabled" : ""}>${seated() ? "Add to cart" : seatChoiceRequired() ? "Choose a seat to buy" : S.session?.authenticated ? "Pick a handle to buy" : "Sign in to buy"}</button>`;
}
function productKind(c) {
  return c.kind === "digital-service-credit" ? "Creative credits" : (c.kind || "Product").replaceAll("-", " ");
}
function productPlaceholder(c) {
  return `<div class="product-placeholder" aria-hidden="true">${esc(sellerName(c.seller).slice(0, 2))}</div>`;
}
function marketPrice(p) {
  if (!p || !Number.isFinite(p.amount)) return "Price unavailable";
  if (p.amount === 0) return "Free";
  try {
    return esc(
      new Intl.NumberFormat("en-US", {
        style: "currency",
        currency: p.currency,
      }).format(p.amount / 100),
    );
  } catch {
    return esc((p.amount / 100).toFixed(2) + " " + p.currency);
  }
}
function marketNav() {
  const current = (path) => location.pathname === path ? ' aria-current="page"' : '';
  return `<nav class="market-nav" aria-label="Market"><a href="/market" data-nav${current('/market')}>All stores</a><a href="/market/cart" data-nav${current('/market/cart')}>Cart (${Market.offers.length})</a><a href="/market/orders" data-nav${current('/market/orders')}>Purchases</a>${hasRole('seller')?`<a href="${sellerPath(S.session.selectedSeat.playerName)}" data-nav>Your storefront</a>`:''}</nav>`;
}
function marketNotice() {
  return Market.error
    ? `<p role="alert" class="market-error">${esc(Market.error)}</p>`
    : "";
}
function yvatarConnectCard() {
  if (Market.sellerConnect?.status === "enabled" &&
      Market.sellerConnect?.accountType === "standard" &&
      Market.sellerConnect?.paymentsEnabled && Market.sellerConnect?.payoutsEnabled)
    return '<p>Your Stripe seller account is connected and ready.</p>';
  if (Market.sellerConnect?.error)
    return `<p role="alert" class="market-error">${esc(Market.sellerConnect.error)}</p>`;
  return '<button class="btn" data-market="connect-seller">Connect seller Stripe account</button>';
}
function marketView(r) {
  let body = "";
  const stores = storefronts();
  if (!r.seller) {
    body = `<h1 class="display">Explore the market</h1><p class="lede">Discover products and services from the people behind them.</p><div class="stores">${stores.map(sellerCard).join("") || emptyBlock("The market is opening", "The first storefronts are on their way. Apply to become a seller and share your offering.")}</div>`;
  } else if (r.seller === "cart") body = viewMarketCart();
  else if (r.seller === "orders") body = viewMarketOrders();
  else {
    const store = stores.find((s) => s.id === r.seller) || (ownsStorefront(r.seller)?{id:r.seller,products:[]}:null);
    const c = store?.products.find((p) => p.id === r.product);
    if (r.product)
      body = c
        ? `<a href="${sellerPath(store.id)}" data-nav>Back to ${esc(sellerName(store.id))}</a><div class="product-detail">${productImage(c) ? `<img src="${esc(productImage(c))}" alt="${esc(c.title)}">` : productPlaceholder(c)}<div><h1>${esc(c.title)}</h1><p>${esc(c.description || "")}</p><p class="price">${marketPrice(c.terms?.price)}</p>${marketProductAction(c)}<p>Sold by <a href="${sellerPath(c.seller)}" data-nav>${esc(sellerName(c.seller))}</a></p>${myRefToken()?`<button class="btn" data-market="share" data-path="${esc(productPath(c))}">Copy your sharing link</button>`:""}<p class="mono" style="color:var(--dim)">A member may receive a reward when a purchase follows their sharing link. Buyers never pay more because of this.</p></div></div>`
        : emptyBlock(
            "Product unavailable",
            "This product is no longer listed. Browse the seller’s other products.",
          );
    else
      body = store
        ? `${storefrontHeader(store)}${store.id === "yvatar" && yvatarSellerSeat() ? yvatarConnectCard() : ""}<section id="storefront-products"><h2>Products <span class="storefront-count">${store.products.length}</span></h2>${store.products.length?`<div class="goods">${store.products.map(marketProductCard).join("")}</div>`:"<p>This seller is preparing their first products. Check back soon.</p>"}</section>`
        : emptyBlock(
            "No products available",
            "This seller has no active products. Browse all stores for more.",
          );
  }
  return `<section class="sec market-page">${marketNav()}${marketNotice()}${body}</section>`;
}
function resumeMarketReturn() {
  const path = sessionStorage.getItem("referron.market.return");
  if (!S.session?.authenticated || !path?.startsWith("/market/")) return;
  if (seated()) {
    sessionStorage.removeItem("referron.market.return");
    history.replaceState({}, "", path);
  } else if (location.pathname !== "/join") {
    history.replaceState({}, "", "/join");
  }
}
async function marketRequest(path, body) {
  const r = await fetch(path, {
    method: body ? "POST" : "GET",
    credentials: "include",
    signal: AbortSignal.timeout(20000),
    headers: {
      ...auth(),
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const data = await r.json();
  const result = data["gm-result"] || data;
  if (!r.ok || result.success === false || result.error)
    throw new Error(
      result.error?.message ||
        result.error?.code ||
        String(result.error || "Request failed"),
    );
  return result;
}
async function marketEvent(type, data = {}) {
  const r = await marketRequest("/events", {
    "event/type": type,
    "event/data": data,
  });
  return r.data || r;
}
async function loadMarket() {
  if (!seated()) {
    Market.cart = null;
    Market.offers = [];
    Market.purchases = { checkouts: [], batches: [], orders: [] };
    Market.subscriptions = [];
    Market.sellerConnect = null;
    return;
  }
  Market.sellerConnect = null;
  if (yvatarSellerSeat()) {
    try {
      Market.sellerConnect = await marketRequest("/api/connect/seller/status");
    } catch (e) {
      Market.sellerConnect = { error: e.message };
    }
  }
  try {
    const carts = await marketEvent("market/query-carts", {
      filters: { buyer: S.session.selectedSeat.playerName, status: "open" },
    });
    Market.cart =
      (carts.carts || []).find(
        (c) =>
          c.buyer === S.session.selectedSeat.playerName && c.status === "open",
      ) || null;
    if (Market.cart) {
      const detail = await marketEvent("market/query-cart", {
        "cart-id": Market.cart.id,
      });
      Market.offers = (detail.offers || []).filter(
        (o) => o.status === "proposed",
      );
    } else Market.offers = [];
    Market.purchases = await marketRequest("/api/market/purchases");
    Market.subscriptions = (await marketRequest("/api/market/subscriptions")).subscriptions || [];
    Market.payment = await marketRequest("/api/market/payment-mode");
  } catch (e) {
    Market.error = e.message;
  }
}
function marketSignIn() {
  sessionStorage.setItem("referron.market.return", location.pathname);
  if (!S.session?.authenticated) {
    const u = signInUrl();
    if (u) location.href = u;
    else toast("Sign in is temporarily unavailable.");
  } else go("/join");
}
async function marketAction(fn) {
  if (Market.busy) return;
  Market.busy = true;
  Market.error = "";
  try {
    await fn();
  } catch (e) {
    Market.error = e.message;
  } finally {
    Market.busy = false;
    if (!Market.embedded) {
      render();
      if (Market.error) {
        const notice = document.querySelector(".market-error");
        notice?.setAttribute("tabindex", "-1");
        notice?.focus({ preventScroll: true });
        notice?.scrollIntoView({ block: "center" });
      }
    }
  }
}
function marketRecipientField() {
  return `<label>Subscription or credit account email <input id="market-recipient" type="email" autocomplete="email" value="${esc(Market.recipient)}"></label><p>Digital credits are redeemable only with their seller and have no cash value.</p>`;
}
function freeSellerCart() {
  return Market.offers.length > 0 && Market.offers.every((offer) => {
    const capability = caps().find((c) => c.id === offer["capability-id"]);
    return capability?.commerce?.["checkout-owner"] === "seller" &&
      offer.terms?.price?.amount === 0;
  });
}
function freeSellerCheckout(checkout) {
  return checkout?.["funds-flow"] === "seller-direct" &&
    checkout.total?.amount === 0;
}
function viewMarketCart() {
  if (!seated())
    return '<h1>Your cart</h1><p>Sign in and choose your handle to start shopping.</p><button class="btn fill" data-market="signin">Sign in to shop</button>';
  const lines = Market.offers
    .map((o) => {
      const c = caps().find((c) => c.id === o["capability-id"]);
      return `<article class="cart-line"><div><h3>${esc(c?.title || o["capability-id"])}</h3><p>${esc(sellerName(o.seller))} · Quantity ${esc(o.terms?.quantity || 1)}</p></div><span>${marketPrice(o.terms?.price)}</span><button class="btn" data-market="remove" data-id="${esc(o.id)}">Remove</button></article>`;
    })
    .join("");
  const groups = new Map();
  for (const o of Market.offers) {
    const key =
      o.commerce?.["checkout-owner"] === "seller" ||
      caps().find((c) => c.id === o["capability-id"])?.commerce?.[
        "checkout-owner"
      ] === "seller"
        ? sellerName(o.seller)
        : "Marketplace";
    groups.set(key, true);
  }
  const freeCart = freeSellerCart();
  const checkoutAvailable = freeCart || Market.payment?.mode === "stripe";
  return `<h1>Your cart</h1>${lines || "<p>Your cart is empty. Explore the market to discover products and services.</p>"}${lines ? `<p>Payment groups: ${[...groups.keys()].map(esc).join(", ")}. You may complete more than one payment.</p>${marketRecipientField()}<button class="btn fill" data-market="checkout" ${Market.busy || !checkoutAvailable ? "disabled" : ""}>${freeCart ? "Review free subscription" : "Review and pay"}</button>${!checkoutAvailable ? "<p>Payments are temporarily unavailable. Your cart is saved.</p>" : ""}` : Market.purchases.checkouts.some((c) => c.status === "pending-payment" && c["funds-flow"] === "seller-direct") ? marketRecipientField() : ""}<div id="market-payment"></div>${viewMarketOrders()}`;
}
function marketStatus(status) {
  const text = String(status || "pending").replaceAll("-", " ");
  return text[0].toUpperCase() + text.slice(1);
}
function viewMarketOrders() {
  const checks = Market.purchases.checkouts || [];
  return `<h2>Purchases and pending payments</h2><button class="btn" data-market="refresh">Refresh payment status</button>${
    checks
      .map(
        (c) =>
          `<article class="cart-line"><div><h3>${c["funds-flow"] === "seller-direct" ? "Seller payment" : "Marketplace payment"}</h3><p>${esc(marketStatus(c.status))} · ${marketPrice(c.total)}</p>${(
            Market.purchases.orders || []
          )
            .filter((o) => (c.orders || []).includes(o.id))
            .map(
              (o) =>
                `<p>${esc(caps().find((p) => p.id === o["capability-id"])?.title || o["capability-id"])}: ${esc(marketStatus(o.fulfillment?.status || o.status))}</p>`,
            )
            .join(
              "",
            )}<small>Reference: ${esc(c.id)}</small></div>${c.status === "pending-payment" ? `<button class="btn" data-market="pay" data-id="${esc(c.id)}" ${Market.busy || (Market.payment?.mode !== "stripe" && !freeSellerCheckout(c)) ? "disabled" : ""}>${freeSellerCheckout(c) ? "Activate free subscription" : "Continue payment"}</button>` : ""}</article>`,
      )
      .join("") || "<p>No purchases yet.</p>"
  }`;
}
async function payMarketCheckout(id) {
  await loadMarket();
  if (Market.error) throw new Error(Market.error);
  const c = Market.purchases.checkouts.find((c) => c.id === id);
  if (!c || c.status !== "pending-payment") return;
  if (Market.payment?.mode !== "stripe" && !freeSellerCheckout(c))
    throw new Error("Payments are temporarily unavailable.");
  if (c["funds-flow"] === "seller-direct") {
    const batch = Market.purchases.batches.find((b) =>
      (b.checkouts || []).includes(id),
    );
    if (!batch)
      throw new Error("Payment group unavailable. Refresh and retry.");
    const email =
      document.querySelector("#market-recipient")?.value || Market.recipient;
    if (!email || !/^\S+@\S+\.\S+$/.test(email)) {
      if (location.pathname !== "/market/cart") go("/market/cart");
      throw new Error(
        "Enter the email for the seller account receiving this purchase.",
      );
    }
    Market.recipient = email;
    let attempt = localStorage.getItem("referron.payment." + id);
    if (!attempt) {
      attempt = crypto.randomUUID();
      localStorage.setItem("referron.payment." + id, attempt);
    }
    const session = await marketRequest(
      `/api/market/checkout-batches/${encodeURIComponent(batch.id)}/checkouts/${encodeURIComponent(id)}/session`,
      { "recipient-email": email, "attempt-id": attempt },
    );
    if (session["ui-mode"] === "none") {
      localStorage.removeItem("referron.payment." + id);
      await loadMarket();
      toast("Subscription activated.");
      return;
    }
    if (!window.Stripe)
      await new Promise((resolve, reject) => {
        const s = document.createElement("script");
        s.src = "https://js.stripe.com/v3/";
        s.onload = resolve;
        s.onerror = () =>
          reject(new Error("Could not load payment form. Retry."));
        document.head.append(s);
      });
    if (location.pathname !== "/market/cart") go("/market/cart");
    Market.embedded = await Stripe(Market.payment["publishable-key"], {
      stripeAccount: session["connected-account"],
    }).initEmbeddedCheckout({
      fetchClientSecret: async () => session["client-secret"],
      onComplete: async () => {
        Market.embedded?.destroy();
        Market.embedded = null;
        await loadMarket();
        render();
        toast(
          "Payment submitted. Your purchase status will update after confirmation.",
        );
      },
    });
    Market.embedded.mount("#market-payment");
  } else {
    const session = await marketRequest("/api/market/checkout-session", {
      "checkout-id": id,
    });
    if (!session.url) throw new Error("Payment provider unavailable.");
    location.href = session.url;
  }
}
document.addEventListener("click", (e) => {
  const b = e.target.closest("[data-market]");
  if (!b) return;
  const action = b.dataset.market;
  if (action === "edit-storefront" && ownsStorefront(route().seller)) {
    Market.storefrontDraft={seller:route().seller,"display-name":sellerName(route().seller),...sellerProfile(route().seller)};
    Market.storefrontError=""; render(); return;
  }
  if (action === "cancel-storefront") {
    Market.storefrontDraft=null; Market.storefrontError=""; render(); return;
  }
  if (action === "signin") {
    marketSignIn();
    return;
  }
  if (action === "share") {
    copyCosellerLink(b.dataset.path || "/market");
    return;
  }
  marketAction(async () => {
    if (!seated()) {
      marketSignIn();
      return;
    }
    if (action === "connect-seller") {
      const connection = await marketRequest("/api/connect/seller/start", {});
      if (!connection.url) throw new Error("Stripe connection is unavailable.");
      location.href = connection.url;
      return;
    }
    if (action === "add") {
      const qty = Number(
        document.querySelector("#product-quantity")?.value || 1,
      );
      if (!Number.isInteger(qty) || qty < 1 || qty > 100)
        throw new Error("Choose a quantity from 1 to 100.");
      if (!Market.cart) {
        await marketEvent("market/create-cart", { cart: { title: "My cart" } });
        await loadMarket();
      }
      if (!Market.cart) throw new Error("Could not create your cart.");
      await marketEvent("market/add-to-cart", {
        "cart-id": Market.cart.id,
        "capability-id": b.dataset.id,
        quantity: qty,
      });
      await loadMarket();
      go("/market/cart");
    }
    if (action === "upgrade") {
      const portal = await marketRequest(
        `/api/market/subscriptions/${encodeURIComponent(b.dataset.id)}/portal`,
        { "target-capability-id": b.dataset.target },
      );
      if (!portal.url) throw new Error("Stripe upgrade is unavailable.");
      location.href = portal.url;
    }
    if (action === "remove") {
      await marketEvent("market/remove-from-cart", {
        "cart-id": Market.cart.id,
        "offer-id": b.dataset.id,
      });
      await loadMarket();
    }
    if (action === "checkout") {
      Market.recipient =
        document.querySelector("#market-recipient")?.value || "";
      if (
        Market.offers.some((o) =>
          caps().find((c) => c.id === o["capability-id"])?.commerce?.[
            "checkout-owner"
          ] === "seller",
        ) &&
        !/^\S+@\S+\.\S+$/.test(Market.recipient)
      )
        throw new Error(
          "Enter the email for the seller account receiving this purchase.",
        );
      await marketEvent("market/accept-cart-offers", {
        "cart-id": Market.cart.id,
        "require-all-valid": true,
      });
      await loadMarket();
    }
    if (action === "pay") await payMarketCheckout(b.dataset.id);
    if (action === "refresh") await loadMarket();
  });
});
