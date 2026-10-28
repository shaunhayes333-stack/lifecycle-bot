#!/usr/bin/env python3
"""V5.0.8418 — behaviour tests for release_apk_verify_8418 (run: python3 ci/test_release_apk_verify_8418.py)."""
import hashlib
import io
import json
import pathlib
import struct
import sys
import tempfile
import unittest
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import release_apk_verify_8418 as rv  # noqa: E402

SRC_MAIN = HERE.parent / "app" / "src" / "main"

# RFC 8032 §7.1 TEST 1
SEED = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
PUB = bytes.fromhex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")


def b58encode(raw: bytes) -> str:
    n = int.from_bytes(raw, "big")
    out = ""
    while n:
        n, r = divmod(n, 58)
        out = rv._B58[r] + out
    return "1" * (len(raw) - len(raw.lstrip(b"\0"))) + out


def make_dex(strings):
    header = bytearray(0x70)
    header[:8] = b"dex\n035\0"
    ids_off = 0x70
    data = bytearray()
    data_off = ids_off + 4 * len(strings)
    ids = bytearray()
    for s in strings:
        enc = s.encode("utf-8")
        ids += struct.pack("<I", data_off + len(data))
        n = len(s)
        while True:
            b = n & 0x7F
            n >>= 7
            data.append(b | (0x80 if n else 0))
            if not n:
                break
        data += enc + b"\0"
    struct.pack_into("<II", header, 0x38, len(strings), ids_off)
    return bytes(header + ids + data)


def wordlist():
    words = [f"w{i:04d}" for i in range(2048)]
    words[0], words[3] = "abandon", "about"
    return "\n".join(words)


VALID_MNEMONIC = " ".join(["abandon"] * 11 + ["about"])
BAD_CHECKSUM = " ".join(["abandon"] * 12)


def make_apk(path, dex_strings, extra=None, with_wordlist=True):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("classes.dex", make_dex(dex_strings))
        if with_wordlist:
            z.writestr("en-mnemonic-word-list.txt", wordlist())
        for k, v in (extra or {}).items():
            z.writestr(k, v)


class Ed25519(unittest.TestCase):
    def test_rfc8032_public_key(self):
        self.assertEqual(rv.ed25519_public_key(SEED), PUB)

    def test_keypair_vs_signature(self):
        self.assertTrue(rv.is_solana_keypair(SEED + PUB))
        self.assertFalse(rv.is_solana_keypair(SEED + bytes(32)))
        self.assertFalse(rv.is_solana_keypair(SEED))

    def test_base58_roundtrip(self):
        self.assertEqual(rv.b58decode(b58encode(SEED + PUB)), SEED + PUB)


class Mnemonic(unittest.TestCase):
    def setUp(self):
        self.index = {w: i for i, w in enumerate(wordlist().split("\n"))}

    def test_checksum(self):
        self.assertTrue(rv.bip39_valid(VALID_MNEMONIC.split(), self.index))
        self.assertFalse(rv.bip39_valid(BAD_CHECKSUM.split(), self.index))

    def test_found_inside_text_only_when_valid(self):
        self.assertTrue(rv.find_mnemonics("seed: " + VALID_MNEMONIC + " end", self.index))
        self.assertFalse(rv.find_mnemonics("seed: " + BAD_CHECKSUM, self.index))
        # wordlist words as separate dex strings (newline joined) are not a phrase
        self.assertFalse(rv.find_mnemonics("\n".join(VALID_MNEMONIC.split()), self.index))


class Dex(unittest.TestCase):
    def test_parse(self):
        self.assertEqual(rv.dex_strings(make_dex(["a", "héllo", ""])), ["a", "héllo", ""])


