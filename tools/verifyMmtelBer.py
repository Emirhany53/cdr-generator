#!/usr/bin/env python3
"""
MMTel CDR (BER) tag-katmani dogrulayici.

EMM'in kabul ettigi ornek dosyadan cikarilan kurallari uygular:
MMTelChargingDataTypes modulu DEFINITIONS IMPLICIT TAGS oldugu icin,
[n] etiketi yalnizca hedef tip CHOICE ise EXPLICIT olur (X.680 31.2.7).
Diger tum alanlarda [n] dogrudan SEQUENCE/SET yerine gecer; araya
UNIVERSAL 16/17 girmemelidir.

Kullanim:
    python3 tools/verifyMmtelBer.py /yol/AVTAS_CDR_MMTEL_....ber
Cikis kodu 0 = temiz, 1 = ihlal bulundu.
"""
import sys

UNIV_SEQ, UNIV_SET = 0x10, 0x11


def parse_tlv(buf, i):
    """Bir TLV oku (belirli VEYA belirsiz uzunluk).

    Donen: (tag_class, tag_num, constructed, content_start, content_end, next_i)
    Belirsiz uzunlukta (0x80) icerik, ic ice TLV'leri atlayarak EOC (00 00)
    isaretine kadar taranir - X.690 8.1.3.6.
    """
    first = buf[i]
    tag_class = first >> 6              # 0=UNIVERSAL 1=APPLICATION 2=CONTEXT 3=PRIVATE
    constructed = bool(first & 0x20)
    tag_num = first & 0x1F
    i += 1
    if tag_num == 0x1F:                 # cok baytli tag (high-tag-number form)
        tag_num = 0
        while True:
            b = buf[i]; i += 1
            tag_num = (tag_num << 7) | (b & 0x7F)
            if not b & 0x80:
                break

    ln = buf[i]; i += 1
    if ln == 0x80:
        content_start = i
        i = skip_to_eoc(buf, i)
        content_end = i
        i += 2                          # EOC (00 00) tuket
    elif ln & 0x80:
        n = ln & 0x7F
        length = int.from_bytes(buf[i:i + n], "big"); i += n
        content_start = i
        content_end = i + length
        i = content_end
    else:
        content_start = i
        content_end = i + ln
        i = content_end

    return tag_class, tag_num, constructed, content_start, content_end, i


def skip_to_eoc(buf, i):
    """Belirsiz-uzunluklu bir TLV'nin icerigini, ic ice TLV'leri (kendi
    belirsiz uzunluklari dahil) atlayarak EOC (00 00) konumuna kadar tara."""
    while not (buf[i] == 0x00 and buf[i + 1] == 0x00):
        _, _, _, _, _, i = parse_tlv(buf, i)
    return i


def children(buf, start, end):
    i = start
    while i < end:
        tc, tn, con, cs, ce, nxt = parse_tlv(buf, i)
        yield tc, tn, con, cs, ce
        i = nxt


# [n] -> beklenen cocuk sekli. Bu kural seti YALNIZCA MMTelRecord SET'inin
# DOGRUDAN alanlarina uygulanir (bkz. main): recordExtensions[25] gibi ic ice
# uzantilarda ayni tag numaralari baska anlamlarda tekrar kullanilabildigi
# icin derine inip kontrol etmiyoruz.
#   "choice"   : cocuklar CONTEXT tag'li olmali (InvolvedParty alternatifi)
#   "elem-seq" : her cocuk UNIVERSAL SEQUENCE (SEQUENCE OF <SEQUENCE>)
#   "elem-set" : her cocuk UNIVERSAL SET       (SEQUENCE OF <SET>)
#   "inline"   : cocuklar dogrudan CONTEXT tag'li alanlar (SET/SEQUENCE gomulu)
RULES = {
    6:   ("list-Of-Calling-Party-Address", "choice"),
    102: ("list-Of-Called-Asserted-Identity", "choice"),
    14:  ("interOperatorIdentifiers", "elem-seq"),
    21:  ("list-Of-SDP-Media-Components", "elem-seq"),
    32:  ("list-Of-Early-SDP-Media-Components", "elem-seq"),
    31:  ("list-of-subscription-ID", "elem-set"),
    25:  ("recordExtensions", "inline"),
    110: ("mMTelInformation", "inline"),
}


def check(buf, tag_num, cs, ce, record_no, problems):
    name, shape = RULES[tag_num]
    kids = list(children(buf, cs, ce))
    if not kids:
        return
    for tc, tn, con, _, _ in kids:
        if shape == "choice":
            ok, want = tc == 2, "CONTEXT [0]/[1] (InvolvedParty)"
        elif shape == "elem-seq":
            ok, want = (tc == 0 and tn == UNIV_SEQ), "UNIVERSAL SEQUENCE"
        elif shape == "elem-set":
            ok, want = (tc == 0 and tn == UNIV_SET), "UNIVERSAL SET"
        else:  # inline
            ok, want = tc == 2, "CONTEXT"
        if not ok:
            extra = "  <-- FAZLADAN EXPLICIT SARMAL" if tc == 0 and tn in (UNIV_SEQ, UNIV_SET) else ""
            problems.append(
                f"kayit #{record_no}: [{tag_num}] {name}: cocuk class={tc} tag={tn}, "
                f"beklenen {want}{extra}"
            )
            return


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    buf = open(sys.argv[1], "rb").read()

    problems = []
    i, record_no = 0, 0
    while i < len(buf):
        tc, tn, con, cs, ce, nxt = parse_tlv(buf, i)
        record_no += 1
        # MMTelServiceRecord ::= CHOICE { mMTelRecord [83], sCIRecord [999] }
        if con and tc == 2 and tn in (83, 999):
            for ctc, ctn, ccon, ccs, cce in children(buf, cs, ce):
                if ctc == 2 and ctn in RULES:
                    check(buf, ctn, ccs, cce, record_no, problems)
        i = nxt

    print(f"{record_no} kayit tarandi.")
    if problems:
        print(f"{len(problems)} ihlal:")
        for p in dict.fromkeys(problems):
            print("  " + p)
        return 1
    print("Tag katmani temiz - EMM'in kabul ettigi sekle uyuyor.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
