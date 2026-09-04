#!/usr/bin/env python3
"""
milestone.py'nin repeated-CHOICE koleksiyonlarini decode etme davranisini
dogrular. Hermetik: repo disindaki EMM referans dosyasina bagimli degil,
kendi kucuk alan agacini ve BER baytlarini elle kurar.

Neden bu test var: BerEncoderService.encodeRepeated bir SEQUENCE OF <CHOICE>
elemanini SARMALAYICISIZ yazar - eleman zaten secilen alternatifin kendi
etiketli TLV'sidir (elementIsChoice dali, encodeConstructed dogrudan uygulanir).
milestone.py'nin eski decode_record'u HER tekrarli alanin elemanini "once ac,
sonra icindekileri agaca gore ada" varsayimiyla yuruyordu - bu varsayim
SEQUENCE OF <SEQUENCE> icin dogru (interOperatorIdentifiers: her eleman kendi
universal SEQUENCE sarmalayicisinda) ama SEQUENCE OF <CHOICE> icin yanlis:
eleman zaten alternatifin kendi TLV'si oldugu icin "ac" diye bir sey yok, ve
eski kod degeri path[i] altina alternatifin ADI OLMADAN, DIS alanin tipiyle
yaziyordu. Sonuc: dogru kodlanmis bir deger, referansla karsilastirilinca hem
"eksik" (path[i].sIP-URI bulunamadi) hem "fazla" (path[i] karsiliksiz) gorunuyordu
- 04.09.2026'da P1 olcumunde canli uretimde boyle yakalandi, baytlar elle
dogrulanip duzeltildi.

Kullanim:
    python3 test_milestone_choice_decode.py
"""
import sys

import milestone

CONTEXT = 2
UNIVERSAL = 0


def tlv(tag_class, tag_number, constructed, content):
    """Tek baytlik tag/uzunluk varsayimiyla kucuk bir TLV kurar (bu testin
    degerleri hep < 128 bayt)."""
    first = (tag_class << 6) | (0x20 if constructed else 0) | tag_number
    return bytes([first, len(content)]) + content


def root(field_tlv):
    """Kayit govdesini tek bir universal SEQUENCE TLV'sine sarar - milestone.py
    her zaman boyle bir govde bekler (once disariyi acar, sonra root_children'i
    icine yurur)."""
    return tlv(UNIVERSAL, 16, True, field_tlv)


