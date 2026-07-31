#!/usr/bin/env python3
"""
808 modulun TAMAMINI cozup, uretilecek BER'i bozacagi BILINEN hata
siniflarina gore tarar.

Neden statik tarama: gercek uretim Spring uygulamasini calistirmayi gerektirir.
Bu script onun yerine mmtel_resolver_port.py (AsnTypeRegistryBuilder +
AsnFieldTreeResolver'in sadik portu) ile her modulun alan agacini kurar ve
agacin kendisinde tespit edilebilen kusurlari raporlar.

SINIRI ACIKCA: bu tarama SEMA kaynakli kusurlari ve cozucunun urettigi agac
bozukluklarini yakalar. Java tarafina ozgu bir kodlama hatasini (encoder'da
byte seviyesinde bir yanlislik) yakalayamaz - onun icin gercek .ber uretip
verifyGenericBer.py ile bakmak gerekir. Amac, hangi modullerin gercekten
denenmesi gerektigini oncelilendirmek.

Taranan hata siniflari:
  A. Kok bulunamiyor / kok 0 alanli      -> uretim bos veya hatali cikar
  B. Ayni govdede TEKRARLANAN ALAN ADI   -> CdrRecordBuilder alanlari isme gore
     (farkli tag'lerle)                     LinkedHashMap'te tuttugu icin ikinci
                                            alan birincinin degerini eziyor
  C1. SET govdesinde tekrarlanan TAG     -> KRITIK. SET bilesenleri sirasizdir,
                                            ayni tag cozulemez. EMM reddeder.
  C2. SEQUENCE govdesinde tekrarlanan TAG-> SEQUENCE siralidir ve resolver
                                            SEQUENCE'i sortSetComponents ile
                                            SIRALAMAZ (yalnizca SET siralanir),
                                            ayrica uretici hicbir OPTIONAL alani
                                            atlamaz. Bu yuzden kayit pozisyonel
                                            olarak tam kalir ve ayirt edilebilir.
                                            Sema X.680'e gore kusurlu ama uretilen
                                            dosya cozulebilir olmali.
  D. repeated CHOICE alani               -> tek elemana sabitlendi (bilgi amacli)

Kullanim:
    python3 scanAllModules.py [datastructure.json] [--verbose]
"""
import sys, json, importlib.util
from collections import Counter

_here = __file__.rsplit('/', 1)[0]
_spec = importlib.util.spec_from_file_location("mmtel_resolver_port", _here + "/mmtel_resolver_port.py")
rp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(rp)

MAX_DEPTH = 6


def walk_bodies(fields, path, out, depth=0, seen=None, kind='SEQUENCE'):
    """Her 'govde' (bir SEQUENCE/SET/CHOICE'un dogrudan alan listesi) icin
    (yol, alanlar, kind) uclusunu toplar. kind onemli: ayni tag'in tekrari
    SET'te cozulemez, SEQUENCE'ta siraya bakilarak cozulebilir."""
    if depth > MAX_DEPTH or not fields:
        return
    out.append((path, fields, kind))
    for f in fields:
        if f.children:
            key = (id(f.children), depth)
            if seen is None:
                seen = set()
            if key in seen:
                continue
            seen.add(key)
            child_kind = 'CHOICE' if f.choice else ('SET' if f.set_ else 'SEQUENCE')
            walk_bodies(f.children, path + (f.field_name,), out, depth + 1, seen, child_kind)


