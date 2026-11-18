---
layout: post
title: "Watching RS256 sign and verify a JWT, byte by byte"
date: 2026-11-13
categories: tech
---

Spring Authorization Server logs a lot when it issues a token: it generates an RSA keypair on startup, signs with the private key, and publishes a JWK at `/oauth2/jwks` so resource servers can verify with only the public key. It's easy to trust that flow without ever seeing the math underneath it. So I rebuilt it from scratch in plain Python — no `cryptography`, no `PyJWT` — real primality testing, real modular exponentiation, real PKCS#1 v1.5 / SHA-256 padding, real base64url JWT framing.

The point wasn't to replace a library. It was to stop treating `RS256` as a magic string in `application.yml` and actually watch the four moves it names:

1. Generate an RSA keypair on startup (`AuthorizationServerConfig`)
2. **Sign** a JWT with the **private** key (the token endpoint)
3. Publish the **public** key as a JWK (`/oauth2/jwks`)
4. **Verify** an incoming token with *only* the public key (`JwtDecoder` on a resource server)

The thing worth saying out loud: this is RSA *signing*, not encryption, and the key roles flip.

```
encrypt:  ciphertext = M^e mod n   (public key operation)
decrypt:  M = C^d mod n            (private key operation)

sign:     signature = hash(data)^d mod n   (PRIVATE key operation)
verify:   hash(data) == signature^e mod n  (PUBLIC key operation)
```

Signing is "encrypt with the private key" — so that anyone holding the public key can check it, but only the private key holder could have produced it. Encryption and signing use the exact same `pow(x, k, n)` formula; only which key plays which role swaps.

---

## 1. Toy keys next to real keys, side by side

Before touching JWTs, I generated two keypairs in the same run and logged every value on matching lines — classic textbook numbers (`p=61, q=53`, trivially factorable, checkable by hand in a REPL) next to an actual 2048-bit keypair, the same security level Spring Boot's `AuthorizationServerConfig` generates:

```
p                      TOY=61           | REAL=0xf3f7f1fbcbf39bc2...
q                      TOY=53           | REAL=0x9350b64e93516105...
n = p*q                TOY=3233         | REAL=0x8c644b2c5cc64a26... (2048 bits)
e (public exponent)    TOY=17           | REAL=65537
d (private exponent)   TOY=2753         | REAL=0x29dcc625a64c6253...
```

Same formulas, same `pow(x, e, n)` / `pow(x, d, n)` round-trip on both sides — `n`'s bit length is the *entire* difference between "toy" and "production." That's the thing I wanted to internalize before trusting the real flow: there's no extra magic in the 2048-bit case, just bigger numbers and a real SHA-256 digest instead of a fake hash int.

```
hash H (input to sign) TOY=42           | REAL=0x87f5bed4c5b6aae9...
signature S=H^d mod n  TOY=3065         | REAL=0x5dc872054f025f1b...
recovered H'=S^e mod n TOY=42           | REAL=0x87f5bed4c5b6aae9...
H == H' ?              TOY=True         | REAL=True
```

## 2. PKCS#1 v1.5 padding — what makes it "RS256" and not just "RSA"

Raw RSA on a bare hash would be insecure and non-standard. `RS256` means: SHA-256 the signing input, prefix the digest with a DER blob that identifies it as SHA-256 (`RFC 3447` / `RFC 8017`), then pad:

```python
SHA256_DIGEST_INFO_PREFIX = bytes.fromhex("3031300d060960864801650304020105000420")

def emsa_pkcs1_v15_encode(message: bytes, key_bytes: int) -> int:
    digest = hashlib.sha256(message).digest()
    digest_info = SHA256_DIGEST_INFO_PREFIX + digest
    padding_len = key_bytes - len(digest_info) - 3
    padded = b"\x00\x01" + b"\xff" * padding_len + b"\x00" + digest_info
    return int.from_bytes(padded, byteorder="big")
```

`0x00 0x01`, then `0xff` padding bytes out to the key size, then `0x00`, then the DER-tagged digest. Sign that integer with `pow(padded_int, d, n)`; verify by recomputing the same padded block from the message and checking it matches `pow(signature_int, e, n)`.

## 3. Assembling the actual JWT

Once signing works on raw bytes, a JWT is just base64url framing around it — header and payload each base64url-encoded, joined with `.`, and the signature computed over `header_b64 + "." + payload_b64`:

```python
def issue_jwt(claims: dict, private_key, kid: str) -> str:
    header = {"alg": "RS256", "typ": "JWT", "kid": kid}
    header_b64 = b64url_encode(json.dumps(header, separators=(",", ":")).encode())
    payload_b64 = b64url_encode(json.dumps(claims, separators=(",", ":")).encode())
    signing_input = f"{header_b64}.{payload_b64}".encode()
    signature_b64 = b64url_encode(rsa_sign(signing_input, private_key))
    return f"{header_b64}.{payload_b64}.{signature_b64}"
```

Running it end to end with claims that look like what Spring Authorization Server would issue:

```
JWT claims: {"sub": "alice", "iss": "custom-auth-server", "iat": 1794556490, "exp": 1794560090, "scope": "read write"}

[SIGN] SHA-256(signing_input) = 855887c6a12665f807531d5980558e4c1b4d7ebf6062939ba15a47315450c924
[SIGN] computing signature = padded_int ^ d mod n  (PRIVATE key operation)

Final JWT: eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6ImRlbW8ta2V5LTEifQ.
eyJzdWIiOiJhbGljZSIsImlzcyI6ImN1c3RvbS1hdXRoLXNlcnZlciIsImlhdCI6MTc5NDU1NjQ5MCwi
ZXhwIjoxNzk0NTYwMDkwLCJzY29wZSI6InJlYWQgd3JpdGUifQ.VhgVomBLaV3ML16T7gSV...
```

## 4. Verifying with only the public key

The resource-server side never sees `d`. It recomputes the SHA-256 of `header_b64.payload_b64`, recomputes the expected padded block, and checks it against `signature^e mod n`:

```python
def verify_jwt(token: str, public_key) -> dict:
    header_b64, payload_b64, signature_b64 = token.split(".")
    signing_input = f"{header_b64}.{payload_b64}".encode()
    signature = b64url_decode(signature_b64)
    if not rsa_verify(signing_input, signature, public_key):
        raise ValueError("Signature verification FAILED - token is forged or corrupted")
    return json.loads(b64url_decode(payload_b64))
```

```
[VERIFY] computing recovered = signature ^ e mod n  (PUBLIC key operation)
[VERIFY] recovered == expected padded block? True
Signature valid. Claims trusted: {"sub": "alice", ...}
```

That `JwtDecoder` on a Spring resource server is doing exactly this — fetching the JWK from `/oauth2/jwks`, turning `n`/`e` back into a public key, and running the same `pow(signature, e, n)` check.

## 5. The tamper check

Flip one character in the payload, keep the original signature, and verify again:

```
=== STEP 4: tamper check - flip one character in the payload ===
[VERIFY] SHA-256(signing_input) = 9163b988f20326a6f73cb2a5a349304a935b7da2d391174597ebf344085274ae
[VERIFY] recovered == expected padded block? False
Correctly rejected: Signature verification FAILED - token is forged or corrupted
```

The recomputed digest no longer matches what the old signature decodes to, because the signature was computed over the *original* bytes. This is the whole reason a JWT can't be edited client-side without invalidating itself — the signature binds to the exact payload bytes, not to some separately-checked field.

---

## Full code and output

Everything above, in one runnable script — no external crypto libraries, real modular exponentiation RSA, real PKCS#1 v1.5 / SHA-256 padding, real base64url JWT framing.

<details markdown="0">
<summary>Click to view the complete script (<code>jwt_rsa_demo.py</code>)</summary>

{% highlight python %}
"""
POC: how Spring Authorization Server's RS256 JWT flow works, in plain Python.

Mirrors exactly what your log shows:
  1. Generate an RSA keypair on startup        (AuthorizationServerConfig)
  2. SIGN a JWT with the PRIVATE key (RS256)    (the token endpoint)
  3. Publish the PUBLIC key as a JWK            (/oauth2/jwks)
  4. VERIFY an incoming token with ONLY the
     public key                                (JwtDecoder on a resource server)

This is RSA *signing*, not encryption:
  sign:   signature = hash(data) ^ d  mod n     (private key operation)
  verify: hash(data) == signature ^ e mod n     (public key operation)

That's the reverse of encryption's roles (encrypt=public, decrypt=private) --
signing is "encrypt with the private key" so that anyone holding the public
key can check it, but only the private key holder could have produced it.

No external crypto libraries used -- real modular exponentiation RSA, real
PKCS#1 v1.5 / SHA-256 padding, real base64url JWT framing.
"""

