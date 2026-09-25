; Minimal test cartridge for the host test (MBC1+RAM+BATTERY).
; Writes a marker to cartridge RAM, plays a tone, mirrors the joypad state to SRAM and counts
; frames (at $A002, for the rewind test).
SECTION "Header", ROM0[$100]
    nop
    jp Start
    ds $150 - @, 0

SECTION "Main", ROM0[$150]
Start:
    di
    ld a, $0A
    ld [$0000], a        ; enable cartridge RAM
    ld a, $42
    ld [$A000], a        ; battery marker
    ld a, $80
    ld [$FF26], a        ; APU on
    ld a, $77
    ld [$FF24], a        ; master volume
    ld a, $11
    ld [$FF25], a        ; channel 1 to both sides
    ld a, $80
    ld [$FF11], a        ; 50% duty
    ld a, $F0
    ld [$FF12], a        ; full volume, no envelope
    ld a, $00
    ld [$FF13], a
    ld a, $87
    ld [$FF14], a        ; trigger
    xor a
    ld [$A002], a        ; frame counter, little endian
    ld [$A003], a
    ld a, $01
    ld [$FFFF], a        ; enable the VBlank interrupt, only to wake HALT
.loop:
    halt                 ; one iteration per frame (IME is off, so no handler runs)
    xor a
    ldh [$FF0F], a       ; clear IF, or the next HALT would return at once
    ld hl, $A002
    inc [hl]
    jr nz, .counted
    inc hl
    inc [hl]
.counted:
    ld a, $20
    ld [$FF00], a        ; select d-pad
    ld a, [$FF00]
    ld a, [$FF00]
    and $0F
    swap a
    ld b, a
    ld a, $10
    ld [$FF00], a        ; select buttons
    ld a, [$FF00]
    ld a, [$FF00]
    and $0F
    or b
    ld [$A001], a        ; joypad mirror (active low)
    jr .loop
