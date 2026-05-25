---
layout: post
title: "TLS vs mTLS vs Basic — and why curl is one line but Java is 150"
date: 2026-05-22
categories: tech
---

## 1. TLS vs mTLS vs Basic — what each one actually does

**TLS (the "S" in HTTPS)**
The client opens a secure channel to the server. The server proves who it is by presenting a certificate signed by a trusted Certificate Authority (CA). The client checks it, both sides agree on an encryption key, and from then on the bytes are encrypted. Only the server's identity is verified — the server has no idea who you are yet.

**mTLS (mutual TLS)**
Same handshake, but with one extra step: the client also presents a certificate, and the server verifies it before the handshake completes. If the client cert is missing, expired, or signed by a CA the server doesn't trust, the connection is refused at the TLS layer — your HTTP request never even gets sent. This is how government / banking ERPs (KACARE etc.) gate access at the network level: no cert, no entry.

**Basic Auth**
Once the TLS channel is open, the server still doesn't know which user is calling. Basic Auth solves that at the HTTP layer: you send `Authorization: Basic base64(username:password)` in the header. Server decodes, checks credentials, returns 200 or 401. Basic Auth is only safe because TLS encrypts it — over plain HTTP, the password is effectively in clear text.

**OAuth 2 Bearer (client credentials)**
Same idea as Basic Auth but indirect. Instead of sending the password on every request, you POST your `clientId` + `clientSecret` to a token endpoint, get back a short-lived `access_token`, and send it as `Authorization: Bearer <token>` on subsequent calls. The benefit: tokens expire (so leaks have a shelf life), and they can carry scopes/roles.

**Key point: these are not alternatives. They stack:**

- TLS + Basic = HTTPS with username/password
- mTLS + Basic = client cert AND username/password
- mTLS + OAuth Bearer = client cert AND access token
- mTLS alone = the cert itself IS your identity, no header auth needed

mTLS authenticates the **machine/process**. Basic / OAuth authenticates the **user / service**. Different layers, different jobs. The integration we'll see later supports all four combinations behind one endpoint.

---

## 2. The mTLS handshake, step by step

```
Client                                       Server
  |                                            |
  |--- ClientHello (supported ciphers) ------->|
  |                                            |
  |<-- ServerHello + Server Certificate -------|
  |<-- CertificateRequest ---------------------|   <-- mTLS-specific
  |<-- ServerHelloDone ------------------------|
  |                                            |
  |--- Client Certificate -------------------->|   <-- mTLS-specific
  |--- ClientKeyExchange --------------------->|
  |--- CertificateVerify --------------------->|   <-- proves client owns the cert
  |--- Finished ------------------------------>|
  |                                            |
  |<-- Finished -------------------------------|
  |                                            |
  |======= encrypted application data ========>|
```

The two lines marked **mTLS-specific** are what turns a plain TLS handshake into mTLS:

1. **CertificateRequest** — the server says "I require a certificate to continue." This is configured on the server side (`ssl_verify_client on;` in nginx, an option on the load balancer, a setting in the API gateway). The client either presents one or the handshake aborts.

2. **CertificateVerify** — the client signs a transcript of the handshake so far with its private key. The server verifies that signature against the public key inside the client cert. This proves the client actually *owns* the private key matching the cert it presented — not just that it grabbed someone else's `.crt` file off a shared server.

If either step fails, the TCP connection is torn down at the network layer. Your HTTP request never goes out, and there's no HTTP response code — the socket just dies. That's why mTLS errors look so different from 401/403 errors.

---

## 3. Where the client cert actually comes from

mTLS is useless without a chain of trust. Concretely, when integrating with an ERP that requires mTLS:

1. **The server admin tells you which CA they trust.** Sometimes a public CA, more often a *private* CA they run themselves (KACARE for Saudi government, internal CAs for B2B integrations, the bank's CA for payment APIs).
2. **You request a client certificate from that CA.** Generate a key pair locally with `openssl`, send the CA a CSR (Certificate Signing Request), they sign it with their CA key, and hand you back a `client.crt`.
3. **You keep `client.key` secret** — this is the file whose loss equals identity theft — and present `client.crt` on every connection.
4. **The server verifies the cert was signed by their CA**, checks expiry, optionally checks revocation (CRL or OCSP), and lets the handshake proceed.

For a typical government / bank integration, steps 1–3 are a paperwork exercise that takes one to two weeks. You'll usually get a `.zip` containing `client.crt`, `client.key`, and `ca-chain.crt` (the CA bundle, in case your machine doesn't already trust their root CA).

