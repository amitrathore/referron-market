"""Catalog refresh validation and last-known-good retention."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("catalog_refresh", ROOT / "scripts/refresh-yvatar-catalog.py")
refresh = importlib.util.module_from_spec(spec)
spec.loader.exec_module(refresh)


class CatalogRefreshTest(unittest.TestCase):
    def setUp(self):
        self.catalog = {"catalog-version": 1, "currency": "USD", "items": [
            {"sku": "single", "title": "1 image credit", "amount-cents": 197,
             "credits": 1, "credit-type": "image", "display-order": 10,
             "images": [{"url": "https://yvatar.com/img/marketplace-image-credits.png", "alt": "Image credits"}],
             "private-token": "must-never-copy"}]}

    def test_public_snapshot_is_browse_only(self):
        snapshot = refresh.build_snapshot(self.catalog)
        product = snapshot["capabilities"]["referron-preview-yvatar-single"]
        self.assertEqual(product["terms"]["price"], {"amount": 197, "currency": "USD"})
        self.assertIs(product["commerce"]["checkout-enabled"], False)
        self.assertNotIn("must-never-copy", json.dumps(snapshot))

    def test_invalid_catalogs_are_rejected(self):
        for change in [lambda c: c.update({"catalog-version": 0}),
                       lambda c: c.update({"items": []}),
                       lambda c: c["items"].append(copy.deepcopy(c["items"][0])),
                       lambda c: c["items"][0].update({"amount-cents": -1}),
                       lambda c: c["items"][0].update({"display-order": None}),
                       lambda c: c["items"][0]["images"][0].update({"url": "javascript:alert(1)"}),
                       lambda c: c["items"][0]["images"][0].update({"url": "http://127.0.0.1/private"})]:
            with self.subTest(change=change):
                catalog = copy.deepcopy(self.catalog)
                change(catalog)
                with self.assertRaises(ValueError):
                    refresh.build_snapshot(catalog)

    def test_failed_refresh_keeps_previous_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "snapshot.json"
            destination.write_text("last-known-good")
            bad = Path(directory) / "bad.json"
            bad.write_text('{"items":[]}')
            with patch.object(refresh, "DESTINATION", destination), patch.object(sys, "argv", ["refresh", "--input", str(bad)]):
                with self.assertRaises(ValueError):
                    refresh.main()
            self.assertEqual(destination.read_text(), "last-known-good")

    def test_successful_refresh_is_repeatable_and_allowlisted(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "snapshot.json"
            source = Path(directory) / "catalog.json"
            source.write_text(json.dumps(self.catalog))
            with patch.object(refresh, "DESTINATION", destination), patch.object(sys, "argv", ["refresh", "--input", str(source)]):
                refresh.main()
                first = json.loads(destination.read_text())
                refresh.main()
                second = json.loads(destination.read_text())
            self.assertEqual(first["capabilities"], second["capabilities"])
            self.assertNotIn("must-never-copy", destination.read_text())

    def test_network_failure_keeps_previous_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "snapshot.json"
            destination.write_text("last-known-good")
            with patch.object(refresh, "DESTINATION", destination), patch.object(sys, "argv", ["refresh"]), patch.object(refresh.urllib.request, "build_opener", side_effect=OSError("offline")):
                with self.assertRaises(OSError):
                    refresh.main()
            self.assertEqual(destination.read_text(), "last-known-good")


if __name__ == "__main__":
    unittest.main()
