#!/usr/bin/env python3
"""
Bir .ber dosyasindan belirli bir CONTEXT tag'i siler ve etkilenen TUM ust
TLV'lerin uzunluklarini yeniden yazar.

Amac TANI koymak: EMM belirli bir alandan sikayet ediyorsa, o alani cikarip
dosyayi tekrar gondermek sucluyu kesin olarak belirler - sema ya da kodlayici
uzerinde tahmin yurutmek yerine tek bir ampirik cevap alinir.

Uzunluk yeniden yazimi sart: bir TLV'yi silmek butun atalarinin icerik
uzunlugunu degistirir ve uzun-form uzunluk baytlarinin SAYISI da degisebilir
(0x82 xx xx -> 0x81 xx), bu yuzden agac asagidan yukari yeniden insa edilir.

Kullanim:
    python3 dropBerField.py girdi.ber cikti.ber TAG [--under A.B.C]
      TAG        silinecek CONTEXT tag numarasi
      --under    (istege bagli) yalnizca bu CONTEXT tag yolunun altindakiler
Ornek:
    python3 dropBerField.py in.ber out.ber 509 --under 83.25
"""
import sys

CONTEXT = 2


def parse_tlv(buf, i):
    start = i
    first = buf[i]
    tag_class = first >> 6
    constructed = bool(first & 0x20)
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


def rebuild(buf, start, end, target, under, path, removed):
    """[start,end) icindeki TLV dizisini hedefi atlayarak yeniden kurar."""
    out = bytearray()
    i = start
    while i < end:
        tag_bytes, tc, tn, con, cs, ce = parse_tlv(buf, i)
        here = path + [tn] if tc == CONTEXT else path

        is_target = (tc == CONTEXT and tn == target
                     and (under is None or path[-len(under):] == under))
        if is_target:
            removed.append(ce - cs)
            i = ce
            continue

        if con:
            inner = rebuild(buf, cs, ce, target, under, here, removed)
            out += tag_bytes + encode_length(len(inner)) + inner
        else:
            out += tag_bytes + encode_length(ce - cs) + buf[cs:ce]
        i = ce
    return bytes(out)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    under = None
    for k, a in enumerate(sys.argv):
        if a == '--under' and k + 1 < len(sys.argv):
            under = [int(x) for x in sys.argv[k + 1].split('.')]
    if len(args) < 3:
        print(__doc__)
        return 2
    src, dst, target = args[0], args[1], int(args[2])

    buf = open(src, 'rb').read()
    removed = []
    out = bytearray()
    i = 0
    records = 0
    while i < len(buf):
        tag_bytes, tc, tn, con, cs, ce = parse_tlv(buf, i)
        records += 1
        path = [tn] if tc == CONTEXT else []
        if con:
            inner = rebuild(buf, cs, ce, target, under, path, removed)
            out += tag_bytes + encode_length(len(inner)) + inner
        else:
            out += buf[i:ce]
        i = ce

    open(dst, 'wb').write(bytes(out))
    print(f"{records} kayit islendi")
    print(f"silinen [{target}] TLV sayisi: {len(removed)} (toplam {sum(removed)} bayt icerik)")
    print(f"{len(buf)} bayt -> {len(out)} bayt")
    return 0


if __name__ == '__main__':
    sys.exit(main())
