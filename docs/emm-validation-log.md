# EMM Doğrulama Günlüğü

Bu dosya, üretilen BER/ASCII dosyalarının Ericsson Mediation Manager (EMM)
tarafından gerçekten kabul edilip edilmediğinin kaydıdır. Dosyalar sorumluya
(Yasin Uz) gönderilir, o EMM'de çalıştırır ve sonucu yazar.

**Neden bu günlük var:** Bu projede "doğru" olmanın tek ölçütü EMM'in kabulüdür.
`self-check`'in temiz çıkması bunu göstermez — üretilen baytları *onları üreten
alan ağacına* karşı denetler, yani karşılaştırmanın iki tarafı da aynı şema
yorumundan gelir. Yorum yanlışsa iki taraf da aynı şekilde yanlış olur ve
"0 hata" raporlanır. LTE-R10 ve GGSN ilk gönderimde tam olarak böyle davrandı.

---

## 1. EMM'den geçen yapılar

| Yapı | Format | Doğrulanan davranış |
|---|---|---|
| `MMTelChargingDataTypes` | BER | `IMPLICIT TAGS` modülünde tam kayıt |
| `GGSNTurkcellCdrR7` | BER | `[6]` SEQUENCE OF CHOICE sarmalayıcısız; `[9]` skaler CHOICE'ta EXPLICIT korunur |
| `LTE-R10` (pGWRecord) | BER | `[11]` primitive BOOLEAN = `8B 01 00`; `pGWRecord [79]` doğru CHOICE alternatifi |
| `Multicloud` | ASCII `.dat` | `\|` alan + `\n` kayıt ayracı; inline şema (JSON'da kayıtlı olmayan) çalışıyor |
| `IMSCDRS` (TokensCSCF) | BER | Anahtar kelimesiz modülde **alan** tag'i IMPLICIT — 34/34 alan EMM çıktısıyla birebir |

## 2. EMM ile kanıtlanan tagging kuralları

1. `[n] EXPLICIT SEQUENCE OF <CHOICE>` → iç universal SEQUENCE **yok**
2. `[n] EXPLICIT <SET>` (skaler) → sarmalayıcı **yok**
3. `[n] EXPLICIT <BOOLEAN>` → **IMPLICIT** (`AB 03 01 01 FF` reddedildi, `8B 01 FF` istendi)
4. Başlığı `IMPLICIT TAGS` demeyen modülde **alan** tag'i → **IMPLICIT**

Hepsini tek cümle açıklıyor: **CHOICE dışında her şey IMPLICIT.** CHOICE'ta
X.680 8.3 explicit'i zorunlu kılar, orada iki okuma zaten aynı baytı üretir.

## 3. Tur tur kronoloji

| Tur | Gönderilen | EMM sonucu |
|---|---|---|
| 1 | MMTel, LTE-R10, GGSN, IMSCDRS, Poc | MMTel **PASS**; LTE `servingNodeAddress.[0] not set`; GGSN `sgsnAddress.[0] not set`; IMSCDRS `TokensCSCF not set`; Poc akış yok |
| 2 | LTE-R10, GGSN, IMSCDRS-TokensCSCF | GGSN **PASS**; LTE `Boolean can only have a maximum length of 1 bytes`; IMSCDRS `Invalid length 590` |
| 3 | LTE-R10, IMSCDRS, GGSN | GGSN **PASS**; LTE `Illegal conversion ... pGWRecord.servingNodeType` (decode geçti, DUP script hatası); IMSCDRS `not set` |
| 4 | LTE-R10-pGWRecord, Multicloud.dat, IMSCDRS-TokensCSCF.dat | LTE **PASS**; Multicloud **PASS**; IMSCDRS `not set` (`.dat` BER olarak okundu → ASCII hipotezi elendi) |
| 5 | IMSCDRS-TokensCSCF-minimal, CSCF-MergedCSCF | İkisi de `Invalid length 114` / `411`. CSCF dosyası da `IMSCDRS.TokensCSCF` olarak çözüldü → akış sabit |
| 6 | IMSCDRS-TokensCSCF-implicit | **PASS** + 34 alanlık ASCII decode döndü |
| 7 | FDRInput, Audit_Record_Collection_St, IMSChargingDataTypes | **YANIT BEKLENİYOR** |

### IMSCDRS'in çözülmesi — altı turluk eleme

| Gönderilen | Sonuç | Elenen |
|---|---|---|
| `A0 { 30 … }` tokenMTAS | `not set` | yanlış CHOICE alternatifi |
| `30 …` çıplak (590 B) | `Invalid length 590` | dış tag doğruymuş |
| `A1 { 30 … }` tokenCSCF | `not set` | üst tip `Cdrs` değil |
| `.dat` ASCII | `not set` | format ASCII değil, BER |
| `30 …` (114 B, kısa-form) | `Invalid length 114` | boyut ve uzunluk biçimi değil |
| `30 …` alanlar IMPLICIT | **PASS** | ← alan kodlamasıymış |

Bildirilen "Invalid length N" her seferinde **tam dosya boyutuydu** (590/114/411).
EMM'in şeması bizimkiyle alan alan aynı çıktı — fark yorumdaydı, bildirimde değil.

## 4. Açık sorular

### 🟡 Tip-seviyesi `[APPLICATION n]` tagging — 7. turda gönderildi

- **Problem:** `FDRInput` (`NrFile ::= [APPLICATION 1] SEQUENCE`) ve
  `Audit_Record_Collection_St` (`LogEntry ::= [APPLICATION 21] SEQUENCE`)
- **Kritik ölçüm:** EMM'den geçen 4 dosyanın **hiçbirinde APPLICATION sınıfı tag
  yok** (hepsi 0; tümü CONTEXT kullanıyor). Bu sınıf hiç sınanmadı.
- **Mevcut davranış:** X.680 31.2.7 gereği sarmalayıcı — `61 37 30 35 …`
- **Alternatif:** `61 35 …` (universal SEQUENCE kalkar, 2 bayt kısa)
- **Reddedilirse:** `AsnFieldTreeResolver.readLeadingTag` içinde tek satır değişir

### 🟡 Primitive ENUMERATED — 7. turda gönderildi

- `IMSChargingDataTypes` → `subscriberRole [1] EXPLICIT SubscriberRole` = `81 01 00`
- MMTel ailesinden (aynı parmak izi, aynı başlık) olduğu için düşük riskli
- BOOLEAN'da kanıtlandı, ENUMERATED'da hiç sınanmadı

### 🟡 Primitive OCTET STRING — AYIRT EDİLEMEDİ

- GGSN'de **her iki kodlama da kabul edildi**: `A1 13 04 11 …` ve `81 11 …`
- Sarmalayıcılı hâlde EMM değeri `04 11 …` diye okuyorsa ilk iki bayt bizim TLV
  başlığımızdır ve `SIZE(4..255)` içinde kaldığı için şikâyet gelmez
- **Yasin'den istenecek:** kabul edilen GGSN dosyasının ASCII decode çıktısında
  `qosRequested` hangi değere çözülüyor?

### 🔵 Multicloud kayıtlı değil

`datastructure.json`'da yok (22.04.2026 tarihli, snapshot'tan yeni), inline modda
üretiliyor. Kalıcı eklenmesi ayrı bir karar.

