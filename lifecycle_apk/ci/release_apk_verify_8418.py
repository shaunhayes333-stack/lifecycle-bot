#!/usr/bin/env python3
"""V5.0.8418 — prove the release APK's identity, signing and security from the APK itself.

Fails (exit 1) when the published candidate is not what an owner can safely install over
the previous release:

  signing   apksigner verifies the APK, v2 scheme is present (minSdk 26 with no v1 needs it),
            and the only signer certificate is the pinned release keystore certificate.
  identity  versionCode == 500 + build number, versionName == AATE_VERSION, minSdk/targetSdk
            sane, application not debuggable, R8 mapping produced.
  upgrade   (compare) versionCode strictly increases over the previous published APK and the
            signer certificate is identical, so `adb install -r` keeps the owner's data.
  security  no exported component without a permission (except the LAUNCHER entry and
            receivers that only accept protected system broadcasts), no debug-only smoke hook,
            no cleartext traffic, network security config trusts system CAs only, Android
            backup is an include-only list of the learning databases (no shared_prefs: wallet
            keys and config are never backed up), and no secret is embedded in classes*.dex,
            assets or resources: PEM private keys, Solana keypairs (base58 / JSON / hex,
            confirmed by deriving the ed25519 public key), BIP39 mnemonics (checksum
            verified against the wordlist bundled in the APK), plaintext API keys / JWTs and
            XOR-obfuscated credentials. A reviewed credential is reported, never silent.

Nothing here touches the device or the network.
"""
from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import json
import os
import pathlib
import re
import shutil
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ANDROID_NS = "http://schemas.android.com/apk/res/android"
A = "{%s}" % ANDROID_NS
PACKAGE = "com.lifecyclebot.aate"

# SHA-256 of the certificate in lifecycle_apk/keystore/release.keystore (alias aate_release).
# Every published AATE APK must carry exactly this signer or Android refuses the upgrade
# (or worse, the owner uninstalls and loses the wallet/learning data to install it).
RELEASE_CERT_SHA256_8418 = "e9e0c91dc5fd72e53474f22c1fa57574d0d9dc1df906776b8218d9d0cc6a9ff3"

MIN_SDK_FLOOR_8418 = 26
TARGET_SDK_FLOOR_8418 = 34

# Exported without a permission is acceptable only for these, each with a reason and the exact
# intent actions it may accept (a new action on the same component fails again).
EXPORTED_ALLOWLIST_8418 = {
    "com.lifecyclebot.ui.SecurityActivity": (
        "LAUNCHER entry; it is the PIN/biometric gate itself", {"android.intent.action.MAIN"}),
    "com.lifecyclebot.ui.PersonaStudioActivity": (
        "V5.9.359 share target for audio/* voice clips; the screen has no wallet, config or trading access",
        {"android.intent.action.SEND"}),
}
# Broadcasts only the system may send (Android rejects them from third-party apps).
PROTECTED_BROADCASTS_8418 = {
    "android.intent.action.BOOT_COMPLETED",
    "android.intent.action.LOCKED_BOOT_COMPLETED",
    "android.intent.action.MY_PACKAGE_REPLACED",
    "android.intent.action.PACKAGE_REPLACED",
    "android.intent.action.QUICKBOOT_POWERON",
    "android.intent.action.TIME_SET",
    "android.intent.action.TIMEZONE_CHANGED",
    "android.intent.action.LOCALE_CHANGED",
    "android.intent.action.ACTION_POWER_CONNECTED",
    "android.intent.action.ACTION_POWER_DISCONNECTED",
    "android.intent.action.BATTERY_OKAY",
    "android.intent.action.BATTERY_LOW",
    "android.intent.action.DEVICE_STORAGE_LOW",
    "android.intent.action.DEVICE_STORAGE_OK",
    "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
}
DEBUG_ONLY_COMPONENTS_8418 = {"com.lifecyclebot.engine.SmokeTestReceiver"}
# The only files Android backup may carry (learning stores; see res/xml/learning_*_8086.xml).
BACKUP_ALLOWED_8418 = {("database", "learning_kv.db"), ("database", "shadow_cold_8397.db")}

