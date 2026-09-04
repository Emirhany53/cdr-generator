#!/usr/bin/env python3
"""
ref2request.py'nin tekrarli CHOICE indekslemesini dogrular.

Neden ayri bir test: EMM'in decode'u bir SEQUENCE OF <CHOICE> blogunu indekssiz,
duz yazar. O bloktaki iki satir ayri iki ELEMANDIR, ayni elemanin iki alani
degil - ve uretec onlari "alan[i].alt" diye adresler. Indeks geri konmazsa
list-Of-Calling-Party-Address'in iki alternatifi (sIP-URI + tEL-URI) tek bir
anahtara cakisir ve referans yeniden uretilemez.

Kural semadan okunur, metinden degil: bir blok yalnizca alan agacinda repeated
VE choice ise elemanlara bolunur. Asagidaki kontroller hem o bolmenin
yapildigini hem de BOLUNMEMESI gereken uc sekle dokunulmadigini pinler
(duz SEQUENCE, tekrarsiz CHOICE, zaten indeksli SEQUENCE OF <SEQUENCE>).

Kullanim:
    python3 test_p3.py DECODE.txt ALANAGACI.json
"""
import json
import re
import sys

import ref2request

EXPECTED_LEAVES = 168
CALLING = 'mMTelRecord.list-Of-Calling-Party-Address'


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    decode_path, tree_path = sys.argv[1], sys.argv[2]
    field_values, leaf_count, _ = ref2request.build(decode_path, tree_path)

    failures = []

    def check(name, condition, detail=''):
        print(f"  {'PASS' if condition else 'FAIL'}  {name}")
        if not condition:
            failures.append(name)
            if detail:
                print(f"        {detail}")

    check("[0] sIP-URI anahtari var",
          f'{CALLING}[0].sIP-URI' in field_values,
          f"bulunan: {[k for k in field_values if 'Calling-Party' in k]}")
    check("[1] tEL-URI anahtari var",
          f'{CALLING}[1].tEL-URI' in field_values)
    check("[0] degeri referanstakiyle ayni",
          field_values.get(f'{CALLING}[0].sIP-URI')
          == 'sip:+905452344327@ims.mnc001.mcc286.3gppnetwork.org',
          repr(field_values.get(f'{CALLING}[0].sIP-URI')))
    check("[1] degeri referanstakiyle ayni",
          field_values.get(f'{CALLING}[1].tEL-URI') == 'tel:+905452344327',
          repr(field_values.get(f'{CALLING}[1].tEL-URI')))
    check("indekssiz eski anahtar uretilmiyor",
          f'{CALLING}.sIP-URI' not in field_values
          and f'{CALLING}.tEL-URI' not in field_values)

    # bolunmemesi gereken sekiller
    check("SEQUENCE OF <SEQUENCE> indeksleri korundu",
          'mMTelRecord.interOperatorIdentifiers[0].originatingIOI' in field_values
          and 'mMTelRecord.interOperatorIdentifiers[1].terminatingIOI' in field_values)
    check("duz SEQUENCE elemanlara bolunmedi",
          'mMTelRecord.nodeAddress.domainName' in field_values
          and not any(re.search(r'nodeAddress\[\d+\]', k) for k in field_values))
    check("tekrarsiz CHOICE indekslenmedi",
          'mMTelRecord.called-Party-Address.sIP-URI' in field_values)
    check(f"{EXPECTED_LEAVES} yaprak korundu",
          len(field_values) == EXPECTED_LEAVES and leaf_count == EXPECTED_LEAVES,
          f"fieldValues={len(field_values)} leaf={leaf_count}")

    print(f"\n{len(failures)} basarisiz" if failures else "\nhepsi gecti")
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
