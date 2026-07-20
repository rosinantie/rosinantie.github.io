---
layout: post
title: "How a C program becomes assembly — one small program, from source to silicon"
date: 2026-07-19
categories: tech
---

Last time I took one tiny program and stared at its assembly from four angles until the "different assemblies" turned out to be mostly spelling. This time I want the whole picture in one place: take one small C program, follow it all the way down, and see *every* C construct turn into the exact instructions the CPU runs. Local variables, function calls, arithmetic, `if`, `for`, `printf`, pointers, arrays, `malloc`/`free` — each one has a machine-level shape, and once you've seen the shape it stops being mysterious.

No single instruction here is hard. The point is the *mapping*: every line of C is a small, predictable pattern in assembly, and the "magic" of a running program is just a few of those patterns stacked up.

Platform note: everything here is **x86-64 on macOS**, built with `clang -O0` so the assembly stays literal — one C idea per instruction, nothing optimized away.

---

## The program

A little calculator: four arithmetic functions, a loop that prints even/odd, and one function that builds an array `[0,1,2,3,4]` and returns it.

```c
#include <stdio.h>
#include <stdlib.h>

int *make_sequence(int n) {
  int *arr = malloc(n * sizeof(int));   // ask the heap for n ints
  if (arr == NULL) {
    return NULL;                        // malloc failed — bail out
  }
  for (int i = 0; i < n; i++) {
    arr[i] = i;
  }
  return arr; // the heap block lives on after this frame is destroyed
}

int add(int a, int b, int x, int o, int p) { return a + b + x + o + p; }
int sub(int a, int b) { return a - b; }
int multiply(int a, int b) { return a * b; }
int divide(int a, int b) { return a / b; }

int main(void) {
  int a = 20;
  int b = 10;

  int sum = add(a, b, 1, 2, 3);
  int diff = sub(a, b);
  int product = multiply(a, b);
  int quotient = divide(a, b);

  printf("Sum       = %d\n", sum);
  printf("Difference= %d\n", diff);
  printf("Product   = %d\n", product);
  printf("Quotient  = %d\n", quotient);

  printf("\nLoop:\n");

  int total = 0;
  for (int i = 0; i < 5; i++) {
    total += i;
    if (i % 2 == 0) {
      printf("%d is even\n", i);
    } else {
      printf("%d is odd\n", i);
    }
  }
  printf("Total = %d\n", total);

  int *sequence = make_sequence(5);
  if (sequence != NULL) {
    printf("\nSequence:\n");
    for (int i = 0; i < 5; i++) {
      printf("%d ", sequence[i]);
    }
    printf("\n");
    free(sequence); // release the heap memory
  }

  return 0;
}
```

Build and run:

```
clang -g -O0 calc.c -o calc && ./calc
```

```
Sum       = 36
Difference= 10
Product   = 200
Quotient  = 2

Loop:
0 is even
1 is odd
2 is even
3 is odd
4 is even
Total = 10

Sequence:
0 1 2 3 4
```

---

## Where everything lives while it runs

Before the instructions, here's the map I keep in my head of *where* each piece of the program sits while it executes — from the binary on disk, through RAM and the CPU, out to the kernel and finally the pixels on screen.