def scan_module(module):
    name = module.get('name')
    contents = module.get('contents') or ''
    result = {'name': name, 'error': None, 'root': None, 'root_kind': None,
              'dup_names': [], 'dup_tags_set': [], 'dup_tags_seq': [],
              'repeated_choice': 0, 'field_count': 0}
    try:
        tagging = rp.detect_tagging_mode(contents)
        registry = rp.build_registry(contents)
        if not registry:
            result['error'] = 'registry bos (sema cozulemedi)'
            return result
        root = rp.select_root_type_name(registry, tagging)
        result['root'] = root
        if root is None:
            result['error'] = 'kok tip secilemedi'
            return result
        kind, fields = rp.resolve_root(registry, root, tagging)
        result['root_kind'] = kind
        result['field_count'] = len(fields)
        if not fields:
            result['error'] = 'kok 0 alanli'
            return result

        bodies = []
        walk_bodies(fields, (root,), bodies, kind=('CHOICE' if kind == 'CHOICE' else kind))
        for path, body, body_kind in bodies:
            names = [f.field_name for f in body if f.field_name]
            for n, c in Counter(names).items():
                if c > 1:
                    tags = sorted({f.tag_number for f in body if f.field_name == n})
                    result['dup_names'].append(('.'.join(path), n, c, tags))
            tags = [f.tag_number for f in body if f.tag_number is not None]
            for t, c in Counter(tags).items():
                if c > 1:
                    entry = ('.'.join(path), t, c, body_kind)
                    if body_kind == 'SET':
                        result['dup_tags_set'].append(entry)
                    else:
                        result['dup_tags_seq'].append(entry)
            for f in body:
                if f.repeated and f.choice:
                    result['repeated_choice'] += 1
    except RecursionError:
        result['error'] = 'cozumde sonsuz dongu (RecursionError)'
    except Exception as exc:
        result['error'] = f'{type(exc).__name__}: {exc}'
    return result


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    ds = args[0] if args else _here + '/../src/main/resources/datastructure.json'
    verbose = '--verbose' in sys.argv

    data = json.load(open(ds, encoding='utf-8'))
    results = [scan_module(m) for m in data]

    errors = [r for r in results if r['error']]
    dupname = [r for r in results if r['dup_names']]
    duptag_set = [r for r in results if r['dup_tags_set']]
    duptag_seq = [r for r in results if r['dup_tags_seq']]
    repch = [r for r in results if r['repeated_choice']]
    clean = [r for r in results if not r['error'] and not r['dup_names']
             and not r['dup_tags_set'] and not r['dup_tags_seq']]

    print(f"Toplam modul: {len(results)}")
    print(f"  Sorunsuz cozulen (A/B/C temiz)          : {len(clean)}")
    print(f"  A. Cozulemeyen / kok sorunlu            : {len(errors)}")
    print(f"  B. Tekrarlanan ALAN ADI iceren          : {len(dupname)}")
    print(f"  C1. Tekrarlanan TAG - SET govdesinde (KRITIK): {len(duptag_set)}")
    print(f"  C2. Tekrarlanan TAG - SEQUENCE govdesinde    : {len(duptag_seq)}")
    print(f"  D. repeated CHOICE iceren (tek elemana sabit): {len(repch)}")
    print()

    if errors:
        print(f"--- A. Kok/cozum sorunu ({len(errors)} modul) ---")
        for r in errors[:25]:
            print(f"  {r['name']:38.38} kok={str(r['root'])[:22]:22} -> {r['error']}")
        if len(errors) > 25:
            print(f"  ... (+{len(errors)-25})")
        print()

    if duptag_set:
        print(f"--- C1. KRITIK: SET govdesinde tekrarlanan tag ({len(duptag_set)} modul) ---")
        print("    SET bilesenleri SIRASIZDIR; ayni tag'in tekrari cozulemez, decoder")
        print("    hangi alanin hangisi oldugunu ayirt edemez. EMM bunu reddeder.")
        for r in duptag_set[:25]:
            for pp, t, c, k in r['dup_tags_set'][:2]:
                print(f"  {r['name']:38.38} {pp[:40]:40} tag[{t}] x{c}")
        print()

    if duptag_seq:
        print(f"--- C2. SEQUENCE govdesinde tekrarlanan tag ({len(duptag_seq)} modul) ---")
        print("    SEQUENCE SIRALIDIR. X.680 OPTIONAL varken farkli tag ister, yani sema")
        print("    teknik olarak kusurlu; ancak uretici HER alani daima yazdigi icin kayit")
        print("    pozisyonel olarak tam ve ayirt edilebilir kaliyor. Tag'leri kendimiz")
        print("    degistirmek semadan sapmak olur - once EMM ile ampirik dogrulama sart.")
        for r in duptag_seq[:25]:
            for pp, t, c, k in r['dup_tags_seq'][:2]:
                print(f"  {r['name']:38.38} {pp[:40]:40} tag[{t}] x{c}")
        print()

    if dupname:
        total = sum(len(r['dup_names']) for r in dupname)
        print(f"--- B. Tekrarlanan ALAN ADI ({len(dupname)} modul, {total} vaka) ---")
        print("    CdrRecordBuilder degerleri alan ADINA gore tuttugu icin ayni adli")
        print("    ikinci alan birincinin degerini aliyor (Bulgu 3 - henuz duzeltilmedi).")
        for r in dupname[:25]:
            for p, n, c, tags in r['dup_names'][:2]:
                print(f"  {r['name']:38.38} {p[:34]:34} '{n}' x{c} tag={tags}")
        if len(dupname) > 25:
            print(f"  ... (+{len(dupname)-25})")
        print()

    if verbose:
        print("--- Tum moduller ---")
        for r in results:
            flag = 'HATA' if r['error'] else ('DUP' if (r['dup_names'] or r['dup_tags_set'] or r['dup_tags_seq']) else 'ok')
            print(f"  [{flag:4}] {r['name']:40.40} kok={str(r['root'])[:26]:26} "
                  f"kind={str(r['root_kind']):9} alan={r['field_count']}")

    return 1 if (errors or duptag_set) else 0


if __name__ == '__main__':
    sys.exit(main())
