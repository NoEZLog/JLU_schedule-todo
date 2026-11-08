#!/usr/bin/env python3
"""
Android In-App Update Tooling.
Implements RSA key generation, manifest signing, manifest verification,
and APK inspection per docs/ANDROID_UPDATE_DESIGN.md.
"""

import argparse
import base64
import hashlib
import json
import os
import sys
import zipfile
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

DEFAULT_KEY_ID = "main-2026"
DEFAULT_KEYS_DIR = Path(__file__).resolve().parent / "keys"


def generate_keys(key_dir: Path = DEFAULT_KEYS_DIR, key_id: str = DEFAULT_KEY_ID):
    key_dir.mkdir(parents=True, exist_ok=True)
    private_path = key_dir / f"update_private_{key_id}.pem"
    public_path = key_dir / f"update_public_{key_id}.pem"

    if private_path.exists():
        print(f"[!] Private key already exists at: {private_path}")
        print("    Refusing to overwrite existing key.")
        sys.exit(1)

    private_key = rsa.generate_private_key(
        public_exponent=65537,
        key_size=2048,
    )
    public_key = private_key.public_key()

    priv_pem = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    pub_pem = public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )

    private_path.write_bytes(priv_pem)
    public_path.write_bytes(pub_pem)

    pub_der = public_key.public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    pub_b64 = base64.b64encode(pub_der).decode("ascii")

    print(f"[+] Successfully generated RSA keypair (2048 bits)")
    print(f"    Key ID: {key_id}")
    print(f"    Private key saved to: {private_path}")
    print(f"    Public key saved to:  {public_path}")
    print("\n--- Base64 SubjectPublicKeyInfo for UpdateSecurityConfig.kt ---")
    print(pub_b64)
    print("----------------------------------------------------------------\n")
    return pub_b64


