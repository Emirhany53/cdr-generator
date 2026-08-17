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
| `CGSN40ber` | BER | Paket-alanı ailesinde **aile genellemesi çalışıyor** — LTE/GGSN verdict'i başka bir modülde tutuyor |
| `CHAD` | BER | CCN/OCC soyunda ilk kanıt; yazılı `EXPLICIT` keyword'leri nötrleştirilmeden geçti |
| `TurkcellCDRCCNCS5` | BER | CCN/OCC soyu, ikinci örnek |
| `SMSCBerCdr` | BER | Düz yapı + 71 çok-baytlı tag |
| `CHFChargingDataTypes16` (kısmi) | BER | **Anahtar kelimesiz + zengin yapı ilk tam kabul** — 2141 bayt, 349 TLV, 6 seviye iç içe, CHOICE/SEQ OF/SET |
| `FDRInput` | BER | Tip-seviyesi `[APPLICATION 1]` **IMPLICIT** — `61 2D 42 12 …` kabul edildi |
| `Audit_Record_Collection_St` | BER | Tip-seviyesi `[APPLICATION 21]` **IMPLICIT** — `75 40 16 08 …` kabul edildi |
| `CGSN40ber` (qos ikilisi) | BER | **OCTET STRING semantiği çözüldü** — aşağıya bak |
| `ConvergenceCdr` | BER | `IMPLICIT TAGS`'te **çıplak SEQUENCE kökü** (14. tur) |
| `NRTRDEInValidationLookup` | BER | tip-seviyesi `[APPLICATION]` + SEQUENCE OF (14. tur) |
| `CME20R12TurkCellber` | BER | U modunda `[APPLICATION]` + SET birlikte, 74 çok-baytlı tag (14. tur) |
| `GPRS-Charging-Extensions-Tr` | BER | **kök SET** + CHOICE üzerinde yazılı `EXPLICIT` (14. tur) |
| `EMM-IMS-Specific` | BER | minimal kök SET (14. tur) |
| `CDRDatamartCCNDMM` | BER | 171 çok-baytlı tag, düz yapı (14. tur) |
| `FCMSMSC` | BER | düz SEQUENCE kökünde CHOICE + SET (14. tur) |

## 2. EMM ile kanıtlanan tagging kuralları

1. `[n] EXPLICIT SEQUENCE OF <CHOICE>` → iç universal SEQUENCE **yok**
2. `[n] EXPLICIT <SET>` (skaler) → sarmalayıcı **yok**
3. `[n] EXPLICIT <BOOLEAN>` → **IMPLICIT** (`AB 03 01 01 FF` reddedildi, `8B 01 FF` istendi)
4. Başlığı `IMPLICIT TAGS` demeyen modülde **alan** tag'i → **IMPLICIT**
5. Başlığı `IMPLICIT TAGS` demeyen modülde **tip** tag'i de → **IMPLICIT** (9. tur, commit `f5ce531`)
6. Başlığı `IMPLICIT TAGS` demeyen modülde, **yazılı keyword taşımayan** bir tag CHOICE tipli alandaysa → **IMPLICIT** (13. tur, commit `e940f0c`). X.680 8.3'ün istisnası.
7. **Yazılı `EXPLICIT` keyword'ü yalnızca CHOICE tipli alanda sarmalayıcı üretir.** SEQUENCE / SET / primitive hedeflerde bağlam tag'i universal tag'in yerine geçer — modülün soyundan bağımsız (14. tur, commit `2006841`). 2. ve 3. kural bunun iki özel hâliymiş.

Hepsini tek cümle açıklıyor: **CHOICE dışında her şey IMPLICIT — yazılı keyword
dahil.** CHOICE'ta X.680 8.3 explicit'i zorunlu kılar, orada iki okuma zaten
aynı baytı üretir.

## 3. Tur tur kronoloji

| Tur | Gönderilen | EMM sonucu |
|---|---|---|
| 1 | MMTel, LTE-R10, GGSN, IMSCDRS, Poc | MMTel **PASS**; LTE `servingNodeAddress.[0] not set`; GGSN `sgsnAddress.[0] not set`; IMSCDRS `TokensCSCF not set`; Poc akış yok |
| 2 | LTE-R10, GGSN, IMSCDRS-TokensCSCF | GGSN **PASS**; LTE `Boolean can only have a maximum length of 1 bytes`; IMSCDRS `Invalid length 590` |
| 3 | LTE-R10, IMSCDRS, GGSN | GGSN **PASS**; LTE `Illegal conversion ... pGWRecord.servingNodeType` (decode geçti, DUP script hatası); IMSCDRS `not set` |
| 4 | LTE-R10-pGWRecord, Multicloud.dat, IMSCDRS-TokensCSCF.dat | LTE **PASS**; Multicloud **PASS**; IMSCDRS `not set` (`.dat` BER olarak okundu → ASCII hipotezi elendi) |
| 5 | IMSCDRS-TokensCSCF-minimal, CSCF-MergedCSCF | İkisi de `Invalid length 114` / `411`. CSCF dosyası da `IMSCDRS.TokensCSCF` olarak çözüldü → akış sabit |
| 6 | IMSCDRS-TokensCSCF-implicit | **PASS** + 34 alanlık ASCII decode döndü |
| 7 | FDRInput, Audit_Record_Collection_St, IMSChargingDataTypes | 9. tur ile birlikte yanıtlandı |
| 8 | CGSN40ber, TurkcellCDRCCNCS5, CHAD, SMSCBerCdr | **4/4 PASS** |
| 9 | FDRInput, Audit_Record_Collection_St, IMSChargingDataTypes, GGSN-qos ikilisi, CHFChargingDataTypes16 | 4 red + 1 çift red — 2'si gerçek kodlama bulgusu, 3'ü yönlendirme |
| 10 | FDRInput, Audit (düzeltilmiş), CGSN40ber-qos ikilisi, CHF (rootType'lı) | **4 PASS** + qos ASCII decode döndü; CHF `Invalid length 102` |
| 11 | CHF: explicit-kept, explicit-collapsed, nfci-minimal | 3 red — ama üçü de bilgi verdi (aşağıda) |
| 12 | CHF NFI bisect: A/B/C | **3/3 PASS** — zengin anahtar-kelimesiz modül ilk kez tam çözüldü |
| 13 | CHF: D `[4]`, E `[5]`, F `[5]` düzleştirilmiş | **D ✓ · E ✗ · F ✓** — teşhis kesin, düzeltme doğrulandı |
| 14 | Davranış sınıfı korpusu — 14 dosya (13.08.2026) | **7 PASS · 6 red · 1 koşulamadı** (17.08.2026) |
| 15 | 14. turun 6 reddi + koşulamayan `IMS-R8-2009-03` (17.08.2026) | YANIT BEKLENİYOR |

### 7. turda gönderilen dosyalar

Sorumluya 12.08.2026'da gönderildi. Dosyalar `target/emm-round7/` altında üretildi
ama **`target/` gitignore'da** — bir `mvn clean` siler. Kimliklerini burada
tutuyoruz ki yanıt geldiğinde hangi baytların sınandığı belirsiz kalmasın:

| dosya | bayt | SHA-256 |
|---|---|---|
| `FDRInput.ber` | 57 | `c5bc5348373657548865fd8e4dfad7f3be795499d2eb2fda39204b2679e5522f` |
| `Audit_Record_Collection_St.ber` | 69 | `526e3621e1215bc87ea606739126ae8daa038fbfe0a57b4ed5395f29c91627bd` |
| `IMSChargingDataTypes.ber` | 2006 | `138c57b9b6643480ad4c1213d9035ac33c2525c0189252f8242a7c0af22c2569` |

Sınanan kritik baytlar (TLV olarak doğrulandı, üçü de eksiksiz parse oluyor):

```
FDRInput.ber                    61 37 / 30 35 …   [APPLICATION 1] + universal SEQUENCE
Audit_Record_Collection_St.ber  75 43 / 30 41 …   [APPLICATION 21] + universal SEQUENCE
IMSChargingDataTypes.ber        mMTelInformation [110] → subscriberRole [1] → 81 01 00
```

### 8. turda gönderilen dosyalar

7. tur cevaplanmadan gönderildi. **Bağımsızlık gerekçesi:** dördü de
`IMPLICIT TAGS` başlıklı ve **hiçbirinde tip-seviyesi tag yok** (`tipTag=0`),
yani ne `[APPLICATION n]` sorusu ne de `5906e76`'nın UNSPECIFIED kuralı bu
baytları değiştirebilir. 7. turun cevabı ne çıkarsa çıksın yeniden üretilmeleri
gerekmez.

| dosya | bayt | kök | soru | SHA-256 |
|---|---|---|---|---|
| `CGSN40ber.ber` | 419 | `CallEventRecord` | aile genellemesi çalışıyor mu | `91e6ddf4c627c1fdc28643204a7bf28c7eb1e550f21c356f865d663774c5804a` |
| `TurkcellCDRCCNCS5.ber` | 419 | `CallDetailOutputRecord` | C ailesi hakkında ilk kanıt | `a2e4579dc867c8de96180bd5b4d2d036dd272e7f824bf05e12978e6b7be50c91` |
| `CHAD.ber` | 481 | `ChargingDataOutputRecord` | C ailesi, ikinci örnek | `9f51f5b9024bb164beb5713c7bca9ec77ee6d4895204f12272e90f89afe3c8f2` |
| `SMSCBerCdr.ber` | 1045 | `SmsCdr` | D ailesi, ikinci örnek | `ddb5387e07ce8b2713445d967b03bafd3fb71b60309a51553cfedb2e896bdcab` |

Dördü de self-check'ten **0 hata / 0 uyarı** ile geçti, TLV bütünlüğü doğrulandı
(62/67/70/150 TLV, taşma yok). `ValidationSampleTest`'e eklendiler (`f3f0067`),
yani `target/validation/` altında da üretiliyorlar.

**Kritik not:** dördü de `asn1tools` ile **derlenmiyor** — 607'lik derlenemeyen
gruba giriyorlar (§8). `SMSCBerCdr`'de ayrıca gerçek bir şema kusuru var:
`Duplicated ENUMERATED number 36 at line 308`. Yani bu dört yapı için
**tek hakem EMM'dir**; bağımsız çapraz kontrol imkânı yok.

### Ne öğreneceğiz

- `CGSN40ber` **geçerse:** bir ailede alınan verdict'in o ailenin diğer
  modüllerine genellenebildiği ilk kez gösterilmiş olur. LTE/GGSN kanıtı
  paket-alanı ailesinin tamamını kapsıyor tezi güçlenir.
- `TurkcellCDRCCNCS5` / `CHAD` **geçerse:** hiç dokunulmamış 17 modüllük CCN/OCC
  soyu hakkında ilk kanıt. Reddedilirse hangi kuralın oraya uymadığı yeni bir
  açık soru olur.
- Toplamda amaç, "682 modül tek kanıta dayanıyor" cümlesini **dört farklı
  aileden kanıt var** haline getirmek.

### 14. turda gönderilen dosyalar — davranış sınıfı korpusu

Sorumluya **13.08.2026 17:36**'da iki parti hâlinde (7+7) gönderildi.
Yanıt bu satır yazılırken (17.08.2026) hâlâ beklenmektedir. Bu tur o gün
günlüğe işlenmemişti; aşağıdaki kayıt oturum dökümünden geri getirildi.

**Seçim yöntemi — 32 BER davranış sınıfı.** 802 üretilebilir modül, kodlayıcının
verdiği kararı değiştiren **8 eksende** parmak izlendi: modül modu (U/I), kök
şekli, yazılı `EXPLICIT`, tip-seviyesi `[APPLICATION]`, nötrleştirme ailesi,
CHOICE, SEQUENCE OF, SET. Çok-baytlı tag ve long-form uzunluk **mekanik**
sayılıp dışarıda bırakıldı — `IMSCDRS.TokensCSCF` ikisini de taşıyor ve EMM
34/34 alanı doğru çözdü; sınıfa katılsalardı 32 yerine 51 sınıf çıkıyordu.

| | |
|---|---|
| Toplam BER davranış sınıfı | **32** |
| O tarihte EMM kanıtıyla temsil edilen | **8 sınıf / 641 modül (%79)** |
| Kanıtsız | **24 sınıf / 161 modül (%20)** |
| Gönderilen dosya | **14** — kanıtsız 24 sınıfın 12'sini, 161 modülün **142'sini (%88)** kapsıyor |

Kapsanmayan 12 sınıf toplam 19 modül; hepsi tekil varyant (ortalama 1,6 modül).
Dosya başına getiri 14'lük sette ~10 modül, kuyrukta ~1,4 modül — kuyruk bilerek
bırakıldı.

| # | dosya | bayt | kök | mod | yaprak | SHA-256 |
|---|---|---|---|---|---|---|
| 1 | `TurkcellImsOmm.ber` | 484 | `TurkcellImsOmm` | U | 46 | `c00053d4222b5d6888669f439a82800fe4d13361df11e7b6b65f2538fc931c12` |
| 2 | `ConvergenceCdr.ber` | 727 | `ConvergenceCdr` | I | 65 | `21ab0f7077f1c55bda69bfaeefddc0771e05b5f391b1d69c9b0bf47f3b57137d` |
| 3 | `NRTRDEInValidationLookup.ber` | 213 | tip-tag'li | U | 24 | `3c2798b2890427b29637566775832a2ecc7502f5eac7fd2224ed9e605af22505` |
| 4 | `CME20R12TurkCellber.ber` | 981 | `CallDataRecord` | U | 125 | `d6af371cd12d47ab84983a401d6fc55d8a735d061efd32bb71ec4205b03f9cbe` |
| 5 | `TAP-0309.ber` | 4832 | `DataInterChange` | I | 483 | `d58881938d3eb196b36de2f8e582307f92a4cd203f83c64b5124da26c7c06d75` |
| 6 | `TAP0309.ber` | 4251 | `DataInterChange` | U | 425 | `62258da58ef84db40bd58ed8d9282fc95db46cd626fbf5c155b9526a90e8ef3d` |
| 7 | `CCNCS55_UpdatedCCR_CCN.ber` | 556 | `ChargingDataOutputRecord` | U | 64 | `09e8978a6ef3d7ae59ce994a50154c128000ac34202741c3b9eeb68586da2b11` |
| 8 | `GPRS-Charging-Extensions-Tr.ber` | 1199 | universal SET | I | 159 | `8a97ebf8602ac5d666a734c020fd0d54d66a15ac2a9e2844ab87c8f3d9012b6e` |
| 9 | `GSN50.ber` | 438 | `CallEventRecord` | U | 55 | `3268e04de65a604f986b0c4dbec0b49fefa3db7df55243929982c553a62794dd` |
| 10 | `IMS-R8-2009-03.ber` | 2697 | `IMSRecord` | I | 257 | `ef9bd72bf5f66d78ac0cc02d424f0de10e3526ba00e58fc21a436e2739183523` |
| 11 | `EMM-IMS-Specific.ber` | 195 | universal SET | I | 27 | `5633e3f3d78a1c28b709682214bddf6e2edabac726512706ea799c6d7c1628fd` |
| 12 | `EnrichedVerazCdr.ber` | 29.456 | `EnrichedVerazCdr` | U | 194 | `c7c594a1329724fe91e739bf6293a68f67c8efc4978bf3f83da3e02e75694b85` |
| 13 | `CDRDatamartCCNDMM.ber` | 1987 | `CDRDatamartCCNDMM` | U | 201 | `98468bd3cb7b72a263146e01979a8d0a0140f55bad50ac8cd47a6741ceaa7dad` |
| 14 | `FCMSMSC.ber` | 717 | `FCMSMSC` | U | 43 | `5adda8124e158c508e1086cb1813c436100c9b84650b10857c623ef70c17ba58` |

On dördü de self-check'ten **0 hata** ile geçti (`NRTRDEInValidationLookup` 2
uyarı, gerisi 0) ve TLV bütünlüğü doğrulandı. Aile dağılımı: TAP 2 ·
paket-alanı 3 · MMTel/IMS 2 · CCN/OCC 1 · NRTRDE 1 · CME 1 · GPRS 1 · diğer 3.