class SecretScan(unittest.TestCase):
    def scan(self, strings, **kw):
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d, "a.apk")
            make_apk(p, strings, **kw)
            return rv.scan_apk_secrets(p)[0]

    def kinds(self, findings):
        return sorted(f["kind"] for f in findings)

    def test_clean(self):
        self.assertEqual(self.scan(["Lcom/lifecyclebot/Foo;", "https://api.example.com", b58encode(PUB)]), [])

    def test_wallet_secrets(self):
        kp = SEED + PUB
        self.assertEqual(self.kinds(self.scan([b58encode(kp)])), ["solana-keypair-base58"])
        self.assertEqual(self.kinds(self.scan([json.dumps(list(kp))])), ["solana-keypair-json"])
        self.assertEqual(self.kinds(self.scan([kp.hex()])), ["solana-keypair-hex"])
        self.assertEqual(self.kinds(self.scan(["x " + VALID_MNEMONIC])), ["bip39-mnemonic"])
        self.assertEqual(self.kinds(self.scan(["ok"], extra={"assets/k.pem": "-----BEGIN PRIVATE KEY-----\n" + "MIIEvQIBADANBgkqhkiG9w0BAQEFAASC" * 3})), ["pem-private-key"])
        self.assertEqual(self.scan(["-----BEGIN PRIVATE KEY-----", "-----END PRIVATE KEY-----"]), [])
        self.assertTrue(all(f["wallet"] for f in self.scan([b58encode(kp)])))

    def test_signature_is_not_a_keypair(self):
        self.assertEqual(self.scan([b58encode(SEED + bytes(range(32)))]), [])

    def test_api_keys(self):
        self.assertEqual(self.kinds(self.scan(["gsk_" + "A1" * 26])), ["groq-api-key"])
        self.assertEqual(self.kinds(self.scan(["https://x/?api-key=12345678-1234-1234-1234-123456789abc"])), ["helius-api-key"])

    def test_obfuscated_credential(self):
        key = b"AATE_V5.0.6672_TURSO_OBF"
        token = "eyJhbGciOiJFZERTQSJ9." + "a" * 60
        enc = __import__("base64").b64encode(bytes(c ^ key[i % len(key)] for i, c in enumerate(token.encode()))).decode()
        f = self.scan([enc, key.decode()])
        self.assertEqual(self.kinds(f), ["xor-obfuscated-credential"])
        self.assertEqual(f[0]["fingerprint"], hashlib.sha256(token.encode()).hexdigest())

    def test_missing_wordlist_fails_closed(self):
        self.assertEqual(self.kinds(self.scan(["ok"], with_wordlist=False)), ["bip39-wordlist-missing"])

    def test_gz_asset_scanned(self):
        import gzip
        self.assertEqual(self.kinds(self.scan(["ok"], extra={"assets/c.jsonl.gz": gzip.compress(b"AKIAABCDEFGHIJKLMNOP")})), ["aws-access-key"])


MANIFEST = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.lifecyclebot.aate">
<application android:allowBackup="true" android:fullBackupContent="@xml/a" android:dataExtractionRules="@xml/b"
  android:networkSecurityConfig="@xml/n">{body}</application></manifest>"""


class ManifestRules(unittest.TestCase):
    def errs(self, body, app_attrs=None):
        xml = MANIFEST.format(body=body)
        if app_attrs:
            xml = xml.replace("<application ", "<application " + app_attrs + " ")
        return rv.manifest_findings(xml)

    def test_clean_components(self):
        body = ('<activity android:name="com.lifecyclebot.ui.SecurityActivity" android:exported="true"><intent-filter>'
                '<action android:name="android.intent.action.MAIN"/></intent-filter></activity>'
                '<receiver android:name="com.lifecyclebot.engine.BootReceiver" android:exported="true"><intent-filter>'
                '<action android:name="android.intent.action.BOOT_COMPLETED"/><action android:name="android.intent.action.MY_PACKAGE_REPLACED"/>'
                '</intent-filter></receiver>'
                '<service android:name="androidx.work.impl.background.systemjob.SystemJobService" android:exported="true" android:permission="android.permission.BIND_JOB_SERVICE"/>'
                '<activity android:name=".ui.BacktestActivity"/>'
                '<provider android:name="androidx.core.content.FileProvider" android:exported="false"/>')
        self.assertEqual(self.errs(body), [])

    def test_failures(self):
        self.assertTrue(self.errs('<receiver android:name="com.lifecyclebot.engine.SmokeTestReceiver" android:exported="true"><intent-filter>'
                                  '<action android:name="com.lifecyclebot.aate.SMOKE_AUTOSTART"/></intent-filter></receiver>'))
        self.assertTrue(self.errs('<activity android:name=".ui.MainActivity" android:exported="true"/>'))
        self.assertTrue(self.errs('<provider android:name="p.P" android:exported="true"/>'))
        self.assertTrue(self.errs('<activity android:name=".ui.X"><intent-filter><action android:name="x.Y"/></intent-filter></activity>'))
        self.assertTrue(self.errs('<activity android:name="com.lifecyclebot.ui.SecurityActivity" android:exported="true"><intent-filter>'
                                  '<action android:name="android.intent.action.VIEW"/></intent-filter></activity>'))
        self.assertTrue(self.errs("", 'android:debuggable="true"'))
        self.assertTrue(self.errs("", 'android:usesCleartextTraffic="true"'))
        no_rules = MANIFEST.format(body="").replace('android:fullBackupContent="@xml/a" ', "")
        self.assertTrue(rv.manifest_findings(no_rules))

    def test_repository_main_manifest_is_release_clean(self):
        # The release manifest is main + release overlays; the debug overlay owns the smoke hook.
        self.assertEqual(rv.manifest_findings((SRC_MAIN / "AndroidManifest.xml").read_text()), [])
        debug = (SRC_MAIN.parent / "debug" / "AndroidManifest.xml").read_text()
        self.assertIn("com.lifecyclebot.aate.SMOKE_AUTOSTART", debug)

    def test_repository_backup_and_network_config(self):
        self.assertEqual(rv.source_security_findings(SRC_MAIN), [])

    def test_backup_of_shared_prefs_fails(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            (root / "res" / "xml").mkdir(parents=True)
            (root / "AndroidManifest.xml").write_text(MANIFEST.format(body="").replace("@xml/a", "@xml/full").replace("@xml/b", "@xml/ext").replace("@xml/n", "@xml/net"))
            (root / "res/xml/net.xml").write_text('<network-security-config><base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="system"/></trust-anchors></base-config></network-security-config>')
            (root / "res/xml/full.xml").write_text('<full-backup-content><include domain="sharedpref" path="."/></full-backup-content>')
            (root / "res/xml/ext.xml").write_text('<data-extraction-rules><cloud-backup><exclude domain="database" path="x"/></cloud-backup></data-extraction-rules>')
            errs = rv.source_security_findings(root)
            self.assertTrue(any("sharedpref" in e for e in errs))
            self.assertTrue(any("no <include>" in e for e in errs))
            (root / "res/xml/net.xml").write_text('<network-security-config><base-config cleartextTrafficPermitted="true"/></network-security-config>')
            self.assertTrue(any("cleartext" in e for e in rv.source_security_findings(root)))


APKSIGNER_OK = """Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
Signer #1 certificate DN: CN=AATE Bot, OU=Trading, O=Lifecycle, L=Sydney, ST=NSW, C=AU
Signer #1 certificate SHA-256 digest: e9e0c91dc5fd72e53474f22c1fa57574d0d9dc1df906776b8218d9d0cc6a9ff3
Signer #1 certificate SHA-1 digest: 069ae0cd4f1d728ceba36ebf51987dc2da41d5e5
"""

BADGING = """package: name='com.lifecyclebot.aate' versionCode='8917' versionName='5.0.8417' platformBuildVersionName='14' compileSdkVersion='34'
sdkVersion:'26'
targetSdkVersion:'34'
application-label:'AATE'
"""


class Signing(unittest.TestCase):
    def test_ok(self):
        info = rv.parse_apksigner(APKSIGNER_OK)
        self.assertEqual(info["schemes"]["v2"], True)
        self.assertEqual(rv.signing_findings(info), [])

    def test_debug_key_or_no_v2_fails(self):
        self.assertTrue(rv.signing_findings(rv.parse_apksigner(APKSIGNER_OK.replace("e9e0c91d", "00000000"))))
        self.assertTrue(rv.signing_findings(rv.parse_apksigner(APKSIGNER_OK.replace("Signature Scheme v2): true", "Signature Scheme v2): false"))))
        self.assertTrue(rv.signing_findings(rv.parse_apksigner("DOES NOT VERIFY\nERROR: x")))

    def test_pinned_cert_matches_committed_keystore_when_keytool_available(self):
        import shutil, subprocess
        if not shutil.which("keytool"):
            self.skipTest("no keytool")
        out = subprocess.run(["keytool", "-list", "-v", "-keystore", str(HERE.parent / "keystore" / "release.keystore"),
                              "-storepass", "aate2024bot", "-alias", "aate_release"], capture_output=True, text=True).stdout
        digest = next((l.split(":", 1)[1].strip().replace(":", "").lower() for l in out.splitlines() if l.strip().startswith("SHA256:")), None)
        if digest is None:
            self.skipTest("keystore not readable with the public fallback password")
        self.assertEqual(digest, rv.RELEASE_CERT_SHA256_8418)


class VersionOrder(unittest.TestCase):
    def test_monotonic(self):
        w = []
        self.assertEqual(rv.version_order_findings(8916, 8917, w), [])
        self.assertEqual(w, [])
        self.assertEqual(rv.version_order_findings(8917, 8917, w), [])
        self.assertEqual(len(w), 1)
        self.assertTrue(rv.version_order_findings(8917, 8916, []))
        self.assertTrue(rv.version_order_findings(None, 8916, []))


class Identity(unittest.TestCase):
    def test_ok(self):
        self.assertEqual(rv.identity_findings(rv.parse_badging(BADGING), "5.0.8417"), [])

    def test_failures(self):
        b = rv.parse_badging(BADGING)
        self.assertTrue(rv.identity_findings(b, "5.0.8418"))
        self.assertTrue(rv.identity_findings(rv.parse_badging(BADGING + "application-debuggable\n"), "5.0.8417"))
        self.assertTrue(rv.identity_findings(rv.parse_badging(BADGING.replace("targetSdkVersion:'34'", "targetSdkVersion:'30'")), "5.0.8417"))
        self.assertTrue(rv.identity_findings(rv.parse_badging(BADGING.replace("sdkVersion:'26'", "sdkVersion:'21'")), "5.0.8417"))


if __name__ == "__main__":
    unittest.main(verbosity=1)
