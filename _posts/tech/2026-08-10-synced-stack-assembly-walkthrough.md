---
layout: post
title: "Watching the stack live: a synced C / assembly / memory walkthrough"
date: 2026-08-10
categories: tech
---

Reading a stack frame diagram is one thing. Watching the *exact same* stack memory get handed from one function to the next, byte for byte, is another — it's the difference between being told frames get reused and seeing it happen under a debugger. So instead of another static diagram, this post links out to a small interactive tool: step through a real program one instruction at a time and watch the C source, the x86-64 disassembly, and the live stack memory stay in lockstep.

**[→ Open the Synced Stack & Assembly Walkthrough]({{ site.baseurl }}/How-The-program-Works/stack_and_heap_flow.html)**

---

## What the tool is actually showing

Three panels, always in sync, driven by one step counter:

- **C Source Code** — the exact line currently executing.
- **x86-64 Disassembly** — the Intel-syntax instructions that line compiled to, highlighted as they execute.
- **Stack Memory** — every live slot from `%rsp` up to the return address, high to low, colored by which function's frame owns it.

A register bar above tracks `RSP`, `RBP`, `RAX`, and `RDI` at each step, and a step-by-step explanation card spells out *what's happening*, *which CPU/memory operation caused it*, and *the one-sentence takeaway* — the same three questions I keep asking myself when I read `objdump` output cold.

## The program under the hood

```c
int main() {
  int main_local = 10;
  int a_returned = function_a(100);   // frame A, then reused
  int c_returned = function_c(main_local); // reuses A's old memory
}

int function_a(int a_param) {
  int a_local = 20;
  char a_buffer[64];
  print_distance("a_local to a_buffer", &a_local, a_buffer); // frame B
  int b_returned = function_b(a_param + 5);                  // frame B again
  return a_local + b_returned;
}
```

`main()` calls `function_a()`, which in turn calls two short-lived helpers — `print_distance()` and `function_b()` — before returning. Only *after* `function_a()` has fully returned does `main()` call `function_c()`. That ordering is the whole point of the demo.

## Three things worth stepping through slowly

**1. Every call is the same four-instruction ritual.** `push rbp; mov rbp, rsp; sub rsp, N` on the way in, `add rsp, N; pop rbp; ret` on the way out. Watch the register bar — `RBP` only ever changes at those two moments, and it always points at the previous frame's saved copy of itself, which is how `ret` knows where to unwind to.

**2. The stack proves its own direction.** `print_distance()` exists for one reason: to subtract two addresses and print the result. `a_local` sits higher up the stack than `a_buffer[64]`, so `&a_local - &a_buffer` comes out **positive** — concrete proof, not an assertion, that the stack grows downward toward lower addresses as each frame is pushed.

**3. Frame memory is reused, not zeroed.** `print_distance()` and `function_b()` are called one after another from inside `function_a()`, and once each one returns, its frame is just marked `reclaimed` — the bytes aren't touched. The next call gets the *identical* addresses. Step forward past `function_b()`'s return and into `function_c()` later on, and watch the stack column: `function_c()` is handed back the same memory `function_a()`'s frame used, because by then that whole frame has unwound too. Nothing on the stack is ever cleared — it's just relabeled by whoever's `rbp`/`rsp` currently claims it. That's also exactly why an uninitialized local variable can appear to have a plausible-looking "leftover" value: it's reading whatever the previous occupant of that address left behind.

The return-value path is worth watching too: `function_b()` computes `b_result` in `eax`, `ret`s, and the very next step in `function_a()` is `mov [rbp-0x54], eax` — the caller pulling the answer straight out of the register the ABI promised it would be in.

---

## Where to go next

This walkthrough assumes the ground floor from two earlier posts:

- **[How a C program becomes assembly]({{ site.baseurl }}/heap-malloc-c-to-assembly.html)** — every C construct mapped to the instructions it compiles to.
- **[From bits to x86-64 assembly]({{ site.baseurl }}/from-bits-to-x86-64-assembly.html)** — bits, registers, the stack, and the calling convention, built up from scratch.

If those make sense, every panel in this tool should read as familiar territory — just moving now instead of frozen in a diagram.
