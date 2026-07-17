---
layout: post
title: "One C program, four views of the same assembly — AT&T, Intel, compiler, and raw bytes"
date: 2026-07-15
categories: tech
---

I kept getting confused by assembly listings that looked totally different but were supposedly "the same thing." Compiler output looked one way, the disassembler in my debugger looked another, and every tutorial online seemed to flip the operand order on me. So I did the thing that always works for me: I took *one* tiny program and looked at it from every angle at once, until the differences stopped being scary and turned out to be mostly spelling.

This is that walkthrough. One 4-line C program, shown at four levels — C source → compiler assembly → the linked binary decoded byte-by-byte → hand-written assembly — in both **AT&T** and **Intel** notation. Underneath all of it, it's a single identical stream of x86-64 machine instructions. Everything that looks different is either notation, packaging, or scaffolding.

Platform note: everything here is **x86-64 on macOS**, built with `clang`.

---

## The basics — a five-minute assembly primer

If you've only ever written high-level code, a few ideas make everything below readable. This is the mental model I wish I'd had before staring at my first disassembly.

**Assembly is a thin, human-readable skin over machine code.** The CPU only runs bytes. Assembly gives each instruction a *mnemonic* (`mov`, `add`, `call`) instead of a raw number, but the mapping is essentially one-to-one — one assembly line becomes one instruction, a handful of bytes. That's the whole trick: `48 8d 05 …` *is* a `lea`; the text is just there so humans don't go blind.

**Registers are the CPU's handful of built-in variables.** They're tiny, ultra-fast storage slots that live *inside* the processor — not in RAM. There are only about 16 general-purpose ones on x86-64, each 64 bits wide. Instead of `int x`, the CPU works with named slots. The ones that show up in this post:

| Register | What it's used for here |
|---|---|
| `rax` | scratch / return value (a function's result comes back in `rax`) |
| `rdi` | 1st function argument |
| `rsi` | 2nd function argument |
| `rsp` | **stack pointer** — top of the current stack |
| `rbp` | **base pointer** — anchor for the current stack frame |
| `rip` | **instruction pointer** — address of the *next* instruction to run |

One naming quirk you'll trip over: the same register has different names by size. `rax` is the full 64 bits, `eax` is its low 32 bits, and `al` is its low 8 bits — all the *same* register. That's why `xor eax, eax` and `mov al, 0` both show up: they're touching parts of `rax`.

**An instruction is a verb plus operands.** `mov rsi, rax` = "move `rax` into `rsi`." Operands come in three flavors, and telling them apart is most of the battle:

- **Register** — `rax`, a named slot: use the value in it.
- **Immediate** — `16` or `$16`, a literal constant baked into the instruction.
- **Memory** — `[rbp-8]` or `-8(%rbp)`, an *address*: go read/write RAM there. The brackets/parentheses mean "the value **at** this address," like a pointer dereference in C.

The core verbs used below, in plain English:

| Instruction | Means |
|---|---|
| `mov` | copy a value from one place to another |
| `lea` | compute an address (**l**oad **e**ffective **a**ddress) — like `&x` in C, *without* dereferencing |
| `push` / `pop` | put a value on the stack / take one off |
| `call` / `ret` | jump into a function / return from it |
| `add` / `sub` | arithmetic |
| `xor eax, eax` | a two-byte idiom for "set `eax` to 0" |
| `jmp` | unconditional jump to another address |

**The stack is scratch memory that grows *downward*.** Each function gets a *frame* — a chunk of stack for its locals. `sub rsp, 16` reserves 16 bytes (the stack grows toward *lower* addresses, so you subtract to make room); `add rsp, 16` gives it back. Locals are then addressed relative to `rbp`, like `[rbp-8]`. That's the `push rbp` / `mov rbp, rsp` / `sub rsp, N` dance at the top of every function — it's just "open a frame."

**Addresses and bytes are written in hex.** `0x100000477` is an address; `48 8d 05` are the raw instruction bytes. Hex just because one hex digit = 4 bits, so a byte is always exactly two digits (`0x48`) — tidy for reading memory.

That's enough to read every line in this post. Now the program.

---

## 0. The source

```c
#include <stdio.h>

int main(void) {
  char *message = "Hello morning";
  printf("%s", message);
  return 0;
}
```

Built with:

```
clang -g -O0 hello.c -o hello_c
```

`-O0` means no optimization, which is exactly what I want here — the assembly comes out literal and easy to read, one C idea per instruction, nothing folded away.

---

## 1. What the CPU actually has to do (System V ABI)

Before any assembly makes sense, the calling convention has to click. `printf("%s", message)` doesn't magically pass arguments — it means "put these values in these specific registers, then `call`." On x86-64 (System V ABI), that's:

| Register | Holds | Why |
|---|---|---|
| `rdi` | address of `"%s"` | 1st argument (the format string) |
| `rsi` | address of `"Hello morning"` | 2nd argument (the value) |
| `al` | `0` | count of float/vector args — required for variadic functions |

That last one surprised me. `printf` is *variadic* (`...`), and the ABI says: before calling a variadic function, `al` must hold how many vector registers were used to pass floating-point args. We pass none, so `al = 0`. That's why every `printf` call site has a `mov al, 0` (or `xor eax, eax`) right before it.

Around that argument setup sits the **stack frame**:

- `push rbp` / `mov rbp, rsp` / `sub rsp, 16` → open the frame
- `add rsp, 16` / `pop rbp` / `ret` → close it back up

That's the whole shape of `main`: open frame, load two pointers, zero `al`, call, return 0, close frame.

---

## 2. View A — Compiler assembly (`clang -S -O0`, AT&T)

This is what the compiler emits when you ask it for assembly instead of a binary (`clang -S`):

```
_main:
    .cfi_startproc               ; unwind info (compiler-only)
    pushq   %rbp
    movq    %rsp, %rbp
    subq    $16, %rsp
    movl    $0, -4(%rbp)         ; unused return slot
    leaq    L_.str(%rip), %rax   ; rax = &"Hello morning"
    movq    %rax, -16(%rbp)
    movq    -16(%rbp), %rsi      ; 2nd arg = message
    leaq    L_.str.1(%rip), %rdi ; 1st arg = "%s"
    movb    $0, %al              ; variadic: 0 float args
    callq   _printf
    xorl    %eax, %eax           ; return 0
    addq    $16, %rsp
    popq    %rbp
    retq
    .cfi_endproc
```

**Compiler fingerprints** — the tells that a human didn't write this: the `.cfi_*` directives (unwind info for stack traces), `.build_version`, `.p2align`, the machine-generated labels `L_.str` / `L_.str.1`, the unused return slot at `-4(%rbp)`, and the pointer parked at `-16(%rbp)`. None of that is *needed* to run the program — it's scaffolding the compiler adds for debuggers, exceptions, and alignment.

---

## 3. View B — The full binary, decoded byte-by-byte

Same instructions as View A — but now assembled *and linked*, then read back out of the running binary in my debugger's disassembly view. This is where it got interesting, because the disassembler shows me the actual addresses, the actual machine-code bytes, and a few things that aren't code at all:

```
ADDRESS       BYTES                  DECODED                    WHAT IT REALLY IS
──────────────────────────────────────────────────────────────────────────────────
0x100000477:  10 c7                  adcb  %al, %bh        ┐
0x100000479:  45 fc                  ...                   │  data / padding
0x10000047B:  00 00                  addb  %al, (%rax)     │  before main
0x10000047D:  00 00                  addb  %al, (%rax)     ┘  (not real code)
─── main() begins ─────────────────────────────────────────────────────────────────
0x10000047F:  48 8d 05 24 00 00 00   leaq  0x24(%rip), %rax     ← rax = "Hello morning"
0x100000486:  48 89 45 f0            movq  %rax, -0x10(%rbp)    ← stash ptr (-0x10 = -16)
0x10000048A:  48 8b 75 f0            movq  -0x10(%rbp), %rsi    ← rsi = message (2nd arg)
0x10000048E:  48 8d 3d 23 00 00 00   leaq  0x23(%rip), %rdi     ← rdi = "%s"    (1st arg)
0x100000495:  b0 00                  movb  $0x0, %al            ← variadic: 0 float args
0x100000497:  e8 08 00 00 00         callq 0x1000004a4          ← call printf (via stub)
0x10000049C:  31 c0                  xorl  %eax, %eax           ← return 0
0x10000049E:  48 83 c4 10            addq  $0x10, %rsp          ← release 16 bytes
0x1000004A2:  5d                     popq  %rbp
0x1000004A3:  c3                     retq                       ← return from main
─── printf stub (PLT trampoline) ───────────────────────────────────────────────────
0x1000004A4:  ff 25 56 0b 00 00      jmpq  *0xb56(%rip)         ← jump into real printf
─── string data, MISREAD as instructions ───────────────────────────────────────────
0x1000004AA:  48 65                  ...                  ┐  "H e"
0x1000004AC:  6c                     insb ...             │  "l"
0x1000004AD:  6c                     insb ...             │  "l"
0x1000004AE:  6f                     outsl ...            │  "o"
0x1000004AF:  20 6d 6f               andb ...             │  " m o"
0x1000004B2:  72 6e                  jb ...               │  "r n"
0x1000004B4:  69 6e 67 00 25 73 00   imull ...            │  "i n g \0 % s \0"
0x1000004BB:  00 ...                 addb ...             ┘  padding / alignment
```

### Decoding the "junk" — it's your strings

The lines at the bottom are **not code**. They're the two string literals, and the disassembler is decoding letters as if they were instructions:

```
48 65 6c 6c 6f 20 6d 6f 72 6e 69 6e 67 00  →  "Hello morning"
25 73 00                                    →  "%s"
```

`48` is `'H'`, `65` is `'e'`, `6c 6c` is `'ll'`, and so on — plain ASCII. The disassembler does **linear disassembly**: it decodes bytes strictly top to bottom and can't tell code from data. Once it walks past `retq` into the `__cstring` section, it happily "decodes" my text as bogus instructions (`insb`, `outsl`, `imull`). Seeing that once cured me of ever trusting a disassembler blindly past the end of a function.

### The four regions

| Range | What it is |
|---|---|
| `0x477 – 0x47D` | data / padding before `main` |
| `0x47F – 0x4A3` | the real `main()` — the instructions that matter |
| `0x4A4 – 0x4A9` | the `printf` stub (a `jmpq` trampoline into libc) |
| `0x4AA – end` | string literals: `"Hello morning"` + `"%s"` |

Only that second row is *my* code.

### How the RIP-relative loads resolve

This was the piece I'd never really understood: how does `leaq 0x24(%rip), %rax` end up pointing at my string? The rule is that the offset is measured from the address of the **next** instruction (that's what `%rip` holds by the time the instruction executes):

