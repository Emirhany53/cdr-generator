#!/usr/bin/env python3
"""
EMM'in decode dokumunu (alanAdi : deger metni) yol/deger ciftlerine cevirir.

Yuvalanmayi GIRINTIDEN DEGIL, SUSLU PARANTEZDEN takip eder. Sebep olculdu:
sorumlunun gonderdigi MMTel dokumunde girinti birkac yerde bozuk - '{' ve bazi
alanlar sutun 0'a kaymis - ama parantezler dengeli (41/41). Girintiye guvenen
bir ayristirici o dosyada ic ice yapiyi sessizce duzlestiriyor ve
list-Of-SDP-Media-Components[1] gibi bir kolu tamamen kaybediyordu.

Uretilen yol bicimi ureticinin kendi adreslemesiyle ayni:

    list-Of-SDP-Media-Components[1].sDP-Media-Components[0].sDP-Media-Descriptions[7]

Kullanim:
    python3 refparse.py DECODE.txt
    # ya da: from refparse import parse
"""
import re
import sys

NAME_VALUE = re.compile(r'^([A-Za-z][A-Za-z0-9_-]*)\s*:\s*(.+)$')
IDX_VALUE = re.compile(r'^\[(\d+)\]\s*:\s*(.+)$')
NAME_ONLY = re.compile(r'^([A-Za-z][A-Za-z0-9_-]*)$')
IDX_ONLY = re.compile(r'^\[(\d+)\]$')


def parse(path):
    """[(yol, ham deger), ...] dondurur. Ham deger decode'daki literaldir."""
    pairs = []
    stack = []       # acik bloklarin adlari; None = adsiz blok
    pending = None   # bir sonraki '{' bu adi alir
    with open(path, encoding='utf-8', errors='replace') as handle:
        for raw in handle.read().splitlines():
            line = raw.strip()
            if not line:
                continue
            if line == '{':
                stack.append(pending)
                pending = None
                continue
            if line == '}':
                if stack:
                    stack.pop()
                continue
            matched = IDX_VALUE.match(line)          # [n] : deger -> tekrarli yaprak elemani
            if matched:
                pairs.append((_join(stack) + '[' + matched.group(1) + ']', matched.group(2)))
                continue
            matched = NAME_VALUE.match(line)         # ad : deger
            if matched:
                base = _join(stack)
                pairs.append((base + '.' + matched.group(1) if base else matched.group(1),
                              matched.group(2)))
                continue
            matched = IDX_ONLY.match(line)           # [n] -> grup elemani basligi
            if matched:
                pending = '[' + matched.group(1) + ']'
                continue
            matched = NAME_ONLY.match(line)          # ad -> blok basligi
            if matched:
                pending = matched.group(1)
    return pairs


def _join(stack):
    """Adsiz bloklari atlar; '[n]' parcasini bir onceki ada indeks olarak yapistirir."""
    out = ''
    for name in stack:
        if name is None:
            continue
        if name.startswith('['):
            out += name
        elif out:
            out += '.' + name
        else:
            out = name
    return out


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    pairs = parse(sys.argv[1])
    print(f"yaprak deger: {len(pairs)}")
    for path, value in pairs:
        print(f"  {path} = {value[:70]}")
    return 0


if __name__ == '__main__':
    sys.exit(main())
