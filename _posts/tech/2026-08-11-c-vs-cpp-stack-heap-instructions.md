---
layout: post
title: "C vs C++ at the instruction level: what stack_var and heap_var actually cost"
date: 2026-08-11
categories: tech
---

## How a program actually runs, from the file to the hardware

Before comparing instructions, it's worth walking through the *entire* pipeline between "you save a `.c` file" and "electricity moves in a transistor" — not the simplified textbook version, but every layer that actually runs.

### 1. Source file
Text on disk. `stack_vs_heap.c` is just bytes — meaningless until every layer below runs.

### 2. Preprocessor
Expands `#include`, `#define`, and macros into one flat blob of C, before the compiler proper ever sees the file. `#include <stdio.h>` is textually replaced with that header's actual contents, right here.

### 3. Compiler front end → IR → optimizer → assembly
Parses the blob into an AST, lowers it to an intermediate representation (LLVM IR for clang), runs it through the optimizer — skipped entirely at `-O0`, which is *why* this post compiles at `-O0`: so the assembly maps cleanly back to source instead of being rearranged — and emits **assembly**: human-readable text, one line per CPU operation.

### 4. Assembler → object file
Turns that assembly text into an **object file** (`.o`): real machine-code bytes, but with holes. It carries a symbol table listing names like `_malloc` and `_printf` that it calls but doesn't define — "someone else will provide these" is literally encoded in the file.

### 5. Static linker → executable
Combines your object file with the compile-time pieces of the runtime, and rewrites internal jump/call targets to real offsets inside the binary. It does **not** resolve `_malloc` or `_printf` yet — those live in a shared library, resolved one step later, at load time.

### 6. Dynamic linker → resolves shared libraries at load time
Before `main` runs at all, the OS hands control to a dynamic linker — `ld.so` on Linux, `dyld` on macOS. It finds `libSystem`/`libc` already sitting in memory — shared across every running process on the machine, which is the entire point: one physical copy of `printf`'s code serves every process, not one per binary — and patches your binary's call sites to point at its real address. Until this step runs, `callq _printf` in the disassembly is calling a placeholder stub, not `printf` itself.

### 7. OS loader → virtual address space
The OS gives your process its own **virtual address space**: a stack region, a heap region, code and data segments — addresses that look like real memory but aren't. **ASLR** randomizes where these regions actually land, which is why `&stack_var` prints a different-looking address on every run.

### 8. MMU + page tables → virtual-to-physical translation
Every memory access — every `mov`, every stack push — goes through the **MMU**, which translates the virtual address the CPU thinks it's touching into a real physical RAM address by walking **page tables** the OS set up. A **TLB** caches recent translations so this doesn't happen the slow way on every single access. This step is invisible in the disassembly and invisible in this post's instruction comparison — but it fires on every line of it.

