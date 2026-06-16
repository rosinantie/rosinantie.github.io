---
layout: post
title: "Running a Linux ELF on my Mac with Docker — and cracking SpookyPass with one command"
date: 2026-06-08
categories: tech
---

I grabbed HackTheBox's **SpookyPass** reversing challenge, unzipped it, and tried to run the binary the way I'd run anything on my Mac:

```
➜  rev_spookypass git:(main) ✗ ./pass
zsh: exec format error: ./pass
```

`exec format error`. The shell can't even *start* it. Before reaching for a disassembler I did the one check that explains everything — ask the file what it actually is:

```
➜  rev_spookypass git:(main) ✗ file ./pass
./pass: ELF 64-bit LSB pie executable, x86-64, version 1 (SYSV),
dynamically linked, interpreter /lib64/ld-linux-x86-64.so.2,
BuildID[sha1]=3008217772cc2426c643d69b80a96c715490dd91,
for GNU/Linux 4.4.0, not stripped
```

There's the whole problem in one line.

---

## 1. Why the Mac flat-out refuses to run it

The two words that matter are **ELF** and **GNU/Linux**.

- My binary is an **ELF** (Executable and Linkable Format) — the executable format Linux uses.
- macOS only knows how to load **Mach-O**, its own format.

So when I type `./pass`, macOS reads the file header, sees a magic number it doesn't recognise as Mach-O, and bails with `exec format error`. This isn't a permissions thing or a missing-library thing — the kernel literally has no loader for this file type. It can't execute it *at all*.

And no, Rosetta doesn't help here. Rosetta translates **x86_64 Mach-O → arm64** on Apple Silicon. It does not turn Linux ELF into anything macOS can run. Different problem entirely. (Same trap I hit from the other direction when I tried to reuse an arm64 binary on an Intel Mac while [building Ghidra's missing decompiler]({% post_url tech/2026-06-06-ghidra-missing-decompile-binary %}) — wrong-format binaries don't fail politely, they just refuse.)

What I need is an actual Linux userland. I don't want to dual-boot or spin up a full VM for one CrackMe, so: **Docker**. One `ubuntu` container, the challenge folder mounted in, run the binary there.

---

## 2. Mount the challenge folder into an Ubuntu container

First, be *in* the challenge directory on macOS:

```
cd /Users/apple/Documents/dilip/github/learn-re/htb/rev_spookypass
pwd
# /Users/apple/Documents/dilip/github/learn-re/htb/rev_spookypass
```

Then start a throwaway Ubuntu container with that directory mounted:

```bash
docker run --rm -it \
  -v "$PWD:/work" \
  ubuntu:24.04 bash
```

Worth unpacking each flag, because this one line does all the work:

- `--rm` — delete the container when I exit. It's disposable; I don't want a graveyard of stopped containers.
- `-it` — **i**nteractive + allocate a **t**ty, so I get a real shell prompt I can type into.
- `-v "$PWD:/work"` — the important bit. Mount my **current macOS folder** at `/work` **inside** the container. The file isn't copied — it's the same bytes on disk, visible from both sides. Edit on the Mac, it changes in the container, and vice-versa.
- `ubuntu:24.04` — the image. Docker pulls it the first time, caches it after.

Drop into the container and the file is right there:

```
root@0ad7bc1c335a:/work# cd /work
root@0ad7bc1c335a:/work# ls
pass
```

Now the magic — the *exact same binary* that macOS refused to touch:

```
root@0ad7bc1c335a:/work# ./pass
Welcome to the SPOOKIEST party of the year.
Before we let you in, you'll need to give us the password:
```

It runs. Because now there's a Linux kernel underneath it that knows how to load an ELF. Same file, same bytes — the only thing that changed is the OS holding it.

> One gotcha on Apple Silicon: `ubuntu:24.04` pulls the arm64 image by default, and this `pass` is x86-64. If it complains, add `--platform linux/amd64` to the `docker run` line and Docker runs it under emulation. On my Intel Mac the architectures already match, so it just works.

---

## 3. Tooling up the container

A fresh Ubuntu image is bare — no `file`, no `gdb`, nothing. Since I'll be poking at binaries, I install the reversing basics once per container:

```bash
apt update
apt install -y \
  file \
  binutils \
  gdb \
  strace \
  ltrace \
  vim \
  curl \
  wget \
  python3
```

What each one buys me:

- **file / binutils** — `file`, plus `strings`, `objdump`, `nm`, `readelf`. The bread and butter.
- **gdb** — the debugger, for stepping through when static reading isn't enough.
- **strace / ltrace** — trace **sys**tem calls and **lib**rary calls live. `ltrace ./pass` will literally show me the `strcmp(...)` call with both arguments as the program runs — often the fastest way to see a password compare.
- **vim / curl / wget / python3** — editing, fetching, and quick scripting.

And here's the thing that confused me the first time: **next session, all of that is gone.** New container, pristine Ubuntu, no `strings`, no `vim`. The next section is why — and how to make it stop.

---

## 4. Why my tools vanish every session — image vs container

I exited the container, came back later, ran the same `docker run …` line, and `strings` was "command not found" again. I hadn't done anything wrong — I'd just misunderstood what `--rm` and the image actually are.

Two ideas untangle the whole thing:

- **Image** = a frozen, read-only *template* (`ubuntu:24.04`). Reusable, never changes.
- **Container** = a *running, throwaway instance* of that image. This is where `bash` lives, and where my `apt install`s landed.

Now re-read the flag: `--rm` means **"delete this container the moment I exit."** So my installs went *into the container*, and `--rm` threw the container away on `exit`. The image is untouched — and the image never had my tools — so the next `docker run` builds a brand-new container from pristine `ubuntu:24.04`, with nothing installed. It doesn't *continue* the old session; it's a fresh one every time.

> The only thing that survived is `/work` — because that's a **mount**, a window into my real Mac filesystem, not part of the container at all. Files persist; runtime installs don't.

The mental model that made it click:

> Installs done **at runtime** (`apt install` inside the container) live in the **container** and die with `--rm`.
> Installs done in a **Dockerfile** live in the **image** and persist.

So the fix is to bake the tools into the image instead of the container.

### The fix: a Dockerfile

I dropped a `Dockerfile` in my `learn-re` folder:

```dockerfile
FROM ubuntu:24.04

# Reverse-engineering toolkit baked into the IMAGE so it survives --rm
RUN apt update && apt install -y \
    binutils \
    gdb \
    gdb-multiarch \
    gcc \
    file \
    vim \
    radare2 \
    xxd
WORKDIR /work
```

Build it **once**:

```bash
cd ~/Documents/Dilip/Github/learn-re
docker build -t re-lab .      # -t names the image "re-lab"; "." = use the Dockerfile here
```

My tools now live in the image. The last piece is to stop throwing the *container* away each time — so instead of `--rm`, I create one **named** container and **resume** it every session:

```bash
docker run -it --name relab -v "$PWD:/work" re-lab bash   # first time only — creates "relab"
docker start -ai relab                                    # every session after — same container
```

`docker start -ai relab` is the whole daily workflow now — **not** a fresh `docker run`. `run` always builds a *new* container from the image; `start` resumes the one I already have. Dropping `--rm` buys me two things: the image's baked-in tools are there from the first second, **and** anything I `apt install` later (like `less`) survives into the next session instead of vanishing with the container. I still rebuild the image — `docker build -t re-lab .` — only when I want a tool in *every* future container, or to bake in users/groups so the setup is reproducible.

### Decoding that create line, flag by flag

The first-time `docker run` stops being magic once you read it as parts:

```
docker run   -it   --name relab   -v "$PWD:/work"   re-lab   bash
└───┬────┘  └┬┘   └────┬─────┘   └──────┬───────┘  └──┬─┘   └─┬─┘
  the verb   │     keep + name         mount          image  command
        interactive  the container                    name   to run
```

- **`docker run`** — *create a new container and start it.* I run this **once**; after that I reuse it with `docker start`, never `run` again.
- **`-it`** — two flags: `-i` keeps input open so I can type; `-t` gives a real terminal (prompt, colors, Ctrl-C). Without them `bash` starts, sees no human attached, and exits immediately.
- **`--name relab`** — give the container a stable name. And notice what's **missing**: no `--rm`. So when I exit, the container *stops* but isn't deleted — `docker start -ai relab` brings it (and everything I installed in it) back.
- **`-v "$PWD:/work"`** — mount my current Mac folder at `/work` inside the container. This is the bridge between the two worlds: edits live on the Mac, so they're safe no matter what happens to the container.
- **`re-lab`** — which image to instantiate. My custom one.
- **`bash`** — what to run inside. (I could run `re-lab gdb ./pass` to drop straight into gdb instead.)

And why `cd` first? Because `-v "$PWD:/work"` mounts *wherever I'm standing*. I `cd` into `learn-re` so the right folder gets mounted — run it from `~` and I'd mount my entire home directory. (I could skip the `cd` by writing the absolute path: `-v ~/Documents/Dilip/Github/learn-re:/work`.)

