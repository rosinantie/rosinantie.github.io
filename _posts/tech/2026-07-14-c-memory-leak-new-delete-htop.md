---
layout: post
title: "Causing a memory leak on purpose — new, delete, and watching RES climb in htop"
date: 2026-07-14
categories: tech
---

I write Java for a living, which means I have never once had to free memory. The garbage collector does it for me — I allocate objects, stop using them, and eventually they vanish. So "memory leak" has always been a phrase I understood in theory but had never *made happen* with my own hands.

C++ is the opposite. There's no GC. Every `new` you write is a promise that somewhere there's a matching `delete`. Break that promise in a loop and you get a textbook leak — and the nicest part is you can watch it climb, live, in `htop`.

So I wrote two versions of the same tiny program. One leaks. One doesn't. The only difference is a single line.

---

## What "managing memory" even means

When a program asks the OS for memory, it gets a block and a pointer to it. In Java the runtime tracks who still points at that block and reclaims it once nobody does. In C++ *you* are the runtime. The rules are blunt:

| You wrote | You owe |
|-----------|---------|
| `new char[N]` | one `delete[] p` |
| `new Thing` | one `delete p` |

Miss the second column and the block is still reserved, but you've thrown away the only pointer to it — so you can never free it *and* you can never use it. That's a leak: memory that's occupied but unreachable. Do it once and nobody notices. Do it in a `while (true)` loop and you eat the machine.

---

## Version 1 — the leak

`playground.cpp`. Every trip through the loop grabs 10 MB, writes to it so the OS backs it with *real* RAM (not just a promise), and then walks away without freeing it.

```cpp
#include "../include/playground.hpp"
#include <chrono>
#include <cstring>
#include <thread>

using namespace std;

void runPlayground() {
  long mb = 0;
  while (true) {
    char *p = new char[10 * 1024 * 1024]; // allocate 10 MB
    memset(p, 1, 10 * 1024 * 1024);       // touch it so real RAM is used
    // NOTE: no delete[] here — this leaks 10 MB every loop
    printf("Leaked %ld MB\n", mb += 10);
    fflush(stdout);
    std::this_thread::sleep_for(std::chrono::milliseconds(200)); // slow it down so it climbs visibly
  }
}
```

Two details that matter:

- **`memset` is not decoration.** Without it the OS may hand you address space it hasn't actually backed with physical pages yet. Touching every byte forces real RAM to be committed — that's what makes `htop` move.
- **The `sleep` is on purpose.** At 10 MB every 200 ms you leak ~50 MB/s. Fast enough to see, slow enough that you get to read the numbers before the OOM killer steps in.

Run it, open `htop`, and watch the **RES** column. It climbs 10 MB every fifth of a second and never comes back down. That staircase-that-only-goes-up is what a leak looks like.

---

## Version 2 — the fix is one line

Same program. The only change is that after I'm done with the block, I hand it back:

```cpp
#include "../include/playground.hpp"
#include <chrono>
#include <cstring>
#include <thread>

using namespace std;

void runPlayground() {
  long mb = 0;
  while (true) {
    char *p = new char[10 * 1024 * 1024]; // allocate 10 MB
    memset(p, 1, 10 * 1024 * 1024);       // touch it so real RAM is used
    delete[] p;                           // <-- give it back before the pointer dies
    printf("Cycled %ld MB\n", mb += 10);
    fflush(stdout);
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
  }
}
```

Now the loop still allocates 10 MB forever — but it frees the block before `p` goes out of scope, so the next iteration reuses the same freed space. The program is doing *just as much* allocation work as the leaky one. The difference is it isn't *accumulating*.

---

## Reading htop — the whole lesson is in one column

I ran the fixed version and let it sit for ~40 seconds:

| Field | My value | Meaning |
|-------|----------|---------|
| **RES** | 10848 KB ≈ 10.6 MB | Real RAM in use: my one 10 MB buffer + a little program/runtime overhead. |
| **VIRT** | 32.06 G | Address space *reserved* (libraries, thread stacks, allocator arenas). Just a promise — not real RAM. Normal on macOS, ignore it. |
| **MEM%** | 0.1% | 0.1% of physical RAM. Tiny. |
| **TIME** | 0:00:38 | Been running 38 s and RES is *still* ~10 MB. |

That flat RES is the point. Thirty-eight seconds, thousands of allocations, and the footprint never grew — because every `new` got its `delete[]`.

Run the two side by side and it's unmistakable:

- **Leak (v1):** RES climbs forever — 10, 20, 30… MB — until the OS runs out and kills the process.
- **Fixed (v2):** RES parks at ~10 MB and stays there no matter how long it runs.

A quick note on **VIRT vs RES**, because it trips people up: VIRT being 32 G does *not* mean the program uses 32 GB of RAM. VIRT is address space the process is *allowed* to touch; RES is what it's *actually* touching right now. When you're hunting a leak, **RES is the number that matters** — VIRT can look huge and mean nothing.

---

## What I took away

- A leak isn't "using a lot of memory" — it's **using memory you can no longer reach or free**. The staircase in RES that only goes up.
- In C++ the fix is discipline: every `new` owes a `delete`, every `new[]` owes a `delete[]`. (In real code you'd let `std::unique_ptr` or a vector own the block so the free is automatic — this manual version just makes the mechanism visible.)
- `htop` is the cheapest leak detector there is. Watch **RES**: flat = healthy, climbing-and-never-falling = a leak.
- And the reason Java never made me think about any of this is that the GC *is* the `delete` I kept forgetting to write. Doing it by hand once is the best way to appreciate what it's been quietly doing all along.
