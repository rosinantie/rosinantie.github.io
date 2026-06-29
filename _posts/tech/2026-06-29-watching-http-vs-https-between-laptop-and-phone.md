---
layout: post
title: "Watching HTTP and HTTPS happen between my laptop and my phone"
date: 2026-06-29
categories: tech
---

In the [ARP spoofing lab]({% post_url tech/2026-06-29-isolated-docker-network-arp-spoofing-lab %}) I watched one device lie to another about who it was, inside a sealed Docker network. This time I wanted the opposite of sealed — a real connection between two real devices on my actual WiFi — and I wanted to watch every byte of it in Wireshark. Then do it again over HTTPS and *see* the difference encryption makes.

The setup is almost embarrassingly simple: serve a file from my laptop, open it on my phone, and capture the conversation in between. But that one round trip contains the entire stack — ARP to find the MAC, TCP to open the pipe, HTTP to move the data, FIN to hang up. And the HTTPS version shows exactly what the encryption hides.

---

## 1. The one-line server

On the laptop, make a page and serve it:

```bash
echo "hello from my laptop" > index.html
python3 -m http.server 8000
```

That's a full HTTP server on port 8000. Find the laptop's LAN IP (`ipconfig getifaddr en0` on macOS) and open it from the phone's browser:

```
http://<your-laptop-IP>:8000
```

The server log immediately shows the phone knocking:

```
Serving HTTP on :: port 8000 (http://[::]:8000/) ...
::ffff:192.168.1.51 - - [29/Jun/2026 18:41:06] "GET / HTTP/1.1" 200 -
::ffff:192.168.1.51 - - [29/Jun/2026 18:41:06] "GET /favicon.ico HTTP/1.1" 404 -
::ffff:192.168.1.51 - - [29/Jun/2026 18:41:24] "GET / HTTP/1.1" 304 -
```

`192.168.1.51` is the phone. The `200` is the page, the `404` is the browser auto-asking for a favicon that doesn't exist, and the `304 Not Modified` on the reload means the browser cached the page and the server told it "nothing changed, reuse your copy." Keep that `304` in mind — it shows up again in the capture.

---

## 2. Capturing the plain-HTTP conversation

Clear Wireshark, start a fresh capture, reload the page on the phone, then stop. Filter to just this device:

```
ip.addr == 192.168.1.51
```

Here's why you can even *see* this traffic: `.51` (phone) is talking to `.34` (laptop). It's to and from your own machine, so the switch delivers those frames to you — the same Layer-2 rule the ARP post leaned on. You're not snooping on anyone; you're watching your own two devices.

Here's the full capture as Wireshark listed it:

```
No.   Time        Source         Destination    Proto  Len  Info
3201  2.981526    192.168.1.51   192.168.1.34   TCP    74   36806 → 8000 [SYN] Seq=0 Win=65535 Len=0 MSS=1460 SACK_PERM TSval=2430649739 TSecr=0 WS=256
3202  2.981718    192.168.1.34   192.168.1.51   TCP    78   8000 → 36806 [SYN, ACK] Seq=0 Ack=1 Win=65535 Len=0 MSS=1460 WS=64 TSval=444530097 TSecr=2430649739 SACK_PERM
3203  2.988713    192.168.1.51   192.168.1.34   TCP    66   36806 → 8000 [ACK] Seq=1 Ack=1 Win=65536 Len=0 TSval=2430649745 TSecr=444530097
3204  2.988717    192.168.1.51   192.168.1.34   HTTP   569  GET / HTTP/1.1
3205  2.988925    192.168.1.34   192.168.1.51   TCP    66   8000 → 36806 [ACK] Seq=1 Ack=504 Win=131328 Len=0 TSval=444530104 TSecr=2430649745
3206  3.003988    192.168.1.34   192.168.1.51   HTTP   170  HTTP/1.0 304 Not Modified
3207  3.004134    192.168.1.34   192.168.1.51   TCP    66   8000 → 36806 [FIN, ACK] Seq=105 Ack=504 Win=131328 Len=0 TSval=444530119 TSecr=2430649745
3208  3.011965    192.168.1.51   192.168.1.34   TCP    66   36806 → 8000 [ACK] Seq=504 Ack=105 Win=65536 Len=0 TSval=2430649769 TSecr=444530119
3209  3.011967    192.168.1.51   192.168.1.34   TCP    66   36806 → 8000 [FIN, ACK] Seq=504 Ack=106 Win=65536 Len=0 TSval=2430649770 TSecr=444530119
3210  3.012056    192.168.1.34   192.168.1.51   TCP    66   8000 → 36806 [ACK] Seq=106 Ack=505 Win=131328 Len=0 TSval=444530127 TSecr=2430649770
3251  3.768488    192.168.1.51   192.168.1.34   TCP    74   36790 → 8000 [SYN] Seq=0 Win=65535 Len=0 MSS=1460 SACK_PERM TSval=2430650511 TSecr=0 WS=256
3253  3.768737    192.168.1.34   192.168.1.51   TCP    78   8000 → 36790 [SYN, ACK] Seq=0 Ack=1 Win=65535 Len=0 MSS=1460 WS=64 TSval=1322402638 TSecr=2430650511 SACK_PERM
3255  3.903635    192.168.1.51   192.168.1.34   TCP    66   36790 → 8000 [ACK] Seq=1 Ack=1 Win=65536 Len=0 TSval=2430650533 TSecr=1322402638
3259  3.903638    192.168.1.51   192.168.1.34   TCP    74   47148 → 8443 [SYN] Seq=0 Win=65535 Len=0 MSS=1460 SACK_PERM TSval=2430650543 TSecr=0 WS=256
3260  3.903638    192.168.1.51   192.168.1.34   TCP    74   36794 → 8000 [SYN] Seq=0 Win=65535 Len=0 MSS=1460 SACK_PERM TSval=2430650543 TSecr=0 WS=256
3261  3.903716    192.168.1.34   192.168.1.51   TCP    66   [TCP Window Update] 8000 → 36790 [ACK] Seq=1 Ack=1 Win=131776 Len=0 TSval=1322402773 TSecr=2430650533
3262  3.903753    192.168.1.34   192.168.1.51   TCP    54   8443 → 47148 [RST, ACK] Seq=1 Ack=1 Win=0 Len=0
3263  3.903886    192.168.1.34   192.168.1.51   TCP    78   8000 → 36794 [SYN, ACK] Seq=0 Ack=1 Win=65535 Len=0 MSS=1460 WS=64 TSval=2953406450 TSecr=2430650543 SACK_PERM
3264  4.072157    192.168.1.51   192.168.1.34   TCP    66   36794 → 8000 [ACK] Seq=1 Ack=1 Win=65536 Len=0 TSval=2430650669 TSecr=2953406450
3265  4.072248    192.168.1.34   192.168.1.51   TCP    66   [TCP Window Update] 8000 → 36794 [ACK] Seq=1 Ack=1 Win=131776 Len=0 TSval=2953406618 TSecr=2430650669
```

A couple of things worth pointing out before the decode: packet `3262` is a `[RST, ACK]` on port `8443` — that's the phone optimistically trying the HTTPS port before I'd started that server, so the laptop slams it shut with a reset. And the `[TCP Window Update]` packets are just the receiver announcing it has more buffer room now. The clean HTTP conversation is the `36806 → 8000` thread (`3201`–`3210`). Decoded packet by packet:

**The TCP three-way handshake — opening the pipe**

```
.51 → .34   [SYN]        phone:  "let's talk, my seq=0"
.34 → .51   [SYN, ACK]   laptop: "I hear you, my seq=0, ack your 1"
.51 → .34   [ACK]        phone:  "great, connected"
```

