---
layout: post
title: "Reverse engineering 101 — from a C password check to Ghidra's decompiler"
date: 2026-06-01
categories: tech
---

I write backend code for a living, so I'm used to looking at programs from the *inside* — source first, binary as an afterthought. Reverse engineering flips that. You start with the compiled artifact and work backwards to figure out what it does, with no source in hand.

The fastest way I found to actually *get* it is to play both sides: write a tiny program myself, compile it, and then pretend I've never seen the source — open the binary in [Ghidra](https://ghidra-sre.org/) and see how much of my own code I can recover. This post is that full loop, start to finish, on a program small enough to fit in your head.

(If you hit the "Decompiler panel won't load" wall on an Intel Mac while following along, that's its own rabbit hole — I wrote it up separately in [building Ghidra's missing decompiler binary]({% post_url tech/2026-06-06-ghidra-missing-decompile-binary %}).)

---

## 1. The target: a password check in C

Nothing fancy. A program that asks for a password and compares it against a hardcoded string:

```c
#include <stdio.h>
#include <string.h>

int main(void) {
  char name[64];

  printf("Enter your password: ");

  if (scanf("%63s", name) != 1) {
    fprintf(stderr, "No input received.\n");
    return 1;
  }

  if (strcmp(name, "FuckingPasswordHey") == 0) {
    printf("password was right\n");
  } else {
    printf("wrong password\n");
  }

  return 0;
}
```

The whole point of the exercise is that secret string. It's hardcoded into the program — and as we'll see, "hardcoded" means "sitting in plaintext in the binary for anyone with a decompiler to read." That's the lesson the rest of the post drives home.

---

## 2. Compile and run it locally

I'm on macOS, so `clang` is already there (`gcc` works the same way on Linux). I keep the source as `pass.c` and drop the executable into a `build/` folder so the working directory stays clean:

```
mkdir -p build
clang pass.c -o build/pass
```

No flags, no optimisation — the plain default build. Run it:

```
$ ./build/pass
Enter your password: hello
wrong password

$ ./build/pass
Enter your password: FuckingPasswordHey
password was right
```

Works exactly as written. Now the fun part: forget the source exists. We have `build/pass` and a question — *what's the password?*

---

## 3. Import the binary into Ghidra

1. Launch Ghidra and create a new project (`File → New Project → Non-Shared Project`).
2. Drag `build/pass` into the project window, or `File → Import File`. Ghidra reads the header and identifies it — on my Mac it shows up as a **Mach-O** `x86_64` executable (on Linux you'd see ELF).
3. Double-click the imported file to open it in the **CodeBrowser**.
4. It pops up *"… has not been analyzed. Would you like to analyze it now?"* — say **Yes** and accept the default analyzers. This is where Ghidra disassembles every function, follows the call graph, recovers strings, and matches library signatures.

When analysis finishes, find the entry point. Open the **Symbol Tree** on the left, expand **Functions**, and click `main` (a stripped binary may only show `entry` — same idea, follow it down). The center pane fills with assembly; the right pane is the **Decompiler**.

---

## 4. Reading the assembly

The disassembly listing is the literal x86_64 instructions Ghidra recovered. You don't need to read all of it, but a few landmarks tell the whole story even before you look at the C:

- A `LEA` loading the address of the `"Enter your password: "` string, followed by a `CALL` to `printf`.
- A `CALL` to `scanf` with the `"%63s"` format string.
- A `LEA` pointing at a string that says **`FuckingPasswordHey`**, then a `CALL strcmp`.
- A `TEST`/`JNZ` on the result that branches to either the `"password was right"` or `"wrong password"` `printf`.

That third bullet is the whole game. The secret never gets hashed or obfuscated — `strcmp` needs the real bytes to compare against, so the plaintext password sits right there in the binary's read-only data, and Ghidra labels the cross-reference for you. You can also jump straight to it via **Window → Defined Strings**, which lists every string the analyzer found.

---

## 5. The Decompiler — assembly back into C

This is the payoff. With `main`/`entry` selected, the Decompiler pane reconstructs C-like pseudocode from the assembly:

```c
undefined4 entry(void)
{
  int iVar1;
  undefined4 local_5c;
  char local_58 [72];
  long local_10;

  local_10 = *(long *)PTR____stack_chk_guard_100001008;
  _printf("Enter your password: ");
  iVar1 = _scanf("%63s",local_58);
  if (iVar1 == 1) {
    iVar1 = _strcmp(local_58,"FuckingPasswordHey");
    if (iVar1 == 0) {
      _printf("password was right\n");
    }
    else {
      _printf("wrong password\n");
    }
    local_5c = 0;
  }
  else {
    _fprintf(*(FILE **)PTR____stderrp_100001010,"No input received.\n");
    local_5c = 1;
  }
  if (*(long *)PTR____stack_chk_guard_100001008 == local_10) {
    return local_5c;
  }
                    /* WARNING: Subroutine does not return */
  ___stack_chk_fail();
}
```

Put the original source next to this and the mapping is almost one-to-one. Worth noticing how the names change, because that's the texture of real RE work:

- **`local_58` is my `name[64]`.** Ghidra has no variable names from a compiled binary, so it invents them from stack offsets. It also sizes the buffer as `[72]`, not `[64]` — the extra bytes are stack alignment padding the compiler added, not something I wrote.
- **`local_5c` is the return value** — `0` on the success path, `1` on the no-input path, exactly matching `return 0;` / `return 1;`.
- **`__stack_chk_guard` / `__stack_chk_fail` are not mine at all.** The compiler inserted a *stack canary*: it stashes a guard value, and before returning it checks the value is untouched. If a buffer overflow smashed the stack, the check fails and the program aborts instead of returning to attacker-controlled code. That's why there's a comparison wrapped around the `return` and a `WARNING: Subroutine does not return` on `__stack_chk_fail`. Reading decompiled output means learning to tell *your* logic apart from *the compiler's* boilerplate.
- **The string survived perfectly.** `_strcmp(local_58, "FuckingPasswordHey")` — there it is, in plaintext, no source required. We answered "what's the password?" purely from the binary.

---

## What I took away from the loop

A few things clicked that no amount of just *reading about* RE had done:

1. **Hardcoded secrets are not secret.** A string compared with `strcmp` lives in the binary as plaintext. If something must stay private, it can't be a literal in the program — it has to be hashed, or never shipped to the client at all. Seeing my own password fall out of the decompiler in thirty seconds made that visceral.
2. **The decompiler is a *reconstruction*, not the original.** Variable names are gone, types are guessed (`undefined4`), buffer sizes include padding, and compiler-inserted machinery (stack canaries, alignment) shows up as if I'd written it. Half the skill is separating my logic from the compiler's.
3. **Build it yourself first.** Compiling a program you wrote and then reversing it gives you an answer key. You can check every guess against the source, which is how the assembly mnemonics and Ghidra's conventions stop being noise and start being readable.

Next step for me is to recompile this same program with `-O2` and `strip` the symbols, then see how much harder the decompiler output gets to read — because real targets never come with a `main` label waiting in the Symbol Tree.
