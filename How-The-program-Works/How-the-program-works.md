c code :

```#include <stdio.h>
#include <stdlib.h>

// --- HEAP DEMO ---
// A stack variable dies the instant its function returns (its frame is gone).
// So a function CANNOT return a pointer to its own local array — that memory
// is reclaimed. To make data OUTLIVE the function, we ask the heap for it with
// malloc(). The heap block stays alive until WE hand it back with free().
//
// make_sequence() builds an array [0,1,2,...,n-1] on the heap and returns it.
// The caller now owns that memory and is responsible for free()-ing it.
int *make_sequence(int n) {
  // malloc reserves n ints on the heap and returns the ADDRESS of that block.
  // Returns NULL if the OS could not give us the memory — always check it.
  int *arr = malloc(n * sizeof(int));
  if (arr == NULL) {
    return NULL;
  }

  for (int i = 0; i < n; i++) {
    arr[i] = i;
  }

  return arr; // safe: the heap block lives on after this frame is destroyed
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

  // Call make_sequence()
  int *sequence = make_sequence(5);

  if (sequence != NULL) {
    printf("\nSequence:\n");

    for (int i = 0; i < 5; i++) {
      printf("%d ", sequence[i]);
    }
    printf("\n");

    free(sequence); // Release the heap memory
  }

  return 0;
}
```


Aseembly code 

