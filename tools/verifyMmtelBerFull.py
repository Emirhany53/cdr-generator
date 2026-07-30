#!/usr/bin/env python3
"""
Semadan turetilen, KAPSAMLI MMTel BER tag-katmani dogrulayici.

verifyMmtelBer.py sadece 8 alani (daha once EMM hatasina karisan ya da
supheli bulunanlari) elle kodlanmis kurallarla kontrol ediyordu. Bu script
onun yerine datastructure.json'daki MMTelChargingDataTypes semasini
AsnTypeRegistryBuilder + AsnFieldTreeResolver (iki EXPLICIT-notralizasyon
fix'i dahil) mantigiyla COZUP, olusan agactaki HER constructed alan icin
beklenen tag seklini otomatik hesaplar ve gercek .ber dosyasindaki byte'larla
karsilastirir - sadece daha once bilinen 8 alani degil, ManagementExtensions
ve MMTelInformation'in yuzlerce ic alanini da kapsayacak sekilde.

Kullanim:
    python3 verifyMmtelBerFull.py /yol/dosya.ber [/yol/datastructure.json]
"""
import sys, json, importlib.util

# mmtel_resolver_port.py'yi yukle (bu script ile ayni klasorde bulunmali)
_spec = importlib.util.spec_from_file_location(
    "mmtel_resolver_port", __file__.rsplit('/', 1)[0] + "/mmtel_resolver_port.py")
_rp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_rp)

UNIV_SEQ, UNIV_SET = 0x10, 0x11
CONTEXT = 2
UNIVERSAL = 0


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
    if ln == 0x80:
        content_start = i
        i = skip_to_eoc(buf, i)
        content_end = i
        i += 2
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
    while not (buf[i] == 0x00 and buf[i + 1] == 0x00):
        _, _, _, _, _, i = parse_tlv(buf, i)
    return i


def children(buf, start, end):
    i = start
    while i < end:
        tc, tn, con, cs, ce, nxt = parse_tlv(buf, i)
        yield tc, tn, con, cs, ce
        i = nxt


def check_shape(buf, path, cs, ce, shape, problems, record_no, hit_paths=None):
    def fail(msg):
        problems.append(f"kayit #{record_no} yol={path}: {msg} (shape={shape})")

    # NULL, children() cagrilmadan ONCE ele alinmali: bozuk bir NULL'un icerik
    # baytlari TLV gibi cozulmeye calisilirsa parser sasirir. Ayrica dogru NULL
    # bos oldugu icin asagidaki "cocuk yoksa cik" korumasina takilir ve hic
    # kontrol edilmemis olurdu.
    if shape == 'null_empty':
        if hit_paths is not None:
            hit_paths.add(path)
        if ce != cs:
            fail(f"NULL alani {ce - cs} bayt icerik tasiyor - X.690 8.8'e gore "
                 f"uzunluk 0 OLMALI (icerik={buf[cs:ce].hex(' ')})")
        return

    kids = list(children(buf, cs, ce))
    if not kids:
        return  # OPTIONAL alan bu kayitta yok - kontrol edilecek bir sey yok
    if hit_paths is not None:
        hit_paths.add(path)  # bu alan bu kayitta doluydu ve fiilen kontrol edildi

    if shape in ('scalar_choice_direct',):
        for tc, tn, con, _, _ in kids:
            if tc != CONTEXT:
                fail(f"CHOICE alani dogrudan CONTEXT beklenirken class={tc} tag={tn} goruldu - FAZLADAN SARMAL SUPHESI")
                return

    elif shape.startswith('repeated_choice_direct'):
        for tc, tn, con, _, _ in kids:
            if tc != CONTEXT:
                fail(f"repeated CHOICE elemani dogrudan CONTEXT beklenirken class={tc} tag={tn} goruldu - FAZLADAN SARMAL SUPHESI")
                return

    elif shape in ('scalar_set_implicit', 'scalar_seq_implicit'):
        for tc, tn, con, _, _ in kids:
            if tc == UNIVERSAL and tn in (UNIV_SEQ, UNIV_SET):
                fail(f"IMPLICIT beklenirken UNIVERSAL {('SET' if tn==UNIV_SET else 'SEQUENCE')} sarmal goruldu - FAZLADAN EXPLICIT SARMAL")
                return

    elif shape in ('scalar_set_explicit', 'scalar_seq_explicit'):
        want_tag = UNIV_SET if shape == 'scalar_set_explicit' else UNIV_SEQ
        if len(kids) != 1 or kids[0][0] != UNIVERSAL or kids[0][1] != want_tag:
            fail(f"EXPLICIT beklenirken tek cocuk UNIVERSAL {('SET' if want_tag==UNIV_SET else 'SEQUENCE')} degil")
            return

    elif shape.startswith('repeated_elem_primitive_'):
        # SEQUENCE OF <primitive>: her eleman kendi UNIVERSAL tag'ini tasiyan
        # bir yaprak TLV olmali. Semada [UNIVERSAL n] ile yeniden etiketlenmis
        # tipler (GraphicStringImp ::= [UNIVERSAL 25] IMPLICIT IA5String) burada
        # 25 bekler; 22 (duz IA5String) gorulmesi override'in kaybedildigini
        # gosterir - EMM'in kabul ettigi referans dosyalar 25 tasiyor.
        want = int(shape.rsplit('_', 1)[1])
        for tc, tn, con, _, _ in kids:
            if tc != UNIVERSAL or tn != want:
                fail(f"eleman UNIVERSAL tag={want} beklenirken class={tc} tag={tn} goruldu")
                return

    elif shape.startswith('repeated_elem_'):
        want_tag = UNIV_SET if 'set' in shape else UNIV_SEQ
        is_explicit = shape.endswith('_explicit')
        if is_explicit:
            # tek cocuk: outer UNIVERSAL SEQUENCE(16) sarmal, icinde elemanlar
            if len(kids) != 1 or kids[0][0] != UNIVERSAL or kids[0][1] != UNIV_SEQ:
                fail("EXPLICIT (repeated) beklenirken tek disaridaki UNIVERSAL SEQUENCE sarmali yok")
                return
            _, _, _, ics, ice = kids[0]
            for tc, tn, con, _, _ in children(buf, ics, ice):
                if tc != UNIVERSAL or tn != want_tag:
                    fail(f"sarmal icindeki eleman UNIVERSAL {('SET' if want_tag==UNIV_SET else 'SEQUENCE')} degil")
                    return
        else:
            for tc, tn, con, ecs, ece in kids:
                if tc != UNIVERSAL or tn != want_tag:
                    fail(f"eleman UNIVERSAL {('SET' if want_tag==UNIV_SET else 'SEQUENCE')} degil (class={tc} tag={tn})")
                    return
                # bir seviye derinlik: ayni universal tag tekrar gorulursek FAZLADAN sarmal var demektir
                grandkids = list(children(buf, ecs, ece))
                if grandkids and grandkids[0][0] == UNIVERSAL and grandkids[0][1] == want_tag:
                    fail("elemanin icinde AYNI universal tag tekrar goruluyor - FAZLADAN EXPLICIT SARMAL SUPHESI")
                    return