def sign_manifest(payload_path: Path, private_key_path: Path, key_id: str = DEFAULT_KEY_ID, output_path: Path = None):
    if not payload_path.exists():
        raise FileNotFoundError(f"Payload JSON not found: {payload_path}")
    if not private_key_path.exists():
        raise FileNotFoundError(f"Private key not found: {private_key_path}")

    # Load and validate payload JSON
    payload_text = payload_path.read_text(encoding="utf-8")
    payload_obj = json.loads(payload_text)

    # Re-serialize to canonical UTF-8 bytes without indentation or unnecessary whitespace
    payload_canonical_bytes = json.dumps(payload_obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    # Load private key
    priv_bytes = private_key_path.read_bytes()
    private_key = serialization.load_pem_private_key(priv_bytes, password=None)

    # Compute SHA256withRSA signature
    signature = private_key.sign(
        payload_canonical_bytes,
        padding.PKCS1v15(),
        hashes.SHA256(),
    )

    # Encode payload as Base64URL (RFC 4648 without padding or with padding)
    payload_b64url = base64.urlsafe_b64encode(payload_canonical_bytes).decode("ascii")
    # Encode signature as standard Base64
    sig_b64 = base64.b64encode(signature).decode("ascii")

    envelope = {
        "schema": 1,
        "keyId": key_id,
        "payload": payload_b64url,
        "signature": sig_b64,
    }

    envelope_json = json.dumps(envelope, ensure_ascii=False, indent=2)

    if output_path:
        output_path.write_text(envelope_json, encoding="utf-8")
        print(f"[+] Signed manifest written to: {output_path}")
    else:
        print(envelope_json)

    return envelope


def verify_manifest(manifest_path: Path, public_key_path: Path):
    if not manifest_path.exists():
        raise FileNotFoundError(f"Manifest file not found: {manifest_path}")
    if not public_key_path.exists():
        raise FileNotFoundError(f"Public key not found: {public_key_path}")

    manifest_obj = json.loads(manifest_path.read_text(encoding="utf-8"))
    payload_b64 = manifest_obj.get("payload", "")
    sig_b64 = manifest_obj.get("signature", "")
    key_id = manifest_obj.get("keyId", "")

    # Decode payload
    # Add padding if needed
    rem = len(payload_b64) % 4
    if rem > 0:
        payload_b64 += "=" * (4 - rem)
    payload_bytes = base64.urlsafe_b64decode(payload_b64.encode("ascii"))

    # Decode signature
    sig_bytes = base64.b64decode(sig_b64.encode("ascii"))

    # Load public key
    pub_bytes = public_key_path.read_bytes()
    public_key = serialization.load_pem_public_key(pub_bytes)

    # Verify signature
    try:
        public_key.verify(
            sig_bytes,
            payload_bytes,
            padding.PKCS1v15(),
            hashes.SHA256(),
        )
        print("[+] Signature VERIFIED successfully!")
    except Exception as e:
        print(f"[-] Signature VERIFICATION FAILED: {e}")
        return False

    payload_json = json.loads(payload_bytes.decode("utf-8"))
    print("\n--- Decoded Payload ---")
    print(json.dumps(payload_json, ensure_ascii=False, indent=2))
    return True


def inspect_apk(apk_path: Path):
    if not apk_path.exists():
        raise FileNotFoundError(f"APK file not found: {apk_path}")

    apk_bytes = apk_path.read_bytes()
    sha256 = hashlib.sha256(apk_bytes).hexdigest()
    size = len(apk_bytes)

    print(f"File: {apk_path.name}")
    print(f"Size: {size} bytes ({size / (1024 * 1024):.2f} MB)")
    print(f"SHA-256: {sha256}")

    # Inspect META-INF/ certificates if available (v1 signing)
    cert_shas = []
    with zipfile.ZipFile(apk_path, "r") as z:
        for name in z.namelist():
            if name.startswith("META-INF/") and (name.endswith(".RSA") or name.endswith(".DSA") or name.endswith(".EC")):
                raw = z.read(name)
                # Note: Full PKCS7 parsing can be done via openssl or cryptography, but apksigner is standard
                print(f"Found signature block: {name}")

    return {"size": size, "sha256": sha256}


def main():
    parser = argparse.ArgumentParser(description="Android update manifest management tool")
    subparsers = parser.add_subparsers(dest="command")

    gen_p = subparsers.add_parser("generate-keys", help="Generate RSA signing keypair")
    gen_p.add_argument("--key-id", default=DEFAULT_KEY_ID, help="Key ID identifier")
    gen_p.add_argument("--out-dir", default=str(DEFAULT_KEYS_DIR), help="Output directory for keys")

    sign_p = subparsers.add_parser("sign", help="Sign update payload JSON")
    sign_p.add_argument("payload", help="Path to raw payload JSON")
    sign_p.add_argument("--key", default=str(DEFAULT_KEYS_DIR / f"update_private_{DEFAULT_KEY_ID}.pem"), help="Path to private key PEM")
    sign_p.add_argument("--key-id", default=DEFAULT_KEY_ID, help="Key ID identifier")
    sign_p.add_argument("--out", help="Output path for stable.json (default: stdout)")

    ver_p = subparsers.add_parser("verify", help="Verify signed manifest")
    ver_p.add_argument("manifest", help="Path to stable.json")
    ver_p.add_argument("--pubkey", default=str(DEFAULT_KEYS_DIR / f"update_public_{DEFAULT_KEY_ID}.pem"), help="Path to public key PEM")

    insp_p = subparsers.add_parser("inspect-apk", help="Inspect APK SHA-256 and size")
    insp_p.add_argument("apk", help="Path to APK file")

    args = parser.parse_args()

    if args.command == "generate-keys":
        generate_keys(Path(args.out_dir), args.key_id)
    elif args.command == "sign":
        sign_manifest(Path(args.payload), Path(args.key), args.key_id, Path(args.out) if args.out else None)
    elif args.command == "verify":
        verify_manifest(Path(args.manifest), Path(args.pubkey))
    elif args.command == "inspect-apk":
        inspect_apk(Path(args.apk))
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
