; Test cartridge for the link cable (MBC1+RAM+BATTERY). Held A at power-on makes it the side that
; clocks the transfers and sends $11; otherwise it waits for the other side's clock and sends $22.
; Either way it stores each byte it receives at $A000, counts transfers at $A001, and notes its
; role at $A002 (1 clocking, 2 following).
; Build: rgbasm -o linkrom.o linkrom.asm && rgblink -o linkrom.gb linkrom.o && rgbfix -v -m 3 -r 2 -p 0 linkrom.gb
SECTION "Header", ROM0[$100]
    nop
    jp Start
    ds $150 - @, 0

SECTION "Main", ROM0[$150]
Start:
    di
    ld sp, $FFFE
    ld a, $0A
    ld [$0000], a        ; enable cartridge RAM
    xor a
    ld [$A000], a
    ld [$A001], a
    ld a, $10
    ldh [$FF00], a       ; select buttons
    ld b, 16             ; let the lines settle (a Color needs longer than two reads)
.settle:
    ldh a, [$FF00]
    dec b
    jr nz, .settle
    bit 0, a             ; A, active low
    jr z, Leader
    ld a, 2
    ld [$A002], a
Follower:
    ld a, $22
    ldh [$FF01], a
    ld a, $80            ; start, external clock
    ldh [$FF02], a
.wait:
    ldh a, [$FF02]
    bit 7, a
    jr nz, .wait
    call Received
    jr Follower
Leader:
    ld a, 1
    ld [$A002], a
    ; Give the other Game Boy time to boot (a Color's boot animation is longer) so it's listening
    ; before the first byte; one that starts listening mid-byte stays out of step, as on hardware.
    ld c, 200
.frame:
    ldh a, [$FF44]
    cp 144
    jr nz, .frame
.vblank:
    ldh a, [$FF44]
    cp 144
    jr z, .vblank
    dec c
    jr nz, .frame
.again:
    ld a, $11
    ldh [$FF01], a
    ld a, $81            ; start, internal clock
    ldh [$FF02], a
.wait:
    ldh a, [$FF02]
    bit 7, a
    jr nz, .wait
    call Received
    ; Give the other side time to get ready for the next byte.
    ld b, 0
.pause:
    dec b
    jr nz, .pause
    jr .again

Received:
    ldh a, [$FF01]
    ld [$A000], a
    ld hl, $A001
    inc [hl]
    ret