def real_content_bounds(buf, cs, ce, shape):
    """Recursion'un devam edebilmesi icin 'gercek' icerigin sinirlarini dondurur
    (varsa sarmal katmanini atlayarak)."""
    kids = list(children(buf, cs, ce))
    if not kids:
        return cs, ce
    if shape in ('scalar_set_explicit', 'scalar_seq_explicit') and len(kids) == 1 and kids[0][0] == UNIVERSAL:
        return kids[0][3], kids[0][4]
    return cs, ce


def walk(buf, cs, ce, path, expected, problems, record_no, depth=0, hit_paths=None):
    if depth > 12:
        return
    for tc, tn, con, ics, ice in children(buf, cs, ce):
        if tc == CONTEXT:
            new_path = path + (tn,)
            if new_path in expected:
                shape = expected[new_path]
                check_shape(buf, new_path, ics, ice, shape, problems, record_no, hit_paths)
                # Yaprak alanlara (NULL gibi) inilmez: icerikleri TLV degildir,
                # cozmeye calismak parser'i sasirtir.
                if con:
                    rcs, rce = real_content_bounds(buf, ics, ice, shape)
                    walk(buf, rcs, rce, new_path, expected, problems, record_no, depth + 1, hit_paths)
            elif con:
                walk(buf, ics, ice, new_path, expected, problems, record_no, depth + 1, hit_paths)
        elif con:
            walk(buf, ics, ice, path, expected, problems, record_no, depth + 1, hit_paths)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    ber_path = sys.argv[1]
    default_ds_path = __file__.rsplit('/', 1)[0] + "/../src/main/resources/datastructure.json"
    ds_path = sys.argv[2] if len(sys.argv) > 2 else default_ds_path

    data = json.load(open(ds_path, encoding='utf-8'))
    contents = None
    for d in data:
        if d['name'] == 'MMTelChargingDataTypes':
            contents = d['contents']
            break
    if contents is None:
        print("MMTelChargingDataTypes modulu bulunamadi")
        return 2

    expected, root_kind, root_fields = _rp.build_expected_shapes(contents, 'MMTelServiceRecord', max_depth=8)
    print(f"Semadan {len(expected)} constructed alan icin beklenen sekil hesaplandi (root_kind={root_kind}).")

    buf = open(ber_path, "rb").read()
    problems = []
    hit_paths = set()
    i, record_no = 0, 0
    while i < len(buf):
        tc, tn, con, cs, ce, nxt = parse_tlv(buf, i)
        record_no += 1
        if con and tc == CONTEXT and tn in (83, 999):
            # mMTelRecord/sCIRecord [83]/[999]'un KENDISI de expected'ta bir yol
            # tasir ((83,) -> scalar_set_implicit): build_expected_shapes bu dis
            # sarmali da hesaba katarak yol uretiyor, o yuzden walk'a bos path
            # degil (tn,) verilmeli - yoksa TUM alt yollar bir seviye kayar ve
            # hicbiri eslesmez (0/54 kapsam gibi yanlis bir sonuc dogurur).
            record_path = (tn,)
            rcs, rce = cs, ce
            if record_path in expected:
                shape = expected[record_path]
                check_shape(buf, record_path, cs, ce, shape, problems, record_no, hit_paths)
                rcs, rce = real_content_bounds(buf, cs, ce, shape)
            walk(buf, rcs, rce, record_path, expected, problems, record_no, 0, hit_paths)
        i = nxt

    print(f"{record_no} kayit tarandi.")
    print(f"Kapsam: {len(hit_paths)}/{len(expected)} constructed alan bu dosyada dolu cikip fiilen kontrol edildi "
          f"(kalani OPTIONAL ve hicbir kayitta doldurulmamis - kontrol edilemedi).")
    missing = sorted(set(expected) - hit_paths)
    if missing:
        print("  Kontrol EDILEMEYEN yollar (bos/populate edilmemis):")
        for p in missing:
            print(f"    {p} beklenen={expected[p]}")
    if problems:
        print(f"{len(problems)} ihlal:")
        for p in dict.fromkeys(problems):
            print("  " + p)
        return 1
    print("Kapsamli kontrol temiz - fiilen kontrol edilen HICBIR constructed alanda sarmal uyusmazligi yok.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