# Reviewed embedded credentials, by SHA-256 of the decoded value. Still printed as a warning
# on every run; a NEW credential (or a changed one) fails the build.
REVIEWED_CREDENTIALS_8418 = {
    "cdc24d238c1e2dcc1712c2cf98b6c50c7e557a7c9e83f0f865752d3f99a545d8":
        "TursoDefaults.AUTH_TOKEN (data/BotConfig.kt, V5.0.6672): operator's collective-learning "
        "Superbrain DB token, XOR-obfuscated in the APK. Not a wallet secret; anyone with the APK "
        "can recover it. Owner decision: rotate/scope it or move it to user settings.",
}

PLAINTEXT_SECRET_PATTERNS_8418 = [
    # Header AND key material: a parser library's bare header constant is not a key.
    ("pem-private-key", re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----\s*[A-Za-z0-9+/=\s]{64,}")),
    ("groq-api-key", re.compile(r"\bgsk_[A-Za-z0-9]{40,}")),
    ("openai-api-key", re.compile(r"\bsk-(?:proj-)?[A-Za-z0-9_-]{32,}")),
    ("anthropic-api-key", re.compile(r"\bsk-ant-[A-Za-z0-9_-]{32,}")),
    ("google-api-key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}")),
    ("xai-api-key", re.compile(r"\bxai-[A-Za-z0-9]{40,}")),
    ("github-token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}")),
    ("aws-access-key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("helius-api-key", re.compile(r"api-key=[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")),
    ("jwt", re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{20,}")),
]

# ---------------------------------------------------------------- ed25519 (RFC 8032) ---
_P = 2 ** 255 - 19
_D = (-121665 * pow(121666, _P - 2, _P)) % _P
_I = pow(2, (_P - 1) // 4, _P)


def _xrecover(y):
    xx = (y * y - 1) * pow(_D * y * y + 1, _P - 2, _P)
    x = pow(xx, (_P + 3) // 8, _P)
    if (x * x - xx) % _P:
        x = x * _I % _P
    if x % 2:
        x = _P - x
    return x


_BY = 4 * pow(5, _P - 2, _P) % _P
_BX = _xrecover(_BY)
_B = (_BX, _BY, 1, _BX * _BY % _P)


def _add(p1, p2):
    x1, y1, z1, t1 = p1
    x2, y2, z2, t2 = p2
    a = (y1 - x1) * (y2 - x2) % _P
    b = (y1 + x1) * (y2 + x2) % _P
    c = t1 * 2 * _D * t2 % _P
    d = z1 * 2 * z2 % _P
    e, f, g, h = b - a, d - c, d + c, b + a
    return (e * f % _P, g * h % _P, f * g % _P, e * h % _P)


def ed25519_public_key(seed: bytes) -> bytes:
    h = hashlib.sha512(seed).digest()
    a = int.from_bytes(h[:32], "little")
    a &= (1 << 254) - 8
    a |= 1 << 254
    q, pt = (0, 1, 1, 0), _B
    while a:
        if a & 1:
            q = _add(q, pt)
        pt = _add(pt, pt)
        a >>= 1
    x, y, z, _ = q
    zi = pow(z, _P - 2, _P)
    x, y = x * zi % _P, y * zi % _P
    return (y | ((x & 1) << 255)).to_bytes(32, "little")


def is_solana_keypair(raw: bytes) -> bool:
    """A 64-byte Solana secret is seed||pubkey; a signature or hash is not."""
    return len(raw) == 64 and ed25519_public_key(raw[:32]) == raw[32:]


_B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
_B58_RE = re.compile(r"(?<![1-9A-HJ-NP-Za-km-z])[1-9A-HJ-NP-Za-km-z]{86,90}(?![1-9A-HJ-NP-Za-km-z])")


def b58decode(text: str) -> bytes:
    n = 0
    for ch in text:
        n = n * 58 + _B58.index(ch)
    body = n.to_bytes((n.bit_length() + 7) // 8, "big") if n else b""
    return b"\x00" * (len(text) - len(text.lstrip("1"))) + body


# ---------------------------------------------------------------------------- BIP39 ---
def bip39_valid(words, wordlist_index) -> bool:
    if len(words) not in (12, 15, 18, 21, 24):
        return False
    try:
        bits = "".join(format(wordlist_index[w], "011b") for w in words)
    except KeyError:
        return False
    cs = len(bits) // 33
    entropy = int(bits[:-cs], 2).to_bytes((len(bits) - cs) // 8, "big")
    digest = hashlib.sha256(entropy).digest()
    return format(digest[0], "08b")[:cs] == bits[-cs:]


def find_mnemonics(text: str, wordlist_index) -> list:
    found = []
    run = []
    for token in re.split(r"( )", text) + ["\0"]:
        if token == " ":
            continue
        if token in wordlist_index:
            run.append(token)
            continue
        if len(run) >= 12:
            for size in (24, 21, 18, 15, 12):
                for start in range(0, len(run) - size + 1):
                    if bip39_valid(run[start:start + size], wordlist_index):
                        found.append(" ".join(run[start:start + 2]) + " …")
                        break
                else:
                    continue
                break
        run = []
    return found


# ------------------------------------------------------------------------------ dex ---
def _uleb128(buf, off):
    result = shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, off
        shift += 7


def dex_strings(buf: bytes) -> list:
    if not buf.startswith(b"dex\n"):
        raise ValueError("not a dex file")
    size, ids_off = struct.unpack_from("<II", buf, 0x38)
    out = []
    for i in range(size):
        (data_off,) = struct.unpack_from("<I", buf, ids_off + 4 * i)
        _, start = _uleb128(buf, data_off)
        end = buf.index(b"\x00", start)
        out.append(buf[start:end].decode("utf-8", errors="replace"))
    return out


# ------------------------------------------------------------------------- scanning ---
def _fingerprint(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8", errors="replace")).hexdigest()


def scan_text(text: str, where: str, wordlist_index, findings: list) -> None:
    for kind, pattern in PLAINTEXT_SECRET_PATTERNS_8418:
        for m in pattern.finditer(text):
            findings.append({"kind": kind, "where": where, "fingerprint": _fingerprint(m.group(0)),
                             "sample": m.group(0)[:12] + "…", "wallet": kind == "pem-private-key"})
    for m in _B58_RE.finditer(text):
        try:
            raw = b58decode(m.group(0))
        except ValueError:
            continue
        if is_solana_keypair(raw):
            findings.append({"kind": "solana-keypair-base58", "where": where, "wallet": True,
                             "fingerprint": _fingerprint(m.group(0)), "sample": m.group(0)[:6] + "…"})
    for m in re.finditer(r"\[\s*(?:\d{1,3}\s*,\s*){63}\d{1,3}\s*\]", text):
        nums = [int(x) for x in re.findall(r"\d+", m.group(0))]
        if all(n < 256 for n in nums) and is_solana_keypair(bytes(nums)):
            findings.append({"kind": "solana-keypair-json", "where": where, "wallet": True,
                             "fingerprint": _fingerprint(m.group(0)), "sample": m.group(0)[:10] + "…"})
    for m in re.finditer(r"\b[0-9a-fA-F]{128}\b", text):
        if is_solana_keypair(bytes.fromhex(m.group(0))):
            findings.append({"kind": "solana-keypair-hex", "where": where, "wallet": True,
                             "fingerprint": _fingerprint(m.group(0)), "sample": m.group(0)[:8] + "…"})
    if wordlist_index:
        for sample in find_mnemonics(text, wordlist_index):
            findings.append({"kind": "bip39-mnemonic", "where": where, "wallet": True,
                             "fingerprint": _fingerprint(sample), "sample": sample})


_B64_RE = re.compile(r"^[A-Za-z0-9+/]{64,}={0,2}$")


def scan_obfuscated(strings: list, where: str, findings: list) -> None:
    """XOR(base64) credentials decoded with any *OBF* key string from the same dex."""
    keys = {s.encode() for s in strings if "OBF" in s and 8 <= len(s) <= 64}
    if not keys:
        return
    for s in strings:
        if not _B64_RE.match(s):
            continue
        try:
            enc = base64.b64decode(s)
        except Exception:
            continue
        for key in keys:
            dec = bytes(c ^ key[i % len(key)] for i, c in enumerate(enc))
            try:
                text = dec.decode("ascii")
            except UnicodeDecodeError:
                continue
            if text.startswith("eyJ") or any(p.search(text) for _, p in PLAINTEXT_SECRET_PATTERNS_8418):
                findings.append({"kind": "xor-obfuscated-credential", "where": where, "wallet": False,
                                 "fingerprint": _fingerprint(text), "sample": text[:6] + "…"})


def load_wordlist(apk: zipfile.ZipFile):
    for name in apk.namelist():
        if re.search(r"(mnemonic-word-list|wordlist/english)\.txt$", name):
            words = [w.strip() for w in apk.read(name).decode("utf-8", "replace").split("\n") if w.strip()]
            if len(words) == 2048:
                return {w: i for i, w in enumerate(words)}, name
    return None, None


def scan_apk_secrets(apk_path: pathlib.Path) -> tuple[list, dict]:
    findings: list = []
    stats = {"dex_files": 0, "dex_strings": 0, "entries_scanned": 0}
    with zipfile.ZipFile(apk_path) as apk:
        wordlist, wl_name = load_wordlist(apk)
        stats["bip39_wordlist"] = wl_name or "MISSING"
        for info in apk.infolist():
            name = info.filename
            data = apk.read(name)
            if re.fullmatch(r"classes\d*\.dex", name):
                strings = dex_strings(data)
                stats["dex_files"] += 1
                stats["dex_strings"] += len(strings)
                scan_text("\n".join(strings), name, wordlist, findings)
                scan_obfuscated(strings, name, findings)
                continue
            if name.startswith("lib/") or name.endswith((".png", ".jpg", ".webp", ".so", ".tflite", ".onnx", ".ogg", ".mp3", ".wav")):
                continue
            if name.endswith(".gz") and info.file_size < 64 * 1024 * 1024:
                try:
                    data = gzip.decompress(data)
                except OSError:
                    pass
            if len(data) > 64 * 1024 * 1024:
                continue
            stats["entries_scanned"] += 1
            scan_text(data.decode("latin-1"), name, wordlist, findings)
    if wordlist is None:
        findings.append({"kind": "bip39-wordlist-missing", "where": "apk", "wallet": True,
                         "fingerprint": "", "sample": "cannot prove no mnemonic is embedded"})
    return findings, stats


# ------------------------------------------------------------------------ manifests ---
def _bool(value, default):
    if value is None:
        return default
    v = value.strip().lower()
    if v in ("true", "0xffffffff", "-1"):
        return True
    if v in ("false", "0x0", "0"):
        return False
    return default


def manifest_findings(xml_text: str) -> list:
    root = ET.fromstring(xml_text)
    pkg = root.get("package") or PACKAGE
    app = root.find("application")
    errors = []
    if app is None:
        return ["manifest has no <application>"]
    if _bool(app.get(A + "debuggable"), False):
        errors.append("application is debuggable")
    if _bool(app.get(A + "usesCleartextTraffic"), False):
        errors.append("usesCleartextTraffic=true")
    if not app.get(A + "networkSecurityConfig"):
        errors.append("no networkSecurityConfig")
    if _bool(app.get(A + "allowBackup"), True):
        if not app.get(A + "fullBackupContent") or not app.get(A + "dataExtractionRules"):
            errors.append("allowBackup without include-only fullBackupContent and dataExtractionRules")
    for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
        for comp in app.findall(tag):
            name = comp.get(A + "name") or comp.get(A + "targetActivity") or "?"
            if name.startswith("."):
                name = "com.lifecyclebot" + name
            if name in DEBUG_ONLY_COMPONENTS_8418:
                errors.append(f"debug-only component in release manifest: {name}")
            filters = comp.findall("intent-filter")
            # Absent attribute: exported iff it has an intent-filter (providers: never, targetSdk >= 17).
            exported = _bool(comp.get(A + "exported"), False if tag == "provider" else bool(filters))
            if not exported or not _bool(comp.get(A + "enabled"), True):
                continue
            if comp.get(A + "permission") or (tag == "provider" and comp.get(A + "readPermission") and comp.get(A + "writePermission")):
                continue
            actions = {a.get(A + "name") for f in filters for a in f.findall("action")}
            allowed = EXPORTED_ALLOWLIST_8418.get(name)
            if allowed is not None and actions <= allowed[1]:
                continue
            if tag == "receiver" and actions and actions <= PROTECTED_BROADCASTS_8418:
                continue
            errors.append(f"exported {tag} without permission: {name} actions={sorted(a for a in actions if a)}")
    return errors


def source_security_findings(src_main: pathlib.Path) -> list:
    errors = []
    manifest = ET.parse(src_main / "AndroidManifest.xml").getroot()
    app = manifest.find("application")
    xml_dir = src_main / "res" / "xml"

    def res_file(attr):
        value = app.get(A + attr) or ""
        m = re.fullmatch(r"@xml/(\w+)", value)
        return (xml_dir / (m.group(1) + ".xml")) if m else None

    nsc = res_file("networkSecurityConfig")
    if nsc is None or not nsc.exists():
        errors.append("networkSecurityConfig source missing")
    else:
        root = ET.parse(nsc).getroot()
        base = root.find("base-config")
        if base is None or base.get("cleartextTrafficPermitted") != "false":
            errors.append("network_security_config base-config must set cleartextTrafficPermitted=false")
        for dc in root.iter("domain-config"):
            if dc.get("cleartextTrafficPermitted") == "true":
                errors.append("network_security_config permits cleartext for " + ",".join(d.text or "" for d in dc.iter("domain")))
        for parent in [base] + list(root.iter("domain-config")):
            if parent is not None and any(c.get("src") == "user" for c in parent.iter("certificates")):
                errors.append("network_security_config trusts user-installed CAs outside debug-overrides")
    if _bool(app.get(A + "allowBackup"), True):
        for attr in ("fullBackupContent", "dataExtractionRules"):
            path = res_file(attr)
            if path is None or not path.exists():
                errors.append(f"{attr} rules missing while allowBackup=true")
                continue
            root = ET.parse(path).getroot()
            sections = [root] if attr == "fullBackupContent" else [s for s in root if s.tag in ("cloud-backup", "device-transfer")]
            if not sections:
                errors.append(f"{path.name}: no backup sections (would back up everything)")
            for sec in sections:
                includes = [(i.get("domain"), i.get("path")) for i in sec.findall("include")]
                if not includes:
                    errors.append(f"{path.name}:{sec.tag}: no <include> list — Android would back up all app data incl. wallet prefs")
                for inc in includes:
                    if inc not in BACKUP_ALLOWED_8418:
                        errors.append(f"{path.name}:{sec.tag}: backs up {inc[0]}/{inc[1]} (only learning DBs may be backed up)")
    return errors


# ------------------------------------------------------------------------- signing ---
def parse_apksigner(text: str) -> dict:
    schemes = {}
    for m in re.finditer(r"Verified using (v\d(?:\.\d)?) scheme[^:]*:\s*(true|false)", text):
        schemes[m.group(1)] = m.group(2) == "true"
    certs = sorted(set(m.group(1).lower() for m in re.finditer(r"certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", text)))
    signers = len(set(re.findall(r"Signer #(\d+) certificate", text)))
    return {"verified": text.strip().startswith("Verifies") or "\nVerifies" in text, "schemes": schemes,
            "cert_sha256": certs, "signers": signers}


def signing_findings(info: dict) -> list:
    errors = []
    if not info["verified"]:
        errors.append("apksigner did not verify the APK")
    if not info["schemes"].get("v2"):
        errors.append("APK Signature Scheme v2 missing (required: minSdk 26, v1 is not produced)")
    if info["cert_sha256"] != [RELEASE_CERT_SHA256_8418]:
        errors.append(f"signer certificate {info['cert_sha256']} is not the release keystore {RELEASE_CERT_SHA256_8418}")
    if info["signers"] != 1:
        errors.append(f"expected exactly one signer, found {info['signers']}")
    return errors


def parse_badging(text: str) -> dict:
    out = {}
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", text)
    if m:
        out.update(package=m.group(1), versionCode=int(m.group(2)), versionName=m.group(3))
    m = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", text, re.M)
    out["minSdk"] = int(m.group(1)) if m else None
    m = re.search(r"^targetSdkVersion:'(\d+)'", text, re.M)
    out["targetSdk"] = int(m.group(1)) if m else None
    out["debuggable"] = bool(re.search(r"^application-debuggable", text, re.M))
    return out


def identity_findings(badging: dict, version_name: str) -> list:
    errors = []
    m = re.fullmatch(r"5\.0\.(\d+)", version_name)
    if not m:
        return [f"invalid AATE_VERSION {version_name!r}"]
    if badging.get("package") != PACKAGE:
        errors.append(f"package {badging.get('package')} != {PACKAGE}")
    if badging.get("versionCode") != 500 + int(m.group(1)):
        errors.append(f"versionCode {badging.get('versionCode')} != {500 + int(m.group(1))}")
    if badging.get("versionName") != version_name:
        errors.append(f"versionName {badging.get('versionName')} != {version_name}")
    if not badging.get("minSdk") or badging["minSdk"] < MIN_SDK_FLOOR_8418:
        errors.append(f"minSdk {badging.get('minSdk')} < {MIN_SDK_FLOOR_8418}")
    if not badging.get("targetSdk") or badging["targetSdk"] < TARGET_SDK_FLOOR_8418 or badging["targetSdk"] < (badging.get("minSdk") or 0):
        errors.append(f"targetSdk {badging.get('targetSdk')} below {TARGET_SDK_FLOOR_8418} or minSdk")
    if badging.get("debuggable"):
        errors.append("release APK is debuggable")
    return errors


# ----------------------------------------------------------------------------- CLI ---
def _tool(name: str) -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    candidates = sorted(pathlib.Path(sdk, "build-tools").glob(f"*/{name}"), reverse=True) if sdk else []
    candidates += [pathlib.Path(sdk, "cmdline-tools", "latest", "bin", name)] if sdk else []
    for c in candidates:
        if c.exists():
            return str(c)
    found = shutil.which(name)
    if not found:
        raise SystemExit(f"::error::{name} not found (ANDROID_HOME={sdk!r})")
    return found


def _run(*cmd) -> str:
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    if proc.returncode != 0:
        raise SystemExit(f"::error::{' '.join(cmd[:2])} failed rc={proc.returncode}: {(proc.stderr or proc.stdout)[-800:]}")
    return proc.stdout


def apk_facts(apk: pathlib.Path) -> tuple[dict, dict]:
    signer = parse_apksigner(_run(_tool("apksigner"), "verify", "--verbose", "--print-certs", str(apk)))
    badging = parse_badging(_run(_tool("aapt2"), "dump", "badging", str(apk)))
    return signer, badging


def cmd_verify(a) -> int:
    apk = pathlib.Path(a.apk)
    version = pathlib.Path(a.version_file).read_text().strip()
    report = {"apk": str(apk), "version": version, "errors": [], "warnings": []}
    signer, badging = apk_facts(apk)
    report.update(signer=signer, badging=badging)
    report["errors"] += signing_findings(signer)
    report["errors"] += identity_findings(badging, version)
    mapping = pathlib.Path(a.mapping)
    if not mapping.exists() or mapping.stat().st_size == 0:
        report["errors"].append(f"R8 mapping missing: {mapping} (minify did not run)")
    manifest_xml = _run(_tool("apkanalyzer"), "manifest", "print", str(apk))
    pathlib.Path(a.out_dir, "release_manifest.xml").write_text(manifest_xml)
    report["errors"] += manifest_findings(manifest_xml)
    report["errors"] += source_security_findings(pathlib.Path(a.src_main))
    findings, stats = scan_apk_secrets(apk)
    report["secret_scan"] = stats
    for f in findings:
        reason = REVIEWED_CREDENTIALS_8418.get(f["fingerprint"])
        line = f"{f['kind']} in {f['where']} ({f['sample']}) sha256={f['fingerprint'][:16]}"
        if reason and not f["wallet"]:
            report["warnings"].append(line + " — REVIEWED: " + reason)
        else:
            report["errors"].append("embedded secret: " + line)
    return _finish(report, pathlib.Path(a.out_dir, "release_verify_8418.json"))


def version_order_findings(previous, candidate, warnings: list) -> list:
    """Android refuses a lower versionCode over an installed one (owner would have to uninstall
    and lose wallet/learning data). An equal code is a same-version rebuild (main commits that do
    not bump AATE_VERSION): installable, but reported so it is never mistaken for a new release."""
    if not previous or not candidate:
        return [f"versionCode unreadable: previous={previous} candidate={candidate}"]
    if candidate < previous:
        return [f"versionCode decreases: previous={previous} candidate={candidate} (Android rejects the downgrade)"]
    if candidate == previous:
        warnings.append(f"versionCode {candidate} equals the previous published APK (same-version rebuild; bump AATE_VERSION to release)")
    return []


def cmd_compare(a) -> int:
    report = {"errors": [], "warnings": []}
    new_signer, new_badging = apk_facts(pathlib.Path(a.apk))
    old_signer, old_badging = apk_facts(pathlib.Path(a.previous))
    report.update(candidate=new_badging, previous=old_badging,
                  candidate_cert=new_signer["cert_sha256"], previous_cert=old_signer["cert_sha256"])
    report["errors"] += version_order_findings(old_badging.get("versionCode"), new_badging.get("versionCode"), report["warnings"])
    if old_signer["cert_sha256"] != new_signer["cert_sha256"]:
        report["errors"].append(f"signer changed {old_signer['cert_sha256']} -> {new_signer['cert_sha256']}: Android rejects the upgrade")
    if old_badging.get("package") != new_badging.get("package"):
        report["errors"].append("package name changed")
    return _finish(report, pathlib.Path(a.out_dir, "release_compare_8418.json"))


def _finish(report: dict, path: pathlib.Path) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, default=str))
    for w in report["warnings"]:
        print(f"::warning title=Release security 8418::{w[:900]}")
    for e in report["errors"]:
        print(f"::error title=Release gate 8418::{e[:900]}")
    print(json.dumps({k: v for k, v in report.items() if k not in ("errors", "warnings")}, default=str)[:4000])
    print(f"RELEASE_VERIFY_8418 errors={len(report['errors'])} warnings={len(report['warnings'])} -> {path}")
    return 1 if report["errors"] else 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    v = sub.add_parser("verify")
    v.add_argument("--apk", required=True)
    v.add_argument("--version-file", default="AATE_VERSION")
    v.add_argument("--mapping", default="app/build/outputs/mapping/release/mapping.txt")
    v.add_argument("--src-main", default="app/src/main")
    v.add_argument("--out-dir", default="release_evidence_8418")
    c = sub.add_parser("compare")
    c.add_argument("--apk", required=True)
    c.add_argument("--previous", required=True)
    c.add_argument("--out-dir", default="release_evidence_8418")
    a = p.parse_args(argv)
    pathlib.Path(a.out_dir).mkdir(parents=True, exist_ok=True)
    return cmd_verify(a) if a.cmd == "verify" else cmd_compare(a)


if __name__ == "__main__":
    sys.exit(main())