```
	.section	__TEXT,__text,regular,pure_instructions
	.build_version macos, 26, 0	sdk_version 26, 2
	.intel_syntax noprefix
	.globl	_make_sequence                  ## -- Begin function make_sequence
	.p2align	4, 0x90
_make_sequence:                         ## @make_sequence
	.cfi_startproc
## %bb.0:
	push	rbp
	.cfi_def_cfa_offset 16
	.cfi_offset rbp, -16
	mov	rbp, rsp
	.cfi_def_cfa_register rbp
	sub	rsp, 32
	mov	dword ptr [rbp - 12], edi
	movsxd	rdi, dword ptr [rbp - 12]
	shl	rdi, 2
	call	_malloc
	mov	qword ptr [rbp - 24], rax
	cmp	qword ptr [rbp - 24], 0
	jne	LBB0_2
## %bb.1:
	mov	qword ptr [rbp - 8], 0
	jmp	LBB0_7
LBB0_2:
	mov	dword ptr [rbp - 28], 0
LBB0_3:                                 ## =>This Inner Loop Header: Depth=1
	mov	eax, dword ptr [rbp - 28]
	cmp	eax, dword ptr [rbp - 12]
	jge	LBB0_6
## %bb.4:                               ##   in Loop: Header=BB0_3 Depth=1
	mov	edx, dword ptr [rbp - 28]
	mov	rax, qword ptr [rbp - 24]
	movsxd	rcx, dword ptr [rbp - 28]
	mov	dword ptr [rax + 4*rcx], edx
## %bb.5:                               ##   in Loop: Header=BB0_3 Depth=1
	mov	eax, dword ptr [rbp - 28]
	add	eax, 1
	mov	dword ptr [rbp - 28], eax
	jmp	LBB0_3
LBB0_6:
	mov	rax, qword ptr [rbp - 24]
	mov	qword ptr [rbp - 8], rax
LBB0_7:
	mov	rax, qword ptr [rbp - 8]
	add	rsp, 32
	pop	rbp
	ret
	.cfi_endproc
                                        ## -- End function
	.globl	_add                            ## -- Begin function add
	.p2align	4, 0x90
_add:                                   ## @add
	.cfi_startproc
## %bb.0:
	push	rbp
	.cfi_def_cfa_offset 16
	.cfi_offset rbp, -16
	mov	rbp, rsp
	.cfi_def_cfa_register rbp
	mov	dword ptr [rbp - 4], edi
	mov	dword ptr [rbp - 8], esi
	mov	dword ptr [rbp - 12], edx
	mov	dword ptr [rbp - 16], ecx
	mov	dword ptr [rbp - 20], r8d
	mov	eax, dword ptr [rbp - 4]
	add	eax, dword ptr [rbp - 8]
	add	eax, dword ptr [rbp - 12]
	add	eax, dword ptr [rbp - 16]
	add	eax, dword ptr [rbp - 20]
	pop	rbp
	ret
	.cfi_endproc
                                        ## -- End function
	.globl	_sub                            ## -- Begin function sub
	.p2align	4, 0x90
_sub:                                   ## @sub
	.cfi_startproc
## %bb.0:
	push	rbp
	.cfi_def_cfa_offset 16
	.cfi_offset rbp, -16
	mov	rbp, rsp
	.cfi_def_cfa_register rbp
	mov	dword ptr [rbp - 4], edi
	mov	dword ptr [rbp - 8], esi
	mov	eax, dword ptr [rbp - 4]
	sub	eax, dword ptr [rbp - 8]
	pop	rbp
	ret
	.cfi_endproc
                                        ## -- End function
	.globl	_multiply                       ## -- Begin function multiply
	.p2align	4, 0x90
_multiply:                              ## @multiply
	.cfi_startproc
## %bb.0:
	push	rbp
	.cfi_def_cfa_offset 16
	.cfi_offset rbp, -16
	mov	rbp, rsp
	.cfi_def_cfa_register rbp
	mov	dword ptr [rbp - 4], edi
	mov	dword ptr [rbp - 8], esi
	mov	eax, dword ptr [rbp - 4]
	imul	eax, dword ptr [rbp - 8]
	pop	rbp
	ret
	.cfi_endproc
                                        ## -- End function
	.globl	_divide                         ## -- Begin function divide
	.p2align	4, 0x90
_divide:                                ## @divide
	.cfi_startproc
## %bb.0:
	push	rbp
	.cfi_def_cfa_offset 16
	.cfi_offset rbp, -16
	mov	rbp, rsp
	.cfi_def_cfa_register rbp
	mov	dword ptr [rbp - 4], edi
	mov	dword ptr [rbp - 8], esi
	mov	eax, dword ptr [rbp - 4]
	cdq
	idiv	dword ptr [rbp - 8]
	pop	rbp
	ret
	.cfi_endproc
                                        ## -- End function
	.globl	_main                           ## -- Begin function main
	.p2align	4, 0x90
_main:                                  ## @main
	.cfi_startproc
## %bb.0:
	push	rbp
	.cfi_def_cfa_offset 16
	.cfi_offset rbp, -16
	mov	rbp, rsp
	.cfi_def_cfa_register rbp
	sub	rsp, 64
	mov	dword ptr [rbp - 4], 0
	mov	dword ptr [rbp - 8], 20
	mov	dword ptr [rbp - 12], 10
	mov	edi, dword ptr [rbp - 8]
	mov	esi, dword ptr [rbp - 12]
	mov	edx, 1
	mov	ecx, 2
	mov	r8d, 3
	call	_add
	mov	dword ptr [rbp - 16], eax
	mov	edi, dword ptr [rbp - 8]
	mov	esi, dword ptr [rbp - 12]
	call	_sub
	mov	dword ptr [rbp - 20], eax
	mov	edi, dword ptr [rbp - 8]
	mov	esi, dword ptr [rbp - 12]
	call	_multiply
	mov	dword ptr [rbp - 24], eax
	mov	edi, dword ptr [rbp - 8]
	mov	esi, dword ptr [rbp - 12]
	call	_divide
	mov	dword ptr [rbp - 28], eax
	mov	esi, dword ptr [rbp - 16]
	lea	rdi, [rip + L_.str]
	mov	al, 0
	call	_printf
	mov	esi, dword ptr [rbp - 20]
	lea	rdi, [rip + L_.str.1]
	mov	al, 0
	call	_printf
	mov	esi, dword ptr [rbp - 24]
	lea	rdi, [rip + L_.str.2]
	mov	al, 0
	call	_printf
	mov	esi, dword ptr [rbp - 28]
	lea	rdi, [rip + L_.str.3]
	mov	al, 0
	call	_printf
	lea	rdi, [rip + L_.str.4]
	mov	al, 0
	call	_printf
	mov	dword ptr [rbp - 32], 0
	mov	dword ptr [rbp - 36], 0
LBB5_1:                                 ## =>This Inner Loop Header: Depth=1
	cmp	dword ptr [rbp - 36], 5
	jge	LBB5_7
## %bb.2:                               ##   in Loop: Header=BB5_1 Depth=1
	mov	eax, dword ptr [rbp - 36]
	add	eax, dword ptr [rbp - 32]
	mov	dword ptr [rbp - 32], eax
	mov	eax, dword ptr [rbp - 36]
	mov	ecx, 2
	cdq
	idiv	ecx
	cmp	edx, 0
	jne	LBB5_4
## %bb.3:                               ##   in Loop: Header=BB5_1 Depth=1
	mov	esi, dword ptr [rbp - 36]
	lea	rdi, [rip + L_.str.5]
	mov	al, 0
	call	_printf
	jmp	LBB5_5
LBB5_4:                                 ##   in Loop: Header=BB5_1 Depth=1
	mov	esi, dword ptr [rbp - 36]
	lea	rdi, [rip + L_.str.6]
	mov	al, 0
	call	_printf
LBB5_5:                                 ##   in Loop: Header=BB5_1 Depth=1
	jmp	LBB5_6
LBB5_6:                                 ##   in Loop: Header=BB5_1 Depth=1
	mov	eax, dword ptr [rbp - 36]
	add	eax, 1
	mov	dword ptr [rbp - 36], eax
	jmp	LBB5_1
LBB5_7:
	mov	esi, dword ptr [rbp - 32]
	lea	rdi, [rip + L_.str.7]
	mov	al, 0
	call	_printf
	mov	edi, 5
	call	_make_sequence
	mov	qword ptr [rbp - 48], rax
	cmp	qword ptr [rbp - 48], 0
	je	LBB5_13
## %bb.8:
	lea	rdi, [rip + L_.str.8]
	mov	al, 0
	call	_printf
	mov	dword ptr [rbp - 52], 0
LBB5_9:                                 ## =>This Inner Loop Header: Depth=1
	cmp	dword ptr [rbp - 52], 5
	jge	LBB5_12
## %bb.10:                              ##   in Loop: Header=BB5_9 Depth=1
	mov	rax, qword ptr [rbp - 48]
	movsxd	rcx, dword ptr [rbp - 52]
	mov	esi, dword ptr [rax + 4*rcx]
	lea	rdi, [rip + L_.str.9]
	mov	al, 0
	call	_printf
## %bb.11:                              ##   in Loop: Header=BB5_9 Depth=1
	mov	eax, dword ptr [rbp - 52]
	add	eax, 1
	mov	dword ptr [rbp - 52], eax
	jmp	LBB5_9
LBB5_12:
	lea	rdi, [rip + L_.str.10]
	mov	al, 0
	call	_printf
	mov	rdi, qword ptr [rbp - 48]
	call	_free
LBB5_13:
	xor	eax, eax
	add	rsp, 64
	pop	rbp
	ret
	.cfi_endproc
                                        ## -- End function
	.section	__TEXT,__cstring,cstring_literals
L_.str:                                 ## @.str
	.asciz	"Sum       = %d\n"

L_.str.1:                               ## @.str.1
	.asciz	"Difference= %d\n"

L_.str.2:                               ## @.str.2
	.asciz	"Product   = %d\n"

L_.str.3:                               ## @.str.3
	.asciz	"Quotient  = %d\n"

L_.str.4:                               ## @.str.4
	.asciz	"\nLoop:\n"

L_.str.5:                               ## @.str.5
	.asciz	"%d is even\n"

L_.str.6:                               ## @.str.6
	.asciz	"%d is odd\n"

L_.str.7:                               ## @.str.7
	.asciz	"Total = %d\n"

L_.str.8:                               ## @.str.8
	.asciz	"\nSequence:\n"

L_.str.9:                               ## @.str.9
	.asciz	"%d "

L_.str.10:                              ## @.str.10
	.asciz	"\n"

.subsections_via_symbols
```
use the ./full_system_overview_v2.png and the
./c_to_assembly_heap_update.png