#### Her dosya hangi soruyu soruyor

| # | dosya | test ettiği hipotez | PASS ne kanıtlar | FAIL ne öğretir |
|---|---|---|---|---|
| 1 | `TurkcellImsOmm` | keyword'süz düz SEQUENCE kökünde SEQUENCE OF | **61 modüllük en büyük tek boşluk** kapanır | koleksiyon kodlaması U modunda farklı |
| 2 | `ConvergenceCdr` | `IMPLICIT TAGS`'te çıplak SEQUENCE kökü | 18 modül; tüm I-kanıtları CHOICE köklüydü | kök şekli mod ile etkileşiyor |
| 3 | `NRTRDEInValidationLookup` | tip-seviyesi `[APPLICATION]` + SEQUENCE OF | 15 modül; `f5ce531` koleksiyonda da geçerli | tip tag'i koleksiyonda farklı davranıyor |
| 4 | `CME20R12TurkCellber` | U modunda `[APPLICATION]` + SET birlikte | 14 modül, 74 çok-baytlı tag | iki özellik birlikte bozuluyor |
| 5 | `TAP-0309` | TAP ailesi, 10 seviye derinlik, 630 çok-baytlı tag | hiç test edilmemiş büyük aile + en derin yapı | TAP'e özgü bir kural var |
| 6 | `TAP0309` | aynı şema, keyword'süz başlık — **5 ile A/B çifti** | U/I ayrımının TAP'te de tuttuğu | ayrım TAP'te tutmuyor |
| 7 | `CCNCS55_UpdatedCCR_CCN` | yazılı `EXPLICIT`, nötrleştirme ailesi **dışında** | aile kapısının doğru yerde olduğu | nötrleştirme genelleşmeli |
| 8 | `GPRS-Charging-Extensions-Tr` | **kök SET** + yazılı `EXPLICIT` | kök SET hiç test edilmedi | `31` kök tag'i kabul edilmiyor |
| 9 | `GSN50` | `[UNIVERSAL n]` override (`594faad`) | X.690 8.19.1 okumamız doğru | override kuralı yanlış |
| 10 | `IMS-R8-2009-03` | nötrleştirme ailesi, yazılı `EXPLICIT` **yok** | aile kapısı keyword'süz sitede de doğru | kapı yazılı keyword'e bağlı |
| 11 | `EMM-IMS-Specific` | minimal kök SET (27 yaprak) | 8'in kontrolü — sade vaka | 8 ile birlikte kök SET sorunu |
| 12 | `EnrichedVerazCdr` | 29 KB, 26 long-form uzunluk, 194 alan | büyük dosya + uzunluk mekaniği | boyut sınırı veya uzunluk kodlaması |
| 13 | `CDRDatamartCCNDMM` | 171 çok-baytlı tag, düz yapı | yoğun çok-baytlı tag | tag kodlama sınırı |
| 14 | `FCMSMSC` | düz SEQUENCE kökünde CHOICE + SET | 8 modül | kök-içi CHOICE farklı |

#### Gönderilirken yazılan tahmin

Yanıt geldiğinde neyi öğrendiğimizi ölçebilmek için önceden kaydedildi:
`CCNCS55_UpdatedCCR_CCN` ~%70 · `IMS-R8-2009-03` ~%65 · `ConvergenceCdr` ~%60 ·
`TurkcellImsOmm` ~%55 · `GSN50` / `CDRDatamartCCNDMM` / `FCMSMSC` ~%50 ·
`EnrichedVerazCdr` ~%45 · `CME20R12TurkCellber` ~%40 ·
`GPRS-Charging-Extensions-Tr` ~%35 · `TAP-0309` ~%30 ·
`NRTRDEInValidationLookup` / `TAP0309` ~%25 · `EMM-IMS-Specific` ~%20.
**Beklenti: 14'ten ~6 PASS.**

