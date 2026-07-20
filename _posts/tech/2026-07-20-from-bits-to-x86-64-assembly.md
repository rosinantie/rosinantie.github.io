---
layout: post
title: "From bits to x86-64 assembly — the foundations I needed before the instructions made sense"
date: 2026-07-20
categories: tech
---

I kept trying to read x86-64 assembly and bouncing off it. Not because any single instruction was hard, but because assembly quietly assumes you already know a whole stack of ideas underneath it — what a byte *is*, why everyone writes addresses in hex, what a register actually holds, where a variable lives. Every time I hit `mov dword ptr [rbp - 8], 20` I was really tripping over the foundations, not the instruction.

So this post is me laying those foundations down in order, each one building on the last, until assembly is the obvious final step instead of a wall. Nine ideas, bottom to top:

1. Binary (bits and bytes)
2. Decimal ↔ Binary ↔ Hexadecimal
3. Memory (RAM) and addresses
4. Data types (`char`, `int`, `float`)
5. CPU and registers
6. Variables and pointers
7. Stack and heap
8. How function calls work
9. Basic C — and finally, x86-64 assembly

Platform note: the concrete details (register names, sizes) are **x86-64 on macOS**, built with `clang`.

---

## 1. Binary — bits and bytes

A computer only has one kind of thing to work with: a **bit**, which is either `0` or `1` (a wire that's off or on). That's it. Everything else is bits in bigger groups.

Group **8 bits** together and you get a **byte**. Eight bits can be arranged in 2⁸ = **256** distinct patterns, so one byte can hold any value from `0` to `255`.

```
1 bit   → 2 values     (0, 1)
1 byte  → 256 values   (0000 0000 … 1111 1111)
```

The single most important habit to build: **numbers, letters, colors, instructions — all of it is just bytes.** The *meaning* comes from how a program chooses to interpret them. The same byte `0100 0001` is the number `65`, the letter `'A'`, or part of a machine instruction depending entirely on context. Nothing in the byte itself says which.

---

## 2. Decimal ↔ Binary ↔ Hexadecimal

We write numbers in **decimal** (base 10) out of habit. The computer stores them in **binary** (base 2). And programmers *display* them in **hexadecimal** (base 16) because it lines up perfectly with bytes. Same number, three costumes.

**Decimal → binary** is just place values that are powers of 2:

```
 128  64  32  16   8   4   2   1     ← place values (2⁷ … 2⁰)
   1   1   0   0   1   0   0   1     ← bits
 128 +64          +8          +1  = 201
```

So `201` in decimal is `1100 1001` in binary.

**Binary → hex** is where hex earns its place. One hex digit represents exactly **4 bits** (a "nibble"), because 4 bits give 16 patterns and hex has 16 symbols (`0–9`, then `A–F` for 10–15). So you just chop the byte into two groups of four:

```
1100 1001
1100 = 12 = C
1001 =  9 = 9
→ 0xC9
```

That's the whole reason addresses and raw bytes are written in hex: **one byte is always exactly two hex digits.** `0xFF` is `255` is `1111 1111`. Tidy and unambiguous, where decimal (`255`) hides the bit pattern and full binary (`11111111`) is a mouthful.

| Decimal | Binary | Hex |
|--:|:--|:--|
| 0 | `0000 0000` | `0x00` |
| 10 | `0000 1010` | `0x0A` |
| 65 | `0100 0001` | `0x41` |
| 201 | `1100 1001` | `0xC9` |
| 255 | `1111 1111` | `0xFF` |

The `0x` prefix just means "read what follows as hex." When you see `0x1000047F` later, it's a hex number — an address.

---

## 3. Memory (RAM) and addresses

**RAM is one gigantic array of bytes.** That's the whole model. Every byte has a numbered slot, and that number is its **address**.

```
address:   0x1000   0x1001   0x1002   0x1003   0x1004  ...
value:     [ 65  ]  [ 66  ]  [ 67  ]  [  0  ]  [ 201 ] ...
```

An address is just an index into that array — exactly like `array[3]`, only the array is all of memory. When you hear "a pointer holds an address," it means the pointer holds one of those slot numbers.

Two consequences worth internalizing now:

- **Bigger values span multiple bytes.** A 4-byte `int` at address `0x1000` occupies `0x1000`–`0x1003`. Its "address" is just where it *starts*.
- **Addresses themselves are numbers**, so they're stored in bytes too. On a 64-bit machine an address is 8 bytes wide — which is why pointers are 8 bytes.

---

## 4. Data types — `char`, `int`, `float`

A "type" in C is really just **two facts: how many bytes, and how to interpret them.**

| Type | Size | Interprets the bytes as |
|---|---|---|
| `char` | 1 byte | a small integer / one ASCII character |
| `int` | 4 bytes | a signed whole number (−2,147,483,648 … 2,147,483,647) |
| `float` | 4 bytes | a real number, IEEE-754 format |
| pointer | 8 bytes | an address |

**`char`** is one byte. `'A'` is stored as `65` (`0x41`) — the ASCII code. There's no separate "character" thing in the hardware; a character *is* a small number.

**`int`** is four bytes = 32 bits. The top bit is the sign, so it ranges from about −2.1 billion to +2.1 billion. `20` is stored as `0x00000014`.

**`float`** is also four bytes, but interpreted completely differently: 1 sign bit, 8 exponent bits, 23 fraction bits (IEEE-754). This is why `float` math behaves unlike `int` math even though both are 32 bits — same size, different *interpretation*. This is the point from section 1 made concrete: the bytes don't carry their own meaning; the type decides it.

---

## 5. CPU and registers

The **CPU** is the part that actually *does* things. Its core loop never changes: **fetch** the next instruction, **decode** it, **execute** it, repeat — billions of times a second.

But the CPU can't compute directly on RAM. RAM is comparatively far away and slow. So the CPU has a tiny set of ultra-fast storage slots built into itself called **registers**. Think of them as the CPU's scratch variables — there are only about 16 general-purpose ones, and each is 64 bits (8 bytes) wide.

The rhythm of almost all computation is: **load values from RAM into registers → do the work in registers → store the result back to RAM.**

On x86-64, the registers that show up constantly:

| Register | Typical job |
|---|---|
| `rax` | scratch, and a function's **return value** |
| `rdi`, `rsi`, `rdx`, `rcx`, `r8`, `r9` | the **1st–6th arguments** to a function |
| `rsp` | **stack pointer** — top of the stack |
| `rbp` | **base pointer** — anchor of the current stack frame |
| `rip` | **instruction pointer** — address of the next instruction |

One naming quirk that confuses everyone at first: the *same* register has different names by width. `rax` is the full 64 bits, `eax` is its low 32 bits, `ax` the low 16, `al` the low 8 — all the same physical register. So when you see `mov eax, ...` and `mov rax, ...`, they're touching the same slot, just different amounts of it. (A 32-bit `int` uses `eax`; an 8-byte pointer uses `rax`.)

---

## 6. Variables and pointers

Now these two land easily.

A **variable** is just a **named spot in memory** with a type. `int a = 20;` means "reserve 4 bytes somewhere, call them `a`, put `20` in them." The name is for you; the machine only knows the address.

A **pointer** is a variable whose *value is an address*. `int *p = &a;` means "`p` holds the address of `a`." Since an address is 8 bytes, every pointer is 8 bytes regardless of what it points to.

```c
int a = 20;      // a: 4 bytes holding 20
int *p = &a;     // p: 8 bytes holding a's address
int b = *p;      // *p = "go to that address and read" → b = 20
```

- `&a` — "**address of** `a`" (get the slot number).
- `*p` — "**value at** the address in `p`" (follow the pointer).

That's genuinely all a pointer is: a number that happens to be a memory address. Everything intimidating about pointers dissolves once you hold onto that.

---

## 7. Stack and heap

Your program's memory has two regions where *your* data lives, and they behave very differently.

**The stack** is automatic, fast, and scoped to function calls. Every time you call a function it gets a **frame** — a chunk of stack holding that call's local variables. When the function returns, its frame is discarded instantly. The stack grows *downward* (toward lower addresses), which is why you'll see `sub rsp, 32` to make room. You don't manage the stack; the compiler does.

**The heap** is manual and long-lived. You ask for memory explicitly with `malloc(n)` and it stays yours until you hand it back with `free()`. Heap data **outlives the function that created it** — which is the entire reason it exists.

```c
int x = 5;                            // stack: gone when the function returns
int *arr = malloc(4 * sizeof(int));   // heap: survives until free(arr)
free(arr);                            // give it back
```

The rule to memorize: **stack = automatic and frame-bound; heap = manual and yours until you free it.** Forget to `free` heap memory and it leaks; that's the whole story of a memory leak.

---

## 8. How function calls work

This is the piece that ties registers and the stack together, and it's governed by a **calling convention** (the System V ABI on x86-64). A function call is a small, fixed ritual:

1. **Put the arguments in registers** — 1st in `rdi`, 2nd in `rsi`, 3rd in `rdx`, and so on.
2. **`call` the function** — this pushes the return address onto the stack and jumps in.
3. **The callee opens a frame** — `push rbp; mov rbp, rsp; sub rsp, N` (save the old anchor, set a new one, reserve N bytes for locals).
4. **It does its work**, leaving the result in `rax`.
5. **It closes the frame and returns** — `add rsp, N; pop rbp; ret`, and `ret` jumps back to the return address.
6. **The caller reads the result from `rax`.**

That single convention — *args go out in `rdi, rsi, …`; the answer comes back in `rax`* — is what lets separately-compiled functions call each other. It's the contract every function on the platform agrees to.

---

## 9. Basic C — and finally, x86-64 assembly

Now every piece is on the table, so a tiny C program reads as exactly the ideas above, stacked:

```c
int add(int a, int b) {   // two int params → rdi, rsi
  return a + b;           // add them → result in rax/eax
}

int main(void) {
  int x = 10;             // a stack variable
  int y = 20;             // another
  int z = add(x, y);      // call: x→rdi, y→rsi, result←rax
  return 0;
}
```

And here is `add`, compiled to x86-64 assembly — read the comments and notice there's nothing new here, just the foundations wearing instruction names:

```
_add:
    push rbp                       ; open a frame (save old anchor)
    mov  rbp, rsp                  ; set the new anchor
    mov  dword ptr [rbp - 4], edi  ; store 1st arg (a) into the frame
    mov  dword ptr [rbp - 8], esi  ; store 2nd arg (b) into the frame
    mov  eax, dword ptr [rbp - 4]  ; load a into a register
    add  eax, dword ptr [rbp - 8]  ; eax = a + b   (the ALU does the work)
    pop  rbp                       ; close the frame
    ret                            ; return — result is in eax
```

Line by line, that's the whole post:

- `push rbp` / `mov rbp, rsp` / `pop rbp` — the **stack frame** ritual (§7, §8).
- `edi`, `esi` — the **argument registers**, 32-bit halves because they're `int`s (§5, §8).
- `[rbp - 4]` — a **local variable**, i.e. a named offset into the frame (§6).
- `dword` — a 4-byte **`int`** (§4).
- `add eax, ...` — the **CPU/ALU** doing arithmetic in a **register** (§5).
- `eax` at the end — the **return value** register (§5, §8).

That's the payoff. Assembly stopped being a wall the moment the layers under it were solid: it's just bits, grouped into typed values, living at addresses in RAM, shuttled through registers by a CPU that follows a calling convention. No single instruction was ever the hard part — the foundations were.

---

## Where to go next

With this groundwork down, the two follow-ups I wrote make a lot more sense:

- **[One C program, four views of the same assembly]({{ site.baseurl }}/one-c-program-four-assembly-views.html)** — the same instructions in AT&T vs Intel, compiler output vs raw bytes, and why they only *look* different.
- **[How a C program becomes assembly]({{ site.baseurl }}/heap-malloc-c-to-assembly.html)** — every C construct (loops, `if`, `printf`, arrays, `malloc`/`free`) mapped to the instructions it compiles to, on a bigger program.

Same CPU, same RAM, same fetch-decode-execute — all the way down.