---

## 4. The certificate file format zoo

PEM, DER, PKCS, JKS, P12 — these are all different ways of packaging the same underlying X.509 data:

| Extension | Format | Contents | Encoding |
| --- | --- | --- | --- |
| `.crt`, `.cer`, `.pem` | X.509 cert | Public cert only | Text — Base64 inside `-----BEGIN CERTIFICATE-----` |
| `.key`, `.pem` | PKCS#1 or PKCS#8 | Private key only | Text — Base64 inside `-----BEGIN ... PRIVATE KEY-----` |
| `.der` | DER | Cert or key | Binary — the raw bytes that PEM Base64-encodes |
| `.p12`, `.pfx` | PKCS#12 | Cert + key + chain in one file | Binary, password-protected |
| `.jks` | Java KeyStore | Same idea as P12 but Java-only | Binary, password-protected |

**Rule of thumb:**

- PEM = what cURL / OpenSSL / nginx / Apache eat natively.
- PKCS#12 / JKS = what Java / Windows / macOS Keychain eat natively.
- Converting between them is a one-line `openssl` or `keytool` command.

Knowing which format your vendor handed you is the difference between a 5-minute config job and a 5-hour debugging session.

---

## 5. Why `curl --cert client.crt --key client.key URL` is one line

cURL is built on top of OpenSSL. OpenSSL was designed to read PEM files (`-----BEGIN CERTIFICATE-----`, `-----BEGIN PRIVATE KEY-----`) directly off the disk. The cURL maintainers wired up the plumbing once. You hand cURL two file paths and it does everything: parses the cert, parses the key, builds the SSL context, runs the mTLS handshake, sends your request. That's it.

---

## 6. Why Java / Spring needs ~150 lines for the same thing

Spring's `RestTemplate` doesn't do TLS itself — it hands the connection off to an HTTP client (Apache HttpClient in our case), which hands TLS off to the JDK's JSSE (Java Secure Socket Extension). And JSSE is strict and old:

1. **JSSE refuses raw PEM files.** It only accepts certs and keys packaged inside a KeyStore (a binary container in PKCS12 or JKS format). So we have to read the PEM, parse it, build a KeyStore in memory, populate it.

2. **JSSE only accepts PKCS#8 private keys.** PEM keys come in two flavors:
   - `-----BEGIN PRIVATE KEY-----` → PKCS#8 ✅ accepted
   - `-----BEGIN RSA PRIVATE KEY-----` → PKCS#1 ❌ rejected

   The `wrapPkcs1RsaAsPkcs8(...)` method in our factory exists purely to add a 26-byte PKCS#8 envelope around a PKCS#1 key so JSSE will accept it. OpenSSL handles both formats natively — that's why cURL doesn't need this.

3. **You have to build the `SSLContext` by hand.** Load cert → parse key → put both into a KeyStore → feed it to a `KeyManagerFactory` → get back a `KeyManager[]` → hand it to `SSLContext.init(...)`. Five objects, each with their own ceremony.

4. **You have to wire the `SSLContext` into the HTTP client.** For Apache HttpClient 5:
   - Build an `SSLConnectionSocketFactory` from the `SSLContext`
   - Wrap it in a `PoolingHttpClientConnectionManager`
   - Build a `CloseableHttpClient`
   - Wrap that as a `HttpComponentsClientHttpRequestFactory`
   - Pass that to `new RestTemplate(...)`

   Five layers of object construction to do what cURL does with two flags.

