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
  C. Ayni govdede TEKRARLANAN TAG        -> tel uzerinde duplicate tag; EMM'in
                                            reddettigi hata sinifi
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


def walk_bodies(fields, path, out, depth=0, seen=None):
    """Her 'govde' (bir SEQUENCE/SET/CHOICE'un dogrudan alan listesi) icin
    (yol, alanlar) ciftini toplar."""
    if depth > MAX_DEPTH or not fields:
        return
    out.append((path, fields))
    for f in fields:
        if f.children:
            key = (id(f.children), depth)
            if seen is None:
                seen = set()
            if key in seen:
                continue
            seen.add(key)
            walk_bodies(f.children, path + (f.field_name,), out, depth + 1, seen)


def scan_module(module):
    name = module.get('name')
    contents = module.get('contents') or ''
    result = {'name': name, 'error': None, 'root': None, 'root_kind': None,
              'dup_names': [], 'dup_tags': [], 'repeated_choice': 0, 'field_count': 0}
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
        walk_bodies(fields, (root,), bodies)
        for path, body in bodies:
            names = [f.field_name for f in body if f.field_name]
            for n, c in Counter(names).items():
                if c > 1:
                    tags = sorted({f.tag_number for f in body if f.field_name == n})
                    result['dup_names'].append(('.'.join(path), n, c, tags))
            tags = [f.tag_number for f in body if f.tag_number is not None]
            for t, c in Counter(tags).items():
                if c > 1:
                    result['dup_tags'].append(('.'.join(path), t, c))
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
    duptag = [r for r in results if r['dup_tags']]
    repch = [r for r in results if r['repeated_choice']]
    clean = [r for r in results
             if not r['error'] and not r['dup_names'] and not r['dup_tags']]

    print(f"Toplam modul: {len(results)}")
    print(f"  Sorunsuz cozulen (A/B/C temiz)          : {len(clean)}")
    print(f"  A. Cozulemeyen / kok sorunlu            : {len(errors)}")
    print(f"  B. Tekrarlanan ALAN ADI iceren          : {len(dupname)}")
    print(f"  C. Tekrarlanan TAG iceren               : {len(duptag)}")
    print(f"  D. repeated CHOICE iceren (tek elemana sabit): {len(repch)}")
    print()

    if errors:
        print(f"--- A. Kok/cozum sorunu ({len(errors)} modul) ---")
        for r in errors[:25]:
            print(f"  {r['name']:38.38} kok={str(r['root'])[:22]:22} -> {r['error']}")
        if len(errors) > 25:
            print(f"  ... (+{len(errors)-25})")
        print()

    if duptag:
        print(f"--- C. Tekrarlanan TAG - tel uzerinde duplicate riski ({len(duptag)} modul) ---")
        for r in duptag[:25]:
            for p, t, c in r['dup_tags'][:2]:
                print(f"  {r['name']:38.38} {p[:40]:40} tag[{t}] x{c}")
        if len(duptag) > 25:
            print(f"  ... (+{len(duptag)-25})")
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
            flag = 'HATA' if r['error'] else ('DUP' if (r['dup_names'] or r['dup_tags']) else 'ok')
            print(f"  [{flag:4}] {r['name']:40.40} kok={str(r['root'])[:26]:26} "
                  f"kind={str(r['root_kind']):9} alan={r['field_count']}")

    return 1 if (errors or duptag) else 0


if __name__ == '__main__':
    sys.exit(main())
