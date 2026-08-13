#!/usr/bin/env python3
"""qosRequested [1] icin ayirt edici ikili uretir.

Kullanim: qosProbe.py girdi.ber cikti.ber OFFSET plain|wrap DEGER

U (plain) : 81 <n> <DEGER>            -> dogru okuma n oktet
W (wrap)  : A1 <n+2> 04 <n> <DEGER>   -> sarmalayici icerigi n+2 oktet

DEGER uzunlugu o yapinin QoSInformation SIZE ust sinirina esit secilir
(GGSNTurkcellCdrR7 icin 17, CGSN40ber icin 12). Boylece sarmalayiciyi
acmayan bir cozucu icin uzunluk kisitin disina cikar. Iki dosya ayni
degeri tasidigi icin EMM'in dondurdugu ASCII decode dogrudan
karsilastirilabilir - cikarim gerekmez.
"""
import sys
# Deger komut satirindan gelir; uzunlugu o yapinin SIZE ust sinirina esit
# secilmelidir - ayirt ediciligi saglayan sey budur. Sarmalayici acilmazsa
# icerik uzunlugu +2 olur ve kisitin disina cikar.
PROBE = None

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
PROBE = sys.argv[5].encode('ascii')
buf = open(src, 'rb').read(); hits = []
out = rebuild(buf, 0, len(buf), off, mode == 'wrap', hits)
open(dst, 'wb').write(out)
print(f"  {dst}: {len(buf)} -> {len(out)} bayt, degistirilen dugum: {len(hits)}")