Full overview v2 — the big change is RAM now has four regions instead of three. The new heap box shows your malloc'd block: 20 bytes holding [0,1,2,3,4], born inside make_sequence, surviving that function's death, dying only at free(). Notice sequence itself still lives on the stack — it's just 8 bytes holding the heap block's address. The kernel band also grew: this program can use two doors now — write() for printf, and mmap() when malloc's allocator needs fresh pages. And the result panel gained your new output: Sequence: 0 1 2 3 4.
Translation update — the five new patterns your code introduced, with the details worth savoring:

malloc(n * sizeof(int)) — the compiler turned × 4 into shl rdi, 2 (shift left 2 bits = multiply by 4), and the address comes back in rax, 64-bit, because pointers are 8 bytes while your ints used 32-bit registers.
arr[i] = i is the jewel: mov dword ptr [rax + 4*rcx], edx. An array subscript is one instruction — the CPU has hardware for base + scale×index address math. That's what an array is.
return arr — the frame dies (pop rbp; ret) but the heap block survives. Your own comment in the code said exactly this, and the assembly proves it: only the address travels back.
if (arr == NULL) is cmp ..., 0 — NULL is literally zero.
free(sequence) is just the pointer into rdi and a call — after which that address is a landmine (use-after-free).

One subtle thing you can verify yourself: main's frame grew from sub rsp, 48 to sub rsp, 64 — the extra 16 bytes hold your 8-byte sequence pointer and the second loop's i, aligned. The compiler counted your variables for you.
