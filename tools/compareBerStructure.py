#!/usr/bin/env python3
"""
Iki BER dosyasinin YAPISINI karsilastirir (degerleri degil).

verifyMmtelBerFull.py semadan turettigi BEKLENTIYE gore denetler; dolayisiyla
yalnizca benim ongordugum hatalari yakalayabilir. Bu script tam tersini yapar:
hicbir sema bilgisi kullanmaz, sadece EMM'in KABUL ETTIGI bir referans dosya ile
bizim urettigimiz dosyayi byte seviyesinde gezip her yolun yapisal imzasini
cikarir ve farklari raporlar.

Boylece "aklima gelmeyen" bir kodlama farki da ortaya cikar: fazladan/eksik
sarmal katman, yanlis universal tag, primitive/constructed uyusmazligi, vb.

Her alan icin imza = (tag class, tag number, constructed) ucluleri kumesi.
Deger baytlari ve tekrar sayilari kasitli olarak yok sayilir - onlar rastgele
uretilen icerige gore zaten farkli olacaktir.

Kullanim:
    python3 compareBerStructure.py REFERANS.ber BIZIMKI.ber [--max-records N]
"""
import sys
from collections import defaultdict

CONTEXT = 2
CLASS_NAMES = {0: 'UNIVERSAL', 1: 'APPLICATION', 2: 'CONTEXT', 3: 'PRIVATE'}
UNIVERSAL_TAG_NAMES = {
    1: 'BOOLEAN', 2: 'INTEGER', 4: 'OCTET STRING', 10: 'ENUMERATED',
    12: 'UTF8String', 16: 'SEQUENCE', 17: 'SET', 19: 'PrintableString',
    22: 'IA5String', 25: 'GraphicString', 27: 'GeneralString',
}


def parse_tlv(buf, i):
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
    ln = buf[i]
    i += 1
    if ln == 0x80:
        content_start = i
        i = skip_to_eoc(buf, i)
        content_end = i
        i += 2
    elif ln & 0x80:
        n = ln & 0x7F
        length = int.from_bytes(buf[i:i + n], 'big')
        i += n
        content_start = i
        content_end = i + length
        i = content_end
    else:
        content_start = i
        content_end = i + ln
        i = content_end
    return tag_class, tag_num, constructed, content_start, content_end, i


def skip_to_eoc(buf, i):
    while not (buf[i] == 0x00 and buf[i + 1] == 0x00):
        _, _, _, _, _, i = parse_tlv(buf, i)
    return i


def children(buf, start, end):
    i = start
    while i < end:
        tc, tn, con, cs, ce, nxt = parse_tlv(buf, i)
        yield tc, tn, con, cs, ce
        i = nxt


def describe(tc, tn, con):
    if tc == 0:
        name = UNIVERSAL_TAG_NAMES.get(tn, f'UNIV-{tn}')
        return f'{name}{"+" if con else ""}'
    return f'[{CLASS_NAMES[tc][:3]} {tn}]{"+" if con else ""}'


def collect(buf, cs, ce, path, sigs, depth=0, max_depth=14):
    """Her constructed yol icin, o yolun DOGRUDAN cocuklarinin imza kumesini
    biriktirir; ardindan CONTEXT etiketli cocuklara inerek yolu uzatir."""
    if depth > max_depth:
        return
    kids = list(children(buf, cs, ce))
    if not kids:
        return
    sigs[path].update(describe(tc, tn, con) for tc, tn, con, _, _ in kids)
    for tc, tn, con, ics, ice in kids:
        if not con:
            continue
        # CONTEXT etiketi yolu bir seviye derinlestirir; universal sarmal
        # (SEQUENCE/SET elemanlari, EXPLICIT katmani) ayni yolda kalir - bu
        # sayede iki dosyada farkli sayida eleman olmasi fark uretmez.
        collect(buf, ics, ice, path + (tn,) if tc == CONTEXT else path,
                sigs, depth + 1, max_depth)


def scan(path, max_records=None):
    buf = open(path, 'rb').read()
    sigs = defaultdict(set)
    i, count = 0, 0
    while i < len(buf):
        tc, tn, con, cs, ce, nxt = parse_tlv(buf, i)
        count += 1
        if con:
            collect(buf, cs, ce, (tn,), sigs)
        i = nxt
        if max_records and count >= max_records:
            break
    return sigs, count


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    if len(args) < 2:
        print(__doc__)
        return 2
    max_records = None
    for a in sys.argv[1:]:
        if a.startswith('--max-records'):
            max_records = int(a.split('=', 1)[1]) if '=' in a else None
    ref_path, our_path = args[0], args[1]

    ref, ref_n = scan(ref_path, max_records)
    our, our_n = scan(our_path, max_records)
    print(f"Referans : {ref_path}\n           {ref_n} kayit, {len(ref)} farkli yol")
    print(f"Bizimki  : {our_path}\n           {our_n} kayit, {len(our)} farkli yol")
    print()

    shared = sorted(set(ref) & set(our))
    only_ref = sorted(set(ref) - set(our))
    only_our = sorted(set(our) - set(ref))

    mismatches = []
    for p in shared:
        # Bizim uretmedigimiz varyantlar sorun degil (OPTIONAL alan / farkli
        # CHOICE dali). Sorun, referansta HIC gorulmeyen bir sey uretmemiz.
        extra = our[p] - ref[p]
        if extra:
            mismatches.append((p, sorted(extra), sorted(ref[p])))

    if mismatches:
        print(f"!! {len(mismatches)} yolda referansta HIC gorulmeyen yapi uretiyoruz:")
        for p, extra, expected in mismatches:
            print(f"  yol={p}")
            print(f"     bizde fazladan : {extra}")
            print(f"     referansta var : {expected}")
        print()
    else:
        print("OK - ortak yollarin HICBIRINDE referansta bulunmayan bir yapi uretmiyoruz.")
        print()

    if only_our:
        print(f"Not: referansta hic gorulmeyen {len(only_our)} yol uretiyoruz "
              f"(referans dosyada o OPTIONAL alanlar bos olabilir - tek basina hata degil):")
        for p in only_our[:40]:
            print(f"  {p} -> {sorted(our[p])}")
        if len(only_our) > 40:
            print(f"  ... (+{len(only_our) - 40} tane daha)")
        print()

    if only_ref:
        print(f"Not: referansta olup bizde olmayan {len(only_ref)} yol var "
              f"(doldurmadigimiz OPTIONAL alanlar - kodlama hatasi degil).")

    return 1 if mismatches else 0


if __name__ == "__main__":
    sys.exit(main())
