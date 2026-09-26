; Test cartridge for the Game Boy Printer: prints one 160x16 strip, the top 8 rows black and the
; bottom 8 white, the way games talk to the printer (INIT, DATA, empty DATA, PRINT packets over
; the link port, each byte clocked out by the Game Boy).
; Build: rgbasm -o printrom.o printrom.asm && rgblink -o printrom.gb printrom.o && rgbfix -v -p 0 printrom.gb
SECTION "Header", ROM0[$100]
    nop
    jp Start
    ds $150 - @, 0

SECTION "Main", ROM0[$150]
Start:
    di
    ld sp, $FFFE
    ld hl, InitPacket
    ld b, InitPacket.end - InitPacket
    call SendBytes
    ld hl, DataHeader
    ld b, DataHeader.end - DataHeader
    call SendBytes
    ; 640 bytes of tile data: 20 tiles of $FF (black), then 20 of $00 (white).
    ld de, 320
.black:
    ld a, $FF
    call SendByte
    dec de
    ld a, d
    or e
    jr nz, .black
    ld de, 320
.white:
    xor a
    call SendByte
    dec de
    ld a, d
    or e
    jr nz, .white
    ld hl, DataTrailer
    ld b, DataTrailer.end - DataTrailer
    call SendBytes
    ld hl, EmptyDataPacket
    ld b, EmptyDataPacket.end - EmptyDataPacket
    call SendBytes
    ld hl, PrintPacket
    ld b, PrintPacket.end - PrintPacket
    call SendBytes
.done:
    jr .done

; Sends b bytes from hl.
SendBytes:
    ld a, [hl+]
    call SendByte
    dec b
    jr nz, SendBytes
    ret

; Sends a on the link port with the Game Boy's own clock, and waits for the transfer to finish.
SendByte:
    ldh [$FF01], a
    ld a, $81
    ldh [$FF02], a
.wait:
    ldh a, [$FF02]
    bit 7, a
    jr nz, .wait
    ret

; Packets: magic $88 $33, command, compression, length (little endian), data, checksum of
; everything after the magic (little endian), then two bytes for the printer's replies.
InitPacket:
    db $88, $33, $01, $00, $00, $00, $01, $00, $00, $00
.end:
DataHeader:
    db $88, $33, $04, $00, $80, $02
.end:
DataTrailer:
    ; $04 + $80 + $02 + 320 * $FF = $13F46
    db $46, $3F, $00, $00
.end:
EmptyDataPacket:
    db $88, $33, $04, $00, $00, $00, $04, $00, $00, $00
.end:
PrintPacket:
    ; One sheet, no margin before, 3 line feeds after, the usual palette, middle exposure.
    db $88, $33, $02, $00, $04, $00, $01, $03, $E4, $40, $2E, $01, $00, $00
.end:
