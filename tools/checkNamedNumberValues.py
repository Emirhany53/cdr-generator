#!/usr/bin/env python3
"""
Semadaki adlandirilmis-sayi (named-number) INTEGER/ENUMERATED alanlarinin
GERCEK .ber baytlarindaki degerinin, semanin izin verdigi sayi kumesi
icinde olup olmadigini dogrular.

Motivasyon: FieldValueGenerator'daki ayni sinif hata (epf1-Role-of-Node=85038
gibi sema disi degerler) daha once RandomValueSource yolunda duzeltildi
(FieldValueGenerator.produce/isCompatibleWithFieldType). Ancak AiValueSource
farkli bir sinif (FieldValueValidator) kullaniyor ve o sinifin
matchesPrimitiveType metodu INTEGER/ENUMERATED icin sadece "sayi mi" diye
bakiyor, "semadaki listede mi" diye bakmiyor - yani AI'in urettigi bir deger
sema disi olsa bile FieldValueValidator onu "uyumlu" sayip encoder'a kadar
gecirebilir. Bu script GERCEK bir .ber dosyasinda bu acigin fiilen bir
soruna yol acip acmadigini, semadan turetilen alan agaciyla byte'lari
esleyerek kontrol eder.

Kullanim:
    python3 checkNamedNumberValues.py dosya.ber datastructure.json MODUL_ADI
"""
import sys, json
sys.path.insert(0, __file__.rsplit('/', 1)[0])
import mmtel_resolver_port as rp


def parse_tlv(buf, i):
    first = buf[i]
    tag_class = first >> 6
    constructed = bool(first & 0x20)
    tag_num = first & 0x1F
    i += 1
    if tag_num == 0x1F:
        tag_num = 0
        while True:
            b = buf[i]; i += 1
            tag_num = (tag_num << 7) | (b & 0x7F)
            if not b & 0x80:
                break
    ln = buf[i]; i += 1
    if ln & 0x80:
        n = ln & 0x7F
        length = int.from_bytes(buf[i:i + n], 'big') if n else 0
        i += n
    else:
        length = ln
    content_start = i
    content_end = i + length
    return tag_class, tag_num, constructed, content_start, content_end, content_end


def children(buf, start, end):
    i = start
    while i < end:
        tc, tn, con, cs, ce, nxt = parse_tlv(buf, i)
        yield tc, tn, con, cs, ce
        i = nxt


def decode_int(buf, cs, ce):
    if ce <= cs:
        return 0
    value = int.from_bytes(buf[cs:ce], 'big', signed=True)
    return value


def declared_numbers(field_type):
    if not field_type or '{' not in field_type:
        return None
    body = field_type[field_type.find('{') + 1: field_type.rfind('}')]
    return {int(m.group(2)) for m in rp.NAMED_NUMBER_ENTRY.finditer(body)}


findings_ok = 0
findings_bad = []
unknown_tags = []


def walk(fields, buf, cs, ce, path):
    global findings_ok
    by_tag = {f.tag_number: f for f in fields if f.tag_number is not None}
    for tc, tn, con, vs, ve in children(buf, cs, ce):
        f = by_tag.get(tn)
        if f is None:
            unknown_tags.append(path + (tn,))
            continue
        process(f, con, buf, vs, ve, path + (tn,))


def process(f, con, buf, vs, ve, path):
    global findings_ok
    is_constructed_schema = bool(f.children) or f.repeated

    if f.repeated:
        if f.choice:
            # Each element is a bare CHOICE-alternative TLV; match against f.children.
            for tc, tn, econ, ecs, ece in children(buf, vs, ve):
                alt = next((c for c in f.children if c.tag_number == tn), None)
                if alt is not None:
                    process(alt, econ, buf, ecs, ece, path + ('elem', tn))
        elif f.children:
            # Each element wrapped in its own UNIVERSAL SEQUENCE/SET tag.
            for tc, tn, econ, ecs, ece in children(buf, vs, ve):
                walk(f.children, buf, ecs, ece, path + ('elem',))
        return

    if f.choice:
        # content IS the selected alternative's own TLV, no universal wrapper.
        for tc, tn, ccon, ccs, cce in children(buf, vs, ve):
            alt = next((c for c in f.children if c.tag_number == tn), None)
            if alt is not None:
                process(alt, ccon, buf, ccs, cce, path + (tn,))
            break
        return

    if f.children:
        if f.explicit:
            # content is ONE universal SEQUENCE/SET TLV; unwrap it.
            inner = list(children(buf, vs, ve))
            if inner:
                _, _, _, ics, ice = inner[0]
                walk(f.children, buf, ics, ice, path)
        else:
            walk(f.children, buf, vs, ve, path)
        return

    # Leaf field: check named-number membership if declared.
    nums = declared_numbers(f.field_type)
    if nums is not None:
        value = decode_int(buf, vs, ve)
        if value in nums:
            findings_ok += 1
        else:
            findings_bad.append((path, f.field_name, f.field_type, value))


def main():
    ber_path, ds_path, module_name = sys.argv[1], sys.argv[2], sys.argv[3]
    data = json.load(open(ds_path))
    mod = next(m for m in data if m.get('name') == module_name)
    contents = mod['contents']
    tagging_mode = rp.detect_tagging_mode(contents)
    registry = rp.build_registry(contents)
    root_name = rp.select_root_type_name(registry, tagging_mode)
    root_kind, alts = rp.resolve_root(registry, root_name, tagging_mode)

    buf = open(ber_path, 'rb').read()
    record_count = 0
    i = 0
    while i < len(buf):
        tc, tn, con, cs, ce, nxt = parse_tlv(buf, i)
        record_count += 1
        if root_kind == 'CHOICE':
            alt = next((a for a in alts if a.tag_number == tn), None)
            if alt is not None:
                process(alt, con, buf, cs, ce, (tn,))
        else:
            walk(alts, buf, cs, ce, ())
        i = nxt

    print(f"{record_count} kayit tarandi, kok: {root_name} ({root_kind})")
    print(f"Adlandirilmis-sayi alanlari: {findings_ok} GECERLI, {len(findings_bad)} SEMA DISI")
    if unknown_tags:
        print(f"Not: {len(unknown_tags)} eslenemeyen (semada bulunamayan) tag yolu var - ilk 10:")
        for p in unknown_tags[:10]:
            print("  ", p)
    if findings_bad:
        print("SEMA DISI degerler:")
        for path, name, ftype, value in findings_bad:
            print(f"  yol={path} alan={name} tip={ftype[:60]} deger={value}")
    return 1 if findings_bad else 0


if __name__ == '__main__':
    sys.exit(main())