## 5. İlgili commit'ler

| Commit | Ne yaptı |
|---|---|
| `1f71a2a` | 3GPP paket-alanı ailesinde EXPLICIT nötrleştirme (LTE/GGSN reddi üzerine) |
| `ef0bf67` | `rootType` — kaydın hangi tip olarak kodlanacağını çağıran seçer |
| `2832d40` | Skaler primitive'lerde EXPLICIT korundu — **`b0a26b9` ile geri alındı** |
| `594faad` | `[UNIVERSAL n]` tag'i sarmalayamaz (X.690 8.19.1, constructed OID) |
| `b0a26b9` | Primitive'lerde de EXPLICIT nötrlendi — LTE BOOLEAN reddi kanıtı |
| `5906e76` | `UNSPECIFIED` tagging modu: alan tag'i IMPLICIT, tip tag'i X.680 EXPLICIT |
| `48ee122` | `/verify-ber` `rootType` desteği |

## 6. Önemli uyarı

`5906e76` ile **682 modül / ~19.500 alan**, tek bir EMM kanıtına (IMSCDRS)
dayanarak değişti. Regresyon testleri temiz (364 test, 808 modül round-trip,
MMTel referans-yakalama uygunluğu) ama bu, 682 modülün hepsinin EMM'den geçeceği
anlamına **gelmez** — aynı yapısal sınıfta oldukları için kapsandılar.

"EMM geçti" ile "üretim kodunda regresyon yok" ayrı şeylerdir; bu günlükte
birincisi tutulur.
