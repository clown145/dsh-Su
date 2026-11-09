"""Exercise the build's real downloader without network access or runtime cache writes."""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


BUILD = Path(__file__).resolve().parents[1] / "build-runtime.sh"
FETCH = re.search(r"^fetch\(\) \{\n.*?^\}", BUILD.read_text(), re.M | re.S).group()
PAYLOAD = b"the pinned Ubuntu package\n"
DIGEST = hashlib.sha256(PAYLOAD).hexdigest()
MOCK = '''#!/usr/bin/env python3
import os
from pathlib import Path
import sys
args = sys.argv[1:]
url = args[-1]
with open(os.environ['FETCH_CALLS'], 'a') as out:
    out.write(url + '\\n')
fallback = 'snapshot.ubuntu.com' in url
case = os.environ['FETCH_CASE']
if not fallback and case != 'success':
    Path(args[args.index('-o') + 1]).write_bytes(b'partial response')
    print({'missing': '404', 'mismatch': '404', 'fallback_failure': '404', 'server_error': '503', 'network_error': '000'}[case], end='')
    sys.exit(7 if case == 'network_error' else 22)
if fallback and case == 'fallback_failure':
    Path(args[args.index('-o') + 1]).write_bytes(b'incomplete snapshot')
    sys.exit(22)
Path(args[args.index('-o') + 1]).write_bytes(b'wrong package' if case == 'mismatch' else b'the pinned Ubuntu package\\n')
if '-w' in args:
    print('200', end='')
'''


class DownloadFallbackTest(unittest.TestCase):
    def run_fetch(self, case, expected=DIGEST, cached=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            mock = root / "curl"
            mock.write_text(MOCK)
            mock.chmod(0o755)
            target = root / "package.deb"
            if cached:
                target.write_bytes(PAYLOAD)
            calls = root / "calls"
            script = '''set -euo pipefail
log() { printf '%s\\n' "$*" >&2; }
die() { printf '%s\\n' "$*" >&2; exit 1; }
sha256() { sha256sum "$1" | cut -d' ' -f1; }
''' + FETCH + '\nfetch https://mirror.invalid/pool/package.deb "$1" "$2" https://snapshot.ubuntu.com/ubuntu/20261101T000000Z/pool/package.deb\n'
            result = subprocess.run(["bash", "-c", script, "fetch-test", str(target), expected],
                env={**os.environ, "PATH": str(root) + os.pathsep + os.environ["PATH"],
                     "FETCH_CASE": case, "FETCH_CALLS": str(calls)}, capture_output=True, text=True)
            return result, target.read_bytes() if target.exists() else None, target.with_suffix(".deb.part").exists(), calls.read_text().splitlines() if calls.exists() else []

    def test_live_download_and_valid_cache_do_not_use_snapshot(self):
        for cached in (False, True):
            result, payload, partial, calls = self.run_fetch("success", cached=cached)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(PAYLOAD, payload)
            self.assertFalse(partial)
            self.assertEqual(0 if cached else 1, len(calls))

    def test_404_fetches_snapshot_and_keeps_the_original_digest_requirement(self):
        result, payload, partial, calls = self.run_fetch("missing")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(PAYLOAD, payload)
        self.assertFalse(partial)
        self.assertEqual(2, len(calls))
        self.assertTrue(calls[1].startswith("https://snapshot.ubuntu.com/ubuntu/"))

    def test_snapshot_checksum_mismatch_cannot_publish_a_package(self):
        result, payload, partial, calls = self.run_fetch("mismatch")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("sha256 mismatch", result.stderr)
        self.assertIsNone(payload)
        self.assertFalse(partial)
        self.assertEqual(2, len(calls))

    def test_other_failures_and_unpinned_downloads_never_use_snapshot(self):
        for case, digest in (("server_error", DIGEST), ("network_error", DIGEST), ("missing", "")):
            result, payload, partial, calls = self.run_fetch(case, expected=digest)
            self.assertNotEqual(0, result.returncode)
            self.assertIsNone(payload)
            self.assertFalse(partial)
            self.assertEqual(1, len(calls))

    def test_failed_snapshot_does_not_leave_a_partial_download(self):
        result, payload, partial, calls = self.run_fetch("fallback_failure")
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(payload)
        self.assertFalse(partial)
        self.assertEqual(2, len(calls))


if __name__ == "__main__":
    unittest.main()
