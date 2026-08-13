#!/usr/bin/env python3
"""CHFChargingDataTypes16'da NetworkFunctionInformation alanlarini bisect eder.

11. tur iki seyi kesinlestirdi:
  * "Invalid length N" bir uzunluk degil, dusen dugumun BITIS OFFSETI
  * dusen dugumlerin ikisi de NetworkFunctionInformation, tam alan setiyle
    ([3] nFunctionConsumerInformation ve ic ice
     servingNetworkFunctionID.[0].servingNetworkFunctionInformation)

Kayitta bu tip birden fazla yerde geciyor, bu yuzden tek bir tanesini
bosaltmak yetmiyor. Bu betik TUM orneklerini ayni alan altkumesine indirger,
boylece hangi alanin sorun oldugu turlar arasinda daraltilabilir.

NFI ornekleri SEKILDEN taninir - cocuk tag'leri {0,1,2,3,4,5} altkumesi,
[0] primitive 1 bayt, [2]/[4]/[5] constructed. Sema bilgisi gerekmez.

Kullanim: chfNfiBisect.py girdi.ber cikti.ber 0,1,3 [flatten5]
          (virgullu liste = KORUNACAK alan tag'leri)

flatten5 : [5] networkFunctionFQDN icindeki IC sarmalayiciyi kaldirir.
    NodeAddress ::= CHOICE { iPAddress [0] IPAddress, domainName [1] IA5String }
    Alternatif [0] uzerinde YAZILI keyword yok; bizim kod "CHOICE uzerindeki
    tag her zaman EXPLICIT" (X.680 8.3) diyerek A0 ekliyor. 11. tur ise
    anahtar kelimesiz modulde varsayilanin IMPLICIT oldugunu, yalnizca YAZILI
    keyword'un kazandigini gosterdi. Bu bayrak o okumayi uretir:
        once : A5 08 { A0 06 { 80 04 xxxx } }
        sonra: A5 06 { 80 04 xxxx }
"""
import sys

def parse(buf, i):
    t = buf[i]; cls = t >> 6; con = bool(t & 0x20); n = t & 0x1F; i += 1
    if n == 0x1F:
        n = 0
        while True:
            b = buf[i]; i += 1; n = (n << 7) | (b & 0x7F)
            if not b & 0x80: break
    l = buf[i]; i += 1
    if l & 0x80:
        k = l & 0x7F; l = int.from_bytes(buf[i:i+k], 'big') if k else 0; i += k
    return cls, n, con, i, i + l

def tagbytes(buf, i):
    j = i; first = buf[j]; j += 1
    if (first & 0x1F) == 0x1F:
        while buf[j] & 0x80: j += 1
        j += 1
    return buf[i:j]

def enclen(n):
    if n < 0x80: return bytes([n])
    body = []
    while n > 0: body.insert(0, n & 0xFF); n >>= 8
    return bytes([0x80 | len(body)]) + bytes(body)

def kids(buf, s, e):
    out = []; i = s
    while i < e:
        cls, n, con, cs, ce = parse(buf, i); out.append((cls, n, con, i, cs, ce)); i = ce
    return out

def is_nfi(buf, cs, ce):
    """NetworkFunctionInformation sekli mi - sema bilgisi kullanmadan.

    Uretici her OPTIONAL alani doldurdugu icin gercek bir NFI ornegi TAM
    [0..5] setini tasir; alt kume kabul etmek AllocationRetentionPriority
    gibi baska SEQUENCE'lari da yakaliyordu."""
    ch = kids(buf, cs, ce)
    tags = [n for _, n, _, _, _, _ in ch]
    if tags != [0, 1, 2, 3, 4, 5]: return False
    by = {n: (con, ce2 - cs2) for _, n, con, _, cs2, ce2 in ch}
    if by[0] != (False, 1): return False          # networkFunctionality
    if by[1][0] or not 1 <= by[1][1] <= 36: return False   # IA5String SIZE(1..36)
    if by[3] != (False, 3): return False          # PLMN-Id SIZE(3)
    return by[2][0] and by[4][0] and by[5][0]     # uc EXPLICIT alan constructed

def rebuild(buf, s, e, keep, hits, flat5=False):
    out = bytearray(); i = s
    while i < e:
        cls, n, con, cs, ce = parse(buf, i)
        tb = tagbytes(buf, i)
        if con and is_nfi(buf, cs, ce):
            inner = bytearray()
            for _, kn, kcon, ki, kcs, kce in kids(buf, cs, ce):
                if kn not in keep:
                    continue
                if kn == 5 and flat5:
                    sub = kids(buf, kcs, kce)
                    if len(sub) == 1 and sub[0][2]:          # tek, constructed alternatif
                        body = buf[sub[0][4]:sub[0][5]]      # onun icerigi
                        inner += buf[ki:kcs - (kcs - ki)] [:0] + bytes([0xA5]) + enclen(len(body)) + body
                        continue
                inner += buf[ki:kce]
            hits.append(n)
            out += tb + enclen(len(inner)) + bytes(inner)
        elif con:
            inner = rebuild(buf, cs, ce, keep, hits, flat5)
            out += tb + enclen(len(inner)) + inner
        else:
            out += buf[i:ce]
        i = ce
    return bytes(out)

src, dst, keep = sys.argv[1], sys.argv[2], {int(x) for x in sys.argv[3].split(',')}
flat5 = len(sys.argv) > 4 and sys.argv[4] == 'flatten5'
buf = open(src, 'rb').read(); hits = []
out = rebuild(buf, 0, len(buf), keep, hits, flat5)
open(dst, 'wb').write(out)
print(f"  {dst}: {len(buf)} -> {len(out)} bayt, indirgenen NFI ornegi: {len(hits)} (tag'ler {hits}), korunan alanlar {sorted(keep)}")