`SYN → SYN,ACK → ACK` is the handshake *every* TCP connection on the internet begins with. You just watched it happen between two things in your own room.

**The HTTP exchange — the actual data**

```
.51 → .34   HTTP  GET / HTTP/1.1            phone requests the page
.34 → .51   HTTP  HTTP/1.0 304 Not Modified laptop: "use your cache"
```

`304` instead of `200` because the phone already had the page — same reason it appeared in the Python log.

**The teardown — closing the pipe**

```
[FIN, ACK]   "I'm done sending"
[ACK]        "acknowledged"
```

`FIN` is a graceful close; both sides agree to hang up.

### Reading the fields

| Field | Meaning |
|-------|---------|
| `36806 → 8000` | phone's random source port → server's port 8000 |
| `[SYN] [ACK] [FIN]` | TCP control flags — open / acknowledge / close |
| `Seq` / `Ack` | byte counters; how TCP guarantees nothing is lost or out of order |
| `Win=65535` | receive window — how much data it'll accept before pausing |
| `MSS=1460` | max segment size — biggest chunk per packet |
| `Len=0` | pure control packet, no payload |
| `TSval` / `TSecr` | timestamps, used to measure round-trip time |

Put this next to the earlier ARP capture and you've seen the full stack of a LAN conversation:

1. **ARP** — "who has `192.168.1.34`?" → find the MAC *(Layer 2)*
2. **TCP SYN** — handshake → open a pipe *(Layer 4)*
3. **HTTP GET** — request / response → exchange data *(Layer 7)*
4. **TCP FIN** — teardown → close the pipe

That's literally what happens every time any device loads any page. You're just watching it in slow motion.

### See the message itself

Right-click the `GET` packet → **Follow → HTTP Stream**. Because it's plain HTTP, you see the raw request and the page's HTML **in cleartext** — a perfect demonstration of why unencrypted HTTP exposes everything.

---

## 3. Now do it over HTTPS

To see what encryption changes, serve the same thing over TLS. First make a self-signed certificate:

```bash
openssl req -new -x509 -keyout key.pem -out cert.pem -days 365 -nodes -subj "/CN=mylaptop"
```

Then start an HTTPS server on `8443` that wraps the same handler in a TLS socket:

```bash
python3 -c "import http.server, ssl; \
ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); \
ctx.load_cert_chain('cert.pem','key.pem'); \
s=http.server.HTTPServer(('0.0.0.0',8443), http.server.SimpleHTTPRequestHandler); \
s.socket=ctx.wrap_socket(s.socket, server_side=True); \
print('Serving HTTPS on :8443'); s.serve_forever()"
```

Optionally watch the raw frames from a second terminal at the same time:

```bash
sudo tcpdump -i en0 -n host 192.168.1.51   # the phone's IP
```

Open `https://<laptop-IP>:8443` on the phone, capture in Wireshark with the same `ip.addr == 192.168.1.51` filter, and stop.

---

## 4. Reading the TLS 1.3 handshake

This is the genuine article — the same protocol protecting a bank login. Here's the full capture:

```
No.    Time       Source         Destination    Proto    Len   Info
10494  16.616277  192.168.1.51   192.168.1.34   TCP      74    51166 → 8443 [SYN] Seq=0 Win=65535 Len=0 MSS=1460 SACK_PERM TSval=2430028672 TSecr=0 WS=256
10495  16.616499  192.168.1.34   192.168.1.51   TCP      78    8443 → 51166 [SYN, ACK] Seq=0 Ack=1 Win=65535 Len=0 MSS=1460 WS=64 TSval=3552164977 TSecr=2430028672 SACK_PERM
10496  16.616868  192.168.1.51   192.168.1.34   TCP      74    51168 → 8443 [SYN] Seq=0 Win=65535 Len=0 MSS=1460 SACK_PERM TSval=2430028729 TSecr=0 WS=256
10497  16.617003  192.168.1.34   192.168.1.51   TCP      78    8443 → 51168 [SYN, ACK] Seq=0 Ack=1 Win=65535 Len=0 MSS=1460 WS=64 TSval=1805661351 TSecr=2430028729 SACK_PERM
10498  16.623195  192.168.1.51   192.168.1.34   TCP      66    51166 → 8443 [ACK] Seq=1 Ack=1 Win=65536 Len=0 TSval=2430028853 TSecr=3552164977
10499  16.623303  192.168.1.34   192.168.1.51   TCP      66    [TCP Window Update] 8443 → 51166 [ACK] Seq=1 Ack=1 Win=131776 Len=0 TSval=3552164984 TSecr=2430028853
10500  16.624098  192.168.1.51   192.168.1.34   TCP      66    51168 → 8443 [ACK] Seq=1 Ack=1 Win=65536 Len=0 TSval=2430028853 TSecr=1805661351
10501  16.624166  192.168.1.34   192.168.1.51   TCP      66    [TCP Window Update] 8443 → 51168 [ACK] Seq=1 Ack=1 Win=131776 Len=0 TSval=1805661358 TSecr=2430028853
10502  16.638297  192.168.1.51   192.168.1.34   TCP      1514  51166 → 8443 [ACK] Seq=1 Ack=1 Win=65536 Len=1448 TSval=2430028856 TSecr=3552164977 [TCP PDU reassembled in 10504]
10503  16.638299  192.168.1.51   192.168.1.34   TCP      1514  51168 → 8443 [ACK] Seq=1 Ack=1 Win=65536 Len=1448 TSval=2430028858 TSecr=1805661351 [TCP PDU reassembled in 10505]
10504  16.638300  192.168.1.51   192.168.1.34   TLSv1.3  622   Client Hello
10505  16.638300  192.168.1.51   192.168.1.34   TLSv1.3  622   Client Hello
10506  16.638378  192.168.1.34   192.168.1.51   TCP      66    8443 → 51166 [ACK] Seq=1 Ack=2005 Win=129792 Len=0 TSval=3552164999 TSecr=2430028856
10507  16.638425  192.168.1.34   192.168.1.51   TCP      66    8443 → 51168 [ACK] Seq=1 Ack=2005 Win=129792 Len=0 TSval=1805661372 TSecr=2430028858
10508  16.639340  192.168.1.34   192.168.1.51   TLSv1.3  1395  Server Hello, Change Cipher Spec, Application Data, Application Data
10509  16.649931  192.168.1.51   192.168.1.34   TCP      66    51166 → 8443 [ACK] Seq=2005 Ack=1330 Win=68352 Len=0 TSval=2430028880 TSecr=3552165000
10510  16.670662  192.168.1.51   192.168.1.34   TLSv1.3  96    Change Cipher Spec, Application Data
10511  16.670765  192.168.1.34   192.168.1.51   TCP      66    8443 → 51166 [ACK] Seq=1330 Ack=2035 Win=131072 Len=0 TSval=3552165032 TSecr=2430028885
10512  16.671164  192.168.1.34   192.168.1.51   TCP      66    8443 → 51166 [FIN, ACK] Seq=1330 Ack=2035 Win=131072 Len=0 TSval=3552165032 TSecr=2430028885
10513  16.671306  192.168.1.51   192.168.1.34   TCP      66    51166 → 8443 [FIN, ACK] Seq=2035 Ack=1330 Win=68352 Len=0 TSval=2430028889 TSecr=3552165000
10514  16.671396  192.168.1.34   192.168.1.51   TCP      66    [TCP Retransmission] 8443 → 51166 [FIN, ACK] Seq=1330 Ack=2036 Win=131072 Len=0 TSval=3552165032 TSecr=2430028889
10515  16.672779  192.168.1.34   192.168.1.51   TLSv1.3  1395  Server Hello, Change Cipher Spec, Application Data, Application Data
10516  16.679712  192.168.1.51   192.168.1.34   TCP      66    51166 → 8443 [ACK] Seq=2036 Ack=1331 Win=68352 Len=0 TSval=2430028907 TSecr=3552165032
10517  16.732257  192.168.1.51   192.168.1.34   TCP      66    51168 → 8443 [ACK] Seq=2005 Ack=1330 Win=68352 Len=0 TSval=2430028913 TSecr=1805661407
10518  16.732258  192.168.1.51   192.168.1.34   TLSv1.3  96    Change Cipher Spec, Application Data
10519  16.732259  192.168.1.51   192.168.1.34   TCP      66    51168 → 8443 [FIN, ACK] Seq=2035 Ack=1330 Win=68352 Len=0 TSval=2430028914 TSecr=1805661407
10520  16.732354  192.168.1.34   192.168.1.51   TCP      66    8443 → 51168 [ACK] Seq=1330 Ack=2035 Win=131072 Len=0 TSval=1805661466 TSecr=2430028913
10521  16.732388  192.168.1.34   192.168.1.51   TCP      66    8443 → 51168 [ACK] Seq=1330 Ack=2036 Win=131072 Len=0 TSval=1805661466 TSecr=2430028914
10522  16.732593  192.168.1.34   192.168.1.51   TCP      66    8443 → 51168 [FIN, ACK] Seq=1330 Ack=2036 Win=131072 Len=0 TSval=1805661466 TSecr=2430028914
10523  16.732956  192.168.1.51   192.168.1.34   TCP      74    51172 → 8443 [SYN] Seq=0 Win=65535 Len=0 MSS=1460 SACK_PERM TSval=2430028916 TSecr=0 WS=256
10524  16.733204  192.168.1.34   192.168.1.51   TCP      78    8443 → 51172 [SYN, ACK] Seq=0 Ack=1 Win=65535 Len=0 MSS=1460 WS=64 TSval=403303154 TSecr=2430028916 SACK_PERM
10525  16.743974  192.168.1.51   192.168.1.34   TCP      66    51168 → 8443 [ACK] Seq=2036 Ack=1331 Win=68352 Len=0 TSval=2430028973 TSecr=1805661466
10526  16.748826  192.168.1.51   192.168.1.34   TCP      66    51172 → 8443 [ACK] Seq=1 Ack=1 Win=65536 Len=0 TSval=2430028973 TSecr=403303154
10527  16.748927  192.168.1.34   192.168.1.51   TCP      66    [TCP Window Update] 8443 → 51172 [ACK] Seq=1 Ack=1 Win=131776 Len=0 TSval=403303170 TSecr=2430028973
10528  16.749751  192.168.1.51   192.168.1.34   TCP      1514  51172 → 8443 [ACK] Seq=1 Ack=1 Win=65536 Len=1448 TSval=2430028978 TSecr=403303154 [TCP PDU reassembled in 10529]
10529  16.749753  192.168.1.51   192.168.1.34   TLSv1.3  383   Client Hello
10530  16.749802  192.168.1.34   192.168.1.51   TCP      66    8443 → 51172 [ACK] Seq=1 Ack=1766 Win=130048 Len=0 TSval=403303171 TSecr=2430028978
10531  16.751709  192.168.1.34   192.168.1.51   TLSv1.3  1514  Server Hello, Change Cipher Spec, Application Data
10532  16.751733  192.168.1.34   192.168.1.51   TLSv1.3  1041  Application Data, Application Data, Application Data
10533  16.759968  192.168.1.51   192.168.1.34   TCP      66    51172 → 8443 [ACK] Seq=1766 Ack=2424 Win=70400 Len=0 TSval=2430028988 TSecr=403303173
10534  16.765059  192.168.1.51   192.168.1.34   TLSv1.3  146   Change Cipher Spec, Application Data
10535  16.765164  192.168.1.34   192.168.1.51   TCP      66    8443 → 51172 [ACK] Seq=2424 Ack=1846 Win=131008 Len=0 TSval=403303186 TSecr=2430028994
10536  16.765604  192.168.1.34   192.168.1.51   TLSv1.3  321   Application Data
10537  16.768947  192.168.1.51   192.168.1.34   TLSv1.3  802   Application Data
10538  16.769017  192.168.1.34   192.168.1.51   TLSv1.3  321   Application Data
10539  16.772664  192.168.1.34   192.168.1.51   TLSv1.3  317   Application Data, Application Data
10541  16.869438  192.168.1.51   192.168.1.34   TCP      66    51172 → 8443 [ACK] Seq=2582 Ack=2934 Win=76288 Len=0 TSval=2430029010 TSecr=403303186
10542  16.869440  192.168.1.51   192.168.1.34   TCP      66    51172 → 8443 [FIN, ACK] Seq=2582 Ack=3186 Win=79104 Len=0 TSval=2430029018 TSecr=403303194
10543  16.869515  192.168.1.34   192.168.1.51   TCP      66    8443 → 51172 [ACK] Seq=3186 Ack=2583 Win=131072 Len=0 TSval=403303290 TSecr=2430029018
```

