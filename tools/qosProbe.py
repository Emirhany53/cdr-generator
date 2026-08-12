#!/usr/bin/env python3
"""qosRequested [1] icin ayirt edici ikili uretir.

U (implicit) : 81 11 <17 ASCII>          -> dogru okuma 17 oktet
W (explicit) : A1 13 04 11 <17 ASCII>    -> sarmalayici icerigi 19 oktet

QoSInformation ::= OCTET STRING (SIZE(4..17)). 19 > 17 oldugu icin, EMM
sarmalayiciyi acmadan icerigi deger sayarsa W'de SIZE ihlali olusur ya da
donen ASCII degerin basinda 04 11 baytlari gorunur. Iki dosya ayni degeri
tasidigi icin donen degerler karsilastirilabilir.
"""
import sys
PROBE = b'QOSPROBE123456789'  # 17 bayt
assert len(PROBE) == 17

def parse(buf, i):
    first = buf[i]; tc = first >> 6; con = bool(first & 0x20); tn = first & 0x1F; i += 1
    if tn == 0x1F:
        tn = 0
        while True:
            b = buf[i]; i += 1; tn = (tn << 7) | (b & 0x7F)
            if not b & 0x80: break
    ln = buf[i]; i += 1
    if ln & 0x80:
        n = ln & 0x7F; length = int.from_bytes(buf[i:i+n], 'big') if n else 0; i += n
    else:
        length = ln
    return tc, tn, con, i, i + length

def tagbytes(buf, i):
    j = i; first = buf[j]; j += 1
    if (first & 0x1F) == 0x1F:
        while buf[j] & 0x80: j += 1
        j += 1
    return buf[i:j]

def enclen(n):
    if n < 0x80: return bytes([n])
    body = []
    while n > 0: body.insert(0, n & 0xFF); n >>= 8
    return bytes([0x80 | len(body)]) + bytes(body)

def rebuild(buf, start, end, target_off, wrap, hits):
    out = bytearray(); i = start
    while i < end:
        tc, tn, con, cs, ce = parse(buf, i)
        tb = tagbytes(buf, i)
        if i == target_off:
            hits.append(i)
            if wrap:
                inner = b'\x04' + enclen(len(PROBE)) + PROBE
                out += b'\xA1' + enclen(len(inner)) + inner
            else:
                out += b'\x81' + enclen(len(PROBE)) + PROBE
        elif con:
            inner = rebuild(buf, cs, ce, target_off, wrap, hits)
            out += tb + enclen(len(inner)) + inner
        else:
            out += tb + enclen(ce - cs) + buf[cs:ce]
        i = ce
    return bytes(out)

src, dst, off, mode = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
buf = open(src, 'rb').read(); hits = []
out = rebuild(buf, 0, len(buf), off, mode == 'wrap', hits)
open(dst, 'wb').write(out)
print(f"  {dst}: {len(buf)} -> {len(out)} bayt, degistirilen dugum: {len(hits)}")
