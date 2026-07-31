#!/usr/bin/env python3
"""
verifyMmtelBerFull.py + checkDuplicateTags.py'nin MMTel'e ozel olmayan,
HERHANGI BIR semaya uygulanabilen genel hali. O ikisi datastructure.json'daki
modul adini ve kok tip adini ('MMTelChargingDataTypes' / 'MMTelServiceRecord')
sabit kodluyordu; asagidaki mantigin kendisi (build_expected_shapes, walk,
check_shape, check_fixed_body) zaten semadan bagimsizdi - sadece main()
sabitti. Bu script ayni mantigi, kok tipin CHOICE mi yoksa duz SEQUENCE/SET
mi oldugunu OTOMATIK algilayarak herhangi bir modul + kok tip icin çalıştırır.

Kullanim:
    python3 verifyGenericBer.py dosya.ber datastructure.json MODUL_ADI [KOK_TIP_ADI]

KOK_TIP_ADI verilmezse StructureParserService'in mantigini yansitan
select_root_type_name ile otomatik secilir.
"""
import sys, json, importlib.util
from collections import Counter

_here = __file__.rsplit('/', 1)[0]
_spec_v = importlib.util.spec_from_file_location("verifyMmtelBerFull", _here + "/verifyMmtelBerFull.py")
_v = importlib.util.module_from_spec(_spec_v)
_spec_v.loader.exec_module(_v)
rp = _v._rp

CONTEXT = 2


def check_fixed_body(buf, cs, ce, path, problems, record_no, is_set=False):
    ctx_tags = [tn for tc, tn, con, i, e in _v.children(buf, cs, ce) if tc == CONTEXT]
    dups = {t: c for t, c in Counter(ctx_tags).items() if c > 1}
    if dups:
        problems.append(f"kayit #{record_no} yol={path}: tekrarlanan tag'ler={dups}")
    if is_set and ctx_tags != sorted(ctx_tags):
        bad = [(ctx_tags[q], ctx_tags[q + 1])
               for q in range(len(ctx_tags) - 1) if ctx_tags[q] > ctx_tags[q + 1]]
        problems.append(f"kayit #{record_no} yol={path}: SET bilesenleri ARTAN SIRADA DEGIL "
                        f"(X.690 11.6 ihlali), bozulma noktalari={bad}")


def dup_walk(buf, cs, ce, path, expected, problems, record_no, depth=0):
    if depth > 12:
        return
    for tc, tn, con, ics, ice in _v.children(buf, cs, ce):
        if tc != CONTEXT:
            continue
        new_path = path + (tn,)
        shape = expected.get(new_path)
        if shape in ('scalar_set_implicit', 'scalar_seq_implicit',
                     'scalar_set_explicit', 'scalar_seq_explicit'):
            rcs, rce = _v.real_content_bounds(buf, ics, ice, shape)
            check_fixed_body(buf, rcs, rce, new_path, problems, record_no,
                             is_set=shape.startswith('scalar_set'))
            dup_walk(buf, rcs, rce, new_path, expected, problems, record_no, depth + 1)
        elif shape and shape.startswith('repeated_elem_'):
            rcs, rce = _v.real_content_bounds(buf, ics, ice, shape)
            for etc, etn, econ, eics, eice in _v.children(buf, rcs, rce):
                if etc == 0 and econ:
                    check_fixed_body(buf, eics, eice, new_path + ('eleman',), problems, record_no,
                                     is_set=(etn == 17))
            dup_walk(buf, rcs, rce, new_path, expected, problems, record_no, depth + 1)
        elif con:
            dup_walk(buf, ics, ice, new_path, expected, problems, record_no, depth + 1)


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    ber_path, ds_path, module_name = sys.argv[1], sys.argv[2], sys.argv[3]
    root_type_name = sys.argv[4] if len(sys.argv) > 4 else None

    data = json.load(open(ds_path, encoding='utf-8'))
    mod = next((d for d in data if d['name'] == module_name), None)
    if mod is None:
        print(f"{module_name} modulu bulunamadi")
        return 2
    contents = mod['contents']

    tagging_mode = rp.detect_tagging_mode(contents)
    registry = rp.build_registry(contents)
    if root_type_name is None:
        root_type_name = rp.select_root_type_name(registry, tagging_mode)

    expected, root_kind, root_fields = rp.build_expected_shapes(contents, root_type_name, max_depth=8)
    print(f"Modul: {module_name}, kok tip: {root_type_name} (root_kind={root_kind})")
    print(f"Semadan {len(expected)} constructed alan icin beklenen sekil hesaplandi.")

    alt_by_tag = {f.tag_number: f for f in root_fields if f.tag_number is not None}

    buf = open(ber_path, "rb").read()
    shape_problems, dup_problems = [], []
    hit_paths = set()
    i, record_no = 0, 0
    while i < len(buf):
        tc, tn, con, cs, ce, nxt = _v.parse_tlv(buf, i)
        record_no += 1

        if root_kind == 'CHOICE':
            alt = alt_by_tag.get(tn) if (con and tc == CONTEXT) else None
            if alt is not None:
                record_path = (tn,)
                rcs, rce = cs, ce
                if record_path in expected:
                    shape = expected[record_path]
                    _v.check_shape(buf, record_path, cs, ce, shape, shape_problems, record_no, hit_paths)
                    rcs, rce = _v.real_content_bounds(buf, cs, ce, shape)
                check_fixed_body(buf, rcs, rce, record_path, dup_problems, record_no, is_set=bool(alt.set_))
                _v.walk(buf, rcs, rce, record_path, expected, shape_problems, record_no, 0, hit_paths)
                dup_walk(buf, rcs, rce, record_path, expected, dup_problems, record_no)
        else:
            # Duz SEQUENCE/SET kok: bu TLV'nin kendisi kaydin UNIVERSAL sarmali,
            # icerigi dogrudan alan listesidir - yapay bir CONTEXT yol katmani yok.
            is_set = (root_kind == 'SET')
            check_fixed_body(buf, cs, ce, (), dup_problems, record_no, is_set=is_set)
            _v.walk(buf, cs, ce, (), expected, shape_problems, record_no, 0, hit_paths)
            dup_walk(buf, cs, ce, (), expected, dup_problems, record_no)

        i = nxt

    print(f"{record_no} kayit tarandi.")
    print(f"Kapsam: {len(hit_paths)}/{len(expected)} constructed alan dolu cikip fiilen kontrol edildi.")

    ok = True
    if shape_problems:
        ok = False
        print(f"{len(shape_problems)} SARMAL SEKLI ihlali:")
        for p in dict.fromkeys(shape_problems):
            print("  " + p)
    else:
        print("Sarmal-sekil kontrolu temiz.")

    if dup_problems:
        ok = False
        print(f"{len(dup_problems)} DUPLICATE/SIRA ihlali:")
        for p in dict.fromkeys(dup_problems):
            print("  " + p)
    else:
        print("Duplicate/sira kontrolu temiz.")

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
