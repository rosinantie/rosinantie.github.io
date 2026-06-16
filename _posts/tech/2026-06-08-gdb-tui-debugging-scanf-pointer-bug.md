---
layout: post
title: "Watching a bug crash in GDB — a missing &, an int mistaken for a pointer"
date: 2026-06-08
categories: tech
---

Now that I have a [Linux container with the RE toolkit baked in]({% post_url tech/2026-06-08-docker-ubuntu-mac-run-linux-elf-spookypass %}), I wanted to actually *watch* a program misbehave instead of just reading its source. The best way to learn a debugger is to feed it a bug you already understand — so I wrote one on purpose.

This is the story of a classic C mistake (`scanf` without the `&`), what it looks like inside **GDB's TUI**, and *why* the crash happens at the register level. The compiler even warned me before I ran it — and the warning turns out to be the whole explanation.

---

## 1. The buggy program

`hello.c` — asks for a number, prints it back. One character is wrong:

```c
#include <stdio.h>

int main(void) {
  int d = 2;
  printf("Welcome to the Debugging programming!\n");

  scanf("%d", d);          // BUG: should be &d

  printf("you gave number is : %d\n", d);
  return 0;
}
```

`scanf("%d", d)` passes `d` — the **value** `2` — where `scanf` expects a **pointer** to write into. The intent was `&d`, the *address* of `d`. Hold that thought.

---

## 2. The compiler already told me

I compiled with `-g` so GDB gets source-level debug info, and gcc immediately flagged it:

```
root@ae22e7a5f3d9:/work/src# gcc -o hello hello.c -g
hello.c: In function 'main':
hello.c:7:14: warning: format '%d' expects argument of type 'int *',
              but argument 2 has type 'int' [-Wformat=]
    7 |   scanf("%d", d);
      |          ~^   ~
      |           |   |
      |           |   int
      |           int *
```

Read that carefully — it's the bug in full. `%d` for `scanf` **expects `int *`** (a pointer to write the parsed number into), but I handed it an **`int`**. gcc compiled it anyway (it's a warning, not an error), so I got an executable with a live landmine in it:

```
root@ae22e7a5f3d9:/work/src# file hello
hello: ELF 64-bit LSB pie executable, x86-64, ... with debug_info, not stripped
```

`with debug_info, not stripped` — exactly what I want for GDB. (Contrast with the stripped HTB binary from the [SpookyPass writeup]({% post_url tech/2026-06-01-reverse-engineering-101-c-to-ghidra %}), where there were no symbols at all.)

---

## 3. Driving GDB's TUI

Load it up:

```
root@ae22e7a5f3d9:/work/src# gdb hello
...
Reading symbols from hello...
(gdb)
```

The single command that turns GDB from a text prompt into a real debugger view:

```
(gdb) lay next
```

`layout next` cycles through GDB's TUI panes. Keep running it and you get **source**, then **assembly**, then **registers** — split-screen above the command line, with the current line highlighted as you step. For this bug the three views I care about are *source* (to see which C line we're on), *asm* (to see the actual instruction that faults), and *regs* (to see the bad pointer).

Set a breakpoint at `main` and start:

```
(gdb) break main
Breakpoint 1 at 0x...: file hello.c, line 5.
(gdb) run
```

Execution stops at the top of `main`. Now step **one source line at a time**:

```
(gdb) next        # or just `n` — run the current line, stop at the next
```

- `next` (`n`) — execute the current line; step *over* function calls.
- `step` (`s`) — like `next`, but step *into* calls.
- `nexti` / `stepi` — same idea, one *instruction* at a time (handy in the asm pane).

> **TUI got garbled?** Program output and GDB share the screen, so the panes smear. `refresh` (or `Ctrl-L`) redraws everything. I needed it constantly once the program started printing.

Step until you're sitting on the `scanf` line, then run it. The moment I type a number (`34`) and hit enter:

```
(gdb) next
34
Program received signal SIGSEGV, Segmentation fault.
0x00007f0c30958146 in __vfscanf_internal (s=<optimized out>, format=<optimized out>,
    argptr=argptr@entry=0x7ffd85143230, mode_flags=mode_flags@entry=2)
    at ./stdio-common/vfscanf-internal.c:1976
```

**SIGSEGV** — a segmentation fault, *inside* `scanf` itself. The crash isn't in my line of source; it's deep in glibc's `__vfscanf_internal` at line 1976. That's the tell: my code handed `scanf` something poisonous, and `scanf` died trying to use it. Note `argptr` — that's the saved pointer to my call's arguments, the thing `scanf` is about to read the (bad) pointer out of.

