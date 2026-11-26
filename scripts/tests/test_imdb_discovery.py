import datetime as dt
import gzip
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import imdb_discovery as discovery

NOW = dt.datetime(2026, 10, 22, tzinfo=dt.timezone.utc)


def inputs(count=3, score="8.1"):
    ratings = "tconst\taverageRating\tnumVotes\n"
    basics = "tconst\ttitleType\tprimaryTitle\toriginalTitle\tisAdult\tstartYear\tgenres\n"
    for number in range(1, count + 1):
        identity = f"tt{number:07}"
        ratings += f"{identity}\t{score}\t20000\n"
        basics += f"{identity}\tmovie\tTitle {number}\tTitle {number}\t0\t2025\tDrama\n"
    return {discovery.INPUTS[0]: gzip.compress(ratings.encode(), mtime=0),
            discovery.INPUTS[1]: gzip.compress(basics.encode(), mtime=0)}


class Response(io.BytesIO):
    def __init__(self, value, extra=0):
        super().__init__(value)
        self.headers = {"Content-Length": str(len(value) + extra), "Last-Modified": "Wed, 21 Oct 2026 00:00:00 GMT"}


class DiscoveryRefreshTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.project = Path(self.temp.name)
        (self.project / "app").mkdir()

    def tearDown(self):
        self.temp.cleanup()

    def run_refresh(self, payload=None, **kwargs):
        payload = payload or inputs()
        def opener(request, timeout):
            return Response(payload[request.full_url.rsplit("/", 1)[-1]])
        return discovery.refresh(self.project, opener=opener, minimum_records=1, now=NOW, progress=lambda _: None, **kwargs)

    def active(self):
        manifest = self.project / "app/discovery-snapshot.properties"
        props = discovery.properties(manifest)
        bundle = discovery.active_assets(self.project / ".local-discovery", props)
        return manifest, props, bundle

    def assert_old_active(self, before):
        manifest, props, bundle = self.active()
        self.assertEqual(before, manifest.read_bytes())
        self.assertEqual(props["sha256"], discovery.sha256(bundle / discovery.ASSET))

    def test_fresh_snapshot_and_unchanged_cached_rebuild_are_complete_and_idempotent(self):
        result = self.run_refresh()
        manifest, props, bundle = self.active()
        self.assertEqual("updated", result["status"])
        self.assertEqual("immutable-v1", props["assetLayout"])
        self.assertEqual(3, result["records"])
        self.assertIn("Information courtesy of IMDb", (bundle / "IMDb-NOTICE.txt").read_text())
        before = manifest.read_bytes()
        cached = self.run_refresh(reuse=True)
        self.assertEqual("unchanged", cached["status"])
        self.assertEqual("cache-reuse", cached["mode"])
        self.assert_old_active(before)
        self.assertFalse(list((self.project / ".local-discovery").glob(".refresh-*")))

    def test_tsv_quotes_are_literal_title_characters_not_csv_quoting(self):
        payload = inputs()
        text = gzip.decompress(payload[discovery.INPUTS[1]]).decode().replace("Title 1", '\"An Unclosed Quote')
        payload[discovery.INPUTS[1]] = gzip.compress(text.encode(), mtime=0)
        self.run_refresh(payload)
        bundle = self.active()[2]
        with gzip.open(bundle / discovery.ASSET, "rt", encoding="utf-8") as source:
            rows = source.read().splitlines()
        self.assertEqual('\"An Unclosed Quote', rows[1].split("\t")[2])

    def test_bad_gzip_and_bad_schema_keep_the_published_snapshot(self):
        self.run_refresh()
        before = self.active()[0].read_bytes()
        for bad in [b"not gzip", gzip.compress(b"wrong\theader\n", mtime=0)]:
            payload = inputs()
            payload[discovery.INPUTS[1]] = bad
            with self.assertRaises((OSError, ValueError)):
                self.run_refresh(payload)
            self.assert_old_active(before)

    def test_activation_failure_cannot_mix_a_new_index_with_an_old_manifest(self):
        self.run_refresh()
        manifest = self.active()[0]
        before = manifest.read_bytes()
        replace = discovery.os.replace
        def fail_pointer(source, target):
            if Path(target) == manifest:
                raise OSError("injected manifest failure")
            return replace(source, target)
        with patch.object(discovery.os, "replace", side_effect=fail_pointer):
            with self.assertRaises(OSError):
                self.run_refresh(inputs(score="9.2"))
        self.assert_old_active(before)
        # A subsequent normal attempt can safely activate the already-complete immutable bundle.
        self.assertEqual("updated", self.run_refresh(inputs(score="9.2"))["status"])

    def test_interrupted_activation_keeps_a_complete_previous_snapshot(self):
        self.run_refresh()
        manifest = self.active()[0]
        before = manifest.read_bytes()
        replace = discovery.os.replace
        def interrupt_pointer(source, target):
            if Path(target) == manifest:
                raise KeyboardInterrupt()
            return replace(source, target)
        with patch.object(discovery.os, "replace", side_effect=interrupt_pointer):
            with self.assertRaises(KeyboardInterrupt):
                self.run_refresh(inputs(score="9.2"))
        self.assert_old_active(before)

    def test_truncated_http_body_is_not_published(self):
        self.run_refresh()
        before = self.active()[0].read_bytes()
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            discovery.refresh(self.project, opener=lambda request, timeout: Response(inputs()[discovery.INPUTS[0]], extra=1),
                              minimum_records=1, now=NOW, progress=lambda _: None)
        self.assert_old_active(before)

    def test_unexpected_coverage_loss_is_rejected(self):
        self.run_refresh()
        before = self.active()[0].read_bytes()
        with self.assertRaisesRegex(ValueError, "coverage"):
            self.run_refresh(inputs(count=1))
        self.assert_old_active(before)

    def test_corrupt_cached_input_cannot_be_reused(self):
        self.run_refresh()
        before = self.active()[0].read_bytes()
        (self.project / ".local-discovery" / discovery.INPUTS[0]).write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "unverified"):
            self.run_refresh(reuse=True)
        self.assert_old_active(before)

    def test_not_modified_checks_reuse_only_verified_cached_inputs(self):
        self.run_refresh()
        before = self.active()[0].read_bytes()
        requests = []
        def unchanged(request, timeout):
            self.assertIsNotNone(request.get_header("If-modified-since"))
            requests.append(request.full_url)
            raise urllib.error.HTTPError(request.full_url, 304, "Not Modified", {}, io.BytesIO())
        result = discovery.refresh(self.project, opener=unchanged, minimum_records=1, now=NOW, progress=lambda _: None)
        self.assertEqual(2, len(requests)); self.assertEqual("unchanged", result["status"])
        self.assertEqual("network-check", result["mode"]); self.assert_old_active(before)

    def test_parallel_refreshes_are_rejected_and_the_lock_releases(self):
        root = self.project / ".local-discovery"
        with discovery.refresh_lock(root):
            with self.assertRaisesRegex(RuntimeError, "already running"):
                with discovery.refresh_lock(root):
                    self.fail("second refresh entered")
        self.assertEqual("updated", self.run_refresh()["status"])


if __name__ == "__main__":
    unittest.main()
