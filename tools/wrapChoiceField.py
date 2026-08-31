#!/usr/bin/env python3
"""
Keyword-less bir CHOICE alanini EXPLICIT wrapper formuna cevirir ve etkilenen
TUM ust TLV'lerin uzunluklarini yeniden yazar.

Amac TANI koymak. Kodlayicimiz keyword'suz bir CHOICE alanini "retag" formunda
yaziyor - alanin kendi tag'i, icinde alternatifin ham icerigi:

    81 08 <8 bayt>                       (retag)

EMM bunu reddediyor. Ayni tip icin KABUL ETTIGI form ise, kardes alan
UsageCounter.* uzerinde 3 turdur dogrulanmis olan sarmalayici:

    A1 0A  80 08 <8 bayt>                (EXPLICIT wrapper + alternatif [0])

Bu arac birinciyi ikinciye cevirir: dis tag constructed yapilir, ic icerik
alternatifin kendi tag'iyle (varsayilan [0]) kendi TLV'sine sarilir.

Uzunluk yeniden yazimi sart: bir TLV'yi buyutmek butun atalarinin icerik
uzunlugunu degistirir ve uzun-form uzunluk baytlarinin SAYISI da degisebilir
(0x81 xx -> 0x82 xx xx), bu yuzden agac asagidan yukari yeniden insa edilir.

Kullanim:
    python3 wrapChoiceField.py girdi.ber cikti.ber --under A.B.C --tags 1,2 [--alt 0]
      --under   yalnizca bu CONTEXT tag yolunun altindakiler (birden fazla kez verilebilir)
      --tags    sarilacak CONTEXT tag numaralari (virgulle)
      --alt     ic alternatifin tag numarasi (varsayilan 0)
Ornek:
    python3 wrapChoiceField.py in.ber out.ber --under 0.20.6 --under 0.28.6 --tags 1,2
"""
import sys

CONTEXT = 2
CONSTRUCTED = 0x20


def parse_tlv(buf, i):
    start = i
    first = buf[i]
    tag_class = first >> 6
    constructed = bool(first & CONSTRUCTED)
    tag_num = first & 0x1F
    i += 1
    if tag_num == 0x1F:
        tag_num = 0
        while True:
            b = buf[i]
            i += 1
            tag_num = (tag_num << 7) | (b & 0x7F)
            if not b & 0x80:
                break
    tag_bytes = buf[start:i]
    ln = buf[i]
    i += 1
    if ln & 0x80:
        n = ln & 0x7F
        length = int.from_bytes(buf[i:i + n], 'big') if n else 0
        i += n
    else:
        length = ln
    return tag_bytes, tag_class, tag_num, constructed, i, i + length


def encode_length(n):
    if n < 0x80:
        return bytes([n])
    body = []
    while n > 0:
        body.insert(0, n & 0xFF)
        n >>= 8
    return bytes([0x80 | len(body)]) + bytes(body)


def context_tag(num, constructed):
    """Tek baytlik CONTEXT tag (num < 31 varsayimi; bu alanlarda oyle)."""
    if num >= 0x1F:
        raise ValueError('cok-baytli tag desteklenmiyor: %d' % num)
    return bytes([0x80 | (CONSTRUCTED if constructed else 0) | num])


def rebuild(buf, start, end, targets, unders, alt_tag, path, wrapped):
    out = bytearray()
    i = start
    while i < end:
        tag_bytes, tc, tn, con, cs, ce = parse_tlv(buf, i)
        here = path + [tn] if tc == CONTEXT else path

        is_target = (tc == CONTEXT and tn in targets and not con
                     and any(path[-len(u):] == u for u in unders))
        if is_target:
            inner = context_tag(alt_tag, False) + encode_length(ce - cs) + buf[cs:ce]
            out += context_tag(tn, True) + encode_length(len(inner)) + inner
            wrapped.append((tn, ce - cs))
            i = ce
            continue

        if con:
            inner = rebuild(buf, cs, ce, targets, unders, alt_tag, here, wrapped)
            out += tag_bytes + encode_length(len(inner)) + inner
        else:
            out += tag_bytes + encode_length(ce - cs) + buf[cs:ce]
        i = ce
    return bytes(out)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    unders, targets, alt_tag = [], set(), 0
    argv = sys.argv
    for k, a in enumerate(argv):
        if a == '--under' and k + 1 < len(argv):
            unders.append([int(x) for x in argv[k + 1].split('.')])
        elif a == '--tags' and k + 1 < len(argv):
            targets = {int(x) for x in argv[k + 1].split(',')}
        elif a == '--alt' and k + 1 < len(argv):
            alt_tag = int(argv[k + 1])
    if len(args) < 2 or not targets or not unders:
        print(__doc__)
        return 2
    src, dst = args[0], args[1]

    buf = open(src, 'rb').read()
    wrapped = []
    out = bytearray()
    i = 0
    while i < len(buf):
        tag_bytes, tc, tn, con, cs, ce = parse_tlv(buf, i)
        path = [tn] if tc == CONTEXT else []
        if con:
            inner = rebuild(buf, cs, ce, targets, unders, alt_tag, path, wrapped)
            out += tag_bytes + encode_length(len(inner)) + inner
        else:
            out += buf[i:ce]
        i = ce

    open(dst, 'wb').write(bytes(out))
    print(f"sarilan TLV sayisi: {len(wrapped)}")
    for tn, ln in wrapped:
        print(f"   [{tn}] {ln} bayt icerik -> A{tn} {{ 8{alt_tag} {ln} ... }}")
    print(f"{len(buf)} bayt -> {len(out)} bayt")
    return 0


if __name__ == '__main__':
    sys.exit(main())
