#!/usr/bin/env python3
"""CHFChargingDataTypes16 icin yazili EXPLICIT hipotezini sinayan varyant.

Modul basligi anahtar kelimesiz. 9. ve 10. tur, boyle bir modulde HEM alan
HEM tip tag'inin EMM tarafinda IMPLICIT okundugunu gosterdi. Sinanmamis tek
site kaldi: alanin uzerinde YAZILI duran EXPLICIT keyword'u. Bizim kodumuzda
yazili keyword kazanir, yani sarmalayici korunur.

nFunctionConsumerInformation icindeki uc alan tam bu sinifta:
    networkFunctionIPv4Address [2] EXPLICIT IPAddress
    networkFunctionIPv6Address [4] EXPLICIT IPAddress
    networkFunctionFQDN        [5] EXPLICIT NodeAddress

Bu betik onlarin sarmalayicilarini kaldirip tag'i en icteki primitive'e
tasir; ust TLV uzunluklari yeniden yazilir.

    once  : A2 06 { 80 04 xxxx }        sonra : 82 04 xxxx
    once  : A5 08 { A0 06 { 80 04 x } } sonra : 85 04 xxxx

Kullanim: chfExplicitProbe.py girdi.ber cikti.ber
"""
import sys

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

def innermost(buf, cs, ce):
    """Ic ice sarmalayicilari gecip en icteki primitive degeri dondurur."""
    tc, tn, con, s, e = parse(buf, cs)
    return innermost(buf, s, e) if con else buf[s:e]

def collapse(buf, start, end, hits):
    """A3'un icerigini yeniden yazar: EXPLICIT sarmalayicilar duser."""
    out = bytearray(); i = start
    while i < end:
        tc, tn, con, cs, ce = parse(buf, i)
        if con and tn in (2, 4, 5):
            val = innermost(buf, cs, ce)
            out += bytes([0x80 | tn]) + enclen(len(val)) + val
            hits.append(tn)
        else:
            out += buf[i:ce]
        i = ce
    return bytes(out)

def rebuild(buf, start, end, hits):
    out = bytearray(); i = start
    while i < end:
        tc, tn, con, cs, ce = parse(buf, i)
        tb = tagbytes(buf, i)
        if tc == 2 and tn == 3 and con and not hits:
            inner = collapse(buf, cs, ce, hits)
            out += tb + enclen(len(inner)) + inner
        elif con:
            inner = rebuild(buf, cs, ce, hits)
            out += tb + enclen(len(inner)) + inner
        else:
            out += buf[i:ce]
        i = ce
    return bytes(out)

src, dst = sys.argv[1], sys.argv[2]
buf = open(src, 'rb').read(); hits = []
out = rebuild(buf, 0, len(buf), hits)
open(dst, 'wb').write(out)
print(f"  {dst}: {len(buf)} -> {len(out)} bayt, duzlestirilen alan: {hits}")