---

## 4. Why it crashes — an int used as an address

This is where the asm and register panes earn their place. Open them with `lay next` until you see the disassembly, and look at the exact instruction GDB stopped on:

```
(gdb) x/i $pc
=> 0x7f0c30958146 <__vfscanf_internal+18710>:    mov    %edx,(%rax)
```

`mov %edx,(%rax)` is the whole crime in one instruction. It means: **store the value in `edx` into the memory at the address in `rax`.** In C terms, `*(int *)rax = edx`. So `rax` had better be a valid, writable address — and `edx` is the number being written. The two instructions just above it (visible in the asm pane) set this up:

```
+18707   mov    (%rax),%rax        ; load the pointer argument out of the arg area
+18710   mov    %edx,(%rax)        ; *** write the parsed int through it  <-- crash
```

Now read the registers at the moment of the fault (`lay regs`, or `info registers`):

```
rax    0x2    2          <-- the "pointer" scanf is writing through
rdx    0x22   34         <-- the number I typed (0x22 == 34), waiting in edx
rsi    0x22   34
...
rip    0x7f0c30958146    <__vfscanf_internal+18710>
```

There it is, laid bare:

- **`edx` = `0x22` = 34** — the integer `scanf` parsed from my input. That's the value it wants to store. Correct.
- **`rax` = `0x2`** — the address it's about to store *into*. **That is not an address — it's the value of `d`.** I passed `d` (which was `2`), so the number `2` got used as a pointer.

So `mov %edx,(%rax)` executes as:

```
*(int *)0x2 = 34;     // write 34 to memory address 0x2
```

Address `0x2` is in the very bottom of the address space — not memory this process owns, not writable. The CPU raises a fault, the kernel kills the program, SIGSEGV. The crash isn't about the number `34` being wrong; it's that **an `int` (`2`) got used where an address belonged**, so `scanf` wrote through a garbage pointer.

To prove what the pointer *should* have been:

```
(gdb) print &d
$1 = (int *) 0x7ffd85143...    # a real stack address, up near rbp/rsp — NOT 0x2
```

`&d` is a real stack address (right in the range of `rbp = 0x7ffd85143220` from the register dump). That's what `rax` needed to hold. Instead it held `0x2`.

And this is *precisely* what gcc warned about back in step 2: `expects 'int *', but argument 2 has type 'int'`. The warning predicted the exact crash — `edx` into `(%rax)` where `rax` is an `int`, not a pointer.

---

## 5. The one-character fix

Give `scanf` the **address** of `d`, so it has a real place to write:

```c
int main(void) {
  int d = 2;
  printf("Welcome to the Debugging programming!\n");

  scanf("%d", &d);                       // &d — the address, not the value

  printf("you gave number is : %d\n", d);
  return 0;
}
```

Recompile — no warning this time — and step through it in GDB again. Now `rsi` holds `&d` (a real `0x7fff…` stack address), `scanf` writes the parsed number safely into `d`, and the program prints it back and exits `0`. No SIGSEGV. The `&` is the entire difference between "here is a box to put the number in" and "here is the number 2, now go write to address 2."

---

## What I took away

1. **`scanf` needs addresses, `printf` needs values.** `printf("%d", d)` is right because it only *reads* `d`. `scanf("%d", &d)` needs the `&` because it has to *write back* into `d` — and to write into a variable you must hand over where it lives, not what it currently holds. Forgetting the `&` is the most common C beginner crash, and now I've seen exactly why.
2. **Compiler warnings are predictions, not noise.** `expects 'int *', but argument 2 has type 'int'` described the segfault before I ever ran the program. Compiling with warnings on (and reading them) would have caught this with zero debugging.
3. **A SIGSEGV inside a library usually means *you* passed it garbage.** The crash was in glibc's `__vfscanf_internal`, not my code — but the cause was my bad pointer. When a fault lands deep in a library, walk back up to what you handed it.
4. **GDB's TUI makes the abstract concrete.** `lay next` + `break main` + `run` + `next`, with the asm and register panes open, turned "a pointer is an address" from a sentence into something I watched happen: `mov %edx,(%rax)` with `rax = 0x2` and `edx = 34` — the parsed number being written through the *value* of `d` instead of its address. `refresh` (`Ctrl-L`) when the panes smear.

Next I want to point this same workflow at a binary I *didn't* write — set a breakpoint on `strcmp` in a CrackMe and read the password straight out of the registers as it's compared, the dynamic-analysis counterpart to the static `strings` trick.