import base64
import hashlib
import json
import random
import time

def log(level: str, msg: str) -> None:
    print(msg)

# ---------------------------------------------------------------------------
# 1. Plain RSA primitives (same as simple_rsa.py)
# ---------------------------------------------------------------------------

def is_prime(n, k=20):
    if n < 2:
        return False
    for p in (2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31):
        if n == p:
            return True
        if n % p == 0:
            return False
    r, d = 0, n - 1
    while d % 2 == 0:
        r += 1
        d //= 2
    for _ in range(k):
        a = random.randrange(2, n - 1)
        x = pow(a, d, n)
        if x == 1 or x == n - 1:
            continue
        for _ in range(r - 1):
            x = pow(x, 2, n)
            if x == n - 1:
                break
        else:
            return False
    return True

def generate_prime(bits):
    while True:
        candidate = random.getrandbits(bits) | (1 << (bits - 1)) | 1
        if is_prime(candidate):
            return candidate

def egcd(a, b):
    if b == 0:
        return a, 1, 0
    g, x1, y1 = egcd(b, a % b)
    return g, y1, x1 - (a // b) * y1

def modinv(a, m):
    g, x, _ = egcd(a, m)
    if g != 1:
        raise ValueError("modular inverse does not exist")
    return x % m

def generate_keypair_raw(bits=2048):
    """Pure computation, no logging -- used for both the toy and real keys."""
    p = generate_prime(bits // 2)
    q = generate_prime(bits // 2)
    while p == q:
        q = generate_prime(bits // 2)
    n = p * q
    phi = (p - 1) * (q - 1)
    e = 65537
    d = modinv(e, phi)
    return p, q, n, phi, e, d

def toy_keypair_raw():
    """Classic textbook numbers (p=61, q=53). NOT secure -- trivially factorable."""
    p, q = 61, 53
    n = p * q
    phi = (p - 1) * (q - 1)
    e = 17
    d = modinv(e, phi)
    return p, q, n, phi, e, d

def side_by_side(label: str, toy_val, real_val) -> None:
    log("INFO", f"{label:<22} TOY={toy_val!s:<12} | REAL={real_val}")

# ---------------------------------------------------------------------------
# 2. PKCS#1 v1.5 signing padding for SHA-256 (what makes this "RS256")
# ---------------------------------------------------------------------------

# DER prefix identifying "this hash is SHA-256" (RFC 3447 / RFC 8017)
SHA256_DIGEST_INFO_PREFIX = bytes.fromhex(
    "3031300d060960864801650304020105000420"
)

def emsa_pkcs1_v15_encode(message: bytes, key_bytes: int, *, verbose_label: str = "") -> int:
    digest = hashlib.sha256(message).digest()
    if verbose_label:
        log("INFO", f"[{verbose_label}] SHA-256(signing_input) = {digest.hex()}")
    digest_info = SHA256_DIGEST_INFO_PREFIX + digest
    padding_len = key_bytes - len(digest_info) - 3
    if padding_len < 8:
        raise ValueError("RSA key too small for SHA-256 PKCS#1 v1.5 padding")
    padded = b"\x00\x01" + b"\xff" * padding_len + b"\x00" + digest_info
    if verbose_label:
        log("INFO", f"[{verbose_label}] EMSA-PKCS1-v1.5 padded block (hex, truncated): "
                     f"{padded[:16].hex()}...{padded[-40:].hex()}")
    return int.from_bytes(padded, byteorder="big")

def rsa_sign(message: bytes, private_key) -> bytes:
    d, n = private_key
    key_bytes = (n.bit_length() + 7) // 8
    padded_int = emsa_pkcs1_v15_encode(message, key_bytes, verbose_label="SIGN")
    log("INFO", "[SIGN] computing signature = padded_int ^ d mod n  (PRIVATE key operation)")
    signature_int = pow(padded_int, d, n)
    log("INFO", f"[SIGN] signature (hex, truncated): {hex(signature_int)[:20]}...{hex(signature_int)[-20:]}")
    return signature_int.to_bytes(key_bytes, byteorder="big")

def rsa_verify(message: bytes, signature: bytes, public_key) -> bool:
    e, n = public_key
    key_bytes = (n.bit_length() + 7) // 8
    signature_int = int.from_bytes(signature, byteorder="big")
    log("INFO", "[VERIFY] computing recovered = signature ^ e mod n  (PUBLIC key operation)")
    recovered_int = pow(signature_int, e, n)
    expected_int = emsa_pkcs1_v15_encode(message, key_bytes, verbose_label="VERIFY")
    match = recovered_int == expected_int
    log("INFO", f"[VERIFY] recovered == expected padded block? {match}")
    return match

# ---------------------------------------------------------------------------
# 3. base64url helpers (JWTs use base64url, no padding)
# ---------------------------------------------------------------------------

def b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")

def b64url_encode_int(n: int) -> str:
    length = (n.bit_length() + 7) // 8
    return b64url_encode(n.to_bytes(length, byteorder="big"))

def b64url_decode(s: str) -> bytes:
    padding = "=" * (-len(s) % 4)
    return base64.urlsafe_b64decode(s + padding)

# ---------------------------------------------------------------------------
# 4. The flow
# ---------------------------------------------------------------------------

def issue_jwt(claims: dict, private_key, kid: str) -> str:
    header = {"alg": "RS256", "typ": "JWT", "kid": kid}
    log("INFO", f"JWT header: {json.dumps(header)}")
    log("INFO", f"JWT claims: {json.dumps(claims)}")

    header_b64 = b64url_encode(json.dumps(header, separators=(",", ":")).encode())
    payload_b64 = b64url_encode(json.dumps(claims, separators=(",", ":")).encode())
    log("INFO", f"base64url(header)  = {header_b64}")
    log("INFO", f"base64url(payload) = {payload_b64}")

    signing_input = f"{header_b64}.{payload_b64}".encode()
    log("INFO", f"signing_input = header_b64 + '.' + payload_b64 ({len(signing_input)} bytes)")

    signature = rsa_sign(signing_input, private_key)
    signature_b64 = b64url_encode(signature)
    log("INFO", f"base64url(signature) = {signature_b64[:20]}...{signature_b64[-20:]}")

    token = f"{header_b64}.{payload_b64}.{signature_b64}"
    log("INFO", "Token assembled: header_b64 '.' payload_b64 '.' signature_b64")
    return token

def verify_jwt(token: str, public_key) -> dict:
    header_b64, payload_b64, signature_b64 = token.split(".")
    log("INFO", "Split token into header_b64 / payload_b64 / signature_b64")

    signing_input = f"{header_b64}.{payload_b64}".encode()
    signature = b64url_decode(signature_b64)
    log("INFO", f"Re-decoded signature ({len(signature)} bytes)")

    if not rsa_verify(signing_input, signature, public_key):
        raise ValueError("Signature verification FAILED - token is forged or corrupted")

    claims = json.loads(b64url_decode(payload_b64))
    log("INFO", f"Decoded claims from payload_b64: {json.dumps(claims)}")
    return claims

def to_jwk(public_key, kid: str) -> dict:
    e, n = public_key
    return {
        "kty": "RSA",
        "use": "sig",
        "alg": "RS256",
        "kid": kid,
        "e": b64url_encode_int(e),
        "n": b64url_encode_int(n),
    }

def run_side_by_side(bits: int = 2048):
    """Generate TOY and REAL keypairs and log every value on the same line,
    TOY on the left, REAL on the right, so you can compare them directly.

    TOY uses the classic textbook numbers (p=61, q=53) -- NOT secure,
    trivially factorable -- it exists purely so the numbers are checkable
    by hand, e.g. in a Python REPL:
        >>> pow(65, 17, 3233)
        2790
        >>> pow(2790, 2753, 3233)
        65
    REAL uses actual random 1024-bit primes -- the same security level
    your Spring Boot AuthorizationServerConfig generates.
    """
    log("INFO", f"=== STEP 0: TOY vs REAL, side by side ({bits}-bit REAL) ===")

    t0 = time.time()
    p_t, q_t, n_t, phi_t, e_t, d_t = toy_keypair_raw()
    p_r, q_r, n_r, phi_r, e_r, d_r = generate_keypair_raw(bits=bits)
    gen_ms = int((time.time() - t0) * 1000)

    side_by_side("p", p_t, hex(p_r))
    side_by_side("q", q_t, hex(q_r))
    side_by_side("n = p*q", n_t, hex(n_r))
    side_by_side("phi(n)=(p-1)(q-1)", phi_t, hex(phi_r))
    side_by_side("e (public exponent)", e_t, e_r)
    side_by_side("d (private exponent)", d_t, hex(d_r))
    log("WARN", f"d is SECRET in both cases -- shown here for STUDY MODE only (keygen took {gen_ms}ms for REAL)")

    log("INFO", "--- ENCRYPT / DECRYPT (same message int, both key sizes) ---")
    message = 65  # e.g. ASCII 'A' -- small enough to be valid for BOTH n's
    c_t = pow(message, e_t, n_t)
    m_t = pow(c_t, d_t, n_t)
    c_r = pow(message, e_r, n_r)
    m_r = pow(c_r, d_r, n_r)
    side_by_side("ciphertext C=M^e mod n", c_t, hex(c_r))
    side_by_side("recovered M'=C^d mod n", m_t, hex(m_r))
    side_by_side("M == M' ?", message == m_t, message == m_r)

    log("INFO", "--- SIGN / VERIFY (toy uses a fake hash int, real uses SHA-256) ---")
    fake_hash = 42
    real_hash_int = int.from_bytes(hashlib.sha256(b"hello from spring boot").digest(), "big") % n_r
    s_t = pow(fake_hash, d_t, n_t)
    v_t = pow(s_t, e_t, n_t)
    s_r = pow(real_hash_int, d_r, n_r)
    v_r = pow(s_r, e_r, n_r)
    side_by_side("hash H (input to sign)", fake_hash, hex(real_hash_int))
    side_by_side("signature S=H^d mod n", s_t, hex(s_r))
    side_by_side("recovered H'=S^e mod n", v_t, hex(v_r))
    side_by_side("H == H' ?", fake_hash == v_t, real_hash_int == v_r)

    side_by_side("n bit length", n_t.bit_length(), n_r.bit_length())
    log("INFO", "Same formulas on both sides: pow(x, e, n) to verify/encrypt, pow(x, d, n) to sign/decrypt.")
    log("INFO", "Only n's size changed -- that size is the entire difference between 'toy' and 'production'.")

    return (e_r, n_r), (d_r, n_r)  # real public_key, private_key -- reused below for the JWT flow

if __name__ == "__main__":
    public_key, private_key = run_side_by_side(bits=2048)
    kid = "demo-key-1"

    log("INFO", "=== STEP 1: publish public key as JWKS (what /oauth2/jwks exposes) ===")
    jwk = to_jwk(public_key, kid)
    log("INFO", f"JWK: {json.dumps(jwk)}")
    jwks = {"keys": [jwk]}
    log("INFO", f"Built JWKSet containing {len(jwks['keys'])} key (kid='{kid}')")

    log("INFO", "=== STEP 2: auth server ISSUES a token: sign claims with PRIVATE key ===")
    claims = {
        "sub": "alice",
        "iss": "custom-auth-server",
        "iat": int(time.time()),
        "exp": int(time.time()) + 3600,
        "scope": "read write",
    }
    token = issue_jwt(claims, private_key, kid)
    log("INFO", f"Final JWT: {token}")

    log("INFO", "=== STEP 3: resource server VERIFIES token using ONLY the public key ===")
    recovered_claims = verify_jwt(token, public_key)
    log("INFO", f"Signature valid. Claims trusted: {json.dumps(recovered_claims)}")

    log("INFO", "=== STEP 4: tamper check - flip one character in the payload ===")
    header_b64, payload_b64, sig_b64 = token.split(".")
    tampered_payload = payload_b64[:-1] + ("A" if payload_b64[-1] != "A" else "B")
    tampered_token = f"{header_b64}.{tampered_payload}.{sig_b64}"
    log("WARN", "Attempting to verify a tampered token (same signature, modified payload)...")
    try:
        verify_jwt(tampered_token, public_key)
        log("ERROR", "Uh oh - tampered token was accepted (should never happen)")
    except ValueError as exc:
        log("INFO", f"Correctly rejected: {exc}")
{% endhighlight %}

</details>

And the full mirrored output from running it — toy numbers on the left, real 2048-bit numbers on the right, through keygen, JWKS, issue, verify, and the tamper check:

<details markdown="0">
<summary>Click to view the full run log</summary>

{% highlight text %}
➜  rsa-demo python3 jwt_rsa_demo.py
=== STEP 0: TOY vs REAL, side by side (2048-bit REAL) ===
p                      TOY=61           | REAL=0xf3f7f1fbcbf39bc2c532c04b8a1d2e27e2a6e7fa20a92464a374834db056c291e62eea723631e5af8e512fbfb072ab2a9d56c4831999a079f457df5829c2447f1751a2afdc1928f38e89be36323c81f5fabd9a1b8aaf86d3767cfac50be44b54cfdcfccfe51468be0f5c788bebcac1b969006a46ad9eb157541cd9527f9d3c61
q                      TOY=53           | REAL=0x9350b64e935161056be01befa0beddc4257a5cf304a8ec1344f99a57577463c3f779c46b1e33a00f70bd108c48788dffd9d1c50352ea0279a7937dc20543e7424aa42fbf3f0601d66919ef295367d8d665be4901a300edebf1beb7651df85811895b3174dfda63a1992decd511e225d5b08db4e9f291f674722db6042848cfd5
n = p*q                TOY=3233         | REAL=0x8c644b2c5cc64a261d2e74408a7c334832e25b549705dd5f0df09254c1802afdf17526304965fffb2d8f0f7bf51607c94afcab1c56f61794201bba807bb8ad1152283ab2b898caaec3cf4056947a98a892a909833eea9ff487ef6ff09b93e1f47b55c702bf394c390c6c218bee6abdf8cd74d7adaa89d510469ddc8e60cfa5e0bd7f8875eb0dd9876d9340812ffaed3fa2815ec4f5d76eda4d1c92fadb00ee4b28ef7277cb94bd6b58c8a8789119b334af398bf65ee999a97e902bf064edbbc4d654fddcdc0a9b6d6d32c9c5e7056221599fd5a2184da0b81787cecc7c6fc43e67337d943747c53446a1d23ebfea6f1bcae883fadfa1663ce94de40074edabb5
phi(n)=(p-1)(q-1)      TOY=3120         | REAL=0x8c644b2c5cc64a261d2e74408a7c334832e25b549705dd5f0df09254c1802afdf17526304965fffb2d8f0f7bf51607c94afcab1c56f61794201bba807bb8ad1152283ab2b898caaec3cf4056947a98a892a909833eea9ff487ef6ff09b93e1f47b55c702bf394c390c6c218bee6abdf8cd74d7adaa89d510469ddc8e60cfa5df3636e02b8bc8dcbf3c806446051ee1539a6019d7d0855e6264ae7555d335c7f54b46c39a772f37ac59ba682c982e7a0a3811026ff265f6b5e2a4ced635e79003745f2b6dc0eb70a3758f1c6661610754f923f284ea9d2bf8af4c1ca2529320d80dfb4f4f7258f8d49e176cddc23d878cb15a64ca3f70be71230354a9cd079f80
e (public exponent)    TOY=17           | REAL=65537
d (private exponent)   TOY=2753         | REAL=0x29dcc625a64c6253d723c0fa75d0ef21ba3a90ed3a533f5d56b4c38d8e5d7f7455898f279adb55673a808c27743f6b76f46b3f42a6139f9845b2befc0ce85682f1b2993e2d9931e6c6f087c8c9617d1fd70b9c908c343e1a1666a9445fbfcb02b1bc34297787c858c28183c74c027c3f325482f38004b589b2e4e39d78f4b20b4b0935f3062d90d55d5f138765837dc106295dcb629c4236a8baf4b5f0d2fa9b042547b24993c4d019b961d2da561181f691ebde21b391cb4e97c22d51c988c1def379e9b23b024d5b829eff504e34777c0ae2080c456d952c0050117ef9ec3340880d702d32a6900fcc006467c51d2430c63983ad431b2675404b008ee89501
d is SECRET in both cases -- shown here for STUDY MODE only (keygen took 837ms for REAL)
--- ENCRYPT / DECRYPT (same message int, both key sizes) ---
ciphertext C=M^e mod n TOY=2790         | REAL=0x72ca4d25d7831994923dae0d136b6070204721e548e1bbe7189e8bd85673a9932e031c5ab39b358f4a7ac878250f1746ab50f0ddcf9988cbc380459588b47f948acb9702f271299825636d731c2d6d1163ebabc07c4e8f6ba32f1591a0eda6dc8142e3ff1b6a2bd01ca55609a3c8700eef3b08e47cad4ef47e20c4369e525865247d09119124a66419a2db45ea125b9beb7aafe5d9b6cb7dc1a712947c9831006484476919fb93e26d868ad7b0d855467132c682b512a4cd884cfea5d5bf6a77cd6b74c9d5756165064043723c760b033979af98298c691096956fabe6f28eaa9f89fd257f754544f35d77c2541321b372b72ab9f4e1f0e20bbf4cef70347ebb
recovered M'=C^d mod n TOY=65           | REAL=0x41
M == M' ?              TOY=True         | REAL=True
--- SIGN / VERIFY (toy uses a fake hash int, real uses SHA-256) ---
hash H (input to sign) TOY=42           | REAL=0x87f5bed4c5b6aae95c2cdcf2803d1d97d809c96a0f2c5f50e6ef90d5e36b4d00
signature S=H^d mod n  TOY=3065         | REAL=0x5dc872054f025f1bea72ac314519307857abbcb1914e5dc2244f3b71ede83a608ace5dc6efb0e1832ec64829d258c435325679767aae13e6bf310b2e2db81379188ef87c7a1b0d3c613f39b5fa0fe6cc26162aecc0dca2b1a9378cba2fee3c29497fadf07280404eb9930f21f0ec4c12b831becd8bd8f5857c1b92749996141514588606a7f0ea4031a1ed615853c587da38768c5a4c5856ac2e09a5463dd63c2f0501f2b81c2419f2138474c2c6e7683c71fab8851d99f30b1635162eb33102238449b0e2855fa31391e7bc8bd48a51535ae2669b1069c1a07f1be35dc72d36a5370affc7e035f6cd7960b2bbc637c9a94c322b8c196cee87fdcc2b12db4fdb
recovered H'=S^e mod n TOY=42           | REAL=0x87f5bed4c5b6aae95c2cdcf2803d1d97d809c96a0f2c5f50e6ef90d5e36b4d00
H == H' ?              TOY=True         | REAL=True
n bit length            TOY=12           | REAL=2048
Same formulas on both sides: pow(x, e, n) to verify/encrypt, pow(x, d, n) to sign/decrypt.
Only n's size changed -- that size is the entire difference between 'toy' and 'production'.
=== STEP 1: publish public key as JWKS (what /oauth2/jwks exposes) ===
JWK: {"kty": "RSA", "use": "sig", "alg": "RS256", "kid": "demo-key-1", "e": "AQAB", "n": "jGRLLFzGSiYdLnRAinwzSDLiW1SXBd1fDfCSVMGAKv3xdSYwSWX_-y2PD3v1FgfJSvyrHFb2F5QgG7qAe7itEVIoOrK4mMquw89AVpR6mKiSqQmDPuqf9Ifvb_Cbk-H0e1XHAr85TDkMbCGL7mq9-M10162qidUQRp3cjmDPpeC9f4h16w3Zh22TQIEv-u0_ooFexPXXbtpNHJL62wDuSyjvcnfLlL1rWMioeJEZszSvOYv2XumZqX6QK_Bk7bvE1lT93NwKm21tMsnF5wViIVmf1aIYTaC4F4fOzHxvxD5nM32UN0fFNEah0j6_6m8byuiD-t-hZjzpTeQAdO2rtQ"}
Built JWKSet containing 1 key (kid='demo-key-1')
=== STEP 2: auth server ISSUES a token: sign claims with PRIVATE key ===
JWT header: {"alg": "RS256", "typ": "JWT", "kid": "demo-key-1"}
JWT claims: {"sub": "alice", "iss": "custom-auth-server", "iat": 1794556490, "exp": 1794560090, "scope": "read write"}
base64url(header)  = eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6ImRlbW8ta2V5LTEifQ
base64url(payload) = eyJzdWIiOiJhbGljZSIsImlzcyI6ImN1c3RvbS1hdXRoLXNlcnZlciIsImlhdCI6MTc5NDU1NjQ5MCwiZXhwIjoxNzk0NTYwMDkwLCJzY29wZSI6InJlYWQgd3JpdGUifQ
signing_input = header_b64 + '.' + payload_b64 (193 bytes)
[SIGN] SHA-256(signing_input) = 855887c6a12665f807531d5980558e4c1b4d7ebf6062939ba15a47315450c924
[SIGN] EMSA-PKCS1-v1.5 padded block (hex, truncated): 0001ffffffffffffffffffffffffffff...0304020105000420855887c6a12665f807531d5980558e4c1b4d7ebf6062939ba15a47315450c924
[SIGN] computing signature = padded_int ^ d mod n  (PRIVATE key operation)
[SIGN] signature (hex, truncated): 0x561815a2604b695dcc...d4268bdc962c8b21e5e7
base64url(signature) = VhgVomBLaV3ML16T7gSV...LO7IBs1CaL3JYsiyHl5w
Token assembled: header_b64 '.' payload_b64 '.' signature_b64
Final JWT: eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6ImRlbW8ta2V5LTEifQ.eyJzdWIiOiJhbGljZSIsImlzcyI6ImN1c3RvbS1hdXRoLXNlcnZlciIsImlhdCI6MTc5NDU1NjQ5MCwiZXhwIjoxNzk0NTYwMDkwLCJzY29wZSI6InJlYWQgd3JpdGUifQ.VhgVomBLaV3ML16T7gSVPx-BOfpzlG_XkwLxNpGhVYjxMqjGz9XkqgLq_GYfg67w0SRkuL7Ix_-ewsIN7OpdlCBLRRA9Umgj5UMEPRyaXWfdgll_9IEr3c8nFbXwAOuFyAaG6Z3OiKeS3lYVMe1JnHJ2Zk_UvCNk_6sjk_Y08_e4vsw-pQ0_ITwCJOOmX4RZIFdSEEmfG5XHDn2_NCYm1hmgkIHG7q7kW2GUKSAEn1lksrBCVFPebh5VJyVM9sVVtaoYyp88HYEFdmsNWpvMnzrL9eXKioB0Cdefcmi6-eLyZjy25uNUR67F9c2yEiAHUpLO7IBs1CaL3JYsiyHl5w
=== STEP 3: resource server VERIFIES token using ONLY the public key ===
Split token into header_b64 / payload_b64 / signature_b64
Re-decoded signature (256 bytes)
[VERIFY] computing recovered = signature ^ e mod n  (PUBLIC key operation)
[VERIFY] SHA-256(signing_input) = 855887c6a12665f807531d5980558e4c1b4d7ebf6062939ba15a47315450c924
[VERIFY] EMSA-PKCS1-v1.5 padded block (hex, truncated): 0001ffffffffffffffffffffffffffff...0304020105000420855887c6a12665f807531d5980558e4c1b4d7ebf6062939ba15a47315450c924
[VERIFY] recovered == expected padded block? True
Decoded claims from payload_b64: {"sub": "alice", "iss": "custom-auth-server", "iat": 1794556490, "exp": 1794560090, "scope": "read write"}
Signature valid. Claims trusted: {"sub": "alice", "iss": "custom-auth-server", "iat": 1794556490, "exp": 1794560090, "scope": "read write"}
=== STEP 4: tamper check - flip one character in the payload ===
Attempting to verify a tampered token (same signature, modified payload)...
Split token into header_b64 / payload_b64 / signature_b64
Re-decoded signature (256 bytes)
[VERIFY] computing recovered = signature ^ e mod n  (PUBLIC key operation)
[VERIFY] SHA-256(signing_input) = 9163b988f20326a6f73cb2a5a349304a935b7da2d391174597ebf344085274ae
[VERIFY] EMSA-PKCS1-v1.5 padded block (hex, truncated): 0001ffffffffffffffffffffffffffff...03040201050004209163b988f20326a6f73cb2a5a349304a935b7da2d391174597ebf344085274ae
[VERIFY] recovered == expected padded block? False
Correctly rejected: Signature verification FAILED - token is forged or corrupted
{% endhighlight %}

</details>

---

## What I took away

1. **Signing is encryption with the roles reversed** — private key operation to sign, public key operation to verify. Same `pow(x, k, n)`, opposite key.
2. **"RS256" is RSA + a specific padding scheme (EMSA-PKCS1-v1.5) + SHA-256**, not raw RSA on a hash. The DER digest-info prefix is what pins the hash algorithm into the padding.
3. **A JWT is just base64url(header) + "." + base64url(payload) + "." + base64url(signature)**, and the signature covers the first two parts concatenated with the dot — nothing more, nothing less.
4. **`/oauth2/jwks` only ever needs to publish `n` and `e`.** `d` never leaves the auth server process. That one asymmetry — sign with the secret half, verify with the public half — is the entire trust model behind every `JwtDecoder` call a resource server makes.

Next time I look at an `AuthorizationServerConfig` bean or a `JwtDecoder` failing with "invalid signature," this is the mental model I want running underneath it instead of a library call I trust blind.