İki bağımsız risk ekseni ayrıldı: **kodlama** (baytlar doğru mu — 6 kanıtlanmış
kural, 9 invariant, 0 ihlal, öngörü iyi) ve **yönlendirme** (EMM'de o tip için
akış tanımlı mı, hangi tipi bekliyor — bilgi bizde yok, 9. turda üç dosya
yalnızca bu yüzden yandı). Düşük tahminlerin çoğu ikinci eksenden geliyor.
`NRTRDEInValidationLookup` ("Lookup" = arama tablosu) ve `EMM-IMS-Specific`
(EMM'in kendi iç yapısı) muhtemelen gönderilen bir CDR tipi bile değil.

Ayrıca `TAP-0309`'da kodlamadan bağımsız bir **veri** riski var: şemadaki
`auditControlInfo` içinde `callEventDetailsCount`, `earliestCallTimeStamp`,
`totalChargeValueList` gibi **kayıtlarla tutarlı olması gereken** alanlar var,
üretici bunları rastgele dolduruyor. TAP reddedilirse önce buraya bakılmalı.

#### ✅ 14. tur sonucu (17.08.2026) — 7 PASS, 6 red, 1 koşulamadı

Beklenti 6 PASS'ti, 7 geldi. Ama asıl bilgi dağılımda: **tahmin edilen sıra
tutmadı.** En düşük olasılık verilen üçü geçti (`EMM-IMS-Specific` %20,
`NRTRDEInValidationLookup` %25, `GPRS-Charging-Extensions-Tr` %35), en yüksek
verilen ikisi düştü (`CCNCS55_UpdatedCCR_CCN` %70, `TurkcellImsOmm` %55).
Tahminlerin çoğu "yönlendirme" ekseninden korkuyordu; gerçekte o eksen
beklenenden iyi, kodlama ekseninde ise iki gerçek kusur çıktı.

| dosya | sonuç | EMM'in beklediği kök | not |
|---|---|---|---|
| `ConvergenceCdr` | ✅ PASS | — | `IMPLICIT TAGS`'te çıplak SEQUENCE kökü **kanıtlandı** (18 modül) |
| `NRTRDEInValidationLookup` | ✅ PASS | — | tip-seviyesi `[APPLICATION]` + SEQUENCE OF (15 modül) |
| `CME20R12TurkCellber` | ✅ PASS | — | U modunda `[APPLICATION]` + SET, 74 çok-baytlı tag (14 modül) |
| `GPRS-Charging-Extensions-Tr` | ✅ PASS | — | **kök SET kanıtlandı** + yazılı `EXPLICIT` (CHOICE üzerinde) (3 modül) |
| `EMM-IMS-Specific` | ✅ PASS | — | minimal kök SET, 8'in kontrolü (2 modül) |
| `CDRDatamartCCNDMM` | ✅ PASS | — | 171 çok-baytlı tag (1 modül) |
| `FCMSMSC` | ✅ PASS | — | düz SEQUENCE kökünde CHOICE + SET (1 modül) |
| `IMS-R8-2009-03` | ⏸ koşulamadı | — | EMM'de akış yok; yeniden gönderilecek |
| `EnrichedVerazCdr` | ❌ | `EnrichedVerazCdr.CDR` | `Invalid length 28133` @ `redirectingInformationSubs` |
| `CCNCS55_UpdatedCCR_CCN` | ❌ | `...ChargingDataOutputRecord` ✓ | `Invalid length 38` @ `sCFPDPRecord.ggsnAddressUsed` |
| `GSN50` | ❌ | `GSN50.CallEventRecord` ✓ | `Invalid length 8` @ `recordExtensions.[0].information` |
| `TurkcellImsOmm` | ❌ | **`TurkcellImsOmm.PostCcnCdr`** | `Invalid length 484` = tam dosya boyutu |
| `TAP-0309` | ❌ | **`TAP-0309.CallEventDetail`** | `CallEventDetail was probably not set` |
| `TAP0309` | ❌ | **`TAP0309.CallEventDetail`** | aynı |

Altı reddin **hiçbiri** tagging kurallarımızı çürütmüyor. Üçü kök tip seçimi,
ikisi tek bir kodlama kuralı, biri çözülmemiş bir `IMPORTS`.

##### 🔴 Bulgu 1 — yazılı `EXPLICIT`'i tip belirler, soy değil (DÜZELTİLDİ)

İki red aynı şekli gösteriyor. Şemalar:

```
CCNCS55:      ggsnAddressUsed            [1]   EXPLICIT GSNAddress
              GSNAddress ::= IPBinaryAddress ::= SEQUENCE { [0] .., [1] .. }
EnrichedVeraz: redirectingInformationSubs [166] EXPLICIT RedirectingInformation
              RedirectingInformation ::= SEQUENCE { [1] .., [2] .., .. }
```

Gönderdiğimiz baytlar (bugün yeniden üretilip TLV olarak okundu):

```
A1 1A  30 18  80 04 ..  81 10 ..          <- fazladan 30 18
BF 81 26 81 83  30 81 80  81 18 ..        <- fazladan 30 81 80
```

Bağlam tag'i ile alanların arasında **fazladan bir universal SEQUENCE** var.
EMM ikisini de tam o alanda reddetti.

**Kontrol aynı turun içinde:** yazılı `EXPLICIT` taşıyıp **geçen** her modülde
keyword bir **CHOICE**'un üzerinde — `GPRS-Charging-Extensions-Tr`
(`[0] EXPLICIT ExtendedDiagnostics`, `[1] EXPLICIT IPAddress`, ikisi de CHOICE)
ve 12. turdan `CHFChargingDataTypes16` (`[2] EXPLICIT IPAddress`). Bir tek kabul
bile CHOICE dışı bir sarmalayıcıya dayanmıyor.

| modül | sonuç | başlık | yazılı EXPLICIT nerede |
|---|---|---|---|
| `GPRS-Charging-Extensions-Tr` | PASS | IMPLICIT | CHOICE (3 alan) |
| `CHFChargingDataTypes16` | PASS (12. tur) | UNSPECIFIED | CHOICE |
| `CCNCS55_UpdatedCCR_CCN` | **FAIL** | UNSPECIFIED | **SEQUENCE** |
| `EnrichedVerazCdr` | **FAIL** | UNSPECIFIED | **SEQUENCE** |

Yani nötrleştirmeyi soya bağlayan kapı (`isVerifiedExplicitNeutralizationFamily`)
yanlış yerdeydi; kararı veren **hedef tipin CHOICE olup olmadığı**. Kapı
kaldırıldı (commit `2006841`). Başlığında açıkça `EXPLICIT TAGS` yazan modül
dokunulmadan bırakıldı — veri setinde öyle modül yok ve hiçbir ölçüm oraya
değmiyor.

**Ölçülen etki:** 808 modüllük denetimde **2 modül / 5 alan**; `vErrors` 45 ve
`vWarnings` 229 değişmedi, 497 test geçiyor. Düzeltilmiş baytlar:
`A1 18 80 04 .. 81 10 ..` ve `BF 81 26 81 80 81 18 ..`.

Bu, tek cümlelik kuralı bir kez daha doğruluyor ve son boşluğunu kapatıyor:
**CHOICE dışında her şey IMPLICIT — yazılı keyword dahil.** 2. kural
(`[n] EXPLICIT <SET>` → sarmalayıcı yok) ve 3. kural (`[n] EXPLICIT <BOOLEAN>`
→ primitive) zaten bunun iki özel hâliymiş; şimdi ikisi de aynı tek kuraldan
çıkıyor.

##### 🟠 Bulgu 2 — çözülmemiş `IMPORTS` opak bir primitive olarak yazılıyor (DÜZELTİLDİ, `3f20dd7`)

`GSN50`:

```
recordExtensions [23] ManagementExtensions OPTIONAL
ManagementExtensions ::= SET OF ManagementExtension
ManagementExtension ::= SEQUENCE {
    identifier   [UNIVERSAL 6] OCTET STRING,
    significance [1] BOOLEAN DEFAULT TRUE,
    information  [2] GprsCdrExtensions OPTIONAL }

IMPORTS GprsCdrExtensions FROM GPRS-Charging-Extensions { .. ericsson .. }
```

`GprsCdrExtensions` bu modülde tanımlı değil, **IMPORT edilmiş**. Çözemediğimiz
için alanı 8 baytlık düz bir değer olarak yazıyoruz — `82 08 4F 58 4E 51 ..` —
ve EMM `Invalid length 8` diyor: onun şemasında orası yapısal bir tip.

İki iyi haber aynı baytlarda: EMM `recordExtensions.[0].information` diye
**yol vererek** hata verdi, yani `[23]`'ün `SET OF` kodlamasını ve
`ManagementExtension` elemanını doğru çözdü. Ayrıca `identifier`'ı geçti —
`[UNIVERSAL 6]` override okumamız (X.690 8.19.1, `594faad`) çalışıyor.

**Kök neden:** `IMPORTS` yan tümcesi hiç okunmuyordu. `buildRegistry` tek bir
modülün metnini alıyor; modül sınırını aşan referansın karşılığı registry'de
yok, alan çocuksuz kalıyor ve kodlayıcı yaprak yazıyor. Özel bir GSN50 kusuru
değil, genel bir eksik.

**Eşleme tahmin gerektirmedi.** Ölçüldü: **17 modül** `IMPORTS` bildiriyor,
**7 farklı** kaynak modül adı geçiyor ve yedisi de veri setinde **tam o adla**,
sembolü tanımlı hâlde mevcut. `GSN50` özelinde import `GPRS-Charging-Extensions`
diyor ve o modül `GprsCdrExtensions ::= SET { ... }` tanımlayıp `EXPORTS` ile
dışa veriyor. Benzerliğe göre eşleme **yanlış olurdu**: üç kaynak yalnızca
sonekle ayrılıyor (`-Tr`, `-KKTC`) ve üç ayrı ithalatçı üçünü ayrı ayrı
adlandırıyor. Arama birebir ada göre yapılıyor.

**Tagging modu ithal edilen ağaçla birlikte taşınıyor.** Import bir başlık
sınırı geçiyor: `GSN50 DEFINITIONS ::=` mod söylemiyor,
`GPRS-Charging-Extensions DEFINITIONS IMPLICIT TAGS ::=` söylüyor. İki okuma
sıradan bağlam tag'inde aynı sonucu veriyor, tek bir yerde ayrılıyor —
keyword'süz tag'in CHOICE tipli alanda olması (13. turun kuralı vs X.680 8.3).
`SDPCCR` ve `CreditControlDataTypes_EC22`'de bu türden **8'er site** var ve her
iki modülün de ithalatçıları mod söylemeyen modüller, yani fark ölçülebilir.
Bu yüzden kopyalanan her tanım, kendi modülünün modunu taşıyor
(`AsnTypeDefinition.taggingMode`).

**Sonuç — `information [2]` artık gerçek yapısıyla üretiliyor:**

```
B7 82 04 1F                      recordExtensions [23]  (SET OF, IMPLICIT)
  30 82 04 1B                    ManagementExtension
    06 08 ..                     identifier [UNIVERSAL 6]   <- override calisiyor
    81 01 00                     significance [1]
    A2 82 04 0A                  information [2] GprsCdrExtensions (SET, IMPLICIT)
      A0 04 { 80 02 .. }         extendedDiagnostics [0] EXPLICIT <CHOICE>  <- sarmalayici KORUNDU
      A1 82 01 B7 { 30 81 E0 .. } chargingContainers [1] SEQUENCE OF
```

Yazılı `EXPLICIT` bir CHOICE'un üzerinde olduğu için 7. kural gereği sarmalayıcı
duruyor; koleksiyon ve SET tag'leri universal tag'in yerine geçiyor.
`GSN50` 222 bayt / 49 yapraktan **1609 bayt / 135 yaprağa** çıktı.

**Kapsam:** import kapatması 7 modülün baytlarını değiştirdi — `GSN50`,
`GSN50X`, `HuaweiGSN50`, `GGSNJ2040R6ber`, `IMSChargingDataTypes`,
`AIMSChargingDataTypes`, `ATS`. Diğer 10 ithalatçıda (`CGSN40ber`, `CHAD`,
`TurkcellCDRCCNCS5`, `OCC4_12`, `CCN_EC22` …) ithal edilen tip seçilen CHOICE
alternatifinin dışında kaldığı için üretilen ağaca girmiyor; **EMM'den geçmiş
hiçbir dosyanın baytı bu yüzden değişmedi.**

##### 🟡 Bulgu 3 — kök tip seçimi (üç dosya, kodlama kusuru değil) (DÜZELTİLDİ, `3f20dd7`)

`LTE-R10` → `pGWRecord`, `IMSCDRS` → `TokensCSCF`, `CHF` → `ChargingRecord`
düzeltmelerinin aynısı. Hangi tipin "kayıt" olduğu tüketen akışın kararı,
sezgisel seçicinin değil:

| modül | bizim seçtiğimiz | EMM'in beklediği | fark |
|---|---|---|---|
| `TurkcellImsOmm` | modül adıyla anılan kök, 46 yaprak | `PostCcnCdr`, 37 yaprak | ayrı tip |
| `TAP-0309` | `DataInterChange` (tüm batch) | `CallEventDetail` | akış tek çağrı kaydı okuyor |
| `TAP0309` | `DataInterChange` | `CallEventDetail` | aynı |

`TAP`'in kök tipi değişince 12.08'de not edilen **`auditControlInfo` iç
tutarlılık riski de ortadan kalkıyor** — `CallEventDetail` o bloğu hiç
içermiyor. Üçünün EMM'in adlandırdığı kökle üretilen hâli
`ValidationSampleTest`'e eklendi, sezgisel okumanın yanına.

`EnrichedVerazCdr` için EMM `CDR` dedi; ölçüldü, sezgisel seçicinin bulduğu kök
zaten o tip (aynı ağaç, aynı boyut) — orada kök sorunu yok, sorun Bulgu 1'di.

**Bilgi artık kalıcı: `src/main/resources/emm-record-bindings.yml`.** Bu beş
eşleşme biliniyordu ama hiçbir yerde yazılı değildi; her çağıran ya hatırlıyordu
ya hatırlamıyordu, aynı sınıftan red bu yüzden tekrar tekrar geldi. Dosya
şemaların yanında duruyor, her satır hangi turdan geldiğini söylüyor ve
`StructureParserService` üzerinden **API, arayüz ve doğrulama örnekleri aynı
yoldan** okuyor. Çağıranın açıkça verdiği `rootType` hâlâ kazanıyor — bir soruyu
EMM'e sormanın yolu o.

| modül | bağlanan | tür |
|---|---|---|
| `IMSCDRS` | `TokensCSCF` | kayıt tipi |
| `CHFChargingDataTypes16` | `ChargingRecord` | kayıt tipi |
| `TurkcellImsOmm` | `PostCcnCdr` | kayıt tipi |
| `TAP-0309` / `TAP0309` | `CallEventDetail` | kayıt tipi |
| `LTE-R10` | `CallEventRecord` → `pGWRecord` | **CHOICE alternatifi** |

`LTE-R10` ayrı tür çünkü `pGWRecord [79]` bir tip değil, `CallEventRecord`
CHOICE'unun alternatifi. Kayıt tipi olarak yazılsaydı registry'de karşılığı
bulunamayıp sessizce düşerdi ve 4. turu geçiren dosya üretilmeyi bırakırdı;
seçim olarak yazılınca örnek çıktının başı `BF 4E` (sGWRecord) yerine
**`BF 4F`** (pGWRecord) oluyor.

**Ölçülen etki (tüm oturum, 808 modül):** 15 modülün baytı değişti — 7'si import
çözümünden, 5'i bağlanan kök tipten, 2'si Bulgu 1'den, 1'i (`CHF`) iç içe bir
EXPLICIT sitesinden 2 bayt. **`MMTelChargingDataTypes` değişmedi** — referans
yakalamayla doğrulanan tek modül yerinde duruyor. `vErrors` 45 / `vWarnings` 229
sabit, 514 test geçiyor.

##### Kapsamın yeni hâli

| | 13.08 | 17.08 |
|---|---|---|
| EMM kanıtlı davranış sınıfı | 8 / 32 | **15 / 32** |
| Kanıtlı modül | 641 (%79) | **695 (%86,7)** |
| EMM'den geçen yapı | 12 | **19** |

Kanıtsız kalan ve **sırada olan** sınıflar: `U·SEQU·-·-·-·-·Q·-` (61 modül,
`TurkcellImsOmm`), TAP ailesi (11 modül, iki dosya), `U·CHOI·E·-·-·C·Q·-`
(5, `CCNCS55`), `U·CHOI·E·-·-·C·Q·S` (3, `GSN50`), `I·CHOI·-·-·N·C·Q·S`
(2, `IMS-R8`), `U·SEQU·E·-·-·-·-·-` (1, `EnrichedVerazCdr`) — toplam 83 modül.
15. tur bu altısını yeniden gönderirse kanıtlı oran **%97**'ye çıkar.

#### ⚠️ Gönderilen baytlar yeniden üretilemez

Dosyalar `target/emm-corpus-r14/` altında toplanmıştı; `target/` gitignore'da ve
16.08'deki `mvn clean` ile silindi. On dördü `ValidationSampleTest`'e **geçici**
olarak eklenip üretildi, düzenleme sonra geri alındı — bugün sample listesinde
21 aile var, bu 14'ü yok. Üretici tohumsuz rastgele olduğu için aynı komut aynı
baytları vermez; üstelik 33e8b01 (TBCD locale) abone-numarası alanlarının
değerlerini değiştirdi. **Yukarıdaki SHA-256 listesi EMM'in elindeki baytların
tek kaydıdır.** Bir dosya reddedilirse teşhis bayt karşılaştırmasıyla değil,
şema/kural düzeyinde yapılacak; gerekirse aynı sınıf yeniden üretilip yeni
SHA ile gönderilecek.

### 15. turda gönderilen dosyalar

Sorumluya **17.08.2026**'da gönderildi. 14. turun altı reddi, düzeltmeler
uygulandıktan sonra yeniden üretildi; artı EMM'de akış bulunamadığı için
koşulamayan `IMS-R8-2009-03`. Yeni bir soru sorulmuyor — bu tur **14. turun
üç bulgusunun düzeltildiğini** sınıyor.

SHA'lar **gönderimden önce** yazıldı (14. turun dersi: dosyalar `target/`
altında kalıp `mvn clean` ile silinmişti ve üretici tohumsuz olduğu için
yeniden üretilemiyorlar).

| # | dosya | bayt | TLV | derin | kök | SHA-256 |
|---|---|---|---|---|---|---|
| 1 | `TurkcellImsOmm.ber` | 446 | 38 | 1 | `PostCcnCdr` | `446fb98456d0e723e3e6f83e974ee5baf7e6e88feab18a005ba153d8b900fd65` |
| 2 | `TAP-0309.ber` | 3019 | 423 | 8 | `CallEventDetail` | `414caded90dd195ef77f8fb5cbd880dc845a117bd759792d0e1044fd2e1fae63` |
| 3 | `TAP0309.ber` | 2663 | 367 | 8 | `CallEventDetail` | `4284a416ccc3c161f8a213a3ba77f24cca0c6f7887545102a6eefb1515166977` |
| 4 | `EnrichedVerazCdr.ber` | 29.444 | 199 | 2 | `CDR` | `f8e8084f36f8b819d09c59d059952f0c3b0ca8aed7887611523fe8fd602a7fd2` |
| 5 | `CCNCS55_UpdatedCCR_CCN.ber` | 486 | 71 | 4 | `ChargingDataOutputRecord` | `de7a6171a99edcf4877a19d55e4026bdb3da1972de99e1ada0d16756e6907a25` |
| 6 | `GSN50.ber` | 2230 | 375 | 10 | `CallEventRecord` | `01058bb5276c09f4472b9fa2db0bfac0027cd907f96b6b2c717bb180936302fd` |
| 7 | `IMS-R8-2009-03.ber` | 2343 | 281 | 6 | `IMSRecord` | `0768b9ec9ce388c14e6158d8afdf1c6c751f94dfa46d230c7a5e10f74ab63d84` |

Yedisi de self-check'ten **0 hata / 0 uyarı** ile geçti ve TLV bütünlüğü
doğrulandı (taşma yok). Yedisi de `ValidationSampleTest`'te kayıtlı, yani
`target/validation/` altında her koşuda üretiliyorlar — ama **aynı baytlarla
değil**, üretici tohumsuz.

#### Her dosya neyi sınıyor

| # | dosya | 14. turdaki hata | uygulanan düzeltme |
|---|---|---|---|
| 1 | `TurkcellImsOmm` | `Invalid length 484` (tam dosya boyutu) | kök tip `PostCcnCdr`'a bağlandı (`3f20dd7`) |
| 2 | `TAP-0309` | `CallEventDetail was probably not set` | kök tip `CallEventDetail`'e bağlandı |
| 3 | `TAP0309` | aynı | aynı |
| 4 | `EnrichedVerazCdr` | `Invalid length 28133` @ `redirectingInformationSubs` | yazılı `EXPLICIT` nötrleştirildi (`2006841`) |
| 5 | `CCNCS55_UpdatedCCR_CCN` | `Invalid length 38` @ `sCFPDPRecord.ggsnAddressUsed` | aynı kural |
| 6 | `GSN50` | `Invalid length 8` @ `recordExtensions.[0].information` | `IMPORTS` corpus'a karşı çözüldü (`3f20dd7`) |
| 7 | `IMS-R8-2009-03` | koşulamadı (akış yok) | değişiklik yok; akış sorusu |

#### Beklenti — gönderimden önce yazıldı

| # | dosya | tahmin | dayanak / kalan risk |
|---|---|---|---|
| 1 | `TurkcellImsOmm` | **~%85** | `PostCcnCdr` 37 düz IA5String alan, `IMSCDRS.TokensCSCF`'in (34 düz alan, aynı başlık sınıfı, PASS) birebir ikizi. EMM tipi kendi adlandırdı, yani akış var ve o tipe bağlı. |
| 2 | `TAP-0309` | **~%60** | Kök artık EMM'in adlandırdığı tip ve `auditControlInfo` tutarlılık riski bu tiple ortadan kalktı. Risk: TAP ailesi hiç geçmedi, 8 seviye derinlik, `CallEventDetail` bir CHOICE — ilk alternatifi (`mobileOriginatedCall`) seçiyoruz, akış başkasını bekliyor olabilir. |
| 3 | `TAP0309` | **~%55** | 2 ile aynı, artı başlığı mod söylemiyor: 4-7. kurallara dayanıyor. |
| 4 | `EnrichedVerazCdr` | **~%75** | EMM 29.456 baytın **27.997'sini çözmüştü**, yani ondan önceki ~180 alan doğruydu; düşen tek site düzeltildi ve modülde bu türden yalnızca 4 site var. Risk: 29 KB boyut. |
| 5 | `CCNCS55_UpdatedCCR_CCN` | **~%70** | Soy kanıtlı (`CHAD`, `TurkcellCDRCCNCS5` PASS) ve düşen site düzeltildi. Risk: EMM kaydın **ikinci alanında** durmuştu, yani kalan 50 yaprak hakkında dış kanıt yok. |
| 6 | `GSN50` | **~%55** | Düşen alan artık gerçek `GprsCdrExtensions ::= SET` yapısıyla çıkıyor; EMM `identifier [UNIVERSAL 6]`'yı ve `SET OF` sarmalayıcısını zaten doğru çözmüştü. Risk: ithal edilen alt ağaç **86 yeni yaprak** getiriyor ve o yüzey EMM tarafından hiç görülmedi. |
| 7 | `IMS-R8-2009-03` | **akışa bağlı** | Kodlama tarafı düşük riskli (MMTel/IMS soyu en iyi kanıtlanmış aile, yazılı `EXPLICIT` yok). Tek soru EMM'de bu tip için çözücü tanımlı mı. |

**Beklenti: 7'den ~4-5 PASS.**

⚠️ **Bir PASS'ın kapsamadığı şey.** `TurkcellImsOmm` geçerse 61 modüllük
`U·SEQU·-·-·-·-·Q·-` sınıfı **tam kapanmaz**: sınıfın ayırt edici özelliği
keyword'süz düz SEQUENCE kökünde **SEQUENCE OF** bulunması, ama `PostCcnCdr`'da
hiç koleksiyon yok (37 alanın hepsi IA5String). Koleksiyon sorusu bu turda da
sınanmıyor; kapatmak için aynı sınıftan koleksiyon taşıyan bir kök gerekiyor.

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

### ✅ ÇÖZÜLDÜ — Tip-seviyesi `[APPLICATION n]` IMPLICIT'tir (9. tur)

Anahtar kelimesiz bir modülde **tip** üzerine yazılan tag da IMPLICIT. İki
bağımsız modül, iki farklı şekil, aynı sebep:

```
FDRInput.NrFile.name was probably not set and is not optional
Audit_Record_Collection_St.LogEntry.collectionConfiguration
    was probably not set and is not optional
```

İkisi de okumanın ulaşabildiği **ilk alanı** işaret ediyor:

| dosya | gönderilen | EMM ne yaptı |
|---|---|---|
| `FDRInput` | `61 37 30 35 62 14 …` | `[APPLICATION 1]`'i açtı, `name` için `[APPLICATION 2]` = `0x62` bekledi, sarmaladığımız `0x30`'u buldu |
| `Audit` | `75 43 30 41 16 08 …` | `serviceName` OPTIONAL olduğu için atladı, ilk **zorunlu** alan `collectionConfiguration`'da aynı `0x30`'a takıldı |

**Düzeltme:** `readLeadingTag` içinde `UNSPECIFIED` artık `resolveExplicit` ile
aynı yönde çözülüyor (commit `f5ce531`). Yazılı keyword hâlâ kazanır.

**Etki:** 11 modül, 374 explicit site kalktı (696 → 322).

**Bağımsız doğrulama:** `TAP0309` (anahtar kelimesiz, 298 site, yazılı keyword
yok) 8484 → **5632** bayta indi. Aynı TAP3 şemasının `IMPLICIT TAGS` başlıklı
ikizi `TAP-0309` ise 5622 bayt. İki kopya artık aynı baytları üretiyor — ki
gerçek TAP3 spesifikasyonu da `DEFINITIONS IMPLICIT TAGS` diyor. Bu, EMM'i
memnun etmenin ötesinde bir tutarlılık kanıtı.

**Not:** `asn1tools` eski kodlamamızı X.680'e uygun bulmuştu (`FDRInput` bayt
bayt round-trip). Yani burada standarttan bilerek sapıyoruz — `5906e76`'daki
aynı tercih. Bu projede üst kanıt EMM'dir.

### ⚠️ 9. turun asıl dersi — üç "FAIL" kodlama hatası değildi

Beş redden **üçü yanlış akışa yönlendirmeden** kaynaklandı; kodlama sınanmadı bile:

| dosya | EMM'in beklediği tip | gerçek sorun |
|---|---|---|
| `GGSN-qos-implicit` / `-explicit` | `CGSN40ber.CallEventRecord` | Dosyalar `GGSNTurkcellCdrR7` kaydıydı, CGSN40ber akışına verildi → **OCTET STRING deneyi hiç koşmadı** |
| `IMSChargingDataTypes` | `IMSCDRS.TokensCSCF` | CSCFColl akışına gitti → **ENUMERATED deneyi hiç koşmadı** |
| `CHFChargingDataTypes16` | `CHFChargingDataTypes16.ChargingRecord` | Bizim sezgimiz `CHFRecord` (CHOICE) seçti, akış `ChargingRecord` (SET) çözüyor → `rootType` ile düzeltildi (`9f60e1f`) |

**Çıkarılan kural:** bir dosya göndermeden önce **EMM'in o akışta hangi tipi
çözdüğü** bilinmeli. Aksi halde tur, kodlama hakkında hiçbir şey öğretmeden
harcanır. Bu, `LTE-R10`'un `pGWRecord` düzeltmesiyle aynı sınıf — üçüncü kez
tekrarlandı.

### 🟠 Primitive ENUMERATED — ölçülemedi, ama çıkarımla büyük ölçüde kapandı

Doğrudan ölçüm **mümkün değil**: EMM'de yönlendirilebildiğini bildiğimiz 11
modülün hiçbirinde `[n] EXPLICIT <ENUMERATED>` yok (alias zinciri derinlemesine
çözülerek tarandı), ve `IMSChargingDataTypes` için CSCFColl dışında akış yok.

Yerine iki ölçülmüş olgu birleşiyor:

1. **Kural 3 (LTE-R10):** `[n] EXPLICIT <primitive BOOLEAN>` → EMM IMPLICIT istedi
2. **10. tur (CGSN40ber decode):** implicit ENUMERATED alanları — `changeCondition`,
   `causeForRecClosing`, `apnSelectionMode`, `chChSelectionMode` — EMM tarafından
   **sembolik adlarıyla doğru çözüldü**

EMM telde `81 01 00` görür; o baytın "modül varsayılanı implicit"ten mi yoksa
"yazılı EXPLICIT nötrlendi"den mi geldiğini ayırt edemez. İki olgu birlikte
mevcut davranışı destekliyor. **Ölçüm değil, çıkarım** — kanıt hiyerarşisinde
bir basamak aşağıda tutulmalı.

### 🟡 (eski kayıt) Primitive ENUMERATED — 7. turda gönderildi

- `IMSChargingDataTypes` → `subscriberRole [1] EXPLICIT SubscriberRole` = `81 01 00`
- MMTel ailesinden (aynı parmak izi, aynı başlık) olduğu için düşük riskli
- BOOLEAN'da kanıtlandı, ENUMERATED'da hiç sınanmadı

### ✅ ÇÖZÜLDÜ — Primitive OCTET STRING: EMM sarmalayıcıyı doğru açıyor (10. tur)

Ayırt edici ikili `CGSN40ber` üzerine kuruldu (`QoSInformation ::= OCTET STRING
(SIZE(4..12))`). İki dosya da **aynı 12 baytlık ASCII değeri** taşıdı:

| dosya | qosRequested baytları | sonuç |
|---|---|---|
| `CGSN40ber-qos-implicit` | `81 0C QOSPROBE1234` | PASS |
| `CGSN40ber-qos-explicit` | `A1 0E 04 0C QOSPROBE1234` | PASS |

**Belirleyici olan, EMM'in döndürdüğü ASCII decode:**

```
qosRequested : '514F5350524F424531323334'H     -> "QOSPROBE1234", 12 bayt
```

Sarmalayıcılı dosyadan geldi ve **`04 0C` öneki yok**. İçerik veri sayılsaydı
`040C514F…` (14 bayt) görülecekti. Yani EMM EXPLICIT sarmalayıcıyı doğru
açıyor; iki kodlama semantik olarak eşdeğer ve OCTET STRING'lerimiz çöp bayt
taşımıyor.

Aynı çıktı iki kuralı daha yeni bir ailede teyit etti: `servedPDPAddress` ve
`diagnostics` (ikisi de `[n] EXPLICIT <CHOICE>`) sarmalayıcılarıyla doğru
çözüldü, ve `dynamicAddressFlag : '0'D` — 2. turda dosyayı batıran BOOLEAN —
temiz geldi.

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

### Kapsamın ölçülmüş hâli

`audit.tsv` üzerinden (12.08.2026, `184c717`) doğrulanan kesin dağılım:

| tagging modu | modül | toplam yaprak alan |
|---|---|---|
| `UNSPECIFIED` (başlık mod söylemiyor) | **712** | 22.133 |
| `IMPLICIT TAGS` | 96 | 8.844 |
| `EXPLICIT TAGS` yazan | **0** | — |
| `AUTOMATIC TAGS` yazan | **0** | — |

Dikkat çeken nokta: korpusta **başlığında açıkça `EXPLICIT TAGS` yazan tek bir
modül yok.** Yani "EXPLICIT dünyası" diye ayrı bir küme yok — 5906e76'nın
yeniden yorumladığı `UNSPECIFIED` kümesi ile aynı şey. Bu da uyarıyı
keskinleştiriyor: değişiklik korpusun **%88'ini** (712/808) kapsıyor ve tek bir
EMM kanıtına dayanıyor.

## 7. Diğer belgelerin durumu — 5906e76 öncesini anlatıyorlar

`README.md` (`bca9c01`) ve `docs/coverage-validation-plan.md` (`419511e`) ikisi de
**10 Ağustos** tarihli; `5906e76` **12 Ağustos**'ta geldi. İkisi de eski encoder'ı
tarif ediyor ve okuyanı yanıltır:

- **README, "Bilinen açık konular":** "712 modülde `[n]` etiketleri X.680 31.2.7
  varsayılanı gereği **sarmalayıcı olarak kodlanıyor**" — 5906e76'dan sonra alan
  tag'leri için bu **yanlış**; artık IMPLICIT. Yalnızca tip-seviyesi tag'ler
  sarmalayıcı kaldı. Aynı bölümdeki "EMM'den gelen üç yanıtın üçü de IMPLICIT
  başlıklı modüllerdendi" cümlesi de eskidi — `IMSCDRS` başlıksız bir modül ve
  6. turda geçti.
- **coverage-validation-plan.md:** Risk tezinin tamamı "her etiketli alan için
  fazladan bir sarmalayıcı TLV" premisine dayanıyor; bu premise alan tag'leri
  için artık geçerli değil. Katman A'da "EXPLICIT yoğun" diye seçilen yapılar
  ölçüldüğünde bugün **hiç explicit alan taşımıyor**: `RepositoryBroadSoftIN` 0,
  `BroadSoft` 0, `TELENITY_SMSC` 0, `LDAPLookup` 0. Seçilme gerekçeleri düştü.
- **Ama planın en değerli kalemi ayakta:** `TAP0309` / `TAP-0309` A/B çifti hâlâ
  keskin ayırt ediyor (298 explicit / 8484 bayt vs 0 explicit / 5622 bayt) —
  çünkü aradaki fark artık alan tag'inden değil, **tip-seviyesi tag'den** geliyor.
  Yani o çift bugün doğrudan 7. turun sorusunu ölçüyor ve değeri artmış durumda.

Bu iki belge güncellenene kadar, tagging davranışı konusunda **bu günlük
esas alınmalıdır.**

## 9. Kanıt korpusu — tek turda azami kanıt (12.08.2026)

Yaklaşım değişti: rastgele tek tek dosya yerine, **her dosya en az bir açık
teknik soruyu test eden** asgari bir set.

### Korpus analizinden çıkan iki düzeltme

**1. `TurkcellCDRCCNCS5` elendi.** `CHAD` ile şema benzerliği %83; 218 tip ortak,
`CCNCS5`'in yalnızca 2 özel tipi var. `CHAD` üst küme, ikisini birden göndermek
bilgi eklemiyor. (8. turda gönderildi, sonucu yine de kaydedilecek.)

**2. `QoSInformation` kısıtı yanlış biliniyordu.** §4 "SIZE(4..255)" diyordu;
şemadaki aktif tanım:

```
QoSInformation ::= OCTET STRING (SIZE (4..17))   -- Huawei
-- QoSInformation ::= OCTET STRING (SIZE (4..255))  -- Cisco   <- YORUMDA
```

