#!/usr/bin/env python3
"""
Gercek ASN.1 anlaminda "duplicate tag" ihlali arar: bir SET/SEQUENCE'in SABIT,
adlandirilmis uyeleri arasinda AYNI CONTEXT tag'in birden fazla gorulmesi.

EMM'in verdigi hata tam olarak budur:
  Duplicate Tag data found for
  MMTelChargingDataTypes.MMTelServiceRecord.mMTelRecord.recordExtensions
      .enhancedPhoneFeatures1.[0]

Bu, verifyMmtelBerFull.py'nin kontrol ETMEDIGI ayri bir hata sinifidir - o
sadece "beklenenden fazla/eksik sarmal katmani" arar, iki FARKLI SIRA
elemanindaki ayni tag'i (repeated CHOICE'ta tamamen normal olan bir durumu)
kontrol etmez.

Onemli ayrim: "SEQUENCE OF <CHOICE>" gibi tekrarlanan alanlarda, birden fazla
eleman AYNI CHOICE alternatifini secebilir - bu durumda ayni CONTEXT tag yan
yana birden fazla kez gorulur ve bu TAMAMEN GECERLIDIR (her eleman kendi
kendini sinirlayan ayri bir TLV'dir). Bu script bu farki semadan turetilen
'expected shape' haritasiyla ayirt eder:
  - scalar_set/seq (implicit/explicit): DOGRUDAN cocuklar sabit, adlandirilmis
    alanlardir - ayni tag'in tekrari GERCEK bir ihlaldir.
  - repeated_elem_set/seq: her eleman kendi UNIVERSAL SET/SEQUENCE sarmalinda -
    bu sarmalin ICINDE (yani BIR elemanin kendi govdesinde) tag tekrari
    ihlaldir, ama ELEMANLAR ARASI ayni-tag'e sahip iki eleman olmasi ihlal
    DEGILDIR (kontrol edilmez).
  - (repeated_)choice_direct: elemanlarin kendisi bare CONTEXT tag'lerdir,
    aralarinda tekrar tamamen normaldir - hic kontrol edilmez.

Kullanim:
    python3 checkDuplicateTags.py /yol/dosya.ber [/yol/datastructure.json]
"""
import sys, json, importlib.util
from collections import Counter

_spec = importlib.util.spec_from_file_location(
    "verifyMmtelBerFull", __file__.rsplit('/', 1)[0] + "/verifyMmtelBerFull.py")
_v = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_v)

CONTEXT = 2


def check_fixed_body(buf, cs, ce, path, problems, record_no, is_set=False):
    ctx_tags = [tn for tc, tn, con, i, e in _v.children(buf, cs, ce) if tc == CONTEXT]
    dups = {t: c for t, c in Counter(ctx_tags).items() if c > 1}
    if dups:
        problems.append(f"kayit #{record_no} yol={path}: tekrarlanan tag'ler={dups}")
    # X.690 11.6: bir SET'in bilesenleri tag sirasina gore yazilmali. EMM'in
    # "Duplicate Tag" hatasinin gercek sebebi buydu: artan sira varsayan bir
    # cozucu, onceki tag'den KUCUK bir tag gorunce onu tekrar saniyor.
    if is_set and ctx_tags != sorted(ctx_tags):
        bad = [(ctx_tags[q], ctx_tags[q + 1])
               for q in range(len(ctx_tags) - 1) if ctx_tags[q] > ctx_tags[q + 1]]
        problems.append(f"kayit #{record_no} yol={path}: SET bilesenleri ARTAN SIRADA DEGIL "
                        f"(X.690 11.6 ihlali), bozulma noktalari={bad}")


def walk(buf, cs, ce, path, expected, problems, record_no, depth=0):
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
            walk(buf, rcs, rce, new_path, expected, problems, record_no, depth + 1)
        elif shape and shape.startswith('repeated_elem_'):
            rcs, rce = _v.real_content_bounds(buf, ics, ice, shape)
            for etc, etn, econ, eics, eice in _v.children(buf, rcs, rce):
                if etc == 0 and econ:  # UNIVERSAL SET(17)/SEQUENCE(16): bir eleman
                    check_fixed_body(buf, eics, eice, new_path + ('eleman',), problems, record_no,
                                     is_set=(etn == 17))
            walk(buf, rcs, rce, new_path, expected, problems, record_no, depth + 1)
        elif con:
            walk(buf, ics, ice, new_path, expected, problems, record_no, depth + 1)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    ber_path = sys.argv[1]
    default_ds_path = __file__.rsplit('/', 1)[0] + "/../src/main/resources/datastructure.json"
    ds_path = sys.argv[2] if len(sys.argv) > 2 else default_ds_path

    data = json.load(open(ds_path, encoding='utf-8'))
    contents = next((d['contents'] for d in data if d['name'] == 'MMTelChargingDataTypes'), None)
    if contents is None:
        print("MMTelChargingDataTypes modulu bulunamadi")
        return 2

    expected, root_kind, _ = _v._rp.build_expected_shapes(contents, 'MMTelServiceRecord', max_depth=8)

    buf = open(ber_path, "rb").read()
    problems = []
    i, record_no = 0, 0
    while i < len(buf):
        tc, tn, con, cs, ce, nxt = _v.parse_tlv(buf, i)
        record_no += 1
        if con and tc == CONTEXT and tn in (83, 999):
            # kok kayit (MMTelRecord) bir SET
            check_fixed_body(buf, cs, ce, (tn,), problems, record_no, is_set=True)
            walk(buf, cs, ce, (tn,), expected, problems, record_no)
        i = nxt

    print(f"{record_no} kayit tarandi.")
    if problems:
        print(f"{len(problems)} GERCEK tag tekrari bulundu:")
        for p in problems:
            print("  " + p)
        return 1
    print("Sabit semali hicbir govdede (kok kayit, scalar SET/SEQUENCE, "
          "veya tekrarlanan elemanlarin HER BIRININ kendi icinde) tag tekrari yok.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
