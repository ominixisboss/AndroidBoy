@ A tiny Game Boy Advance test ROM for tests/host_test.c. Build with tools/build_gba_test_rom.sh.
@
@ Every frame, at the start of vertical blank, it reads the keys and fills the screen (mode 3, 240x160, 15-bit colour) with the
@ pressed-keys mask as the colour: bit 0 (A) is the lowest red bit, bits 5-9 (Right, Left, Up,
@ Down, R, L) are green, so L and R show as different greens. It also writes the mask to
@ cartridge SRAM (bytes 0-1) and counts frames in SRAM bytes 2-3 and in EWRAM at 0x02000000.

    .arm
    .section .text
    .global _start
_start:
    b main

    @ Cartridge header: the start of the Nintendo logo, title, game code, maker and the fixed 0x96.
    .byte 0x24, 0xFF, 0xAE, 0x51
    .space 152
    .ascii "ANDROIDBOY  "
    .ascii "ABTE"
    .ascii "01"
    .byte 0x96, 0x00, 0x00
    .space 7
    .byte 0x00          @ version
    .byte 0x00          @ header checksum, filled in by the build script
    .space 2

main:
    mov r0, #0x04000000         @ I/O registers
    mov r1, #0x400
    orr r1, r1, #3
    strh r1, [r0]               @ DISPCNT: mode 3, background 2 on
    mov r8, #0                  @ frame counter
    mov r6, #0x0E000000         @ cartridge SRAM
    mov r9, #0x02000000         @ EWRAM

loop:
wait_draw:                      @ wait for the end of vertical blank ...
    ldrh r2, [r0, #6]
    cmp r2, #160
    bge wait_draw
wait_blank:                     @ ... then for the start of the next one
    ldrh r2, [r0, #6]
    cmp r2, #160
    blt wait_blank

    add r2, r0, #0x130
    ldrh r3, [r2]               @ KEYINPUT: 0 = pressed
    mvn r3, r3
    mov r4, #0x300
    orr r4, r4, #0xFF
    and r3, r3, r4              @ pressed keys, bits 0-9

    strb r3, [r6]               @ SRAM: keys, then the frame count
    lsr r7, r3, #8
    strb r7, [r6, #1]
    add r8, r8, #1
    strb r8, [r6, #2]
    lsr r7, r8, #8
    strb r7, [r6, #3]
    str r8, [r9]                @ EWRAM: the frame count

    @ Fill the screen eight words (16 pixels) at a time, so it's done within vertical blank.
    orr r1, r3, r3, lsl #16
    mov r2, r1
    mov r5, r1
    mov r7, r1
    mov r10, r1
    mov r11, r1
    mov r12, r1
    mov lr, r1
    mov r4, #0x06000000         @ VRAM
    add r3, r4, #0x12C00        @ its end: 240 * 160 * 2 bytes
fill:
    stmia r4!, {r1, r2, r5, r7, r10, r11, r12, lr}
    cmp r4, r3
    blt fill
    b loop
