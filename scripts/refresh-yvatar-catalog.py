#!/usr/bin/env python3
"""Build a reviewed, public-only Yvatar preview. Never reads production config."""
import argparse
import datetime
import json
import os
from pathlib import Path
import tempfile
import urllib.request
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
SOURCE = "https://yvatar.com/seller-commerce/v1/catalog"
DESTINATION = ROOT / "resources/catalog/yvatar.json"


def https_url(value):
    if not isinstance(value, str):
        return False
    url = urlsplit(value)
    return (url.scheme == "https" and bool(url.hostname) and not url.username
            and not url.password and url.hostname not in {"localhost", "127.0.0.1", "::1"})


def build_snapshot(catalog, source=SOURCE):
    version = catalog.get("catalog-version")
    items = catalog.get("items")
    if type(version) is not int or version < 1 or not isinstance(items, list) or not items:
        raise ValueError("Invalid catalog version or empty catalog")
    currency = catalog.get("currency")
    if not isinstance(currency, str) or len(currency) != 3 or not currency.isalpha() or not currency.isupper():
        raise ValueError("Invalid currency")
    capabilities = {}
    for item in items:
        sku = item.get("sku")
        title = item.get("title") or item.get("label")
        price = item.get("amount-cents")
        credits = item.get("credits")
        credit_type = item.get("credit-type")
        order = item.get("display-order")
        images = item.get("images")
        if (not isinstance(sku, str) or not sku or len(sku) > 100
                or not all(c.isascii() and (c.isalnum() or c in "-_") for c in sku)
                or not isinstance(title, str) or not title.strip()
                or type(price) is not int or price <= 0
                or type(credits) is not int or credits <= 0
                or credit_type not in {"image", "video", "script"}
                or type(order) is not int or order < 0
                or not isinstance(images, list) or not images):
            raise ValueError("Invalid product fields")
        public_images = []
        for image in images:
            if not https_url(image.get("url")) or not isinstance(image.get("alt"), str) or not image["alt"].strip():
                raise ValueError("Invalid product image")
            # Bundle the unchanged official artwork so previews do not depend on
            # provider authentication or cross-site image availability.
            url = image["url"]
            for kind in ("image", "video", "script"):
                if url == f"https://yvatar.com/img/marketplace-{kind}-credits.png":
                    url = f"/assets/sellers/marketplace-{kind}-credits.png"
            public_images.append({"url": url, "alt": image["alt"]})
        product_id = "referron-preview-yvatar-" + sku
        if product_id in capabilities:
            raise ValueError("Duplicate SKU")
        capabilities[product_id] = {
            "id": product_id, "seller": "yvatar", "title": title,
            "description": f"{credits} {credit_type} creation credits. Redeemable only on Yvatar; no cash value.",
            "kind": "digital-service-credit", "status": "active",
            "external-ref": "yvatar:" + sku,
            "terms": {"price": {"amount": price, "currency": currency}},
            "images": public_images, "display-order": order,
            "category-ids": ["software"],
            "commerce": {"checkout-enabled": False, "catalog-version": version},
        }
    return {
        "source": source, "retrieved-at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "catalog-version": version,
        "profile": {"display-name": "Yvatar Store", "tagline": "Bring your characters to life.",
                    "description": "Create characters, images, and video with Yvatar service credits.",
                    "logo-url": "/assets/sellers/yvatar-logo.png",
                    "website-url": "https://yvatar.com/", "brand-primary": "#7657ff",
                    "brand-secondary": "#2ab7ff", "brand-button": "#ffd95a"},
        "capabilities": capabilities,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, help="Review a local catalog response instead of fetching")
    parser.add_argument("--source", default=SOURCE, help="Provenance of a local input; fetch destination is fixed")
    args = parser.parse_args()
    if args.input:
        catalog = json.loads(args.input.read_text())
    else:
        headers = {}
        token = os.environ.get("YVATAR_CATALOG_TOKEN")
        if token:
            headers["Authorization"] = "Bearer " + token
        # Never forward credentials to a redirect destination.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *unused, **kwargs):
                return None
        request = urllib.request.Request(SOURCE, headers=headers)
        with urllib.request.build_opener(NoRedirect).open(request, timeout=20) as response:
            catalog = json.load(response)
    snapshot = build_snapshot(catalog, args.source if args.input else SOURCE)
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", dir=DESTINATION.parent, delete=False) as output:
        temporary = Path(output.name)
        json.dump(snapshot, output, indent=2)
        output.write("\n")
    try:
        temporary.replace(DESTINATION)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"Prepared {len(snapshot['capabilities'])} preview products. Review the diff before publishing.")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        raise SystemExit("Catalog refresh failed; previous snapshot retained. Check source format and access.")