![Full system overview — disk, RAM's four regions, CPU, kernel, terminal and result, showing where each part of the program lives while it runs](/How-The-program-Works/full_system_overview_updated (1).png)

Reading it top to bottom:

- **Disk** holds the binary at rest — the machine bytes of my six functions plus the format strings. `malloc`, `free`, and `printf` aren't in my file; `dyld` links them from libc at launch.
- **RAM** has four regions: **code pages** (read-only instructions), **string pages** (the 11 `printf` format strings), the **stack** (automatic, frame-sized locals like `a`, `b`, `sum`, `i`), and the **heap** (the `malloc`'d block that outlives the function that made it).
- **The CPU** is the worker: the control unit does fetch → decode → execute, `rip` walking the bytes; registers hold the working values; the ALU does the arithmetic; the MMU translates every address.
- **The kernel** is how the program touches the outside world — `write()` is where every `printf` ends up, and `mmap()` is how `malloc` gets fresh pages when it needs them.
- **Terminal → GPU → display** turns the bytes `write()` delivered into glyphs you can read.

The one distinction worth carrying into the assembly: **the stack is automatic and frame-bound; the heap is manual and yours until you free it.**

---

## A one-screen assembly primer

Every function below is built from the same small vocabulary. If these click, the full listing reads like prose.

**Registers** are the CPU's built-in variables — a handful of 64-bit slots. The same register has different names by width: `rax` (64-bit) / `eax` (low 32) / `al` (low 8) are all the *same* register.

| Register | Role here |
|---|---|
| `eax`/`rax` | scratch + **return value** (a function's result comes back here) |
| `edi, esi, edx, ecx, r8d, r9d` | **1st–6th integer arguments**, in that order (System V ABI) |
| `rbp` | **base pointer** — anchor for the current stack frame; locals are `[rbp - N]` |
| `rsp` | **stack pointer** — top of the stack |
| `rip` | instruction pointer — address of the next instruction |

**The instructions used, in plain English:**

| Instruction | Means |
|---|---|
| `mov dst, src` | copy a value (Intel syntax: destination first) |
| `lea` | compute an address, like `&x` — no memory read |
| `push` / `pop` | put/take a value on/off the stack |
| `call` / `ret` | jump into a function / return from it |
| `add` / `sub` / `imul` | integer add / subtract / multiply |
| `cdq` + `idiv` | signed divide: quotient → `eax`, remainder → `edx` |
| `shl r, 2` | shift left 2 bits = multiply by 4 |
| `movsxd` | copy a 32-bit value into a 64-bit register, sign-extended |
| `cmp` + `jne`/`jge`/`je` | compare, then jump if a condition holds |
| `jmp` | unconditional jump |
| `xor eax, eax` | two-byte idiom for "set `eax` to 0" |

**Two rituals you'll see in every function:**

- **The frame prologue/epilogue.** Every function opens with `push rbp; mov rbp, rsp; sub rsp, N` (save the old anchor, set a new one, reserve `N` bytes for locals) and closes with `add rsp, N; pop rbp; ret`. That's just "open a frame" / "close a frame."
- **`mov al, 0` before every `printf`.** `printf` is *variadic*, and the ABI requires `al` to hold how many vector (float) registers were used for arguments. We pass none, so `al = 0`. It shows up before all 14 `printf` calls.

That's the whole toolkit. Now the mapping.

---

## Every C construct → its assembly

### Local variables are stack slots

`int a = 20; int b = 10;` becomes two writes to fixed offsets from `rbp`:

```
mov dword ptr [rbp - 8], 20      ; a = 20
mov dword ptr [rbp - 12], 10     ; b = 10
```

A variable *is* a labelled spot in the current frame. `dword` = 32 bits, because `int` is 4 bytes.

### A function call is "load the registers, then `call`"

`add(a, b, 1, 2, 3)` loads the five arguments into the ABI's argument registers and calls. The fifth spills into `r8d` — you can literally watch the argument order:

```
mov edi, dword ptr [rbp - 8]     ; 1st arg = a
mov esi, dword ptr [rbp - 12]    ; 2nd arg = b
mov edx, 1                       ; 3rd arg
mov ecx, 2                       ; 4th arg
mov r8d, 3                       ; 5th arg
call _add
mov dword ptr [rbp - 16], eax    ; sum = return value (in eax)
```

Inside `add`, the arguments are copied to its own frame and summed in `eax`, which is exactly where the caller reads the result. **Arguments go out in `edi, esi, …`; the answer comes back in `eax`.** That single convention is what makes functions composable.

### Arithmetic is one ALU instruction each

```
add  eax, ...     ; +
sub  eax, ...     ; -
imul eax, ...      ; *
cdq / idiv ...     ; /  (quotient in eax, remainder in edx)
```

Division is the interesting one: `cdq` sign-extends `eax` into the `edx:eax` pair, then `idiv` leaves the **quotient in `eax`** and the **remainder in `edx`**. That's why `i % 2` uses the *same* `idiv` — the even/odd test just reads `edx` (the remainder) instead of `eax`.

### `if` / `for` are `cmp` + a jump

The even/odd branch is a compare and a conditional jump:

```
cdq
idiv ecx                 ; ecx = 2  →  remainder lands in edx
cmp  edx, 0              ; i % 2 == 0 ?
jne  LBB5_4              ; nonzero → jump to the "odd" branch
... "even" branch ...
jmp  LBB5_5
LBB5_4: ... "odd" branch ...
```

A `for` loop is the same idea wrapped in a back-edge — initialize, test-and-exit at the top, body, increment, then `jmp` back to the test:

```
mov dword ptr [rbp - 36], 0      ; i = 0
LBB5_1:
    cmp dword ptr [rbp - 36], 5  ; i < 5 ?
    jge LBB5_7                    ; no → leave the loop
    ... body ...
    add eax, 1                    ; i++
    jmp LBB5_1                    ; back to the test
LBB5_7:
```

Structured control flow doesn't exist at the machine level — it's all `cmp` and `jmp` to labels.

### `printf` — load args, zero `al`, load the format address

```
mov esi, dword ptr [rbp - 16]    ; 2nd arg = the value
lea rdi, [rip + L_.str]          ; 1st arg = address of "Sum       = %d\n"
mov al, 0                        ; 0 vector args (variadic rule)
call _printf
```

`lea rdi, [rip + L_.str]` is how a string constant becomes an argument: the address is computed **relative to `rip`**, so the code doesn't care where the OS loaded it. Those `L_.str` labels live in a separate `__cstring` section at the very bottom of the listing — the "string pages" from the system diagram.

### Pointers, arrays, and the heap

This is the part that finally made pointers concrete for me, so it gets its own diagram. Everything here is the same primitives — registers, `mov`, address math — pointed at heap memory instead of the stack.

![C to assembly — malloc, the NULL check, arr[i] indexing, returning a pointer, and free, each mapped from C to the instructions clang produced](/How-The-program-Works/c_to_assembly_updated.png)

Walking `make_sequence` against the assembly:

```
_make_sequence:
    push  rbp
    mov   rbp, rsp
    sub   rsp, 32
    mov   dword ptr [rbp - 12], edi        ; save n
    movsxd rdi, dword ptr [rbp - 12]       ; rdi = n, widened to 64-bit
    shl   rdi, 2                           ; rdi = n * 4   (sizeof int)
    call  _malloc                          ; address of the block comes back in rax
    mov   qword ptr [rbp - 24], rax        ; arr = that address
    cmp   qword ptr [rbp - 24], 0          ; arr == NULL ?
    jne   LBB0_2
    mov   qword ptr [rbp - 8], 0           ; return NULL
    jmp   LBB0_7
LBB0_2:
    mov   dword ptr [rbp - 28], 0          ; i = 0
LBB0_3:
    mov   eax, dword ptr [rbp - 28]
    cmp   eax, dword ptr [rbp - 12]        ; i < n ?
    jge   LBB0_6
    mov   edx, dword ptr [rbp - 28]        ; edx = i
    mov   rax, qword ptr [rbp - 24]        ; rax = arr (base address)
    movsxd rcx, dword ptr [rbp - 28]       ; rcx = i (index)
    mov   dword ptr [rax + 4*rcx], edx     ; arr[i] = i
    ...
LBB0_6:
    mov   rax, qword ptr [rbp - 24]
    mov   qword ptr [rbp - 8], rax         ; return value = arr
LBB0_7:
    mov   rax, qword ptr [rbp - 8]
    add   rsp, 32
    pop   rbp
    ret
```

Five things to take from it:

1. **`malloc(n * sizeof(int))`** — `× 4` becomes `shl rdi, 2`, and the size math uses **64-bit** registers (`rdi`, `rax`) because a pointer is 8 bytes. The block's address returns in `rax`. (`movsxd` widens the 32-bit `n` to 64 bits first so the address arithmetic is clean.)
2. **`if (arr == NULL)`** is `cmp ..., 0` — **`NULL` is literally zero.** One compare, one jump.
3. **`arr[i] = i`** is a single instruction: `mov dword ptr [rax + 4*rcx], edx`. An array subscript is just `base + scale × index` address math the CPU does in hardware. That *is* what an array is.
4. **`return arr`** returns only the 8-byte *address* in `rax`. The frame is torn down (`add rsp, 32; pop rbp; ret`) but the heap block survives — which is exactly why you can't return a *local* array (its frame is reclaimed) and exactly why `malloc` exists.
5. **`free(sequence)`** (over in `main`) is just `mov rdi, ...; call _free` — hand the address back. Touch it afterwards and you've written a use-after-free.

### `return 0`

The program ends with the idiom for zero:

```
xor eax, eax     ; return 0
add rsp, 64
pop rbp
ret
```

---

## The full assembly

Here's the *entire* listing `clang -S -O0 -masm=intel` produced — every function, the `main` that ties them together, the compiler scaffolding, and the string literals. With the primer above, there shouldn't be a single unexplained line:

```
    .section    __TEXT,__text,regular,pure_instructions
    .build_version macos, 26, 0    sdk_version 26, 2
    .intel_syntax noprefix
    .globl    _make_sequence                  ## -- Begin function make_sequence
    .p2align    4, 0x90
_make_sequence:                         ## @make_sequence
    .cfi_startproc
## %bb.0:
    push    rbp
    .cfi_def_cfa_offset 16
    .cfi_offset rbp, -16
    mov    rbp, rsp
    .cfi_def_cfa_register rbp
    sub    rsp, 32
    mov    dword ptr [rbp - 12], edi
    movsxd    rdi, dword ptr [rbp - 12]
    shl    rdi, 2
    call    _malloc
    mov    qword ptr [rbp - 24], rax
    cmp    qword ptr [rbp - 24], 0
    jne    LBB0_2
## %bb.1:
    mov    qword ptr [rbp - 8], 0
    jmp    LBB0_7
LBB0_2:
    mov    dword ptr [rbp - 28], 0
LBB0_3:                                 ## =>This Inner Loop Header: Depth=1
    mov    eax, dword ptr [rbp - 28]
    cmp    eax, dword ptr [rbp - 12]
    jge    LBB0_6
## %bb.4:                               ##   in Loop: Header=BB0_3 Depth=1
    mov    edx, dword ptr [rbp - 28]
    mov    rax, qword ptr [rbp - 24]
    movsxd    rcx, dword ptr [rbp - 28]
    mov    dword ptr [rax + 4*rcx], edx
## %bb.5:                               ##   in Loop: Header=BB0_3 Depth=1
    mov    eax, dword ptr [rbp - 28]
    add    eax, 1
    mov    dword ptr [rbp - 28], eax
    jmp    LBB0_3
LBB0_6:
    mov    rax, qword ptr [rbp - 24]
    mov    qword ptr [rbp - 8], rax
LBB0_7:
    mov    rax, qword ptr [rbp - 8]
    add    rsp, 32
    pop    rbp
    ret
    .cfi_endproc
                                        ## -- End function
    .globl    _add                            ## -- Begin function add
    .p2align    4, 0x90
_add:                                   ## @add
    .cfi_startproc
## %bb.0:
    push    rbp
    .cfi_def_cfa_offset 16
    .cfi_offset rbp, -16
    mov    rbp, rsp
    .cfi_def_cfa_register rbp
    mov    dword ptr [rbp - 4], edi
    mov    dword ptr [rbp - 8], esi
    mov    dword ptr [rbp - 12], edx
    mov    dword ptr [rbp - 16], ecx
    mov    dword ptr [rbp - 20], r8d
    mov    eax, dword ptr [rbp - 4]
    add    eax, dword ptr [rbp - 8]
    add    eax, dword ptr [rbp - 12]
    add    eax, dword ptr [rbp - 16]
    add    eax, dword ptr [rbp - 20]
    pop    rbp
    ret
    .cfi_endproc
                                        ## -- End function
    .globl    _sub                            ## -- Begin function sub
    .p2align    4, 0x90
_sub:                                   ## @sub
    .cfi_startproc
## %bb.0:
    push    rbp
    .cfi_def_cfa_offset 16
    .cfi_offset rbp, -16
    mov    rbp, rsp
    .cfi_def_cfa_register rbp
    mov    dword ptr [rbp - 4], edi
    mov    dword ptr [rbp - 8], esi
    mov    eax, dword ptr [rbp - 4]
    sub    eax, dword ptr [rbp - 8]
    pop    rbp
    ret
    .cfi_endproc
                                        ## -- End function
    .globl    _multiply                       ## -- Begin function multiply
    .p2align    4, 0x90
_multiply:                              ## @multiply
    .cfi_startproc
## %bb.0:
    push    rbp
    .cfi_def_cfa_offset 16
    .cfi_offset rbp, -16
    mov    rbp, rsp
    .cfi_def_cfa_register rbp
    mov    dword ptr [rbp - 4], edi
    mov    dword ptr [rbp - 8], esi
    mov    eax, dword ptr [rbp - 4]
    imul    eax, dword ptr [rbp - 8]
    pop    rbp
    ret
    .cfi_endproc
                                        ## -- End function
    .globl    _divide                         ## -- Begin function divide
    .p2align    4, 0x90
_divide:                                ## @divide
    .cfi_startproc
## %bb.0:
    push    rbp
    .cfi_def_cfa_offset 16
    .cfi_offset rbp, -16
    mov    rbp, rsp
    .cfi_def_cfa_register rbp
    mov    dword ptr [rbp - 4], edi
    mov    dword ptr [rbp - 8], esi
    mov    eax, dword ptr [rbp - 4]
    cdq
    idiv    dword ptr [rbp - 8]
    pop    rbp
    ret
    .cfi_endproc
                                        ## -- End function
    .globl    _main                           ## -- Begin function main
    .p2align    4, 0x90
_main:                                  ## @main
    .cfi_startproc
## %bb.0:
    push    rbp
    .cfi_def_cfa_offset 16
    .cfi_offset rbp, -16
    mov    rbp, rsp
    .cfi_def_cfa_register rbp
    sub    rsp, 64
    mov    dword ptr [rbp - 4], 0
    mov    dword ptr [rbp - 8], 20
    mov    dword ptr [rbp - 12], 10
    mov    edi, dword ptr [rbp - 8]
    mov    esi, dword ptr [rbp - 12]
    mov    edx, 1
    mov    ecx, 2
    mov    r8d, 3
    call    _add
    mov    dword ptr [rbp - 16], eax
    mov    edi, dword ptr [rbp - 8]
    mov    esi, dword ptr [rbp - 12]
    call    _sub
    mov    dword ptr [rbp - 20], eax
    mov    edi, dword ptr [rbp - 8]
    mov    esi, dword ptr [rbp - 12]
    call    _multiply
    mov    dword ptr [rbp - 24], eax
    mov    edi, dword ptr [rbp - 8]
    mov    esi, dword ptr [rbp - 12]
    call    _divide
    mov    dword ptr [rbp - 28], eax
    mov    esi, dword ptr [rbp - 16]
    lea    rdi, [rip + L_.str]
    mov    al, 0
    call    _printf
    mov    esi, dword ptr [rbp - 20]
    lea    rdi, [rip + L_.str.1]
    mov    al, 0
    call    _printf
    mov    esi, dword ptr [rbp - 24]
    lea    rdi, [rip + L_.str.2]
    mov    al, 0
    call    _printf
    mov    esi, dword ptr [rbp - 28]
    lea    rdi, [rip + L_.str.3]
    mov    al, 0
    call    _printf
    lea    rdi, [rip + L_.str.4]
    mov    al, 0
    call    _printf
    mov    dword ptr [rbp - 32], 0
    mov    dword ptr [rbp - 36], 0
LBB5_1:                                 ## =>This Inner Loop Header: Depth=1
    cmp    dword ptr [rbp - 36], 5
    jge    LBB5_7
## %bb.2:                               ##   in Loop: Header=BB5_1 Depth=1
    mov    eax, dword ptr [rbp - 36]
    add    eax, dword ptr [rbp - 32]
    mov    dword ptr [rbp - 32], eax
    mov    eax, dword ptr [rbp - 36]
    mov    ecx, 2
    cdq
    idiv    ecx
    cmp    edx, 0
    jne    LBB5_4
## %bb.3:                               ##   in Loop: Header=BB5_1 Depth=1
    mov    esi, dword ptr [rbp - 36]
    lea    rdi, [rip + L_.str.5]
    mov    al, 0
    call    _printf
    jmp    LBB5_5
LBB5_4:                                 ##   in Loop: Header=BB5_1 Depth=1
    mov    esi, dword ptr [rbp - 36]
    lea    rdi, [rip + L_.str.6]
    mov    al, 0
    call    _printf
LBB5_5:                                 ##   in Loop: Header=BB5_1 Depth=1
    jmp    LBB5_6
LBB5_6:                                 ##   in Loop: Header=BB5_1 Depth=1
    mov    eax, dword ptr [rbp - 36]
    add    eax, 1
    mov    dword ptr [rbp - 36], eax
    jmp    LBB5_1
LBB5_7:
    mov    esi, dword ptr [rbp - 32]
    lea    rdi, [rip + L_.str.7]
    mov    al, 0
    call    _printf
    mov    edi, 5
    call    _make_sequence
    mov    qword ptr [rbp - 48], rax
    cmp    qword ptr [rbp - 48], 0
    je    LBB5_13
## %bb.8:
    lea    rdi, [rip + L_.str.8]
    mov    al, 0
    call    _printf
    mov    dword ptr [rbp - 52], 0
LBB5_9:                                 ## =>This Inner Loop Header: Depth=1
    cmp    dword ptr [rbp - 52], 5
    jge    LBB5_12
## %bb.10:                              ##   in Loop: Header=BB5_9 Depth=1
    mov    rax, qword ptr [rbp - 48]
    movsxd    rcx, dword ptr [rbp - 52]
    mov    esi, dword ptr [rax + 4*rcx]
    lea    rdi, [rip + L_.str.9]
    mov    al, 0
    call    _printf
## %bb.11:                              ##   in Loop: Header=BB5_9 Depth=1
    mov    eax, dword ptr [rbp - 52]
    add    eax, 1
    mov    dword ptr [rbp - 52], eax
    jmp    LBB5_9
LBB5_12:
    lea    rdi, [rip + L_.str.10]
    mov    al, 0
    call    _printf
    mov    rdi, qword ptr [rbp - 48]
    call    _free
LBB5_13:
    xor    eax, eax
    add    rsp, 64
    pop    rbp
    ret
    .cfi_endproc
                                        ## -- End function
    .section    __TEXT,__cstring,cstring_literals
L_.str:                                 ## @.str
    .asciz    "Sum       = %d\n"

L_.str.1:                               ## @.str.1
    .asciz    "Difference= %d\n"

L_.str.2:                               ## @.str.2
    .asciz    "Product   = %d\n"

L_.str.3:                               ## @.str.3
    .asciz    "Quotient  = %d\n"

L_.str.4:                               ## @.str.4
    .asciz    "\nLoop:\n"

L_.str.5:                               ## @.str.5
    .asciz    "%d is even\n"

L_.str.6:                               ## @.str.6
    .asciz    "%d is odd\n"

L_.str.7:                               ## @.str.7
    .asciz    "Total = %d\n"

L_.str.8:                               ## @.str.8
    .asciz    "\nSequence:\n"

L_.str.9:                               ## @.str.9
    .asciz    "%d "

L_.str.10:                              ## @.str.10
    .asciz    "\n"

.subsections_via_symbols
```

The lines starting with `.` are **directives, not instructions** — bookkeeping for the assembler and linker, none of which the CPU executes:

- `.section`, `.globl`, `.p2align` — where code/data goes, which symbols are visible, how to align them.
- `.cfi_*` — call-frame info, so debuggers can unwind the stack; pure metadata.
- `.build_version`, `.subsections_via_symbols` — platform and linker hints.
- `## %bb.N` and the `LBBx_y` names — the compiler's basic-block labels; only the ones that are jump targets matter.

Strip all of that and you're left with exactly the instructions the primer covered.

---

## One detail you can verify yourself

`main` opens its frame with `sub rsp, 64`. Count the locals it needs — `a, b, sum, diff, product, quotient, total`, two loop counters, and the 8-byte `sequence` pointer — and you'll land near that number once you round up for 16-byte stack alignment. The compiler tallied every variable and sized the frame for me. Reading a frame size and reconstructing *why* it's that number is my favorite five-second check that I actually understand what's on the stack.

---

## What I took away

A whole program is just a handful of patterns, repeated:

1. **A variable is a stack slot.** `[rbp - N]`, sized by its type.
2. **A function call is a calling convention.** Args into `edi, esi, …`; result back in `eax`; frame opened and closed around it.
3. **Control flow is `cmp` + `jmp`.** `if`, `else`, and `for` are all compares and jumps to labels — structure is a fiction the compiler maintains for us.
4. **A pointer is an address in a register**, and **an array is `base + scale × index`** — arithmetic, not a container.
5. **The stack is automatic, the heap is manual.** `malloc` hands you memory that outlives its function; `free` gives it back.

Same CPU, same RAM, same fetch-decode-execute underneath every line. Once you can read one small program end to end, every bigger one is just more of the same patterns stacked higher.
