#!/usr/bin/env python3
"""Re-key the receipt golden vectors (src/test/resources/receipt/vectors-v1.json).

TEST-ONLY tool. It generates a brand-new random Ed25519 keypair, writes it into the
fixture and re-signs every vector with it:

    signature = Ed25519(secret, signature_domain || 0x00 || hash_bytes)

It deliberately does NOT touch canonical strings, receipt hashes, salts or commitments:
those are independent of the key, and they stay pinned so the canonicalization contract
(schema "ir/1") is unchanged. It is written in Python (json + cryptography), not in the
Java code under test, so the vectors keep being produced outside the implementation.

The keypair is a test fixture and nothing else: no environment (demo, staging, UAT, PROD)
uses it, and it must never be copied into one.

Usage: python3 src/test/tools/receipt-vectors-rekey.py [path-to-vectors-v1.json]
"""
import base64
import json
import os
import sys

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

KEY_ID = "test-only-fixture-2026-09-30"
KEY_NOTE = ("TEST-ONLY fixture, generated 2026-09-30 for public release; "
            "never used outside the test suite")

path = sys.argv[1] if len(sys.argv) > 1 else "src/test/resources/receipt/vectors-v1.json"
with open(path, encoding="utf-8") as f:
    data = json.load(f)

seed = os.urandom(32)
private = Ed25519PrivateKey.from_private_bytes(seed)
public = private.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)

data["key"] = {
    "note": KEY_NOTE,
    "key_id": KEY_ID,
    "secret_key_base64": base64.b64encode(seed + public).decode("ascii"),
    "public_key_hex": public.hex(),
}

sig_domain = data["signature_domain"].encode("utf-8")
for v in data["vectors"]:
    algo, hexhash = v["receipt_hash"].split(":", 1)
    assert algo == "sha256", v["receipt_hash"]
    message = sig_domain + b"\x00" + bytes.fromhex(hexhash)
    v["signature_base64"] = base64.b64encode(private.sign(message)).decode("ascii")

with open(path, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
    f.write("\n")
print(f"re-keyed {len(data['vectors'])} vectors with {KEY_ID} (public key {public.hex()})")