```
leaq 0x24(%rip), %rax  @ 0x47F, next instr = 0x486  →  0x486 + 0x24 = 0x4AA  → "Hello morning" ✓
leaq 0x23(%rip), %rdi  @ 0x48E, next instr = 0x495  →  0x495 + 0x23 = 0x4B8  → "%s"            ✓
```

`0x4AA` lands exactly on the `"Hello morning"` bytes, and `0x4B8` lands right after its null terminator, on `"%s"`. That's why the debugger can annotate those loads with `; "Hello morning"` and `; "%s"` — it did the same arithmetic.

### View A → View B: what linking added

Going from the compiler's text to the decoded binary, the concrete additions are:

- **Real addresses** (`0x10000047F`) instead of just labels.
- **Machine-code bytes** (`48 8d 05 24 …`) — the actual instruction encoding.
- **Labels resolved to offsets** — `L_.str(%rip)` became `0x24(%rip)`.
- **`_printf` reached through a stub** — a `jmpq *…(%rip)` trampoline that jumps into libc's real `printf` at runtime, instead of a bare `callq _printf` symbol.

---

## 4. View C — Hand-written, Intel syntax

Now the same program, but written *by hand* the way I'd actually type it, in Intel notation:

```
.intel_syntax noprefix
.section __TEXT,__text
.globl _main
.extern _printf

_main:
    push rbp
    mov rbp, rsp
    sub rsp, 16
    lea rax, [rip + message]
    mov [rbp-8], rax
    mov rsi, [rbp-8]       # 2nd arg: message
    lea rdi, [rip + fmt]   # 1st arg: "%s"
    xor eax, eax           # variadic: 0 float args
    call _printf
    xor eax, eax           # return 0
    add rsp, 16
    pop rbp
    ret

.section __TEXT,__cstring
message:
    .asciz "Hello morning"
fmt:
    .asciz "%s"
```

Build it with `clang hello_intel.s -o hello_intel` (the `.intel_syntax noprefix` header lives inside the file).

---

## 5. View D — Hand-written, AT&T syntax

Identical logic, AT&T notation:

```
.section __TEXT,__text
.globl _main
.extern _printf

_main:
    pushq %rbp
    movq %rsp, %rbp
    subq $16, %rsp
    leaq message(%rip), %rax
    movq %rax, -8(%rbp)
    movq -8(%rbp), %rsi     # 2nd arg
    leaq fmt(%rip), %rdi    # 1st arg
    xorl %eax, %eax
    callq _printf
    xorl %eax, %eax         # return 0
    addq $16, %rsp
    popq %rbp
    retq

.section __TEXT,__cstring
message:
    .asciz "Hello morning"
fmt:
    .asciz "%s"
```

> **⚠️ Views C & D are hand-written, not compiler output.** I deliberately dropped the scaffolding: no `.cfi_*`, no return slot, friendly labels `message` / `fmt` instead of `L_.str`, and I parked the pointer at `-8(%rbp)` where the compiler used `-16`. It still assembles and prints the same thing — the compiler is just more paranoid about alignment and unwinding than a human writing a toy needs to be.

---

## 6. Difference 1 — AT&T vs Intel is pure notation

This is the difference that used to trip me up constantly, and it turns out to be *only spelling*. Same instructions, same bytes, different way of writing them down:

| Concept | AT&T (A, B, D) | Intel (C) |
|---|---|---|
| Operand order | `mov src, dst` | `mov dst, src` ← reversed |
| Registers | `%rax` | `rax` |
| Immediates | `$16` | `16` |
| Memory / RIP | `message(%rip)` | `[rip + message]` |
| Operand size | suffix: `movq`, `leaq` | plain `mov`, `lea` |
| Header needed | (default) | `.intel_syntax noprefix` |

The operand-order flip is the big one. `mov %rax, %rsi` (AT&T) and `mov rsi, rax` (Intel) do the *same thing* — copy `rax` into `rsi`. Once you internalize "AT&T reads left-to-right as source-to-destination, Intel reads destination-first," most of the confusion evaporates.

---

## 7. Difference 2 — compiler vs hand-written

The other axis of difference is scaffolding. These all show up in the compiler's output and are absent from my hand-written versions:

| Trait | Compiler (A) | Hand-written (C, D) |
|---|---|---|
| `.cfi_*`, `.build_version`, `.p2align` | ✅ | — |
| `movl $0, -4(%rbp)` (return slot) | ✅ | — |
| Labels | `L_.str`, `L_.str.1` | `message`, `fmt` |
| Pointer stack slot | `-16(%rbp)` | `-8(%rbp)` |
| `.subsections_via_symbols` | ✅ | — |

None of it changes what the program *does*. It's bookkeeping for debuggers, exception unwinding, and alignment guarantees.

---

## 8. The payoff — read across one row

Here's the whole point of the exercise. Every row below is the **same step** of the program, expressed four ways. Reading across, the "four different assemblies" collapse into one:

| Step | A: compiler AT&T | B: bytes | D: hand AT&T | C: hand Intel |
|---|---|---|---|---|
| load message | `leaq L_.str(%rip),%rax` | `48 8d 05 24…` | `leaq message(%rip),%rax` | `lea rax,[rip+message]` |
| store ptr | `movq %rax,-16(%rbp)` | `48 89 45 f0` | `movq %rax,-8(%rbp)` | `mov [rbp-8],rax` |
| 2nd arg | `movq -16(%rbp),%rsi` | `48 8b 75 f0` | `movq -8(%rbp),%rsi` | `mov rsi,[rbp-8]` |
| 1st arg | `leaq L_.str.1(%rip),%rdi` | `48 8d 3d 23…` | `leaq fmt(%rip),%rdi` | `lea rdi,[rip+fmt]` |
| call | `callq _printf` | `e8 08 00…` | `callq _printf` | `call _printf` |
| return 0 | `xorl %eax,%eax` | `31 c0` | `xorl %eax,%eax` | `xor eax,eax` |

The bytes column is the ground truth — `48 8d 05` *is* `lea` of a RIP-relative address into `rax`, no matter which of the three text spellings you started from.

---

## 9. ⚠️ A bug that cost me ten minutes — smart quotes

If you copy assembly out of a note-taking app or a chat window, watch out for this one:

```
fmt:
    .asciz “%s”     ← curly/smart quotes — WRONG, the assembler rejects these
```

Typographic "curly" quotes (`“ ”`) come from editors that auto-format text. A compiler never emits them, and the assembler chokes on them with a confusing error. The fix is to retype them as straight ASCII quotes:

```
    .asciz "%s"
```

I now paste hand-written assembly through a plain-text editor first, specifically to strip this.

---

## 10. Build & run — all of it

```
clang -g -O0 hello.c -o hello_c        # the C source
clang -S -O0 hello.c                    # emit View A (compiler AT&T)
clang -S -O0 -masm=intel hello.c        # emit compiler Intel
clang hello_att.s   -o hello_att        # hand-written AT&T
clang hello_intel.s -o hello_intel      # hand-written Intel (.intel_syntax inside)
```

All of them print `Hello morning` and exit `0`.

---

## What I took away

One program compiles down to **one identical stream of x86-64 machine instructions**. Every "difference" I was tripping over falls into exactly three buckets:

1. **Notation** — AT&T vs Intel. Reversed operand order, `%`/`$` sigils, size suffixes. Spelling only; the bytes are identical.
2. **Packaging** — source text vs compiler assembly vs disassembled bytes-and-addresses. Same instructions, shown at different stages of the build. Linking is what turns labels into real addresses and routes `printf` through a stub.
3. **Scaffolding** — the compiler adds `.cfi_*`, machine-generated labels, and a return slot that a human writing the same thing by hand just skips. It doesn't change behavior.

And the detail that stuck with me most: in the finished binary, only the handful of instructions from `0x47F` to `0x4A3` are actually *my* `main`. Everything else the disassembler showed me was the `printf` stub and my two string literals — and those strings only looked like instructions because a linear disassembler can't tell code from data once it walks off the end of the function.