def main():
    failures = []

    def check(name, condition, detail=''):
        print(f"  {'PASS' if condition else 'FAIL'}  {name}")
        if not condition:
            failures.append(name)
            if detail:
                print(f"        {detail}")

    # ---- 1) P2'nin hedeflediği şekil: repeated CHOICE, İKİ alternatif deklare
    #         edilmiş, İKİ farklı eleman telde. Üretecin bugünkü hali bunu henüz
    #         üretmiyor (CHOICE_ELEMENT_COUNT=1) - bu, aracın onu ölçebileceğini
    #         önceden kanıtlıyor.
    two_alt_field = {
        'fieldName': 'list-Of-Calling-Party-Address',
        'fieldType': 'ListOfInvolvedParties',
        'repeated': True,
        'choice': True,
        'tagNumber': 6,
        'children': [
            {'fieldName': 'sIP-URI', 'fieldType': 'IA5String', 'tagNumber': 0},
            {'fieldName': 'tEL-URI', 'fieldType': 'IA5String', 'tagNumber': 1},
        ],
    }
    content = (tlv(CONTEXT, 0, False, b'sip:test1@example.org')
               + tlv(CONTEXT, 1, False, b'tel:0123456789'))
    data = root(tlv(CONTEXT, 6, True, content))
    leaves = milestone.decode_record(data, [two_alt_field])

    paths = {p: (v, t) for p, v, t in leaves}
    check("[0] -> sIP-URI dogru yolda ve degerde",
          paths.get('list-Of-Calling-Party-Address[0].sIP-URI')
          == (b'sip:test1@example.org', 'IA5String'),
          f"bulunan: {paths.get('list-Of-Calling-Party-Address[0].sIP-URI')}")
    check("[1] -> tEL-URI dogru yolda ve degerde",
          paths.get('list-Of-Calling-Party-Address[1].tEL-URI')
          == (b'tel:0123456789', 'IA5String'),
          f"bulunan: {paths.get('list-Of-Calling-Party-Address[1].tEL-URI')}")
    check("baska yaprak uretilmedi", len(leaves) == 2, f"leaves={leaves}")

    # ---- 2) Bugunku gercek uretim sekli: TEK alternatif deklare edilmis, TEK
    #         eleman telde. 04.09.2026'da p1.ber'de elle dogrulanan baytlarin
    #         birebir ayni - canli uretimle bagi kopmasin diye kopyalandi.
    one_alt_field = {
        'fieldName': 'list-Of-Calling-Party-Address',
        'fieldType': 'ListOfInvolvedParties',
        'repeated': True,
        'choice': True,
        'tagNumber': 6,
        'children': [
            {'fieldName': 'sIP-URI', 'fieldType': 'IA5String', 'tagNumber': 0},
        ],
    }
    single_value = b'sip:+905452344327@ims.mnc001.mcc286.3gppnetwork.org'
    data = root(tlv(CONTEXT, 6, True, tlv(CONTEXT, 0, False, single_value)))
    leaves = milestone.decode_record(data, [one_alt_field])

    check("tek alternatifte de [0].sIP-URI dogru cikiyor",
          leaves == [('list-Of-Calling-Party-Address[0].sIP-URI', single_value, 'IA5String')],
          f"bulunan: {leaves}")

    # ---- 3) Bozulmamasi gereken sekil: SEQUENCE OF <SEQUENCE>. Her eleman
    #         KENDI universal SEQUENCE sarmalayicisinda - interOperatorIdentifiers
    #         gibi. choice=False oldugu icin yeni dal hic devreye girmemeli.
    seq_of_seq_field = {
        'fieldName': 'interOperatorIdentifiers',
        'fieldType': 'InterOperatorIdentifiers',
        'repeated': True,
        'choice': False,
        'tagNumber': 20,
        'children': [
            {'fieldName': 'originatingIOI', 'fieldType': 'GraphicString', 'tagNumber': 0},
            {'fieldName': 'terminatingIOI', 'fieldType': 'GraphicString', 'tagNumber': 1},
        ],
    }
    elem0 = tlv(UNIVERSAL, 16, True, tlv(CONTEXT, 0, False, b'ims.mnc001.mcc286.3gppnetwork.org'))
    elem1 = tlv(UNIVERSAL, 16, True, tlv(CONTEXT, 1, False, b'vodafone.com.tr'))
    data = root(tlv(CONTEXT, 20, True, elem0 + elem1))
    leaves = milestone.decode_record(data, [seq_of_seq_field])

    check("SEQUENCE OF <SEQUENCE> [0] hala dogru",
          leaves[0] == ('interOperatorIdentifiers[0].originatingIOI',
                        b'ims.mnc001.mcc286.3gppnetwork.org', 'GraphicString'),
          f"bulunan: {leaves[0] if leaves else None}")
    check("SEQUENCE OF <SEQUENCE> [1] hala dogru",
          len(leaves) > 1 and leaves[1] == ('interOperatorIdentifiers[1].terminatingIOI',
                                            b'vodafone.com.tr', 'GraphicString'),
          f"bulunan: {leaves[1] if len(leaves) > 1 else None}")

    # ---- 4) Eslesmeyen bir tag: tanimsiz alternatif <tagN> olarak dusmeli,
    #         sessizce atlanmamali veya cokmemeli.
    data = root(tlv(CONTEXT, 6, True, tlv(CONTEXT, 9, False, b'unknown-alt')))
    leaves = milestone.decode_record(data, [one_alt_field])
    check("bilinmeyen alternatif <tag9> olarak dusuyor, sessizce kaybolmuyor",
          leaves == [('list-Of-Calling-Party-Address[0].<tag9>', b'unknown-alt', None)],
          f"bulunan: {leaves}")

    print(f"\n{len(failures)} basarisiz" if failures else "\nhepsi gecti")
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