### 9. CPU fetch-decode-execute — what that means on real hardware
The textbook model is fetch → decode → execute → repeat. What a real chip does:
- x86-64 instructions like `mov`/`call`/`ret` are decoded into **micro-ops** via a **microcode** layer — the instruction set itself is an abstraction over what the silicon actually does.
- The CPU doesn't execute one instruction at a time in order. It runs **out-of-order**, executing whichever decoded micro-op has its inputs ready first, and only reorders results back into program order when something else depends on that order being visible.
- **Branch prediction** guesses which way a `cmp` + `je` will go before the comparison result exists, and starts executing down that guessed path speculatively — wrong guesses get discarded, right ones save dozens of cycles.
- Instructions and data are pulled through an **L1 → L2 → L3 cache** hierarchy before ever reaching RAM. A cache miss costs roughly 100x a cache hit — a big part of *why* stack access (almost always cache-hot, since it's small and recently touched) is cheap and heap access (colder, more scattered) can be comparatively expensive. That cost is invisible at the instruction level, which is exactly why this post's side-by-side disassembly can show `malloc` and `new` as "the same shape" while their real-world cost still differs.

### 10. Syscalls — when the program needs the kernel
Every `printf` call in this post eventually has to put bytes on your terminal, and user-space code isn't allowed to touch hardware directly. `printf` formats the string into a buffer, then issues a **syscall** — `write()` — which traps into the kernel, switching the CPU into a higher privilege ring. The kernel copies those bytes to the terminal driver, which is what actually puts characters on your screen. That's a full privilege-level context switch, not a `callq`.

### 11. The physical layer
Every bit — every register bit, every stack byte, every opcode — is a **voltage level** on a wire: high or low, nothing else. **Transistors** are switches; wired together as logic gates (AND, OR, NOT, NAND), they implement the ALU's adder, the branch's comparator, the memory controller's read logic. A **clock oscillator** generates a timing signal — often billions of pulses a second — and every stage above (fetch, decode, execute, cache lookup, page-table walk) advances one step per pulse. There's no layer below this one: it's current moving through silicon, paced by a crystal.

---

Steps 3–5 are what this post actually puts side by side: the assembly two compilers produce for the same C-shaped code, and the one place their output meaningfully diverges (`free` vs. `delete` — step 5's job, done differently). Steps 6–11 are the ground floor underneath every instruction in that comparison — the reason `callq _malloc` in a disassembly listing isn't the whole story, just the visible tip of a pipeline that ends in a voltage change.

---

Every "C++ has overhead" conversation eventually turns into an opinion war. I wanted a number instead of an opinion, so I wrote the smallest possible program that touches both the stack and the heap, compiled it in C and in C++, and put `llvm-objdump` on both binaries. Same source shape, same optimization level, same machine. Whatever differs in the disassembly is the real cost of the language — not a guess about it.

---

## The two programs

`stack_vs_heap.c` — one stack int, one heap int, print both, print their raw bytes, free the heap one:

```c
#include <stdio.h>
#include <stdlib.h>

int main() {
  int stack_var = 5;                   // lives on the STACK
  int *heap_var = malloc(sizeof(int)); // lives on the HEAP
  *heap_var = 5;

  printf("stack_var  -> address: %p   value: %d\n", (void *)&stack_var,
         stack_var);
  printf("heap_var   -> address: %p   value: %d\n", (void *)heap_var,
         *heap_var);

  unsigned char *s_bytes = (unsigned char *)&stack_var;
  unsigned char *h_bytes = (unsigned char *)heap_var;
  printf("stack raw bytes: %02x %02x %02x %02x\n", s_bytes[0], s_bytes[1],
         s_bytes[2], s_bytes[3]);
  printf("heap  raw bytes: %02x %02x %02x %02x\n", h_bytes[0], h_bytes[1],
         h_bytes[2], h_bytes[3]);

  free(heap_var);
  return 0;
}
```

`stack_vs_heap_min.cpp` — the exact same program, `new`/`delete` in place of `malloc`/`free`, still plain `printf` (no `iostream`, deliberately — I wanted to isolate the allocator, not drag in stream operator overloading too):

```cpp
#include <cstdio>

int main() {
  int stack_var = 5;                // lives on the STACK
  int *heap_var = new int;          // lives on the HEAP (C++ equivalent of malloc)
  *heap_var = 5;

  printf("stack_var  -> address: %p   value: %d\n", (void *)&stack_var,
         stack_var);
  printf("heap_var   -> address: %p   value: %d\n", (void *)heap_var,
         *heap_var);

  unsigned char *s_bytes = (unsigned char *)&stack_var;
  unsigned char *h_bytes = (unsigned char *)heap_var;
  printf("stack raw bytes: %02x %02x %02x %02x\n", s_bytes[0], s_bytes[1],
         s_bytes[2], s_bytes[3]);
  printf("heap  raw bytes: %02x %02x %02x %02x\n", h_bytes[0], h_bytes[1],
         h_bytes[2], h_bytes[3]);

  delete heap_var;
  return 0;
}
```

Compiled both the same way, at `-O0` so the compiler doesn't optimize either one into something unrecognizable:

```bash
clang   -g -O0 -o stack_vs_heap     stack_vs_heap.c
clang++ -g -O0 -std=c++17 -o stack_vs_heap_min stack_vs_heap_min.cpp

llvm-objdump -d --disassemble-symbols=_main stack_vs_heap
llvm-objdump -d --disassemble-symbols=_main stack_vs_heap_min
```

---

## Side by side, instruction by instruction

Addresses differ because the C++ binary's `main` starts at a different file offset and ends up with a slightly bigger stack frame — so I'm aligning by what each block of instructions *does*, not by raw address.

```
STEP                  C (stack_vs_heap)                     C++ (stack_vs_heap_min)
--------------------------------------------------------------------------------------------------
prologue              pushq %rbp                            pushq %rbp
                      movq  %rsp, %rbp                      movq  %rsp, %rbp
                      subq  $0x20, %rsp   ; 32-byte frame   subq  $0x30, %rsp   ; 48-byte frame

stack_var = 5         movl  $0x5, -0x8(%rbp)                movl  $0x5, -0x8(%rbp)   [IDENTICAL]

allocate heap int     movl  $0x4, %edi                      movl  $0x4, %edi
                      callq _malloc                         callq operator_new(ulong)

store heap_var ptr    movq  %rax, -0x10(%rbp)               movq  %rax, -0x10(%rbp)   [IDENTICAL]

*heap_var = 5         movq  -0x10(%rbp), %rax               movq  -0x10(%rbp), %rax
                      movl  $0x5, (%rax)                    movl  $0x5, (%rax)   [IDENTICAL]

printf(stack_var)     leaq  fmt(%rip), %rdi                 leaq  fmt(%rip), %rdi
                      leaq  -0x8(%rbp), %rsi                leaq  -0x8(%rbp), %rsi
                      callq _printf                         callq _printf   [IDENTICAL]

printf(heap_var)      movq  -0x10(%rbp), %rsi               movq  -0x10(%rbp), %rsi
                      movl  (%rax), %edx                    movl  (%rax), %edx
                      callq _printf                         callq _printf   [IDENTICAL]

raw-byte loop         movzbl (%rax), %esi  (x4 per var)     movzbl (%rax), %esi  (x4 per var)
(unrolled, no loop,   callq _printf                         callq _printf
4 bytes each)                                               [IDENTICAL]

free / delete         movq -0x10(%rbp), %rdi                movq -0x10(%rbp), %rax
                      callq _free                           movq %rax, -0x28(%rbp)
                                                             cmpq $0x0, %rax
                                                             je   <skip>            ; null check
                                                             movq -0x28(%rbp), %rdi
                                                             movl $0x4, %esi        ; size arg
                                                             callq operator_delete(void*,size_t)
                                                             <skip>:

epilogue              xorl  %eax, %eax                      xorl  %eax, %eax
                      addq  $0x20, %rsp                     addq  $0x30, %rsp
                      popq  %rbp                            popq  %rbp
                      retq                                  retq
```

---

## The two places the languages actually diverge

**1. `malloc(4)` and `new int` compile to the same shape.**

```
bf 04 00 00 00   movl $0x4, %edi   ; size argument, either way
callq            ; _malloc, or operator new(unsigned long)
```

Same argument register, same result register, same call site pattern. `new int` really is "ask the allocator for 4 bytes" at the machine level — `operator new` is usually a thin wrapper around `malloc` itself.

**2. `delete` does more work than `free`, and it's spec-mandated, not accidental.**

```
C:    movq -0x10(%rbp), %rdi
      callq _free                           ; unconditional

C++:  movq -0x10(%rbp), %rax
      movq %rax, -0x28(%rbp)
      cmpq $0x0, %rax
      je   <skip>                           ; null-check first
      movq -0x28(%rbp), %rdi
      movl $0x4, %esi                       ; sized deallocation
      callq operator_delete(void*, size_t)
      <skip>:
```

Two language rules fall straight out of this:

- **`delete` on a null pointer must be a guaranteed no-op**, so the compiler inserts the check before the call. `free(NULL)` is already defined as safe by the C standard, and that safety lives *inside* `free`, not at the call site — so C never needs the branch.
- **Sized deallocation (C++17)**: the compiler knows `heap_var` points at exactly 4 bytes, so it hands that size straight to `operator delete(void*, size_t)`, letting the allocator skip looking the block size up itself. `free()` has no equivalent — it always looks the size up internally.

Everything else — the stack write, the heap write, both `printf` calls, the byte-by-byte raw-byte prints — is line-for-line identical between the two binaries.

---

## Why an earlier, class-based version looked completely different

Before narrowing it down to two plain ints, I ran the same experiment with a small `Person` class (`char name[16]`, `int age`) constructed once on the stack and once with `new` on the heap, printed through `std::cout` instead of `printf`. That version's `main` came out **four times longer** in the C++ binary — but none of the extra size was stack-vs-heap cost. It was three unrelated things stacking up:

- **Every `cout << x` is a separate function call**, resolved per type at compile time (`operator<<` for a string, then for a `char*`, then for an `int`). A single chained `cout` line expands into as many `callq` instructions as it has `<<` operators, where the equivalent `printf` call is one instruction.
- **Constructors and destructors are real function calls with mangled symbols** (`Person::Person(char const*, int)` becomes `__ZN6PersonC1EPKci` in the binary), and the compiler silently inserts a destructor call at *every* exit path of a function that owns a stack object — that's what makes an object's cleanup impossible to forget on the stack, and entirely your job on the heap via `delete`.
- **A stack-resident fixed buffer (`char name[16]`) triggers a stack-protector canary** — a guard value pushed on entry and checked before every return, unrelated to allocation and purely there to catch a buffer overflow clobbering the return address.

None of those three are "heap vs stack" costs. They're costs of *other* C++ features (operator overloading, RAII, buffer-overflow hardening) that happened to ride along because the demo used a class instead of a bare `int`. Once the plain-int version stripped all three away, the stack-vs-heap disassembly came back looking almost identical between the two languages — which was the actual answer I was after.

---

## What I took away

- **Stack vs heap allocation itself costs the same in C and C++.** `new`/`delete` are calling conventions wrapped around the same allocator C already had.
- **The only real per-object cost `delete` adds over `free` is a null-check and a size argument** — both there to satisfy a language guarantee, not compiler sloppiness.
- **Every other "C++ is heavier" result I'd seen before this was actually iostream, RAII destructor calls, or stack-protector overhead** — real costs, but costs of features you opted into by using a class, not costs of the language's memory model.
- If you want to know what a language feature "really costs," writing the smallest program that isolates *just* that feature and reading the disassembly beats reasoning about it from first principles every time.