Bu, ayırt edilemezlik gerekçesini değiştiriyor. 17 oktetlik bir değer
`A1 13 04 11 …` diye sarmalandığında, sarmalayıcının içeriği **19 oktettir** ve
19 > 17. Yani sarmalayıcıyı açmayan bir çözücü için değer kısıt dışına çıkar.
Bu, iki okumanın ilk kez ayrılabildiği nokta.

### `qosRequested` ayırt edici ikilisi

`tools/qosProbe.py` ile üretildi. **İki dosya da aynı 17 baytlık ASCII değeri
taşıyor** (`QOSPROBE123456789`), tek fark sarmalayıcı:

| dosya | qosRequested baytları | doğru okuma | sarmalayıcıyı yok sayan okuma |
|---|---|---|---|
| `GGSN-qos-implicit.ber` | `81 11 <17B>` | 17 oktet ✓ | — |
| `GGSN-qos-explicit.ber` | `A1 13 04 11 <17B>` | 17 oktet ✓ | **19 oktet ✗ (SIZE dışı)** |

Değerler aynı olduğu için **EMM'in döndürdüğü ASCII decode doğrudan
karşılaştırılabilir**: ikisi de `QOSPROBE123456789` dönerse sarmalayıcı doğru
açılıyor; explicit varyantta değerin başında `04 11` görünürse EMM içeriği
deger sayıyor demektir. Çıkarım gerekmez, ölçüm yeterli.

**Not:** `GGSN-qos-explicit.ber` bizim encoder'ımızın bugün ürettiği biçim
değildir (paket-alanı ailesinde EXPLICIT nötrleniyor). Bilerek üretilmiş bir
sapmadır; kendi self-check'imiz onu işaretler, bu beklenen davranıştır.

### `CHFChargingDataTypes16` — en geniş tekil boşluk

EMM'in zengin yapı üzerine verdiği her verdict `IMPLICIT TAGS` başlıklı bir
modülden geldi. Başlığı mod söylemeyen 712 modülü temsil eden tek kabul edilmiş
dosya `IMSCDRS` ve o 42 düz yaprak — içinde CHOICE, SEQUENCE OF, SET yok.

`CHFChargingDataTypes16` aynı başlık, hepsi içinde: 269 yaprak, 9 CHOICE,
17 SEQUENCE OF, 7 SET, 10 çok-baytlı tag, 19 long-form uzunluk ve **4 adet
yazılı `EXPLICIT` keyword'ü** — `5906e76`'nın bilerek dokunmadığı ve hiçbir
yanıtın kapsamadığı site.

## 8b. CHF — açık kalan tek kodlama sorusu

EMM'in kendi `CHFChargingDataTypes16` şeması Yasin'den alındı (11.tur öncesi).
`ChargingRecord`'un **20 alanı da birebir aynı**; `NetworkFunctionInformation`,
`IPAddress`, `IPBinaryAddress`, `NodeAddress`, `PLMN-Id`, `NetworkFunctionName`,
`NetworkFunctionality` — hepsi aynı. Fark beyanda değil, yorumda; IMSCDRS'teki
durumun aynısı.

### İki turun birlikte söylediği

| tur | kök | EMM |
|---|---|---|
| 9 | `CHFRecord` (CHOICE) → `BF 81 48 …` | "ChargingRecord was probably not set" |
| 10 | `ChargingRecord` (çıplak SET) → `31 82 …` | "Invalid length 102 of …**nFunctionConsumerInformation**" |

10. tur hatası kaydın **içinden** geliyor ve belirli bir alan adlandırıyor. Yani
**çıplak SET kökü doğru**; sorun gerçekten `[3]`'ün içinde. (CHOICE sarmalayıcı
hipotezi bu veriyle çürüdü.)

### Kalan tek yorum farkı: yazılı `EXPLICIT`

Modül başlığı anahtar kelimesiz. 6., 9. ve 10. turlar böyle bir modülde hem
**alan** hem **tip** tag'inin EMM tarafında IMPLICIT okunduğunu gösterdi.
Sınanmamış tek site, alanın üzerinde **yazılı duran** `EXPLICIT` keyword'ü —
bizim kodumuzda keyword kazanır.

Modülün 4 yazılı `EXPLICIT`'inden **3'ü tam da düşen alanın içinde**:

```
networkFunctionIPv4Address [2] EXPLICIT IPAddress
networkFunctionIPv6Address [4] EXPLICIT IPAddress
networkFunctionFQDN        [5] EXPLICIT NodeAddress
```

### ✅ 11. tur sonucu — hipotez çürüdü, ama üç şey kesinleşti

**1. "Invalid length N" bir uzunluk değil, düşen düğümün BİTİŞ OFFSETİ.**

| tur | bildirilen | ölçülen |
|---|---|---|
| 10 | `Invalid length 102` | `[3]` düğümünün bitişi = **102** |
| 11 (`minimal`) | `Invalid length 406` | iç içe düğümün bitişi = **406** |

Aynı imza IMSCDRS'te de görülmüştü (590/114/411 = tam dosya boyutu). Artık
anlamı biliniyor: EMM nereye kadar okuyabildiğini söylüyor.

**2. EMM yazılı `EXPLICIT` keyword'ünü OKUYOR ve UYGULUYOR.** `collapsed`
varyantının hatası birebir şunu diyor:

```
networkFunctionIPv4Address choice is explicit and optional without ellipsis and
explicit tag of that choice has been parsed which means choice coming in data but
input data element in that explicit choice does not match any of the elements
```

Yani sarmalayıcıyı düşürmek **yanlış**; mevcut davranışımız (`kept`) doğru.
Anahtar kelimesiz modülde kural artık tam olarak şu: **varsayılan IMPLICIT,
yazılı keyword kazanır** — `resolveExplicit`/`readLeadingTag` bugün zaten böyle.

**3. Zengin yapı doğru çözülüyor.** `minimal` varyantı `[3]`'ü geçti ve
**6 seviye derine** indi:

```
ChargingRecord (SET)
 └ listOfMultipleUnitUsage (SEQ OF)
    └ usedUnitContainers (SEQ OF)
       └ pDUContainerInformation (SEQUENCE)
          └ servingNetworkFunctionID (SEQ OF)
             └ servingNetworkFunctionInformation  <- burada durdu
```

CHOICE, SEQUENCE OF, SET, iç içe constructed — hepsi 406 bayt boyunca doğru
çözüldü. **Anahtar kelimesiz sınıfın zengin yapıda çalıştığının ilk kanıtı.**

### Kalan sorun izole edildi

Düşen iki düğüm de aynı tip: `NetworkFunctionInformation`, tam alan setiyle.
Kayıtta bu tipten **6 örnek** var (biri `[3]`, beşi iç içe
`servingNetworkFunctionInformation`), bu yüzden tek birini boşaltmak yetmiyor.

### 12. tur — NFI bisect (`tools/chfNfiBisect.py`)

Altı örneğin **hepsi** aynı alan altkümesine indirgeniyor:

| dosya | korunan alanlar | `[3]` içeriği | bayt | SHA-256 |
|---|---|---|---|---|
| `CHF-A-nfi-min.ber` | `[0]` | `A3 03 80 01 09` | 2141 | `1b3d40e17e4fd225af800b4249819d62efb0a8b1efbecb62e967bd2ba3b27f5e` |
| `CHF-B-nfi-plain.ber` | `[0] [1] [3]` — yazılı EXPLICIT olmayanlar | `A3 1C 80 01 09 81 12 … 83 03 …` | 2291 | `4493d847a944ba90432e9ba00ec877650ee44086d82732fe5ab6fd93d7314a50` |
| `CHF-C-nfi-explicit.ber` | `[0] [2]` — tek EXPLICIT CHOICE alanı | `A3 0B 80 01 09 A2 06 80 04 …` | 2189 | `df064d2963925ee9c3ecd562b9f854636f852501dd989ac6b148f9ada4b385bf` |

**Sonuç: üçü de PASS.**

### 🏆 12. turun kazandırdığı — sınıfın ilk tam kabulü

`CHF-A` geçti: **2141 baytlık tam bir kayıt, 349 TLV, 6 seviye iç içe yapı**
baştan sona çözüldü. Bu, başlığı tagging modu söylemeyen **ve** zengin yapılı
(CHOICE + SEQUENCE OF + SET + iç içe constructed) bir modülün ilk tam kabulü.

O sınıfta **144 modül** var ve bugüne kadar hiçbiri geçmemişti; geçen üçü
(`IMSCDRS` 42, `FDRInput` 4, `Audit` 7 yaprak) hepsi düz yapıydı.

### Kusur mantıkla iki alana indi

| dosya | alanlar | sonuç |
|---|---|---|
| A | `[0]` | PASS |
| B | `[0] [1] [3]` | PASS |
| C | `[0] [2]` | PASS |
| (10. tur) | `[0..5]` tam set | FAIL |

`A ∪ B ∪ C = {0,1,2,3}` — hepsi çalışıyor. Geriye **test edilmemiş `[4]` ve
`[5]`** kalıyor. Kusur ikisinden birinde.

### 13. tur — hangisi ve neden

`[2]` ile `[4]` **aynı tipte** (`EXPLICIT IPAddress`) ve aynı şekli üretiyor
(`A2 06 {80 04 …}` / `A4 06 {80 04 …}`). `[2]` geçtiğine göre `[4]`'ün de
geçmesi beklenir — **eğer yapısal bir sebep varsa `[5]`'tedir**, çünkü tek
yapısal aykırılık orada:

```
NodeAddress ::= CHOICE { iPAddress [0] IPAddress, domainName [1] IA5String }
```

`iPAddress [0] IPAddress` — `IPAddress` bir CHOICE ve `[0]` üzerinde **yazılı
keyword yok**. Bizim kod "CHOICE üzerindeki tag her zaman EXPLICIT"
(X.680 8.3) diyerek fazladan bir `A0` katmanı ekliyor. Ama 11. tur şunu
gösterdi: anahtar kelimesiz modülde **varsayılan IMPLICIT, yalnızca yazılı
keyword kazanır.** `[0]`'da yazılı keyword yok.

| dosya | `[3]` içeriği | ne sorar | bayt |
|---|---|---|---|
| `CHF-D-only4.ber` | `A3 0B 80 01 09 A4 06 80 04 …` | `[4]` tek başına sağlam mı | 2189 |
| `CHF-E-only5.ber` | `A3 0D 80 01 09 A5 08 A0 06 80 04 …` | `[5]` mevcut kodlamayla | 2201 |
| `CHF-F-only5-flat.ber` | `A3 0B 80 01 09 A5 06 80 04 …` | `[5]` iç `A0` kaldırılmış | 2189 |

### ✅ 13. tur sonucu — teşhis kesin

| dosya | `[3]` içeriği | EMM |
|---|---|---|
| `CHF-D-only4` | `A4 06 80 04 …` | **PASS** |
| `CHF-E-only5` | `A5 08 **A0 06** 80 04 …` | **FAIL** (`Invalid length 61`) |
| `CHF-F-only5-flat` | `A5 06 80 04 …` | **PASS** |

**Kural:** anahtar kelimesiz bir modülde, **yazılı keyword taşımayan** bir tag
CHOICE tipli bir alanın üzerindeyse **IMPLICIT'tir** — X.680 8.3'ün "CHOICE
üzerindeki tag her zaman EXPLICIT" kuralı bu sınıfta geçerli değil.