TCP opens the pipe exactly as before, then TLS takes over. Stripped to the essentials of one connection it reads like this:

```
.51 → .34   SYN          ┐
.34 → .51   SYN, ACK     ├ TCP handshake (open the pipe)
.51 → .34   ACK          ┘
.51 → .34   TLSv1.3  Client Hello                        phone: ciphers + key-share
.34 → .51   TLSv1.3  Server Hello, Change Cipher Spec,   laptop: picks cipher,
                     Application Data, Application Data          sends cert (ENCRYPTED!)
.51 → .34   TLSv1.3  Change Cipher Spec, Application Data phone: "encryption on", request
.34 → .51   TLSv1.3  Application Data ...                laptop: 🔒 the web page
            FIN, ACK ...                                 teardown
```

### The TLS 1.3 thing worth noticing

Look at the Server Hello packet: *Server Hello, Change Cipher Spec, **Application Data, Application Data**.*

In the plain-HTTP capture you could read the bytes. Here the certificate is **gone** — it's labeled `Application Data`, meaning it's already encrypted.

> **TLS 1.3 encrypts the certificate.** The key exchange happens in the very first Client Hello / Server Hello, so by the time the server sends its certificate the tunnel already exists. In old TLS 1.2 the cert went in cleartext — you could read its CN, issuer, everything. TLS 1.3 hides it. That's why there's no readable "Certificate" packet anymore, only `Application Data`.