### Why I keep one container alive instead of a fresh one each time

A container lives only as long as the process inside it. `… re-lab bash` exists only while that `bash` is open; type `exit` and the process ends, so the container **stops**. With `--rm` it's also *deleted*; with a named container it just sits there stopped, ready to resume.

That difference is exactly why I stopped using `--rm`. The first time I `apt install`ed `less` (or set up a `devs` group) inside a `--rm` container, it died on `exit` — and the next `docker run` started pristine again, so I'd be reinstalling `less` every single session. With a named container I just `docker start -ai relab` and last session's state is all still there.

```bash
docker run -it --name relab -v "$PWD:/work" re-lab bash   # once
docker start -ai relab                                    # every session after
```

| Style | Each session | Persists runtime installs? | Clutter |
|---|---|---|---|
| **Throwaway** (`--rm`) | `docker run --rm -it …` | ❌ (only what's in the image) | none |
| **Persistent** (named) | `docker start -ai relab` | ✅ | one named container |

The rule of thumb I landed on: tools I *always* want go in the **Dockerfile** (reproducible, present in every container); the **persistent container** holds the session state I pick up as I work — extra installs, users, scratch setup. Files stay on the Mac via `/work` either way. The only cost is one named container sitting on disk — `docker rm relab` whenever I want a clean slate.

---

## 5. Cracking the easy SpookyPass: `strings`

SpookyPass is the gentlest possible reversing intro, and it teaches exactly one lesson: **a hardcoded secret is not a secret.** The program compares your input against a password baked into the binary — and `strcmp` needs the real password in plaintext to compare against. Which means it's just *sitting there* in the file's data section.

So before any disassembly, dump every printable string in the binary:

```
root@0ad7bc1c335a:/work# strings ./pass
...
Welcome to the SPOOKIEST party of the year.
Before we let you in, you'll need to give us the password:
s3cr3t_p455_f0r_gh05t5_4nd_gh0ul5
Welcome inside!
You won't easily get the password from me
...
```

There it is — `s3cr3t_p455_f0r_gh05t5_4nd_gh0ul5`, nestled right between the prompt and the success message. (Same string I later watched fall out of Ghidra's decompiler in the [reverse-engineering 101 walkthrough]({% post_url tech/2026-06-01-reverse-engineering-101-c-to-ghidra %}) — `strings` just gets you there in one command instead of a full analysis pass.)

Feed it back to the program:

```
root@0ad7bc1c335a:/work# ./pass
Welcome to the SPOOKIEST party of the year.
Before we let you in, you'll need to give us the password:
s3cr3t_p455_f0r_gh05t5_4nd_gh0ul5
Welcome inside!
HTB{...}
```

And the program hands over the `HTB{...}` flag. Three steps, no debugger: `strings` → read the password → paste it back.

If `strings` had spat out thousands of lines, I'd narrow it — `strings ./pass | grep -i pass`, or pipe through `less` — but for SpookyPass the password is right there in the open.

---

## What I took away

1. **`exec format error` is an OS/format mismatch, not a bug.** Before doing anything else, run `file` on the binary. `ELF` + `for GNU/Linux` on a Mac means "wrong operating system," and no amount of `chmod +x` will fix that — you need a Linux kernel under it.
2. **Docker is the cheapest Linux you'll ever spin up.** `docker run --rm -it -v "$PWD:/work" ubuntu bash` gives me a real Linux userland with my files mounted in, in seconds, and cleans itself up on exit. No VM, no dual boot, for a one-file challenge.
3. **Image vs container is the whole mental model.** The image is the frozen template; the container is the throwaway instance. `--rm` deletes the *container*, so anything I `apt install` at runtime dies with it — that's why my tools vanished every session. Bake them into a **Dockerfile** and they live in the *image*, surviving `--rm` forever.
4. **`-v "$PWD:/work"` is a mount, not a copy.** The binary is the same bytes on both sides — which is the whole point. macOS *holding* the file and macOS *running* it are different acts; Docker only changes the second. Files in `/work` survive `--rm` because they never lived in the container.
5. **The simplest crack is `strings`.** Anything compared with `strcmp` lives in the binary as plaintext. SpookyPass exists to make that visceral: the "secret" is one `strings` away. If a value must actually stay private, it can't be a literal in the program.

Next rung on the ladder is a SpookyPass variant where the password is built up at runtime or lightly obfuscated so `strings` shows nothing useful — that's where `ltrace ./pass` (watch the `strcmp` arguments live) and Ghidra start earning their keep. But the workflow is set now: `file` to diagnose, Docker to run, and the lightest tool that cracks it first.
