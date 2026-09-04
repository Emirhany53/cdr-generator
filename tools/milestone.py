#!/usr/bin/env python3
"""
Uretilen bir .ber'i EMM decode dokumuyla YOL BAZINDA karsilastirir.

compareBerStructure.py yapiyi karsilastirir ve degerleri kasitli olarak yok
sayar; bu script tam tersini olcer: alan agacini kullanarak dosyayi cozer ve
"ayni yolda ayni DEGER" sayisini cikarir. Bir referans kaydini yeniden uretmeye
calisirken sorulan soru budur - alan adinin dosyada bir yerde gecmesi hicbir sey
kanitlamaz, deger dogru yolda olmalidir.

Uc sayi verir:
    correct = referansta olup uretilende AYNI YOLDA ayni degerle bulunan
    missing = referansta olup uretilende bulunmayan
    extra   = uretilende olup referansta karsiligi olmayan (gurultu)

Ayrica tekrarli yollarin eleman sayilarini referansla yan yana yazar ve
'[1] instance gercekten var mi, altindaki yaprak dogru degeri mi tasiyor'
sorusuna nokta atisi cevap verir.

Kullanim:
    python3 milestone.py URETILEN.ber ALANAGACI.json DECODE.txt [ETIKET]

ALANAGACI.json dosyayi COZMEK icin kullanilir; uretim hangi secimlerle
yapildiysa agac da o secimlerle alinmalidir, yoksa cozucu tanimadigi tag'i
'<tagN>' diye raporlar ve fark gercek olmayan bir yerde gorunur.
"""
import collections
import json
import re
import sys


def read_tlv(buf, i):
    first = buf[i]
    constructed = bool(first & 0x20)
    number = first & 0x1F
    i += 1
    if number == 0x1F:
        number = 0
        while True:
            number = (number << 7) | (buf[i] & 0x7F)
            more = buf[i] & 0x80
            i += 1
            if not more:
                break
    length = buf[i]
    i += 1
    if length & 0x80:
        count = length & 0x7F
        length = int.from_bytes(buf[i:i + count], 'big')
        i += count
    return constructed, number, i, length


def decode_record(data, root_children):
    """[(yol, ham bayt, alan tipi), ...] - tekrarli alanlar [i] ile indekslenir."""
    leaves = []

    def children_of(field):
        return (field or {}).get('children') or []

    def by_tag(fields):
        table = {}
        for field in fields:
            if field.get('tagNumber') is not None:
                table.setdefault(field['tagNumber'], field)
        return table

    def walk(buf, fields, prefix):
        table = by_tag(fields)
        i = 0
        while i < len(buf):
            constructed, number, off, length = read_tlv(buf, i)
            content = buf[off:off + length]
            field = table.get(number)
            name = field['fieldName'] if field else f"<tag{number}>"
            path = f"{prefix}.{name}" if prefix else name
            kids = children_of(field)
            if field and field.get('repeated'):
                if constructed:
                    j, index = 0, 0
                    while j < len(content):
                        inner_con, _, inner_off, inner_len = read_tlv(content, j)
                        element = content[inner_off:inner_off + inner_len]
                        element_path = f"{path}[{index}]"
                        if inner_con and kids:
                            walk(element, kids, element_path)
                        else:
                            leaves.append((element_path, element, field.get('fieldType')))
                        j = inner_off + inner_len
                        index += 1
                i = off + length
                continue
            if constructed and kids:
                walk(content, kids, path)
            elif constructed:
                walk(content, fields, path)
            else:
                leaves.append((path, content, (field or {}).get('fieldType')))
            i = off + length

    _, _, off, length = read_tlv(data, 0)
    walk(data[off:off + length], root_children, "")
    return leaves


def strip_index(path):
    return re.sub(r'\.+', '.', re.sub(r'\[\d+\]', '', path)).strip('.')


def reference_value(raw):
    raw = raw.strip()
    if raw == 'NULL':
        return '<NULL>'
    matched = re.match(r"^'([0-9A-Fa-f]*)'H$", raw)
    if matched:
        digits = matched.group(1).upper()
        try:
            text = bytes.fromhex(digits).decode('ascii')
            if text and all(32 <= ord(c) < 127 for c in text):
                return text
        except ValueError:
            pass
        return digits
    matched = re.match(r"^'(-?\d+)'D$", raw)
    if matched:
        return matched.group(1)
    matched = re.match(r"^'([A-Za-z0-9_-]+)\s*\((-?\d+)\)'$", raw)
    if matched:
        return matched.group(2)
    if raw.startswith('"'):
        return raw[1:].rsplit('"', 1)[0]
    return raw


def generated_value(raw_bytes, field_type):
    field_type = (field_type or '').upper()
    if not raw_bytes:
        return '<NULL>'
    if 'INTEGER' in field_type or 'ENUM' in field_type:
        return str(int.from_bytes(raw_bytes, 'big', signed=False))
    try:
        text = raw_bytes.decode('ascii')
        if all(32 <= ord(c) < 127 for c in text):
            return text
    except UnicodeDecodeError:
        pass
    return raw_bytes.hex().upper()


def element_counts(paths):
    counts = collections.Counter()
    for path in paths:
        for matched in re.finditer(r'\[(\d+)\]', path):
            key = path[:matched.start()]
            counts[key] = max(counts[key], int(matched.group(1)) + 1)
    return counts


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    ber_path, tree_path, decode_path = sys.argv[1], sys.argv[2], sys.argv[3]
    label = sys.argv[4] if len(sys.argv) > 4 else ber_path

    sys.path.insert(0, __file__.rsplit('/', 1)[0])
    import refparse

    tree = json.load(open(tree_path, encoding='utf-8'))
    root_children = ((tree.get('fields') or [{}])[0]).get('children') or []
    leaves = decode_record(open(ber_path, 'rb').read(), root_children)
    reference = refparse.parse(decode_path)

    ref_bag = collections.Counter(
        (strip_index(p), reference_value(v)) for p, v in reference)
    our_bag = collections.Counter(
        (strip_index(p), generated_value(b, t)) for p, b, t in leaves)
    matched = sum(min(count, our_bag.get(key, 0)) for key, count in ref_bag.items())
    ref_total, our_total = sum(ref_bag.values()), sum(our_bag.values())

    print(f"=========== {label} ===========")
    print(f"correct : {matched} / {ref_total}   (%{matched / ref_total * 100:.1f})")
    print(f"missing : {ref_total - matched}")
    print(f"extra   : {our_total - matched}")

    ref_counts = element_counts(p for p, _ in reference)
    our_counts = element_counts(p for p, _, _ in leaves)
    watched = sorted(set(ref_counts) | set(our_counts))
    differing = [w for w in watched if ref_counts.get(w, 0) != our_counts.get(w, 0)]
    print(f"\ntekrar sayisi FARKLI olan yollar ({len(differing)}):")
    for path in differing:
        print(f"  {path[-66:]:66s} ref {ref_counts.get(path, 0):3d} / uretilen {our_counts.get(path, 0):3d}")

    print("\neksik ve fazla degerler:")
    for title, left, right in (("EKSIK", ref_bag, our_bag), ("FAZLA", our_bag, ref_bag)):
        items = [(k[0], k[1], c - min(c, right.get(k, 0)))
                 for k, c in left.items() if c - min(c, right.get(k, 0)) > 0]
        print(f"  {title} ({sum(i[2] for i in items)}):")
        for path, value, count in sorted(items):
            print(f"     x{count}  {path[-52:]:52s} = {value[:34]!r}")
    return 0


if __name__ == '__main__':
    sys.exit(main())