```
NodeAddress ::= CHOICE { iPAddress [0] IPAddress, domainName [1] IA5String }
```

`[5]`'in kendi yazılı `EXPLICIT`'i onurlandırılıyor (dış `A5` kalıyor); içteki
`iPAddress [0]` üzerinde yazılı keyword yok ve bizim eklediğimiz `A0` katmanı
fazla.

### ⚠️ Düzeltme denendi ve GERİ ALINDI — koordineli değişiklik gerekiyor

Encoder tarafı çalıştı: `AsnField.choiceTagImplicit` bayrağı + `BerEncoderService`
içinde tag değiştirme ile `[3]` doğru şekli üretti (`A5 06 80 04 …`), ve
EMM-kanıtlı 10 modülün **hiçbirinin baytı değişmedi**.

Ama `BerVerifier`'ın walker'ı **aynı X.680 8.3 varsayımını taşıyor**. Yeni
baytlarda 7 hata üretti:

```
ERROR [walker] …networkFunctionFQDN.iPAddress:
    EXPLICIT tag [0] on 'iPAddress' should wrap exactly one TLV but wraps 0
```

Sonuç: `AllModulesRoundTripTest` ve `ShippedFieldRulesRoundTripTest` düştü,
`vErrors` 45 → 67. Değişiklik geri alındı; repo `371 test / 0 failure`,
`vErrors 45` durumunda.

### ✅ UYGULANDI — commit `e940f0c`

Beş parça birlikte gitti:

| # | dosya | ne yapıldı |
|---|---|---|
| 1 | `AsnField` | `choiceTagImplicit` bayrağı — `choice && !repeated && yazılı keyword yok && tagNumber!=null && mode==UNSPECIFIED` |
| 2 | `AsnFieldTreeResolver` | bayrak iki builder çağrısında da set ediliyor |
| 3 | `BerEncoderService` | `retagOutermost` — sarmalamak yerine içeriğin dış tag'ini değiştiriyor |
| 4 | `BerVerifier.walkChoice` | bu alanlarda sarmalayıcı aramıyor (`onlyChild` çağrılmıyor) |
| 5 | `TagShapeRule` | şekil alternatiften geldiğinde "container ama tag primitive" uyarısı vermiyor |

**Sonuç:**

| ölçü | önce | sonra |
|---|---|---|
| test | 371 | **372** (yeni regresyon testi) |
| failure / error | 0 / 0 | **0 / 0** |
| `vErrors` | 45 | **45** |
| `vWarnings` | 225 | **229** |
| bayt değişen modül | — | **1** (`CHFChargingDataTypes16`, 2368 → 2324) |

`[3]` artık EMM'in kabul ettiği şekli üretiyor:

```
A3 34 80 01 00 81 12 … A2 06 80 04 … 83 03 … A4 06 80 04 … A5 06 80 04 …
                                                            ^^^^^^^^^^^ A0 katmani gitti
```

**Regresyon kapısı tuttu:** 12 EMM-kanıtlı modülün tamamı + `TAP0309`/`TAP-0309`
bayt bayt aynı, referans yakalama testi geçiyor.

**+4 uyarı bilinçli.** Implicit tagging CHOICE alternatifinin kimliğini siler,
bu yüzden walker o alt ağaçları "doğrulanamadı" diye işaretliyor — "doğru" diye
değil. Bu, o alanların gerçek durumu.

**Not:** Kural 5 modül / 21 alan için geçerli ama bu üretimde yalnızca CHF
alanları dolduruldu; diğer 4 modülde ilgili alanlar OPTIONAL ve üretilmedi.

**Etki alanı ölçüldü:** 21 alan / 5 modül (`UNSPECIFIED`). Aynı kalıp
`IMPLICIT TAGS` modüllerinde 163 alan / 31 modülde var ve **bilerek
dokunulmuyor** — MMTel hem EMM hem referans yakalamayla kanıtlı, o sınıf için
kanıt yok.

SHA-256: D `ab0b0f225090fb2e25fcae0884a5296d22cd3a785168f81380d6790ace495b3a`,
E `07dc5e888a70884cd89ed9d0edd82ce5c99e5cc210f492bbd47dc245ca4657e6`,
F `ce077c5a5cda8f3112687e13765ce43efaf713aebb66f5e02fd35cac002c334a`

### (eski) 11. tur ikilisi (`tools/chfExplicitProbe.py`)

| dosya | `[3]` içeriği | bayt | SHA-256 |
|---|---|---|---|
| `CHF-explicit-kept.ber` | `A2 06 {80 04 …}` — sarmalayıcı korunur | 2449 | `ef9f5ad3e38eacbb0a8ce2973dd2f17cca4e83d908eaf0f9338b905dcc1b3d82` |
| `CHF-explicit-collapsed.ber` | `82 04 …` — sarmalayıcı düşer | 2441 | `0fec613322520647e680f12e70ffc8ef9ef09fd215f98780cae240c8284f94a0` |
| `CHF-nfci-minimal.ber` | `A3 03 80 01 09` — sadece zorunlu `[0]` | 2398 | `f85e84f6adfdeb4e7235b3cec7af2f20aa224debd4af655bb48ba3d3b1e020c9` |

`NetworkFunctionInformation`'ın **altı alanından yalnızca `[0]` zorunlu**, kalan
beşi OPTIONAL. `minimal` varyantı bu yüzden geçerli bir kayıttır ve hatayı
ikiye böler:

| minimal | anlamı |
|---|---|
| **PASS** | `[3]`'ün kendisi ve kökten oraya kadar olan her şey doğru → kusur beş opsiyonel alandan birinde, sonraki tur bisect edilir |
| **FAIL** | Sorun `[3]`'ün yapısında ya da ondan öncesinde → EXPLICIT hipotezi de düşer, daha yukarı bakılır |

`collapsed` **geçerse:** anahtar kelimesiz modülde EMM yazılı `EXPLICIT`'i de yok
sayıyor → kural "başlık mod söylemiyorsa her şey IMPLICIT" haline gelir ve
`resolveExplicit` içindeki keyword önceliği bu sınıf için kalkar.
**Geçmezse:** sorun bu değil; sıradaki şüpheli değer düzeyinde (IPv6 alanına
IPv4 alternatifi, FQDN alanına adres alternatifi seçmemiz).

## 9b. 16.08.2026 — EMM beklenirken yapılan denetim (BER üretimine dokunulmadı)

14 dosyanın yanıtı beklendiği için kodlayıcıya, TLV yazıcıya ve tagging
mantığına **dokunulmadı**. Denetim `.txt` yolu, yapay zeka tesisatı ve test
kapsamı üzerinde yapıldı. Denetim sonunda `vErrors 45 / vWarnings 229 /
17 strict-fail modül` değişmeden duruyor — BER tarafının bozulmadığının ölçüsü.

### ⚠️ Kaybolan çalışma (15.08.2026 gecesi)

`target/surefire-reports` altında **23:34'te geçmiş ama kaynakta olmayan**
6 test sınıfı / 67 test bulundu. `git reflog`'un son kaydı `reset: moving to
HEAD`; commit'lenmemiş çalışma geri alınmış, git nesnelerinde de yok.

Kanıt sadece raporlar değil: 8080'de duran 23:11 derlemesi, kaynakta bulunmayan
`POST /api/cdr/generate/manifest` ucunu servis ediyordu. Sözleşmesi o JVM'den
çıkarıldı ve yeniden yazıldı.

Kaybolan ve bugün geri getirilen dört üretim eksiği: manifest ucu, geçici dosya
temizliği, `/generate`'in alansız yapıyı reddetmesi, Gemini katmanının testleri.

### 🔴 Türkçe-locale hatası — `TbcdCodec` (ölçüldü ve düzeltildi)

`isLikelyTbcd` alan adını `toLowerCase()` ile katlıyordu, locale vermeden.
Servis Türkçe makinelerde koşuyor ve orada `'I'` → noktasız `'ı'`:

```
"servedMSISDN".toLowerCase()   -> "servedmsısdn"   (tr_TR)
              .contains("msisdn") -> false
