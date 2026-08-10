#!/usr/bin/env python3
"""
Bir .ber dosyasinda belirli bir CONTEXT tag'in icerigini fazladan bir
UNIVERSAL SEQUENCE (tag 16) katmaniyla sarmalar ve etkilenen tum ust
TLV'lerin uzunluklarini yeniden yazar.

Amac: enhancedPhoneFeatures1 [509] gibi "EXPLICIT SEQUENCE OF X" alanlarinda,
mevcut encoder'in disaridaki universal SEQUENCE (liste) katmanini atladigi
teorisini test etmek icin, o katmani GERI EKLEYIP EMM'e tekrar sunmak.

dropBerField.py'nin tam tersi: TLV silmek yerine, hedef tag'in mevcut
icerigini oldugu gibi koruyup disina yeni bir UNIV-SEQUENCE(16) TLV
sarmaliyor. Boylece:
    once: CTX[509] { UNIV-SET(17) elem }
    sonra: CTX[509] { UNIV-SEQUENCE(16) { UNIV-SET(17) elem } }

Kullanim:
    python3 wrapBerField.py girdi.ber cikti.ber TAG
"""
import sys

CONTEXT = 2
UNIV_SEQUENCE_TAG = 0x30  # universal, constructed, tag number 16


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
    ln = buf[i]
    i += 1
    if ln & 0x80:
        n = ln & 0x7F
        length = int.from_bytes(buf[i:i + n], 'big') if n else 0
        i += n
    else:
        length = ln
    return tag_class, tag_num, constructed, i, i + length


def tag_bytes_of(buf, i):
    j = i
    first = buf[j]
    j += 1
    if (first & 0x1F) == 0x1F:
        while buf[j] & 0x80:
            j += 1
        j += 1
    return buf[i:j]


def encode_length(n):
    if n < 0x80:
        return bytes([n])
    body = []
    while n > 0:
        body.insert(0, n & 0xFF)
        n >>= 8
    return bytes([0x80 | len(body)]) + bytes(body)


def rebuild(buf, start, end, target, path, wrapped):
    out = bytearray()
    i = start
    while i < end:
        tc, tn, con, cs, ce = parse_tlv(buf, i)
        tag_bytes = tag_bytes_of(buf, i)
        here = path + [tn] if tc == CONTEXT else path

        is_target = (tc == CONTEXT and tn == target)

        if is_target:
            inner_content = buf[cs:ce]  # untouched original content
            wrapped_inner = bytes([UNIV_SEQUENCE_TAG]) + encode_length(len(inner_content)) + inner_content
            wrapped.append(True)
            out += tag_bytes + encode_length(len(wrapped_inner)) + wrapped_inner
        elif con:
            inner = rebuild(buf, cs, ce, target, here, wrapped)
            out += tag_bytes + encode_length(len(inner)) + inner
        else:
            out += tag_bytes + encode_length(ce - cs) + buf[cs:ce]
        i = ce
    return bytes(out)


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    src, dst, target = sys.argv[1], sys.argv[2], int(sys.argv[3])

    buf = open(src, 'rb').read()
    wrapped = []
    out = bytearray()
    i = 0
    records = 0
    while i < len(buf):
        tc, tn, con, cs, ce = parse_tlv(buf, i)
        records += 1
        tag_bytes = tag_bytes_of(buf, i)
        path = [tn] if tc == CONTEXT else []
        if con:
            inner = rebuild(buf, cs, ce, target, path, wrapped)
            out += tag_bytes + encode_length(len(inner)) + inner
        else:
            out += buf[i:ce]
        i = ce

    open(dst, 'wb').write(bytes(out))
    print(f"{records} kayit islendi")
    print(f"sarmalanan [{target}] TLV sayisi: {len(wrapped)}")
    print(f"{len(buf)} bayt -> {len(out)} bayt")
    return 0


if __name__ == '__main__':
    sys.exit(main())
