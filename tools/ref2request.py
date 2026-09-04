#!/usr/bin/env python3
"""
EMM decode dokumunu POST /api/cdr/generate-ber istegine cevirir.

Iki isi yapar ve ikisi de SEMAYA bakar, metne degil:

1) Deger bicimi. Decode 'HEX'H / '123'D / 'ad (7)' / "metin" / NULL yazar;
   uretec ise alanin ASN.1 tipine gore girdi bekler. Metin tipli bir alanda
   ('IA5String', 'UTF8String', ...) hex cozulur, OCTET STRING'de hex birakilir,
   ENUMERATED'da sayi kullanilir. NULL alan icin BOS DEGERLI anahtar yazilir:
   referenceMode yalnizca anahtarlara bakip alanin tarif edilip edilmedigine
   karar verir, encoder ise NULL'i icerige hic bakmadan dogru yazar (X.690 8.8).

2) Tekrarli CHOICE indeksi. EMM bir SEQUENCE OF <CHOICE> blogunu INDEKSSIZ,
   duz yazar:

       list-Of-Calling-Party-Address
       {
           sIP-URI : "sip:+90..."      <- eleman [0]
           tEL-URI : "tel:+90..."      <- eleman [1]
       }

   Ayni dosyada SEQUENCE OF <SEQUENCE> ise "[0] { ... }" bicimindedir
   (interOperatorIdentifiers). Yani indeksin yoklugu bir veri farki degil,
   EMM'in yazim bicimi - ama uretec tekrarli alanin cocugunu "alan[i].alt" diye
   adresliyor, dolayisiyla indeksi geri koymak gerekir. Karar alan agacindan
   verilir: bir blok yalnizca repeated VE choice ise elemanlara bolunur, boylece
   siradan bir SEQUENCE'in kardes alanlari yanlislikla ayri instance sayilmaz.

Kullanim:
    python3 ref2request.py DECODE.txt CIKTI.json ALANAGACI.json

ALANAGACI.json: GET /api/cdr/structures/{modul}?<secimler> yanitidir. Deger
bicimi ve tekrar/CHOICE karari oradan okunur, kok tipin adi da oradan gelir.
"""
import json
import re
import sys

import refparse

TEXT_TYPES = ("IA5String", "UTF8String", "GraphicString", "PrintableString", "VisibleString")


def field_metadata(tree):
    """Alan agacini {indekssiz yol: {type, repeated, choice}} sozlugune duzler."""
    meta = {}

    def walk(fields, prefix=""):
        for field in fields:
            name = field.get('fieldName')
            if not name:
                continue
            path = f"{prefix}.{name}" if prefix else name
            meta[path] = {
                'type': field.get('fieldType'),
                'repeated': bool(field.get('repeated')),
                'choice': bool(field.get('choice')),
            }
            if field.get('children'):
                walk(field['children'], path)

    walk(tree.get('fields') or [])
    return meta


def convert(name, raw, meta_by_name):
    """Decode literalini ureticinin bekledigi girdi bicimine cevirir."""
    raw = raw.strip()
    field_type = (meta_by_name.get(name) or {}).get('type') or ''
    is_text = any(token in field_type for token in TEXT_TYPES)

    if raw == 'NULL':
        return ''                                     # tarif edilmis ama degersiz
    matched = re.match(r"^'([0-9A-Fa-f]*)'H$", raw)
    if matched:
        digits = matched.group(1).upper()
        if is_text:
            try:
                return bytes.fromhex(digits).decode('ascii')
            except ValueError:
                return digits
        return digits
    matched = re.match(r"^'(-?\d+)'D$", raw)
    if matched:
        return matched.group(1)
    matched = re.match(r"^'([A-Za-z0-9_-]+)\s*\((-?\d+)\)'$", raw)
    if matched:
        return matched.group(2)                       # ENUMERATED: sayisi
    if raw.startswith('"'):
        return raw[1:].rsplit('"', 1)[0]
    return raw


def indexify_choice_collections(pairs, meta, root):
    """Duz yazilmis SEQUENCE OF <CHOICE> elemanlarina indeks geri koyar."""
    out, counters = [], {}
    for path, raw in pairs:
        parent, _, leaf = path.rpartition('.')
        if parent:
            lookup = f"{root}.{re.sub(r'\\[\\d+\\]', '', parent)}"
            info = meta.get(lookup)
            if info and info['repeated'] and info['choice']:
                index = counters.get(parent, 0)
                counters[parent] = index + 1
                path = f"{parent}[{index}].{leaf}"
        out.append((path, raw))
    return out


def build(decode_path, tree_path):
    tree = json.load(open(tree_path, encoding='utf-8'))
    meta = field_metadata(tree)
    root = (tree.get('fields') or [{}])[0].get('fieldName')

    by_name = {}
    for path, info in meta.items():
        by_name.setdefault(path.split('.')[-1], info)

    pairs = indexify_choice_collections(refparse.parse(decode_path), meta, root)

    field_values, empties = {}, 0
    for path, raw in pairs:
        name = re.sub(r'\[\d+\]$', '', path.split('.')[-1])
        value = convert(name, raw, by_name)
        if value == '':
            empties += 1
        field_values[f"{root}.{path}"] = value
    return field_values, len(pairs), empties


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    decode_path, out_path, tree_path = sys.argv[1], sys.argv[2], sys.argv[3]
    field_values, leaf_count, empties = build(decode_path, tree_path)

    request = {
        "structureName": "MMTelChargingDataTypes",
        "recordCount": 1,
        "referenceMode": True,
        "choiceSelections": {},
        "fieldValues": field_values,
    }
    with open(out_path, 'w', encoding='utf-8') as handle:
        json.dump(request, handle, indent=1, ensure_ascii=False)

    indexed = sum(1 for key in field_values if re.search(r'\[\d+\]', key))
    print(f"referanstan cikan yaprak : {leaf_count}")
    print(f"fieldValues girdisi      : {len(field_values)}")
    print(f"  indeksli anahtar       : {indexed}")
    print(f"  degersiz (NULL) alan   : {empties}")
    print(f"NOT: choiceSelections bos birakildi - cagiran doldurur.")
    return 0


if __name__ == '__main__':
    sys.exit(main())