5. **Optionally disable cert validation** (when the server uses a self-signed cert your JDK doesn't know about). cURL has `-k`. In Java you replace the default `TrustManager` with a custom `X509TrustManager` whose methods do nothing — that's the `TrustAllManager` class at the bottom of our factory.

So every line of `MtlsRestTemplateFactory` maps to one of these JSSE requirements. It's not over-engineered — JSSE is just the way it is.

---

## 7. The shortcut we could have taken (and why we didn't)

If you run this once outside the app:

```bash
openssl pkcs12 -export -in client.crt -inkey client.key -out client.p12
```

…you get a `client.p12` file Java loads in ~5 lines. We'd skip the PEM parsing, the PKCS#1 → PKCS#8 wrapping, all of it.

We didn't do that because it makes deployment depend on someone remembering to run `openssl` and copy the `.p12` to the right path. By parsing PEM directly in code, the ops side just drops `client.crt` and `client.key` on the server, sets two paths in properties, and the app handles the rest.

That's the trade: more Java code, simpler operations.

---

## 8. Debugging cheat sheet (memorize this layer-by-layer)

| Error | Layer | What it means |
| --- | --- | --- |
| `401 Unauthorized` | HTTP / app | TLS handshake succeeded. Server got your request and rejected it. Bad password, wrong scope, or expired token. |
| `403 Forbidden` | HTTP / app | Auth worked, role/permission didn't. |
| `SSLHandshakeException: PKIX path building failed` | TLS | The server's cert is signed by a CA your JDK doesn't trust. Either install the CA or set `insecureSkipTlsVerify=true`. |
| `SSLHandshakeException: bad_certificate` / `Empty client certificate chain` | mTLS | Server rejected your client cert. Wrong cert, expired, or signed by a CA the server doesn't trust. |
| `Connection reset` / closed during handshake | mTLS | Server expected a client cert and you didn't send one. |

**Rule:** if you see 401/403, the network is fine — argue with the server admin. If you see `SSLHandshakeException`, you haven't even reached the application yet — fix the certs.

### Before you blame Java — prove the certs work with `openssl s_client`

Before debugging Java, prove the certs work outside any Java code:

```bash
openssl s_client \
  -connect erp.example.gov.sa:443 \
  -cert client.crt \
  -key client.key \
  -showcerts
```

What to look for in the output:

- `Verify return code: 0 (ok)` → handshake succeeded with mTLS. Your certs are good. The bug is in Java config.
- `alert handshake failure` → server rejected your cert. Wrong cert, wrong CA, or expired.
- `Verify return code: 21 (unable to verify the first certificate)` → server cert isn't trusted by your machine. Add `-CAfile server-ca.crt`, or `-noverify` for a quick check.
- `no peer certificate available` → server didn't ask for a client cert. The endpoint isn't actually mTLS-protected, or you hit the wrong port.

This 4-line command replaces 20 minutes of squinting at Java stack traces. Run it first, every time.

---

## 9. Using the factory in a real Spring service

The factory in isolation just produces a `RestTemplate`. The interesting part is how it slots into a service that has to handle *all* the auth combinations — no auth, Basic, OAuth client-credentials — with mTLS optional on top.

In our integration, each request to the endpoint carries its *own* cert paths, credentials, and target URL (the same Spring app serves many tenants, each with their own ERP). So we build the `RestTemplate` **per request**, not as a `@Bean`. The controller is intentionally thin:

```java
@PostMapping("/fetch-employee-data")
public ResponseEntity<Object> fetchEmployeeData(@RequestBody ERPEmployeeFetchRequestV2 request) {
    log.info("FETCHING ERP EMPLOYEE DATA -- orgId={}, authType={}, useMtls={}",
            request.getOrgId(), request.getAuthType(), request.isUseMtls());
    Object data = erpEmployeeReaderServiceV2.fetchEmployeeData(request);
    return AppResponse.success("Employee data fetched successfully", HttpStatus.OK, data);
}
```

The service picks the transport based on `useMtls`:

```java
private RestTemplate buildRestTemplate(ERPEmployeeFetchRequestV2 request) {
    if (request.isUseMtls()) {
        boolean skipVerify = request.getInsecureSkipTlsVerify() == null
                          || request.getInsecureSkipTlsVerify();
        return mtlsRestTemplateFactory.build(
                request.getCertPath(), request.getKeyPath(), skipVerify,
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
    factory.setReadTimeout(READ_TIMEOUT_MS);
    return new RestTemplate(factory);
}
```

Then the HTTP-layer auth is layered on top — and notice this is **completely independent of whether mTLS is on**:

```java
switch (authType) {
    case BASIC:
        headers.setBasicAuth(request.getUsername(), request.getPassword());
        break;
    case OAUTH_CLIENT_CREDENTIALS:
        headers.setBearerAuth(accessToken);
        break;
    case NONE:
    default:
        break;
}
```

The same `RestTemplate` carries the Basic header / Bearer token over whatever transport (mTLS or plain TLS) the factory built. That's exactly the "auth stacks" idea from section 1 — the cert authenticates the machine, the header authenticates the user.

The OAuth flow itself is the standard `client_credentials` grant, with a small wrinkle: some legacy servers want the grant as a `POST` form body, others want it as a `GET` query string. The service handles both:

```java
HttpHeaders headers = new HttpHeaders();
headers.setBasicAuth(request.getClientId(), request.getClientSecret());
headers.setAccept(List.of(MediaType.APPLICATION_JSON));

if (method == HttpMethod.GET) {
    String separator = url.contains("?") ? "&" : "?";
    url = url + separator + "grant_type=client_credentials";
    entity = new HttpEntity<>(headers);
} else {
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
    body.add("grant_type", "client_credentials");
    entity = new HttpEntity<>(body, headers);
}
```

The end result: one endpoint, one factory, four working auth combinations (`{none, Basic, OAuth} x {mTLS on/off}`). The full service is in Appendix B at the bottom.

---

## 10. Production checklist

mTLS rarely fails in dev. It fails 11 months into prod when something silently expires. Things to wire up before going live:

1. **Cert expiry monitoring.** Pipe `openssl x509 -in client.crt -enddate -noout` into Prometheus / your monitoring tool. Page someone 30 days before expiry.
2. **Rotation procedure documented.** Where does the new cert come from? Who's the CA contact? How is the secret rotated without downtime? Write it down *before* you need it.
3. **`insecureSkipTlsVerify: false` in prod.** Use Spring profiles so the dev value can't leak. Our service defaults `skipVerify` to true when the flag is null — convenient for onboarding new tenants, but every prod tenant's config must set this explicitly to `false`.
4. **Secrets management.** `client.key` is a credential. Treat it like a database password — Vault, AWS Secrets Manager, sealed Kubernetes secrets. Never bake it into the Docker image, never commit it to git.
5. **Server cert chain trust.** If the ERP's server cert is signed by a private CA, that CA's root needs to be in the JVM truststore (`$JAVA_HOME/lib/security/cacerts`) or imported via:

   ```bash
   keytool -import -trustcacerts -alias erpca \
     -file erp-root-ca.crt \
     -keystore $JAVA_HOME/lib/security/cacerts
   ```

   Otherwise you're stuck with `insecureSkipTlsVerify=true`, which is fine for staging and a disaster in prod.

---

## 11. Memorable summary

> TLS wraps the wire. mTLS also checks who you are at the door. Basic / OAuth Bearer is the username/password (or token) on the inside.
>
> cURL is short because OpenSSL eats PEM. Java is long because JSSE only eats keystores, and our factory's job is to translate PEM → keystore at runtime.

---

## 12. What to learn next (related topics)

mTLS is one piece of a much larger picture. Roughly in order of how often you'll bump into them as a backend dev:

1. **OAuth 2 grants beyond client-credentials** — `authorization_code` (web apps with user login), `refresh_token`, PKCE for mobile/SPA. Once you have client-credentials working, the others are 80% the same wiring.
2. **OIDC (OpenID Connect)** — the layer on top of OAuth that adds a standard `id_token` (a JWT) describing *who* the user is. This is what powers "Sign in with Google".
3. **JWT internals** — header, payload, signature; how to validate one without calling the issuer; key rotation via JWKS. If you've done OAuth client-credentials, you've already received a JWT — opening it up is the next step.
4. **TLS internals** — cipher suites, perfect forward secrecy, TLS 1.2 vs 1.3 handshakes (1.3 combines steps and is faster). Reading the TLS 1.3 RFC once is worth a day of your career.
5. **PKI in depth** — root CAs, intermediate CAs, certificate chains, CRL vs OCSP for revocation, certificate pinning. Why pinning can save you from a rogue CA but also brick your app overnight.
6. **HMAC and signed requests** — how AWS SigV4 and many bank APIs work. No certs, no tokens — you sign each request with a shared secret. Different threat model from mTLS, similar problem solved.
7. **mTLS at the service-mesh layer** — Istio, Linkerd, Consul Connect. In Kubernetes, mTLS *between* microservices is usually done by a sidecar proxy, not by your code. Your application stays plain HTTP and the mesh wraps the wire.
8. **ACME / Let's Encrypt automation** — how public TLS certs are issued and renewed without humans. The same primitives can run a private mTLS CA (`step-ca`, `smallstep`).
9. **SSH key auth** — same asymmetric-crypto idea (public/private key pair), different protocol. Understanding SSH makes mTLS click instantly.
10. **HSMs and YubiKeys** — for high-assurance setups, the client private key never leaves a hardware chip. Signing happens inside the device, so even root on the host can't exfiltrate the key.

Each of these deserves its own post — but coming back to this one, you should now have a working mental model for the whole left half of that list.

---

## Appendix A — `MtlsRestTemplateFactory` in full

<details markdown="0">
<summary>Click to view the complete factory (Java, no BouncyCastle)</summary>

{% highlight java %}
package com.spring.project.UTILS;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;

/**
 * Builds a {@link RestTemplate} that performs mTLS using a PEM certificate + private key
 * loaded from the filesystem. PEM ({@code -----BEGIN CERTIFICATE-----}) and either PKCS#8 or
 * PKCS#1 RSA PEM ({@code -----BEGIN PRIVATE KEY-----} or {@code -----BEGIN RSA PRIVATE KEY-----})
 * private keys are supported. No BouncyCastle.
 */
@Component
@Slf4j
public class MtlsRestTemplateFactory {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 60_000;

    public RestTemplate build(String certPath, String keyPath, boolean insecureSkipTlsVerify) {
        return build(certPath, keyPath, insecureSkipTlsVerify, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    public RestTemplate build(String certPath, String keyPath, boolean insecureSkipTlsVerify,
                              int connectTimeoutMs, int readTimeoutMs) {
        if (isBlank(certPath) || isBlank(keyPath)) {
            throw new IllegalStateException("mTLS not configured: cert and key paths are required");
        }
        try {
            SSLContext sslContext = buildSslContext(Path.of(certPath), Path.of(keyPath), insecureSkipTlsVerify);

            SSLConnectionSocketFactoryBuilder builder =
                    SSLConnectionSocketFactoryBuilder.create().setSslContext(sslContext);
            if (insecureSkipTlsVerify) {
                builder.setHostnameVerifier(NoopHostnameVerifier.INSTANCE);
            }
            SSLConnectionSocketFactory sslSocketFactory = builder.build();

            ConnectionConfig connectionConfig = ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                    .build();

            PoolingHttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                    .setSSLSocketFactory(sslSocketFactory)
                    .setDefaultConnectionConfig(connectionConfig)
                    .build();

            RequestConfig requestConfig = RequestConfig.custom()
                    .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                    .build();

            CloseableHttpClient httpClient = HttpClients.custom()
                    .setConnectionManager(cm)
                    .setDefaultRequestConfig(requestConfig)
                    .build();

            log.info("mTLS RestTemplate built (cert={}, insecureSkipTlsVerify={})", certPath, insecureSkipTlsVerify);
            return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build mTLS RestTemplate: " + ex.getMessage(), ex);
        }
    }

    private SSLContext buildSslContext(Path certFile, Path keyFile, boolean insecureSkipTlsVerify) throws Exception {
        if (!Files.exists(certFile)) {
            throw new IllegalStateException("Certificate file not found: " + certFile);
        }
        if (!Files.exists(keyFile)) {
            throw new IllegalStateException("Private key file not found: " + keyFile);
        }

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs;
        try (InputStream in = Files.newInputStream(certFile)) {
            certs = cf.generateCertificates(in);
        }
        if (certs.isEmpty()) {
            throw new IllegalStateException("No certificates found in: " + certFile);
        }

        PrivateKey privateKey = parsePrivateKey(Files.readString(keyFile));

        char[] dummyPassword = "changeit".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("client", privateKey, dummyPassword, certs.toArray(new Certificate[0]));

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, dummyPassword);

        TrustManager[] trustManagers = insecureSkipTlsVerify ? new TrustManager[]{ new TrustAllManager() } : null;

        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), trustManagers, new SecureRandom());
        return ssl;
    }

    private PrivateKey parsePrivateKey(String pem) throws Exception {
        String normalized = pem.replace("\r", "").trim();

        if (normalized.contains("-----BEGIN RSA PRIVATE KEY-----")) {
            byte[] pkcs1Der = decodePem(normalized,
                    "-----BEGIN RSA PRIVATE KEY-----",
                    "-----END RSA PRIVATE KEY-----");
            byte[] pkcs8Der = wrapPkcs1RsaAsPkcs8(pkcs1Der);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8Der));
        }

        if (normalized.contains("-----BEGIN EC PRIVATE KEY-----")) {
            throw new IllegalStateException(
                    "EC private key is in SEC1 format; conversion to PKCS#8 is not supported in code yet.");
        }

        byte[] keyBytes = decodePem(normalized,
                "-----BEGIN PRIVATE KEY-----",
                "-----END PRIVATE KEY-----");
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception rsaEx) {
            return KeyFactory.getInstance("EC").generatePrivate(spec);
        }
    }

    private byte[] decodePem(String pem, String beginMarker, String endMarker) {
        String base64 = pem
                .replace(beginMarker, "")
                .replace(endMarker, "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(base64);
    }

    private byte[] wrapPkcs1RsaAsPkcs8(byte[] pkcs1) {
        // PKCS#8 PrivateKeyInfo envelope around a PKCS#1 RSAPrivateKey:
        //   SEQUENCE { INTEGER 0, SEQUENCE { OID rsaEncryption, NULL }, OCTET STRING <pkcs1> }
        byte[] prefix = new byte[] {
                0x30, (byte) 0x82, 0, 0,
                0x02, 0x01, 0x00,
                0x30, 0x0d,
                0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
                0x05, 0x00,
                0x04, (byte) 0x82, 0, 0
        };
        int outerContentLen = (prefix.length - 4) + pkcs1.length;
        prefix[2] = (byte) ((outerContentLen >> 8) & 0xff);
        prefix[3] = (byte) (outerContentLen & 0xff);
        prefix[prefix.length - 2] = (byte) ((pkcs1.length >> 8) & 0xff);
        prefix[prefix.length - 1] = (byte) (pkcs1.length & 0xff);

        byte[] out = new byte[prefix.length + pkcs1.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(pkcs1, 0, out, prefix.length, pkcs1.length);
        return out;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class TrustAllManager implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
{% endhighlight %}

</details>

---

## Appendix B — `ERPEmployeeReaderServiceV2Impl` in full

<details markdown="0">
<summary>Click to view the complete service (controller + interface + impl)</summary>

{% highlight java %}
// ----- Controller -----
@PostMapping("/fetch-employee-data")
public ResponseEntity<Object> fetchEmployeeData(@RequestBody ERPEmployeeFetchRequestV2 request) {
    log.info("FETCHING ERP EMPLOYEE DATA -- orgId={}, authType={}, useMtls={}",
            request.getOrgId(), request.getAuthType(), request.isUseMtls());
    Object data = erpEmployeeReaderServiceV2.fetchEmployeeData(request);
    return AppResponse.success("Employee data fetched successfully", HttpStatus.OK, data);
}

// ----- Interface -----
package com.spring.project.ERPIntegrationV2.service;

import com.spring.project.ERPIntegrationV2.dto.ERPEmployeeFetchRequestV2;

public interface ERPEmployeeReaderServiceV2 {
    Object fetchEmployeeData(ERPEmployeeFetchRequestV2 request);
}

// ----- Impl -----
package com.spring.project.ERPIntegrationV2.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spring.project.ERP.payload.response.ERPOAuthTokenResponse;
import com.spring.project.ERPIntegrationV2.dto.ERPEmployeeFetchRequestV2;
import com.spring.project.ERPIntegrationV2.enums.AuthType;
import com.spring.project.ERPIntegrationV2.service.ERPEmployeeReaderServiceV2;
import com.spring.project.UTILS.MtlsRestTemplateFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ERPEmployeeReaderServiceV2Impl implements ERPEmployeeReaderServiceV2 {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private final MtlsRestTemplateFactory mtlsRestTemplateFactory;

    @Override
    public Object fetchEmployeeData(ERPEmployeeFetchRequestV2 request) {
        validate(request);
        AuthType authType = request.getAuthType() == null ? AuthType.NONE : request.getAuthType();
        RestTemplate restTemplate = buildRestTemplate(request);

        String accessToken = null;
        if (authType == AuthType.OAUTH_CLIENT_CREDENTIALS) {
            accessToken = requestAccessToken(restTemplate, request);
        }
        JsonNode body = requestEmployeeData(restTemplate, request, authType, accessToken);
        return applyKeyMapping(body, request.getKeyMapping());
    }

    private JsonNode applyKeyMapping(JsonNode node, Map<String, String> mapping) {
        if (node == null || mapping == null || mapping.isEmpty()) return node;
        return renameKeys(node, mapping);
    }

    private JsonNode renameKeys(JsonNode node, Map<String, String> mapping) {
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode el : node) {
                out.add(renameKeys(el, mapping));
            }
            return out;
        }
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            // 1. Emit fields in keyMapping order
            for (Map.Entry<String, String> m : mapping.entrySet()) {
                JsonNode value = node.get(m.getKey());
                if (value != null) {
                    out.set(m.getValue(), renameKeys(value, mapping));
                }
            }
            // 2. Append unmapped source fields (preserve their original order)
            node.fields().forEachRemaining(entry -> {
                if (!mapping.containsKey(entry.getKey())) {
                    out.set(entry.getKey(), renameKeys(entry.getValue(), mapping));
                }
            });
            return out;
        }
        return node;
    }

    private void validate(ERPEmployeeFetchRequestV2 request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (isBlank(request.getEmployeeUrl())) {
            throw new IllegalArgumentException("employeeUrl is required");
        }
        AuthType authType = request.getAuthType() == null ? AuthType.NONE : request.getAuthType();
        switch (authType) {
            case BASIC:
                if (isBlank(request.getUsername()) || isBlank(request.getPassword())) {
                    throw new IllegalArgumentException("username and password are required for BASIC auth");
                }
                break;
            case OAUTH_CLIENT_CREDENTIALS:
                if (isBlank(request.getClientId()) || isBlank(request.getClientSecret())) {
                    throw new IllegalArgumentException("clientId and clientSecret are required for OAUTH_CLIENT_CREDENTIALS");
                }
                if (isBlank(request.getTokenUrl())) {
                    throw new IllegalArgumentException("tokenUrl is required for OAUTH_CLIENT_CREDENTIALS");
                }
                break;
            case NONE:
            default:
                break;
        }
        if (request.isUseMtls()) {
            if (isBlank(request.getCertPath()) || isBlank(request.getKeyPath())) {
                throw new IllegalArgumentException("certPath and keyPath are required when useMtls=true");
            }
        }
    }

    private RestTemplate buildRestTemplate(ERPEmployeeFetchRequestV2 request) {
        if (request.isUseMtls()) {
            boolean skipVerify = request.getInsecureSkipTlsVerify() == null || request.getInsecureSkipTlsVerify();
            return mtlsRestTemplateFactory.build(request.getCertPath(), request.getKeyPath(), skipVerify,
                    CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    private String requestAccessToken(RestTemplate restTemplate, ERPEmployeeFetchRequestV2 request) {
        HttpMethod method = resolveMethod(request.getTokenMethod(), HttpMethod.POST);

        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(request.getClientId(), request.getClientSecret());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<?> entity;
        String url = request.getTokenUrl();
        if (method == HttpMethod.GET) {
            String separator = url.contains("?") ? "&" : "?";
            url = url + separator + "grant_type=client_credentials";
            entity = new HttpEntity<>(headers);
        } else {
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "client_credentials");
            entity = new HttpEntity<>(body, headers);
        }

        log.info("Requesting access token via {} {}", method, url);
        ResponseEntity<ERPOAuthTokenResponse> response = restTemplate.exchange(
                url, method, entity, ERPOAuthTokenResponse.class);

        ERPOAuthTokenResponse body = response.getBody();
        if (body == null || isBlank(body.getAccessToken())) {
            throw new IllegalStateException("Access token not returned by token endpoint");
        }
        log.info("Access token received (type={}, expiresIn={})", body.getTokenType(), body.getExpiresIn());
        return body.getAccessToken();
    }

    private JsonNode requestEmployeeData(RestTemplate restTemplate, ERPEmployeeFetchRequestV2 request,
                                         AuthType authType, String accessToken) {
        HttpMethod method = resolveMethod(request.getEmployeeMethod(), HttpMethod.GET);

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        switch (authType) {
            case BASIC:
                headers.setBasicAuth(request.getUsername(), request.getPassword());
                break;
            case OAUTH_CLIENT_CREDENTIALS:
                headers.setBearerAuth(accessToken);
                break;
            case NONE:
            default:
                break;
        }

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        log.info("Requesting employee data via {} {} (auth={}, mTLS={})",
                method, request.getEmployeeUrl(), authType, request.isUseMtls());
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                request.getEmployeeUrl(), method, entity, JsonNode.class);
        log.info("Employee response status: {}", response.getStatusCode());
        return response.getBody();
    }

    private HttpMethod resolveMethod(String method, HttpMethod fallback) {
        if (isBlank(method)) return fallback;
        try {
            return HttpMethod.valueOf(method.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
{% endhighlight %}

</details>