```

`i` taşıyan her token — `msisdn`, `imsi`, `imei` — sessizce ölüydü;
`callingparty` / `otherparty` çalışmaya devam ettiği için arıza kısmi görünüyordu.

| ölçü | değer |
|---|---|
| aday OCTET STRING yaprak (77 modül) | 194 |
| `tr_TR` altında tanınan | 68 |
| **davranışı değişen alan / modül** | **126 / 60** |

Üç çağıran da etkileniyordu: `FieldValueGenerator` (paketlemiyordu),
`FieldValueValidator` (TBCD dalını hiç çalıştırmıyordu), `CdrPromptBuilder`
(modele "TBCD'ye paketle" demiyordu). İngilizce bir CI'da test yeşil kalır,
üretimde yanlış davranırdı.

**Düzeltme:** `toLowerCase(Locale.ROOT)` (+ `toUpperCase(Locale.ROOT)`).
`TbcdCodecLocaleTest` katlamayı açıkça Türkçe locale altında koşturuyor.

**Doğrulama:** `servedIMSI` artık gerçek TBCD üretiyor —
`82066146401243F0` çözüldüğünde `286016640421340`, yml'deki IMSI desenine uygun.

**EMM için önemi:** düzeltme abone-numarası alanlarının baytlarını değiştirir.
21 doğrulama örneği içinde etkilenenler: `MMTelChargingDataTypes` 1 alan,
`GGSNTurkcellCdrR7` 3, `LTE-R10` 3, `TurkcellCDRCCNCS5` 1, `CHAD` 1,
`CHFChargingDataTypes16` 8. Etkilenmeyenler: `IMSCDRS`, `CGSN40ber`,
`SMSCBerCdr`, `FDRInput`, `Audit_Record_Collection_St`, `TAP-0309`,
`IMSChargingDataTypes`. Değişim **yalnızca değer baytlarında**, tagging
şeklinde değil — `ReferenceCaptureConformanceTest` ve 808 modül round-trip
geçiyor. 14 dosyanın yanıtı gelene kadar tek `git revert` ile geri alınabilsin
diye kendi commit'inde tutuldu.

### `.txt` yolu — ölçülen durum ve kapatılan açıklar

802 modül × 3 kayıt üretilip incelendi: 109.857 hücre, **802/802 modülde satır
genişliği tutarlı**, ASCII dışı 0, ayraç sızıntısı 0, üç kaydı da birebir aynı
olan modül 0. Temel yapı sağlam. Kapatılanlar:

| açık | önce | sonra |
|---|---|---|
| geçici dosya | her indirme `/tmp`'de dosya bırakıyordu (878 dosya / 3,5 MB ölçüldü) | `/generate` bellekte üretiyor, dosya hiç açılmıyor |
| satır sonu | `System.lineSeparator()` — Windows'ta CRLF | sabit `\n` (Multicloud'un kabul edildiği biçim) |
| alansız yapı | `/generate` 200 + boş satırlı dosya | 400, `/generate-ber` ile aynı yanıt |
| `|` ve satır sonu | sessizce kolonu/kaydı bölüyordu | reddediliyor, mesaj kolonu adlandırıyor |
| ASCII dışı karakter | `UnmappableCharacterException: Input length = 1` | alanı ve karakteri adlandıran red |

Kaçış yerine **red** seçildi: bir kaçış sözleşmesi tel üzerine hiçbir kabul
edilmiş dosyanın taşımadığı baytlar koyardı; `Multicloud` hiç kullanmıyordu.

`POST /generate/manifest` geri geldi: metin ve kolon haritası **tek üretimden**
dönüyor, böylece konuma göre okuyan tüketici hangi genişlikte dosya tuttuğunu
ayırt edebiliyor.

### 🟠 Ölçülen ama BİLEREK dokunulmayan iki kusur

Rastgele üreticinin ürettiği değer, AI yolunu koruyan `FieldValueValidator`'a
soruldu. **34.401 yapraktan 678'i kendi doğrulayıcısından geçemiyor**
(249'u NULL alan, gürültü; ~429'u gerçek). İki kök neden:

**(A) Kural eşleşmesi ASN.1 tipine bakmıyor** (`AiConfigProperties.findRuleFor`,
6+ karakterli ifadeler alt dize olarak da aranıyor):

```
recipAddressTon             (INTEGER SIZE(3))  -> "ipAddress" kurali
camelDestinationNumberType  (ENUMERATED)       -> "calledNumber" kurali
```

Sonuç çift: rastgele üretici anlamsız değer koyuyor (`recipAddressTon = 62128`,
oysa TON tek haneli), **ve** AI doğru enum değerini üretse bile telefon
regex'ine takılıp reddediliyor. AI o alanlarda hiç çalışamıyor.

**(B) Kural deseni alanın SIZE'ına sığmayınca değer kırpılıyor**
(`FieldValueGenerator`, `value.substring(0, effectiveMax)`):

```
submitDate          IA5STRING(SIZE(6)) -> "20260113" kirpilip "202601"
callingPartyNumber  IA5STRING(SIZE(6)) -> "055598"
```

Kırpılan değer artık ne tarih ne telefon numarası. Doğru davranış, deseni
sığdıramıyorsa alana uygun bir değer üretmek olurdu.

Ek olarak `servedIMEISV` gibi bazı alanlar locale düzeltmesinden sonra da TBCD
üretmiyor: `toOctetStringContent` düz rakam dizisinin uzunluğunu **bayt** sayısı
ile karşılaştırıyor (15 > 8), oysa 15 rakam TBCD'de 8 bayta paketleniyor.
Aynı bayt/karakter karışıklığı ailesinden.

**Üçü de `FieldValueGenerator`'ı değiştirmeyi gerektiriyor, yani EMM'de
incelenen dosyaların baytlarını.** 14 dosyanın yanıtından sonra ele alınacak.

### Test durumu

| | önce | sonra |
|---|---|---|
| test | 422 | **497** |
| başarısız / hata | 0 / 0 | **0 / 0** |
| `vErrors` / `vWarnings` | 45 / 229 | **45 / 229** |

Yeni sınıflar: `TbcdCodecLocaleTest`, `TextManifestAndTempFileTest`,
`UnresolvableStructureTextRefusalTest`, `CdrPromptBuilderTest`,
`GeminiFieldValueProviderTest`, `GeminiResponseSchemaFactoryTest`.
Gemini adaptörünün test kapsamı 0'dan gerçek bir HTTP sözleşmesine çıktı
(`MockRestServiceServer`, ağa çıkmadan).

---

## 8. Bağımsız çözücü turu — `asn1tools` (12.08.2026, EMM'den bağımsız)

7. turun yanıtı beklenirken yapıldı. Amaç `coverage-validation-plan.md`'nin
0-2. adımları: şema bilen, bizim alan ağacımızı paylaşmayan bir oracle.

`asn1tools 0.167.0`, **izole bir venv'de** denendi; `pom.xml`'e hiçbir bağımlılık
eklenmedi.

### Negatif kontrol — oracle ayırt ediyor mu (önce bu)

| bilerek bozuk girdi | `openssl asn1parse` | `asn1tools` |
|---|---|---|
| kesik dosya | RED | RED |
| bozuk uzunluk baytı | RED | RED |
| **BOOLEAN uzunluk 3** (`01 03 01 01 FF`) | **KABUL** ❌ | **RED** ✅ |
| yanlış tag (`[0]` yerine `[7]`) | — | RED ✅ |

Üçüncü satır: o bayt dizisi **EMM'in LTE-R10'u 2. turda reddettiği hatanın ta
kendisi.** openssl geçiriyor, asn1tools yakalıyor. Bu ölçüm `primitive-length`
kuralını doğurdu (commit `e93e7bf`).

### ⚠️ Oracle'ın kör noktası — "decode başarılı" kriter DEĞİLDİR

Tüm üyeleri OPTIONAL olan bir SEQUENCE'te `asn1tools` **hiçbir şeyi
tüketmeden başarı döndürüyor**:

| girdi | sonuç |
|---|---|
| geçerli kayıt | `{'a':'ABC','b':7}` |
| **saf çöp** (`30 06 DEADBEEFCAFE`) | **`{}` — KABUL**, üstelik "8/8 bayt tüketildi" |
| yanlış tag | `{}` — KABUL |

`decode_with_length` de yardımcı olmuyor; dış SEQUENCE'in beyan ettiği uzunluğu
tüketilmiş sayıyor. Bu bir teori değil, bizim dosyamızda gerçekleşti:
`IMSCDRS-TokensCSCF` (527 B, 34 alan) "başarıyla çözüldü" ama **0 alan** döndü ve
re-encode 2 bayta indi.

Bu korpusta çoğu CDR gövdesi all-OPTIONAL (medyan 19 yaprağın 19'u OPTIONAL), yani
kör nokta istisna değil kural. **Geçerli kriter:** decode → re-encode → bayt bayt
karşılaştır, ya da okunan alan sayısını yazdığımızla karşılaştır.

### Soru 1-2: ne derlendi, ne derlenemedi

| | modül | oran |
|---|---|---|
| derlendi | **201** | %24,9 |
| derlenemedi | **607** | %75,1 |
| — `ParseError` | 492 | |
| — `CompileError` | 115 | |

Derlenememe sebepleri **bizim kodumuzla ilgili değil**, vendored ASN.1'in X.680'e
uymaması:

- **identifier'da alt çizgi** — `acmeFlowId_FS1_F`, `eVENT_RECORD`,
  `beforeRefill_accountFlags`. X.680 identifier'da `_` kabul etmez.
- **modül adında alt çizgi** — `Audit_Record_Collection_St` daha 1. satırda düşüyor.
- **duplicate type** (`CompileError`) — `Type 'Epf3Service' already defined`,
  `Type 'UsedServiceUnit' already defined`. Planın Katman C negatif kontrolünün
  beklediği sınıf.

Derlenenler arasında kritik olanlar var: `MMTelChargingDataTypes`, `IMSCDRS`,
`TAP0309`, `TAP-0309`, `FDRInput`, `NRTRDEINFLOWV0201`.

### Soru 3: decode sonuçları (doğru kriterle)

| modül | sonuç | ayrıntı |
|---|---|---|
| `FDRInput` | **TAM** | 4 alan okundu, **re-encode bayt bayt aynı** |
| `TAP-0309` | **TAM** | bayt bayt aynı |
| `NRTRDEINFLOWV0201` | **TAM** | bayt bayt aynı |
| `IMSCDRS-TokensCSCF` | SESSİZ | 0 alan okudu (yukarıdaki kör nokta) |
| `MMTelChargingDataTypes` | RED | `interOperatorIdentifiers`: `30` bekledi, `80` buldu |
| `IMSCDRS` (`Cdrs` kökü) | RED | tag uyuşmazlığı — 5906e76'nın IMPLICIT kuralı |
| `NRTRDETadigidImsiLookup` | RED | kök tip adlandırma farkı, gerçek hata değil |

**`FDRInput`'un bayt bayt aynı çıkması 7. turu doğrudan ilgilendiriyor:** bağımsız,
standarda uygun bir çözücü bizim `61 37 30 35 …` kodlamamızı aynen okuyor ve aynı
baytları geri üretiyor. Yani tip-seviyesi `[APPLICATION n]` okumamız X.680'e
uygun. EMM bunu reddederse **sapan taraf EMM'dir, biz değiliz** — ki bu, alan
seviyesinde IMSCDRS'te zaten bir kez yaşandı.

### En önemli sonuç: sapma bizde değil, şema ile gerçeklik arasında

`MMTelChargingDataTypes` reddedildi çünkü şema
`interOperatorIdentifiers [14] EXPLICIT …` diyor ve biz sarmalayıcıyı kaldırıyoruz
(`isVerifiedExplicitNeutralizationFamily`). Buraya kadar "bizim workaround'umuz
standart dışı" denebilirdi.

**Ama aynı çözücüye EMM'in kabul ettiği gerçek şebeke yakalamasından bir kayıt
verildi ve o da reddedildi** (`list-Of-Calling-Party-Address`, aynı sınıf konum).

Yani gerçek Ericsson ekipmanının ürettiği, EMM'in üretimde işlediği baytlar da
yazılı ASN.1'e uymuyor. Sonuç:

> Bu aile için **yazılı şemaya uygunluk yanlış hedeftir.** Nötrleştirme
> workaround'u standarttan sapma değil, gerçekliğe uyumdur.

Bu, `asn1tools`'un bu projedeki temel sınırını da tanımlıyor: **EMM'in yerine
geçemez**, çünkü en az bir ailede standart ile gerçeklik ayrışıyor ve önemli olan
gerçekliktir.

### Soru 4: 805 yapı için gerçekçi bir oracle mı?

**Genel doğrulayıcı olarak hayır, hedefli regresyon aracı olarak evet.**

Üç bağımsız kısıt üst üste biniyor: %25 derleme oranı, all-OPTIONAL körlüğü, ve
en az bir ailede şema-gerçeklik ayrışması. Bu haliyle 805 yapıya "geçti/kaldı"
notu veremez.

Değer ürettiği yer dar ama gerçek:

1. **Hata sınıfı avı** — `primitive-length` bu turda böyle çıktı. Bulunan her
   sınıf kalıcı bir self-check kuralına dönüşür ve bir daha EMM turu yakmaz.
2. **Bayt bayt round-trip** — `FDRInput`, `TAP-0309`, `NRTRDEINFLOWV0201` gibi
   temiz derlenen yapılarda gerçek doğrulama; hedef seçimi için kullanılabilir.
3. **Şema kusuru envanteri** — 607 derlenemeyen modülün sebebi çoğunlukla
   vendored ASN.1'in bozukluğu. Bu, "hangi modüller zaten şema olarak kusurlu"
   sorusunun ilk ölçülmüş cevabı.

Ölçüm betikleri kalıcı değil (scratchpad'de). Tekrar gerekirse: şemaları
`datastructure.json`'dan dışa aktar, izole venv'de `asn1tools` ile derle,
`manifest.tsv`'deki kök tiple decode et, **re-encode karşılaştırmasıyla** notla.
