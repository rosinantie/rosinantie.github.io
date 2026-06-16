---
layout: post
title: "Ghidra's decompiler won't load on my Intel Mac — building the one missing binary"
date: 2026-06-06
categories: tech
---

I downloaded Ghidra 12.1.2, opened a CrackMe-style binary (`pass`, the spooky-pass challenge), clicked `main` in the Symbol Tree — and the Decompiler panel showed a red error instead of C code. The analyzer had run fine; it was specifically the *decompiler* that refused to come up.

This post is the trail of how I tracked it down, because the first fix I tried was wrong in an instructive way, and the real fix is a one-line build target that almost nobody documents.

---

## 1. The symptom: a missing native binary

Ghidra is mostly Java, but the decompiler is a native C++ program that the Java side talks to over a pipe. It lives under the install at:

```
Ghidra/Features/Decompiler/os/<platform>/decompile
```

There's one folder per platform. Mine looked like this:

```
os/
├── linux_x86_64/   decompile  sleigh
├── mac_arm_64/     decompile  sleigh
├── mac_x86_64/                sleigh      ← decompile is MISSING
└── win_x86_64/     decompile  sleigh
```

Every platform shipped both `sleigh` (the disassembler) and `decompile` — **except** `mac_x86_64`, which only had `sleigh`. That's the binary Ghidra was failing to launch.

The tempting shortcut: "`mac_arm_64` has a `decompile`, just copy that one over." No. I'm on a **real Intel Mac** (`sysctl machdep.cpu.brand_string` → `Intel(R) Core(TM) i7-9750H`). An arm64 binary will not run on Intel — Rosetta translates the *other* direction (x86_64 on Apple Silicon), not arm64 on Intel. The arm copy is dead weight here. I need a genuine x86_64 `decompile`.

Good news: the prebuilt Ghidra ships its full C++ source + Makefile, and macOS already has `clang++`, `make`, `bison`, and `flex`. So I can compile the one missing binary myself, version-matched to the install.

---

## 2. The wrong fix: `make decompile`

The obvious move is to `cd` into the C++ source and build:

```
cd Ghidra/Features/Decompiler/src/decompile/cpp
make -j4            # or: make decompile
```

Both blow up immediately with the same error, repeated across several files:

```
./loadimage_bfd.hh:37:10: fatal error: 'bfd.h' file not found
   37 | #include <bfd.h>
      |          ^~~~~~~
In file included from bfd_arch.cc:17:
In file included from codedata.cc:18:
In file included from analyzesigs.cc:17:
make: *** [com_dbg/depend] Error 1
```

`bfd.h` is the **Binary File Descriptor** library header, part of GNU binutils. macOS doesn't ship it. The natural next step everyone reaches for is `brew install binutils` and then wiring up include paths — and that's a rabbit hole of version mismatches and linker flags.

It's also completely unnecessary. The clue is *which* files need `bfd.h`: `loadimage_bfd`, `bfd_arch`, `analyzesigs`, `codedata`. Those belong to the **standalone command-line decompiler** — a separate program that loads ELF/Mach-O/PE files on its own using BFD. That's not what Ghidra uses. Ghidra feeds bytes to the decompiler over a pipe; it never needs BFD at all.

---

## 3. Reading the Makefile: two different "decompile" binaries

The Makefile defines several object-file sets. The relevant two:

```make
COMMANDLINE_NAMES = $(CORE) $(DECCORE) $(EXTRA) $(SLEIGH) consolemain
GHIDRA_NAMES      = $(CORE) $(DECCORE) $(GHIDRA)
```

- `COMMANDLINE_NAMES` → the `decomp_opt` / `decompile` target. Pulls in `$(EXTRA)`, which is where the BFD loaders live. **Needs `bfd.h`.**
- `GHIDRA_NAMES` → the `ghidra_opt` target. Uses `loadimage_ghidra` (the pipe loader) instead. **No BFD anywhere.**

And the binary that gets installed into `os/<platform>/decompile` is built by `ghidra_opt`, confirmed by the install rule:

```make
install_ghidraopt: ghidra_opt
	cp ghidra_opt $(GHIDRA_BIN)/Ghidra/Features/Decompiler/os/$(OSDIR)/decompile
```

There's even a guard that explains *why* `make decompile` tried to compile the BFD files at all. The Makefile picks which dependency-scan to run based on the goal you ask for:

```make
DEPNAMES = com_dbg/depend com_opt/depend     # default — scans the BFD sources
ifeq ($(MAKECMDGOALS),ghidra_opt)
	DEPNAMES = ghi_opt/depend                # ← BFD-free scan
endif
```

So with no explicit goal, `make` runs the command-line dependency scan, which `#include`s `bfd.h` just to *generate dependencies* — and dies before compiling a single real object. Ask for `ghidra_opt` and it switches to the BFD-free scan entirely. The error was never about my toolchain; it was about asking for the wrong target.

---

## 4. The right fix: `make ghidra_opt`

```
cd Ghidra/Features/Decompiler/src/decompile/cpp
make ghidra_opt -j4
```

Builds clean — no `bfd.h`, no Homebrew, nothing. It compiles ~70 `.cc` files and links a single executable named `ghidra_opt`:

```
ghidra_opt: Mach-O 64-bit executable x86_64
```

(Note it built x86_64 by default, because that's what the machine reports. Exactly what I need.) Then drop it into the empty slot under the name Ghidra expects:

```
cp ghidra_opt ../../../os/mac_x86_64/decompile
xattr -d com.apple.quarantine ../../../os/mac_x86_64/decompile   # clear Gatekeeper flag
chmod +x ../../../os/mac_x86_64/decompile
```

Quick sanity check that macOS doesn't kill it on launch (Gatekeeper would show `Killed: 9`):

```
echo "" | ./decompile ; echo "exit=$?"
# exit=0   → launches and exits cleanly, not blocked
```

Restart Ghidra, reopen the project, click `main` — the Decompiler panel now renders C, `strcmp(input, "s3cr3t_p455_f0r_gh05t5_4nd_gh0ul5")` and all. Done.

---

## What I'd tell past-me

Three things, in order of how much time each would have saved:

1. **Don't copy the arm binary onto an Intel Mac.** Check the *real* CPU with `sysctl machdep.cpu.brand_string`, not `uname -m` (which can lie under Rosetta). Wrong-arch binaries don't error helpfully — they just refuse.
2. **`bfd.h not found` is not a "go install binutils" problem.** It's a "you asked for the wrong make target" problem. The file that needs it (`loadimage_bfd`) is for the *standalone* decompiler, which Ghidra never uses.
3. **The target is `ghidra_opt`, not `decompile`.** One word, and the whole BFD dependency vanishes because the Makefile switches to a different dependency scan for that goal.

The deeper lesson is the same one I keep relearning: when a build fails on a missing header, read *which sources* pull it in before you start installing things. Half the time the header belongs to a component you don't even want, and the fix is to stop building that component — not to satisfy it.

If I ever reinstall or upgrade Ghidra, this binary disappears again, so it's worth keeping the two commands (`make ghidra_opt -j4` + the `cp`) somewhere I can find them. Like, say, this post.