So TLS 1.3 is one round-trip faster *and* more private than the 1.2 flow. You're watching the modern version.

### Why there are several connections

The browser opened multiple parallel TCP connections (different source ports — `51166`, `51168`, `51172`) to load faster. Some started a handshake and closed early with a `FIN` — that's the browser pre-connecting, and the self-signed-cert warning interrupting it. One connection completes the full exchange: Client Hello → Server Hello → Application Data (the page) → FIN. The aborted ones are the browser retrying after you tapped through the warning. The occasional `[TCP Retransmission]` / out-of-order packets are just WiFi hiccups; TCP re-sends and nothing breaks.

### The field decoder

| You see | Meaning |
|---------|---------|
| `TLSv1.3` | protocol version negotiated — the latest |
| `Client Hello` | phone proposes ciphers + sends its key-share |
| `Server Hello` | laptop picks the cipher + sends its key-share |
| `Change Cipher Spec` | "everything after this is encrypted" (legacy marker in 1.3) |
| `Application Data` | 🔒 encrypted payload — the cert, then the actual page |
| `[TCP Retransmission]` | normal WiFi hiccup; TCP re-sends |
| `FIN, ACK` | graceful connection close |

### Prove the encryption to yourself

Right-click an `Application Data` packet → **Follow → TLS Stream**. You see encrypted gibberish — the page is in there, unreadable. Compare that to the plain-HTTP Follow Stream that showed the HTML clearly. That side-by-side *is* the whole point of HTTPS:

```
HTTP  Follow Stream → "hello from my laptop"     anyone on the path reads it
HTTPS Follow Stream → 9f a2 c8 1e ... (garbage)  encrypted, unreadable
```

---

## 5. The "Not Secure" warning is the system working

Opening the HTTPS page, the phone warns "Not Secure." That's not a failure — it's HTTPS's trust system doing its job. The connection **is** encrypted; the problem is **identity**, not encryption. Two different promises:

1. **Encryption** — nobody can read the traffic. ✅ The server does this fine (you saw the encrypted TLS data).
2. **Identity** — the server is really who it claims to be. ❌ This is what fails.

The browser is saying: *"This connection is encrypted, but I can't verify who I'm talking to."*

### Why it can't verify me

Real sites' certificates are signed by a **Certificate Authority** (Let's Encrypt, DigiCert, …), and your device ships with ~150 trusted CAs built in:

```
browser trusts → a CA (e.g. Let's Encrypt)
                  └─ which signed → the website's certificate
                                     └─ so the browser trusts the site ✅
```

My cert was **self-signed** — I signed it myself with `openssl`:

```
browser trusts → ???
                 └─ "mylaptop" signed its OWN certificate
                    → no trusted CA vouches for it → NOT trusted ❌
```

It's like showing up with an ID card you printed at home. The encryption is real, but no authority vouches that "mylaptop" is genuine — so the browser warns.

### Why this is exactly the shield against the ARP attack

This is the precise mechanism that stops the [ARP-spoofing MITM]({% post_url tech/2026-06-29-isolated-docker-network-arp-spoofing-lab %}) from working against a real site. An attacker who intercepts your connection to your bank has to present a certificate — but they can't get a real CA to sign one for `yourbank.com`. So they either:

- present a self-signed cert → browser screams "Not Secure" (you'd notice), or
- present no valid cert → the connection fails.

The warning you're seeing is the same alarm that would fire during a real attack. Feature, not bug.

### Making the warning go away

1. **Just proceed** (fine for your own test) — Advanced → Proceed. You're choosing to trust your own cert.
2. **Install the cert as trusted on the phone** — copy `cert.pem` over and add it under the phone's trusted certificates. Then "mylaptop" is a known authority → no warning. *(This is also how `mitmproxy` decrypts HTTPS — the same trick.)*
3. **Get a real CA-signed cert** — for a public domain, Let's Encrypt verifies you control the domain, signs your cert for free, and every browser trusts it automatically.

| | Self-signed server | Real website |
|---|---|---|
| Encrypted? | ✅ yes | ✅ yes |
| Identity verified by a CA? | ❌ no | ✅ yes |
| Browser warning? | ⚠️ "Not Secure" | none |

"Not Secure" here really means **"unverified identity," not "unencrypted."** The traffic is fully encrypted — the browser just can't confirm who `mylaptop` is, because I vouched for myself instead of a trusted authority.

---

## What I took away

The complete journey, end to end:

1. **Discover devices** — `arp` / `nmap` / Bonjour ✅
2. **Identify a device** — `.51` connected to my server ✅
3. **Plain connection** — TCP handshake + HTTP, fully **readable** ✅
4. **Encrypted connection** — TLS 1.3 handshake, **unreadable** ✅

The lesson that sticks: the difference between HTTP and HTTPS isn't abstract once you've watched both in Wireshark. Same handshake, same ports, same FIN — but Follow Stream shows cleartext HTML one time and encrypted noise the next. And the "Not Secure" warning, which feels like a problem, is actually the *identity* half of HTTPS protecting me from the exact MITM I built in the previous post. Encryption hides the bytes; the certificate chain proves who's on the other end. You need both, and now I've seen both happen between two devices on my own desk.
