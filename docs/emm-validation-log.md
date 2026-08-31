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
| `TurkcellImsOmm` (PostCcnCdr) | BER | kök tip bağlaması çalışıyor (15. tur) |
| `TAP-0309` (CallEventDetail) | BER | **TAP ailesinin ilk kabulü**, 8 seviye derinlik (15. tur) |
| `TAP0309` (CallEventDetail) | BER | TAP'in keyword'süz ikizi (15. tur) |
| `EnrichedVerazCdr` | BER | 29 KB, 26 long-form uzunluk; 7. kural doğrulandı (15. tur) |
| `CCNCS55_UpdatedCCR_CCN` | BER | 7. kural, ikinci bağımsız soyda doğrulandı (15. tur) |
| `GSN50` | BER | **çözülmüş `IMPORTS`** — ithal edilen `SET` doğru çözüldü (15. tur) |
| `IMS-R8-2009-03` (ATSRecord) | BER | boş `SET` gövdesi `31 00`; iç içe koleksiyonun kayıp orta katmanı (`30`) tamamlandı (16-17. tur) |

## 2. EMM ile kanıtlanan tagging kuralları

1. `[n] EXPLICIT SEQUENCE OF <CHOICE>` → iç universal SEQUENCE **yok**
2. `[n] EXPLICIT <SET>` (skaler) → sarmalayıcı **yok**
3. `[n] EXPLICIT <BOOLEAN>` → **IMPLICIT** (`AB 03 01 01 FF` reddedildi, `8B 01 FF` istendi)
4. Başlığı `IMPLICIT TAGS` demeyen modülde **alan** tag'i → **IMPLICIT**
5. Başlığı `IMPLICIT TAGS` demeyen modülde **tip** tag'i de → **IMPLICIT** (9. tur, commit `f5ce531`)
6. **Yazılı keyword taşımayan** bir tag CHOICE tipli **alandaysa** → **IMPLICIT** (13. tur, commit `e940f0c`; 19. turda `IMPLICIT TAGS` başlıklı modülleri de kapsayacak şekilde genişletildi). X.680 8.3'ün istisnası.
7. **Yazılı `EXPLICIT` keyword'ü yalnızca CHOICE tipli alanda sarmalayıcı üretir.** SEQUENCE / SET / primitive hedeflerde bağlam tag'i universal tag'in yerine geçer — modülün soyundan bağımsız (14. tur, commit `2006841`). 2. ve 3. kural bunun iki özel hâliymiş.
8. 6. kural **alan** tag'leri içindir; **tip** üzerine yazılmış bir CHOICE tag'i (`CallEvent ::= [APPLICATION 1] CHOICE`) X.680 8.3'te kalır — daha doğrusu, EMM onun için henüz **hiçbir okumayı kabul etmedi** (19. tur: EXPLICIT de IMPLICIT de aynı sözlerle reddedildi). 4./5. kuralın alan ve tip için birlikte gittiği yerde 6. kural ayrışıyor. Açık soru, bkz. Bulgu 10.

Hepsini tek cümle açıklıyor: **yazılı `EXPLICIT` dışında her şey IMPLICIT.**
19. tura kadar bu cümlenin sonunda "CHOICE hariç" vardı; `SDPCCR` onu da
kaldırdı — bir CHOICE alanı da, üzerinde keyword yazmıyorsa, IMPLICIT
kodlanıyor. Geriye tek istisna kaldı: tip üzerine yazılmış CHOICE tag'i
(8. kural), ki orada henüz doğru cevabı bilmiyoruz.

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
| 15 | 14. turun 6 reddi + koşulamayan `IMS-R8-2009-03` (17.08.2026) | **6 PASS · 1 red** (18.08.2026) — üç düzeltmenin üçü de doğrulandı |
| 16 | `IMS-R8-2009-03-A` (boş SET kodlaması 31 00, tek değişken) | **red — ama `[25]` doğrulandı** (19.08.2026): `recordExtensions` hatası kalktı, EMM 513 → **2065**'e ilerledi, yeni hata `list-of-Call-Transfer-Info` |
| 17 | `IMS-R8-2009-03-A-round17` (iç içe koleksiyonun orta katmanı, tek değişken) | **PASS** (19.08.2026) — `IMS-R8-2009-03` bu sınıfın ilk tam kabulü |
| 18 | Geniş korpus taraması — 15 dosya, EMM'den hiç geçmemiş/kardeş-olmayan yapılar (19.08.2026) | **12 PASS · 3 red** (19.08.2026) |
| 19 | 18. turun 3 reddi: `NRTRDEINFMSInput_Intermediate` (kök CHOICE tag'i IMPLICIT), `SDPCCR` (usageThresholds sökülmüş izolasyon), `ABSSDPXML` (kök tipi YAML'dan bağlandı) — 20.08.2026 | **1 PASS · 2 red** (20.08.2026): `ABSSDPXML` **PASS**; `SDPCCR` **ilerledi** 7411 → **9980** (yeni alan `chargingContextOutputFields`); `NRTRDE` **aynı hata**, kök CHOICE tag'i için IMPLICIT okuması da çürüdü |
| 20 | `SDPCCR` (rule 9 retag) + `NRTRDEINFMSInput_Intermediate` A/B/C (Moc/Mtc/Gprs, çıplak alternatif) — 20.08.2026 | **NRTRDE 3/3 PASS** — tip-seviyesi CHOICE tag'i tel üzerinde hiç yok (Bulgu 10 kapandı); `SDPCCR` aynı düğümde 6087, CHOICE etiketlemesi suçlu değil |
| 21 | `NRTRDEINFMSInput`+`_Intermediate` (varsayılan üretim yolu) + `SDPCCR` (usageThresholds CHOICE alanları sökülmüş) — 21.08.2026 | **NRTRDE 2/2 PASS**; `SDPCCR` çok daha derine ilerledi → `appliedProductFees…usageCounterChange.usageCounterMoney` @9437 |
| 22 | `SDPCCR` tam dosya (`UsageCounterType → usageCounterMoney` bağlaması) | **gönderilmedi** — bağlama type-global olduğu için EMM'in kabul ettiği `UsageCounter.*` yapısını da bozuyordu, geri alındı |
| 23 | — | (numara kullanılmadı) |
| 24 | `SDPCCR` (`productFeeUsageCounters` EXPLICIT wrapper formuna çevrildi, 28 site) | **yanıt bekleniyor** |
| 25 | **Stress set — 18 dosya**, hiç sınanmamış 18 davranış imzası (21.08.2026) | **16 PASS · 2 red** — `DbLookupTable_IA5` (Bulgu 8 tekrar), `ATS_ONDER` (duplicate tag) |
| 26 | `DbLookupTable_IA5` (kök tipi `DBDataRecord`'a bağlandı) | **PASS** — Bulgu 8'in ikinci bağımsız kanıtı |
| 27 | `ATS_ONDER` (`dialedPartyAddress [203]` sökülmüş izolasyon) | **PASS** — C-sınıfının tek suçlu olduğu kanıtlandı |

### 18. turda gönderilen dosyalar — geniş korpus taraması

Rastgele değil: 805 modül otomatik profillendi (kök şekli, tagging modu,
CHOICE/koleksiyon/SET varlığı, çok-baytlı tag, uzun-form uzunluk, self-check
err/warn), EMM'den geçmiş 25 yapı + 19 bilinen şema-bozuk modül + 16 aynı
davranış sınıfının kardeşi elendi, kalan 742 aday 38 farklı davranış imzasına
ayrılıp en nadir/zengin temsilciler seçildi. Ayrıntılı seçim gerekçesi
sohbet geçmişinde; SHA'lar gönderimden önce yazıldı ve self-check ile
(STRICT mod) ayrıca yeniden doğrulandı — 15/15 dosyada `errors=0`.

| # | dosya | bayt | self-check | EMM sonucu | SHA-256 |
|---|---|---|---|---|---|
| 1 | `EMM-Context-Specific` | 31 | 0/0 | ✅ PASS | `676af89d51254c95…` |
| 2 | `GPRS-Charging-Extensions` | 769 | 0/0 | ✅ PASS | `4fbaebb65b6e5ec9…` |
| 3 | `NRTRDEINFMSInput_Intermediate` | 206 | 0/0 | ❌ **red** | `5b2023e3a1d30ac8…` |
| 4 | `Poc` | 280 | 0/0 | ✅ PASS | `821aba227394e06c…` |
| 5 | `NRTRDEInOperatorLookup` | 142 | 0/2 | ✅ PASS | `0980303f2fe297a0…` |
| 6 | `SDPCCR` | 12480 | 0/0 | ❌ **red** | `3809005bbc0c38fc…` |
| 7 | `CCN_EC22` | 519 | 0/0 | ✅ PASS | `ee2af76b75ce55a2…` |
| 8 | `UAGRecordsBer` | 3248 | 0/0 | ✅ PASS | `8fd1f68731dca29c…` |
| 9 | `HuaweiGSN50` | 1418 | 0/0 | ✅ PASS | `4b318046637394ae…` |
| 10 | `CDRDatamartSDPColl` | 2647 | 0/2 | ✅ PASS | `4ebb792636ca559e…` |
| 11 | `CDRDatamartAIR` | 1700 | 0/0 | ✅ PASS | `5c3e65c862ec89cd…` |
| 12 | `ABSSDPXML` | 54 | 0/2 | ❌ **red** | `3b3c9f7b42504…` |
| 13 | `BalanceUpdateMatched` | 92 | 0/0 | ✅ PASS | `884d0fe860064c11…` |
| 14 | `BgwAuditTrail_Output` | 116 | 0/2 | ✅ PASS | `6daa8297453a7a58…` |
| 15 | `BroadSoft` | 4383 | 0/0 | ✅ PASS | `7d7b5da6020cb934…` |

**EMM'in birebir mesajları:**

```
Beklenen structure: SDPCCR.SDPCreditControlRecord
Failed to decode received data.
A block of 'SDPCCR.ber', originating from SDPCCR, was corrupt (record #0).
Invalid length 7411 of field
  "SDPCCR.SDPCreditControlRecord.creditControlRecord.bonusAdjustment.usageThresholds.[0]"

Beklenen structure: NRTRDEINFMSInput_Intermediate.CallEvent
Failed to decode received data.
A block of 'NRTRDEINFMSInput_Intermediate.ber', originating from
  NRTRDEINFMSInput_Intermediate, was corrupt (record #0).
The type: NRTRDEINFMSInput_Intermediate.CallEvent was probably not set
and is not optional

Beklenen structure: ABSSDPXML.SnapshotData
Failed to decode received data.
A block of 'ABSSDPXML.ber', originating from ABSSDPXML, was corrupt (record #0).
Invalid length 54 of field ""
```

**Kapsam:** 12/15 PASS ilk denemede — hiçbiri daha önce EMM'e gönderilmemiş
yeni yapılardı. 3 red, üçü de farklı sınıftan; aşağıda teker teker teşhis
ediliyor.

##### 🔴 Bulgu 6 — kök-seviyeli CHOICE tag'i, `buildRootTagCarrier`'da `choiceTagImplicit` hiç hesaplanmıyor

`NRTRDEINFMSInput_Intermediate`: `CallEvent ::= [APPLICATION 1] CHOICE { moc Moc OPTIONAL, mtc Mtc OPTIONAL, gprs Gprs OPTIONAL }` — modül başlığı keyword'süz (`DEFINITIONS ::=`), tag'in kendisi de `EXPLICIT`/`IMPLICIT` yazmıyor. Bu tam olarak 13. turda kanıtlanan **6. kuralın** (keyword'süz CHOICE tag'i → IMPLICIT) senaryosu — ama orada tag bir **alana** yazılıydı, burada **tipin kendisine** yazılı.

Alan-seviyesi kod (`attachChildren`/`parseFieldLines`) `choiceTagImplicit(tag, repeated, choiceElement, taggingMode)`'u çağırıp sonucu `AsnField.choiceTagImplicit`'e yazıyor. **`buildRootTagCarrier` bu çağrıyı hiç yapmıyor** — Lombok builder'da alan atlanınca `boolean` varsayılan olarak `false` kalıyor. Sonuç: `BerEncoderService.wrapInTlv`'deki `if (choice && field.isChoiceTagImplicit())` dalı asla tetiklenmiyor, kod hep EXPLICIT çift-sarmalamaya düşüyor.

Gönderilen bayt: `61 81 CB  63 81 C8 ...` (dış `[APPLICATION 1]` EXPLICIT sarıyor, iç `[APPLICATION 3]` = Moc alternatifinin kendi tipi tag'i). Olması gereken (rule 6 uygulanırsa): dış `[APPLICATION 1]` **IMPLICIT** olarak Moc'un kendi `[APPLICATION 3]` tag'inin YERİNE geçmeli, yani tek bir `61 ...` — iç `63 ...` hiç yazılmamalı.

**Kapsam ölçüldü:** korpusta yalnızca **2 site**, ikisi de bu ailenin kardeşi — `NRTRDEINFMSInput` ve `NRTRDEINFMSInput_Intermediate`. Başka hiçbir modülde kök CHOICE'un kendisi tag taşımıyor. 26 PASS'in hiçbiri bu koşulu tetiklemiyor (regresyon riski yok).

##### 🟡 Bulgu 7 — `SDPCCR`: alan-alan doğrulandı, kesin neden bulunamadı

`SDPCCR.SDPCreditControlRecord.creditControlRecord.bonusAdjustment.usageThresholds.[0]`, offset 7411'de (düğümün bitiş ofseti, 11. tur kuralı). `BonusAdjustment`'ın 11 alanı (`[0]`-`[10]`), `UsageThreshold`'un 6 alanı (`[0]`-`[5]`), `UsageCounterType` CHOICE'unun EXPLICIT sarmalaması — hepsi şema metniyle VE bu projenin kanıtlanmış tüm kurallarıyla (4,5,6,7) birebir tutarlı çıktı. `UsageThreshold` bir SET değil SEQUENCE (sıralama sorunu yok), IMPORTS yok (mod-sınırı sorunu yok), iç içe koleksiyon yok (bugünkü düzeltmenin kapsamı dışında).

Bayt-seviyesinde hiçbir tutarsızlık bulunamadı — self-check de (STRICT) 0 hata veriyor. Bu, `IMSCDRS`/`CHF`'in yaşadığı "şema ile gerçeklik ayrışması" sınıfına benziyor olabilir (vendörlenen metin EMM'in gerçek şemasıyla birebir örtüşmüyor) ama kanıtlanmadı. **Önerilen sonraki adım:** `tools/dropBerField.py` ile `usageThresholds [10]`'u dosyadan çıkarıp geri kalan ~12.4 KB'ın bağımsız olarak doğru olduğunu kanıtlamak — `IMSCDRS`/`CHF`'te işe yarayan izolasyon tekniği.

##### 🔴 Bulgu 8 — kök seçim sezgiseli: adsız `SEQUENCE OF` sarmalayıcısını atlıyor

`ABSSDPXML`: `SnapshotData ::= SEQUENCE OF SnapshotRecord` — EMM `SnapshotData`'yı (dış sarmalayıcı) bekliyor, ama `StructureParserService.selectRootTypeName` **bilinçli bir tasarım kararıyla** iç eleman tipini (`SnapshotRecord`) kök seçiyor:

> *"'SEQUENCE OF X' şeklindeki referanssız bir alias... X'in kendisini de geçerli bir kök adayı yapar... asıl veri kaydı X'tir."*

Bu varsayım artık EMM tarafından **yanlışlığı kanıtlandı**: EMM dış `SEQUENCE OF` sarmalayıcısını (`30 <len>` — SnapshotRecord'ların bir listesi) bekliyor, biz tek bir çıplak `SnapshotRecord` gönderdik. `Invalid length 54` = tüm dosya boyutu — EMM daha ilk tag'de takıldı (IMSCDRS'in ilk turlarındaki "N = tam dosya boyutu" deseniyle aynı).

Bu heuristik hiçbir EMM turunda daha önce bağımsız doğrulanmamıştı (kod yorumunda round numarası yok, sadece "DBDataRecord" örnek olarak anılıyor). Korpusta bu şekle (`X ::= SEQUENCE OF Y`, adsız/referanssız) uyan **çok sayıda DB lookup tablosu modülü** var (`DbLookupTable_NUM`, `SOLSMSLookup`, `CCRMissingLookup`, `OMMFcmsServiceVariant`, `TwoVariable`, `CUSTOMERIDMSGIDMAP_Table`, `STypeTableIMSI_LATETAPIN` ve benzerleri) — tam kapsam ölçülmedi, ama bu tek örnek zaten heuristiğin **yanlış olduğunu** kanıtlamaya yetiyor.

### 19. tur için hazırlanan dosyalar (20.08.2026, henüz gönderilmedi)

#### Bulgu 6 düzeltildi — kod, ayrı commit

`AsnFieldTreeResolver.buildRootTagCarrier`'a eksik olan `.choiceTagImplicit(choiceTagImplicit(tag, false, choice, taggingMode))` çağrısı eklendi ([AsnFieldTreeResolver.java:209](../src/main/java/com/turkcell/cdrgenerator1/parser/AsnFieldTreeResolver.java)). Tam test paketi (`mvn clean test`, 514 test) 0 hata ile geçti.

`NRTRDEINFMSInput_Intermediate` yeniden üretildi ve bayt-seviyesinde doğrulandı:

| | eski (round 18, reddedildi) | yeni (düzeltme sonrası) |
|---|---|---|
| baş baytlar | `61 81 CB 63 81 C8 …` (çift EXPLICIT sarmalama) | `61 81 C6 5F 22 …` (tek IMPLICIT tag, iç `63 …` yok) |
| bayt | 206 | 201 |
| SHA-256 | `5b2023e3a1d30ac8f4f5a55f70db904a4d91c9012fcef451d96fcd69db6a1447` | `639408999d01c5c4ae2d8e378ab74be8099da73fe692f10bf94b33023094b9f5` |
| self-check (STRICT) | — | 0 hata / 1 uyarı (`CHOICE carries A-[1] but resolved tree holds 'moc'` — walker'ın IMPLICIT-retag'li CHOICE kökünü derin doğrulayamaması, zaten bilinen bir sınır, bkz. 6. kural) |

İçerik baytları rastgele üretildiği için eski dosyayla birebir aynı değil (uzunluk farkı da bundan), ama yapısal iddia doğrulandı: iç `63 81 C8` EXPLICIT sarmalayıcısı tamamen kayboldu, dış tag tek başına `moc` alternatifinin kendi `[APPLICATION 3]` tag'inin yerine geçti — 6. kuralın tam öngördüğü şekil. **Gönderilmeye hazır**, SHA yukarıda kayıtlı.

#### Bulgu 7 — SDPCCR izolasyon dosyası hazır

`tools/dropBerField.py scratchpad/round18/SDPCCR.ber out.ber 10 --under 0.12` ile `bonusAdjustment.usageThresholds` (tek TLV, 58 bayt içerik) çıkarıldı:

| | değer |
|---|---|
| bayt | 12480 → **12420** |
| SHA-256 (izolasyon dosyası) | `1d0aea3dc65a005b7d82de0626c38ca481caa7f97db7247f95a3ee26a5d565ca` |
| self-check (STRICT), gerçek `SDPCCR` şemasına karşı | **0 hata / 0 uyarı** |

`usageThresholds` OPTIONAL olduğu için eksikliği şema ihlali değil. Bu dosya EMM'e gönderilirse: **PASS** gelirse suçlu kesinleşir (`usageThresholds`/`UsageThreshold`/`UsageCounterType` üçlüsünden biri — vendörlenen şema metninin EMM'in gerçek şemasıyla ayrıştığı, IMSCDRS/CHF sınıfı bir sorun); yine **red** gelirse (ve mesaj artık `usageThresholds` alanını göstermiyorsa) hata dosyanın başka bir yerinde demektir ve aramaya sıfırdan başlanır. **Gönderilmeye hazır**, SHA yukarıda kayıtlı.

#### Bulgu 8 — kapsam tam ölçüldü: 84 modül, ama tek tip değil

Reflection ile gerçek `selectRootTypeName` doğrudan çağrılıp (mock değil, üretimdeki kod) 805 modülün tamamı tarandı: **84 modül** "adsız `SEQUENCE OF X`" sarmalayıcısını atlayıp `X`'i kök seçiyor. Ama bu 84'ün EMM açısından aynı riski taşımadığı ortaya çıktı — ayırıcı, seçilen iç tipin **kendi tip-seviyesi tag'i olup olmadığı**:

| grup | modül sayısı | örnek | EMM kanıtı |
|---|---|---|---|
| **TAGGED-INNER** — iç tip kendi `[n]` tag'ini taşıyor | 13 | `NRTRDEInValidationLookup` (`Row ::= [0] IMPLICIT SEQUENCE {...}`) | **PASS** — round 14/15'te doğrudan doğrulandı (`MyResult` sarmalayıcısı hiç aranmadı, EMM'in kendi akışı `Row`'u bekliyor) |
| **BARE-INNER** — iç tip çıplak, kendi tag'i yok | 71 | `ABSSDPXML` (`SnapshotRecord ::= SEQUENCE {...}`, tag yok) | **RED** — round 18, `ABSSDPXML.SnapshotData` bekleniyor |

`ABSSDPXML`'in gönderilen baytı (`30 34 A0 18 …`) incelendiğinde: kök `SnapshotRecord`'un kendi alanları doğrudan `30 <len>` ile açılmış — dışında `SEQUENCE OF` listesinin `30 <len>` katmanı hiç yok. EMM tam ilk tag'de "Invalid length 54" (=dosyanın tamamı) diyor çünkü tek elemanlı bir listeyi bile saran o ekstra `30 <len>` katmanını bekliyor.

BARE-INNER grubundaki 71 modül 8 aile halinde kümeleniyor (aynı şema, çok kopya): `SnapshotData ::= SEQUENCE OF SnapshotRecord` ailesi (8 modül: `ABSSDPXML`, `SnapshotDataDS`, `XMLCagkan`, `XMLCagkanOutput`, `XMLDENEME`, `XMLOMERO`, `XMLSnapshotDataABS`, `XMLSnapshotDataDS`), `DBDataRecord ::= SEQUENCE OF DBRecord` ailesi (26 modül, `DBLookupTable_*`/`DbLookupTable_*`), `STypeDataModule ::= SEQUENCE OF STypeRecord` (3), `MerRadiusRecord`/`MerRecord ::= SEQUENCE OF ...` (6, MMSR ailesi), kalan ~28 modül tekil varyant.

**Tasarım sorusu artık netleşti — heuristiği değiştirmek YANLIŞ olur:** BARE-INNER'ı düzeltmek için heuristiği "her zaman sarmalayıcıyı seç" şeklinde tersine çevirmek, halihazırda EMM'in kabul ettiği 13 TAGGED-INNER modülü (round 14/15 kanıtı) kırar. İki seçenek kaldı:

1. **Yalnızca `ABSSDPXML`'i `emm-record-bindings.yml`'e bağla** (`ABSSDPXML → SnapshotData`) — dar, kanıtlanmış, sıfır regresyon riski, ama diğer 70 modül kanıtsız kalır.
2. **Heuristiği ayırıcıya göre böl**: `selectRootTypeName`'in alias-unwrap dalında, iç tip kendi tag'ini taşımıyorsa (BARE-INNER) sarmalayıcıyı (X değil, alias'ın kendisini) tercih et; taşıyorsa (TAGGED-INNER) mevcut davranışı koru. Bu, tek bir kod değişikliğiyle 71 modülün tamamını (yalnızca 1'i EMM-kanıtlı) aynı yöne çeviriyor — 70'i hâlâ ekstrapolasyon.

Önerim 2: ayırıcı (kendi tag'i var/yok) zaten EMM'in iki farklı yanıtıyla birebir örtüşüyor, rastgele değil. Ama karar kullanıcıya ait; hangisi seçilirse seçilsin, `ABSSDPXML`'in kendisi round 19'da tekrar gönderilip düzeltmenin ilk bağımsız kanıtı olmalı.

**Karar: Seçenek 1, sıfır blast radius ile.** `emm-record-bindings.yml`'e `ABSSDPXML → SnapshotData` bağlandı. Kalan 70 BARE-INNER modül ve Seçenek 2 (heuristiğin ayırıcıya göre bölünmesi), bu dosya EMM'den PASS aldıktan sonra ayrı bir tech-debt/cleanup turu olarak ele alınacak.

**Bağlama tek başına yetmedi — ikinci bir bulgu.** `recordType` yalnızca `resolveRootTypeName`'in hangi ismi seçtiğini değiştiriyor; `AsnFieldTreeResolver.resolveRoot`'un alias-zinciri takip döngüsü ise seçilen isim ne olursa olsun `ALIAS ::= SEQUENCE OF X` biçimindeki her tipi `X`'e kadar açıyordu — heuristiğin kendi yolu bu döngüye hiç girmiyor (zaten `X`'i doğrudan döndürüyor), ama binding üzerinden gelen "SnapshotData" adı tam bu döngüye giriyor ve anında geri açılıyordu. Bağlamadan hemen sonra yeniden üretilen dosya, öncekiyle **birebir aynı çıplak şekli** veriyordu (`30 34 A0 18 …` → `30 4A A0 2E …`, sadece rastgele değerler farklı) — mimaride "kök tipin kendisi bir liste" durumunu temsil edecek hiçbir yapı yoktu.

**Düzeltme (kullanıcı onayıyla, tek entegre commit):**
- `AsnFieldTreeResolver.resolveRoot`: alias-zinciri takibi artık zincirdeki İLK tekrarlı (`SEQUENCE OF`/`SET OF`) adımı `repeatedRootAliasName` olarak hatırlıyor, hâlâ açıp `X`'in alanlarını çözüyor ama artık bunu **kaybetmiyor**. `ResolvedRoot` yeni `repeatedRoot`/`repeatedRootIsSet` alanları taşıyor.
- `AsnStructure`: aynı iki alan eklendi.
- `BerEncoderService.encodeRecord`: `structure.isRepeatedRoot()` true ise yeni `encodeRepeatedRoot` yolu — `X`'in kendi alanları ÖNCEDEN OLDUĞU GİBİ tek bir kendiliğinden-sınırlı SEQUENCE/SET TLV'ye kodlanıyor, sonra bu TLV bir kez daha düz universal `SEQUENCE OF`/`SET OF` (0x30/0x31) içine sarılıyor.
- **Kasıtlı olarak kapsam dışı bırakıldı:** sarmalayıcı alias'ın kendi tag'i olduğu durum (`X ::= [n] SEQUENCE OF Y`) — `AsnTypeRegistryBuilder`, `tagPrefix`'i yalnızca gövdesi `{` ile başlayan YAPISAL tanımlar için dolduruyor; bir ALIAS (parantezsiz `SEQUENCE OF`) için bu alan hep boş kalıyor, tag ham `aliasTarget` metninin içinde gömülü kalıyor. Korpusta bu şekle uyan (kendi tag'i olan bir SEQUENCE OF sarmalayıcı) hiçbir modül yok, bu yüzden okunması EMM'e karşı ölçülene kadar ertelendi — tahmin yürütülmedi. `RepeatedRootEncodingTest#aTaggedWrapperIsStillARepeatedRootButItsOwnTagIsNotReadBack` bu kasıtlı boşluğu kilitliyor.
- Yeni test: `src/test/java/com/turkcell/cdrgenerator1/service/RepeatedRootEncodingTest.java` (5 test) — heuristiğin kendi yolunun (84 modülün 83'ü artı gelecekteki Seçenek 2) etkilenmediğini de doğruluyor.
- Tam paket (`mvn clean test`): önce **514**, değişiklik sonrası **519** test (5 yeni), **0 hata**.

**`ABSSDPXML` yeniden üretildi, düzeltme sonrası:**

| | binding öncesi (round 18) | binding sonrası, kod düzeltmesi ÖNCESİ | kod düzeltmesi SONRASI |
|---|---|---|---|
| baş baytlar | `30 34 A0 18 …` | `30 4A A0 2E …` (aynı çıplak şekil) | `30 62 30 60 A0 2E …` (dış `SEQUENCE OF` katmanı geri geldi) |
| bayt | 54 | 76 | **100** |
| SHA-256 | `3b3c9f7b425040ef394576924cba16cd585764108202044cd2a822d383e96cf6` | `dbe1de01548333bc8f52aa436d0214199263f63f01b74ec591cc49b70e859a29` | **`1bef124dd1b61502685dca639e4888bc8040f65f333613beb5b298f6295ba1ce`** |
| self-check (STRICT) | — | — | 0 hata / 1 uyarı (`No field of this body carries tag U-[16]` — walker'ın yeni dış sarmalayıcıyı henüz tanımaması, zaten bilinen bir sınır, Bulgu 6'daki CHOICE uyarısıyla aynı sınıf) |

**Gönderilmeye hazır**, SHA yukarıda kayıtlı.

### Round 19 — üç dosya da hazır

| dosya | bayt | SHA-256 | dosya yolu |
|---|---|---|---|
| `NRTRDEINFMSInput_Intermediate.ber` | 201 | `639408999d01c5c4ae2d8e378ab74be8099da73fe692f10bf94b33023094b9f5` | `scratchpad/round19/NRTRDEINFMSInput_Intermediate.ber` |
| `SDPCCR-no-usageThresholds.ber` | 12420 | `1d0aea3dc65a005b7d82de0626c38ca481caa7f97db7247f95a3ee26a5d565ca` | `scratchpad/round19/SDPCCR-no-usageThresholds.ber` |
| `ABSSDPXML.ber` | 100 | `1bef124dd1b61502685dca639e4888bc8040f65f333613beb5b298f6295ba1ce` | `scratchpad/round19/ABSSDPXML.ber` |

Üçü de STRICT self-check'ten 0 hata ile geçti (NRTRDE ve ABSSDPXML birer bilinen/belgelenmiş walker uyarısı taşıyor, SDPCCR 0/0). Sorumluya gönderilmeye hazır.

### 19. turun yanıtı — 1 PASS, 2 red (20.08.2026)

```
ABSSDPXML  ->  başarılı.

'NRTRDEINFMSInput_Intermediate.ber' -> Failed to decode received data.
A block of 'NRTRDEINFMSInput_Intermediate.ber', originating from
  NRTRDEINFMSInput_Intermediate, was corrupt (this would have been record #0).
The type: NRTRDEINFMSInput_Intermediate.CallEvent was probably not set
and is not optional

SDPCCR -> Failed to decode received data.
A block of 'SDPCCR-no-usageThresholds.ber', originating from SDPCCR,
  was corrupt (this would have been record #0).
Invalid length 9980 of field
  "SDPCCR.SDPCreditControlRecord.creditControlRecord.chargingContextOutputFields.[0]"
```

##### ✅ `ABSSDPXML` PASS — Bulgu 8 kapandı

Kök tip bağlaması + `repeatedRoot` kodlaması doğrulandı. Bu, **`SEQUENCE OF` sarmalayıcısının kök seviyesinde de yazılması gerektiğinin ilk EMM kanıtı**. Kalan 70 BARE-INNER modül için Seçenek 2 (heuristiğin ayırıcıya göre bölünmesi) artık kanıtlı bir temele oturuyor — ayrı bir tech-debt turu olarak açılabilir.

##### 🟢 Bulgu 9 — `SDPCCR`: izolasyon işe yaradı, kök neden bulundu

`usageThresholds` çıkarılınca o hata **kayboldu** ve EMM 7411'den **9980'e ilerledi** — yani sökülen alan gerçek suçluydu ve arada kalan ~2.5 KB temiz. `IMS-R8-2009-03`'ün 16. turdaki 513 → 2065 ilerlemesiyle aynı desen.

Yeni hata noktası `chargingContextOutputFields.[0]` ile birlikte **iki taraflı bir kontrast** ortaya çıktı — hepsi tek modülde, tek başlık altında (`DEFINITIONS IMPLICIT TAGS`), hepsi CHOICE tipli tag'li alan:

| alan | tip | yazılı keyword | EMM |
|---|---|---|---|
| `ContextParameter.parameterValue [1] EXPLICIT ContextParameterValueType` | CHOICE | **EXPLICIT** | ✅ geçti (alan `[10]`) |
| `TreeDefinedField.parameterValue [1] EXPLICIT TreeDefinedFieldType` | CHOICE | **EXPLICIT** | ✅ geçti (alan `[11]`) |
| `UsageThreshold.usageThresholdValueBefore [2] UsageCounterType` | CHOICE | **yok** | ❌ red (18. tur) |
| `ServiceOutputField.parameterValue [1] ServiceOutputFieldType` | CHOICE | **yok** | ❌ red (19. tur) |

Tek değişken **yazılı keyword**. Yani 6. kural yalnızca başlıksız modüllerde değil, `IMPLICIT TAGS` diyen modüllerde de geçerli.

Eski gerekçe — *"`IMPLICIT TAGS` modülü 8.3'ü korur, çünkü MMTel'in kabul edilmiş dosyaları ve referans yakalaması buna dayanır"* — ölçüldüğünde tutmadı: MMTel'in 9 keyword'süz CHOICE alanı **hiç yazılmıyor**, `CdrRecordBuilder.shouldSkipImplicitChoice` onları atlıyor. O workaround'un gerekçesi de zaten "EMM'in implicit-CHOICE **hoisting**'i" — yani aynı kuralın çözücü tarafından görünüşü. Workaround yerinde bırakıldı (ayrı bir tur konusu), ama artık kök nedeni biliyoruz.

**Düzeltme** (`45a364a`): `choiceTagImplicit` artık `EXPLICIT`/`AUTOMATIC` demeyen her başlıkta çalışıyor.

**Ölçülen etki** (deterministik probe, 805 modül, taban `329bfbe`): **7 modül** bayt değiştirdi — `SDPCCR` ve ikizi `CreditControlDataTypes_EC22` (−56 bayt = 28 sarmalayıcı), `ATS_ONDER`, `LTE-R10-TURKCELL-SYNVRS`, `NEWDS`, `TurkcellCDRCCNCS40` (−2 bayt), artı 19. turun `ABSSDPXML`'i (+2). Her değişim 2'nin negatif katı — kalkan sarmalayıcının tag+uzunluk baytları. **36 EMM-kanıtlı modülün hiçbiri değişmedi**; `ReferenceCaptureConformanceTest` gerçek MMTel yakalamasına karşı atlanmadan geçti. 519 test, 0 hata.

Bayt seviyesinde, `usageThresholds`'un ilk elemanında:

```
ESKI (reddedildi):   80 [0]  81 [1]  A2 [2] len=10 constructed { 80 <8> }   <- EXPLICIT sarmalayici
YENI (round 20):     80 [0]  81 [1]  82 [2] len=8  primitive                <- IMPLICIT retag
```

##### 🔴 Bulgu 10 — `NRTRDE`: tip-seviyesi CHOICE tag'i için iki okumanın ikisi de reddedildi

18. tur EXPLICIT (`61 { 63 {...} }`) gönderdi → red. 19. tur IMPLICIT retag (`61 {...}`) gönderdi → **birebir aynı hata mesajı**. Yani 19. turun Bulgu 6 düzeltmesi **çürütüldü**, ve geriye üçüncü bir okuma kalıyor: **`CallEvent`'in `[APPLICATION 1]` tag'i tel üzerinde hiç yok**, kayıt doğrudan seçilen alternatifin kendi tag'i (`63`/`64`/`65`) ile başlıyor.

Bu okumayı destekleyen iki şey var. (a) EMM'in mesajı, `IMSCDRS`'in ilk turlarındaki "dış tag hiç eşleşmedi" imzasının aynısı. (b) Gerçek NRTRDE/TAP standardında (GSMA TD.35) çağrı-olayı CHOICE'u **tagsız**, `[APPLICATION 3/4/5]` alternatiflerin üzerinde — vendörlenen metindeki `[APPLICATION 1]` yerel bir aktarım artefaktı olabilir.

`45a364a` bu yüzden 6. kuralı **alan** tag'leriyle sınırladı (`!tag.fromType()`): NRTRDE'nin iki modülü 18. turdaki kodlamalarına geri döndü, ve kod artık ölçülmemiş bir okumayı ölçülmüş gibi taşımıyor.

### 20. turda gönderilen dosyalar (20.08.2026)

| # | dosya | bayt | ne soruyor | SHA-256 |
|---|---|---|---|---|
| 1 | `SDPCCR.ber` | 12427 | Bulgu 9'un düzeltmesi doğru mu — 18. ve 19. turun **iki** hata noktası da artık IMPLICIT retag ile kodlanıyor | `cbbc44dd1e4a21beb6524a83c0f2789e1e1a5cf1e08b60221ff15bbae51a49ef` |
| 2 | `NRTRDEINFMSInput_Intermediate-A-moc.ber` | 201 | `CallEvent` tag'i **hiç yazılmazsa** — çıplak `63 …` (moc) | `964e03afb0249c9043e621a26ef1355854e720223945e9ad1b7a21a9d425618e` |
| 3 | `NRTRDEINFMSInput_Intermediate-B-mtc.ber` | 173 | aynı soru, çıplak `64 …` (mtc) | `39bc98d6f40d5ac143b0272b21a8f294c69a142f1f3f107770b9e2f97a82e949` |
| 4 | `NRTRDEINFMSInput_Intermediate-C-gprs.ber` | 214 | aynı soru, çıplak `65 …` (gprs) | `298b3f9db146e8e64e0fce63a642d945e580ccd4675f617a0a95792a5666fd38` |

2-4 aynı anda **iki** boyutu ayırıyor. Üçü de geçerse: kural "tip-seviyesi CHOICE tag'i tel üzerinde yok". Yalnızca biri geçerse: EMM'in akışı o alternatife bağlı (`LTE-R10`'un `pGWRecord`'u gibi) ve bağlama `emm-record-bindings.yml`'e yazılır. Hiçbiri geçmezse: dış tag hipotezi de yanlış, hata `Moc`'un içinde ve aramaya alan seviyesinden başlanır.

Üçü de kod değişikliği gerektirmedi — mevcut `rootType` parametresiyle üretildi (`Moc`/`Mtc`/`Gprs`), yani 20. tur NRTRDE tarafında **kodda hiçbir varsayım taşımıyor**. Dördü de STRICT self-check'te 0 hata (NRTRDE üçü 0 uyarı; `SDPCCR`'ın 35 uyarısı, walker'ın IMPLICIT-retag'lenmiş CHOICE alt-ağaçlarını doğrulayamaması — bilinen sınır, ve tam olarak yeni kuralın devreye girdiği 35 noktayı işaretliyor).

### 20. turun yanıtı — NRTRDE 3/3 PASS, SDPCCR aynı düğümde farklı offset'te tekrar red (20.08.2026)

```
NRTRDE (3 dosya) -> hepsi başarılı.

SDPCCR.ber -> Failed to decode received data.
A block of 'SDPCCR.ber', originating from SDPCCR, was corrupt (this would
  have been record #0).
Invalid length 6087 of field
  "SDPCCR.SDPCreditControlRecord.creditControlRecord.bonusAdjustment.usageThresholds.[0]"
```

##### ✅ NRTRDE 3/3 PASS — Bulgu 10 kapandı, üçüncü okuma doğrulandı

`Moc`/`Mtc`/`Gprs` üçü de geçti — yani `CallEvent`'in `[APPLICATION 1]` tag'i **belirli bir alternatife bağlı bir EMM akış konfigürasyonu değil** (LTE-R10'un `pGWRecord`'u gibi); tag tel üzerinde **hiç yok**. Kalıcı düzeltme (`e3062cc`): `buildRootTagCarrier`, keyword'süz tag'e sahip kök CHOICE için (yalnızca modül başlığı `UNSPECIFIED` iken — ölçülen tam koşul) artık `null` dönüyor, kod `encodeRecord`'un zaten var olan "seçili alternatifi çıplak yaz" yoluna düşüyor. Korpus taraması: bu şekle (`X ::= [n] CHOICE` keyword'süz) uyan başka **hiçbir modül yok** — değişiklik yalnızca bu iki NRTRDE modülünü etkiliyor, 36 kanıtlı modülün hiçbiri kıpırdamadı (deterministik probe ile doğrulandı).

##### 🟡 Bulgu 11 — `SDPCCR`: aynı hata, farklı offset — kusur CHOICE etiketlemesiyle ilgisiz çıktı

Rakam değişti (7411 → 6087) ama bu sadece dosya boyutunun küçülmesinden (round 9'un 28 sarmalayıcıyı kaldırması) — TLV analizi ikisinin de **aynı düğümün kendi bitiş ofseti** olduğunu doğruluyor (round 18: `usageThresholds[0]` 56 bayt, biter @7411; round 20: aynı alan 52 bayt, biter @6087). Yani **rule 9 düzeltmesi bu alanı hiç etkilemedi** — EMM tıpkı öncekiyle aynı düğümde, aynı şekilde takılıyor.

Bu, önceki hipotezi (kusur CHOICE alan etiketlemesinde) çürütüyor: iki farklı kodlama (round 18'in EXPLICIT sarmalaması, round 20'nin IMPLICIT retag'ı) **aynı reddi** üretti. Değişmeyen tek şey `usageThresholds`'un CHOICE-DIŞI alanları: `usageThresholdID [0]`, `action [1]`, `associatedPartyID [4]`, `forAllSubscribersOnAccount [5]`.

Ayrıca dikkat çekici bir iç-tutarlılık: `UsageCounter` (bonusAdjustment'ın komşu koleksiyonu, `usageCounters [9]`) aynı `UsageCounterType` CHOICE'unu **yazılı `EXPLICIT`** keyword'üyle taşıyor ve açıkça geçiyor (hata `usageThresholds`'ta, yani `usageCounters` önce başarıyla çözülmüş olmalı) — `UsageThreshold`'un keyword'süz aynı CHOICE'u ise iki kodlamada da reddediliyor. Bu, Bulgu 9'un genel kuralını **zayıflatmıyor** (ContextParameter/TreeDefinedField/ServiceOutputField üçlüsü hâlâ geçerli bir örüntü), ama `usageThresholds`'un kendi başına **ayrı, henüz teşhis edilmemiş** bir sorunu olduğunu gösteriyor.

**Sonraki bisection adımı**, round 20'nin baytları üzerinde `dropBerField.py` ile hazırlandı: `usageThresholdValueBefore [2]` ve `usageThresholdValueAfter [3]` (CHOICE alanların ikisi de) çıkarıldı, `usageThresholdID`/`action`/`associatedPartyID`/`forAllSubscribersOnAccount` dokunulmadan bırakıldı. STRICT self-check 0 hata / 31 uyarı (kalan uyarılar başka alanların bilinen CHOICE-retag sınırları, `usageThresholds` ile ilgisi yok).

- **Geçerse:** kusur kesinlikle CHOICE alanlarında (belki `UsageCounterType`'ın seçilen alternatifiyle ilgili bir şey, ya da CHOICE alanlarının ikisinin birlikte varlığıyla ilgili), sıradaki bisection CHOICE alanlarını teker teker izole eder.
- **Geçmezse (aynı `usageThresholds` hatası, farklı offset):** kusur `usageThresholdID`/`action`/`associatedPartyID`/`forAllSubscribersOnAccount`'tan birinde — muhtemelen `UsageThresholdID`'nin kısıtı (`INTEGER (1..2147483647)`) ya da `associatedPartyID`'nin `NumberString` boyut kısıtıyla ilgili bir üretim hatası.
- **Hiç `usageThresholds` hatası vermezse (ilerlerse):** dört alanın hiçbiri suçlu değil, sorun muhtemelen koleksiyonun kendisinde (eleman sayısı, sıralama) — IMSCDRS/CHF sınıfı bir şema-gerçeklik ayrışması ihtimali güçlenir.

### 21. turda gönderilen dosyalar (20.08.2026)

| # | dosya | bayt | ne soruyor | SHA-256 |
|---|---|---|---|---|
| 1 | `NRTRDEINFMSInput_Intermediate.ber` | 201 | kalıcı düzeltmenin varsayılan üretim yolunda (rootType override'sız) doğru çalıştığı — `moc` seçili, dış tag yok | `785e4b95165fca1decb139a1700f9208bc027efc952dc737f34d2c8a4503d975` |
| 2 | `NRTRDEINFMSInput.ber` | 156 | aynı düzeltme, kardeş modül | `71a8182aa53d141186da1df14b9d387379e7e23e2b63d429098e3d598d96f8ab` |
| 3 | `SDPCCR-choiceFieldsOnlyDropped.ber` | 12387 | Bulgu 11 bisection'ı — `usageThresholds`'un CHOICE alanları çıkarılmış, gerisi dokunulmamış | `3440948d82918055d5f3e4e2cdafbcd208d5eedfc72e81f33238fccd8e6acbb5` |

1-2 artık **gerçek varsayılan üretim yoludur** (round 20'deki gibi elle seçilmiş `rootType` değil) — API'nin/UI'nin bu modüller için üreteceği dosyayla birebir aynı. Üçü de STRICT self-check'te 0 hata; tam paket 519 test, 0 hata.

### 21. turun yanıtı — NRTRDE tamamen kapandı, SDPCCR çok daha derine ilerledi (21.08.2026)

```
NRTRDE (2 dosya) -> hepsi başarılı.

SDPCCR-choiceFieldsOnlyDropped.ber -> Failed to decode received data.
Invalid length 9437 of field
  "SDPCCR.SDPCreditControlRecord.creditControlRecord.appliedProductFees.[0]
   .productFeeUsageCounters.[0].usageCounterChange.usageCounterMoney"
```

##### ✅ NRTRDE — gerçek varsayılan üretim yolu da doğrulandı

`NRTRDEINFMSInput_Intermediate` ve `NRTRDEINFMSInput`, artık hiçbir `rootType` zorlaması olmadan, API'nin/UI'nin üreteceği gerçek dosyalarla geçti. Bulgu 10 artık yalnızca "doğru" değil, **üretimde kullanılan haliyle doğrulanmış**.

##### 🟢 Bulgu 12 — bisection'ın "geçerse" dalı gerçekleşti: kusur gerçekten CHOICE alanlarındaydı, ama nedeni beklenenden farklı

`usageThresholds`'un iki CHOICE alanını (`[2]`/`[3]`) çıkarmak hatayı tamamen ortadan kaldırdı — EMM çok daha derine, `appliedProductFees[0].productFeeUsageCounters[0].usageCounterChange` alanına kadar ilerledi. Ama bu kez hata mesajı **seçilen CHOICE alternatifinin adını** veriyor: `usageCounterMoney`.

TLV analizi kök nedeni kesinleştirdi. `ProductFeeUsageCounter.usageCounterChange [1] UsageCounterType` (yazılı keyword yok). `UsageCounterType ::= CHOICE { usageCounterUnit [0] Integer64, usageCounterMoney [1] MonetaryUnits OPTIONAL }`. Resolver, alan-seviyesi bir CHOICE için **her zaman ilk alternatifi** çözüyor (`usageCounterUnit`), rootType seçimlerinin aksine burada bir override mekanizması işletilmiyordu. `choiceTagImplicit` devreye girip `usageCounterUnit`'in içeriğini alanın kendi tag'i `[1]`'e retag edince, tel üzerindeki bayt `81 <8 bayt integer>` oldu — ama `[1]`, `UsageCounterType`'ın **kendi** şemasında `usageCounterMoney`'in tag'i! EMM tag `[1]`'i görüp "bu usageCounterMoney" diye okudu, sonra ham bir INTEGER'ı `MonetaryUnits` SEQUENCE'i gibi ayrıştırmaya çalışıp çöktü.

Bu, round 12/13'te "kanıtlanan" 6. kuralın kendisinde **hiç fark edilmemiş bir belirsizlik** ortaya çıkardı — kodun kendi yorumu bunu zaten önceden yazmıştı: *"the two readings diverge wherever the numbers differ, and no site like that has been measured."* CHF'nin kanıtı (`iPAddress [0]` → `iPBinaryAddress [0]`) alan numarası ile alternatifin kendi numarasının **tesadüfen** ikisinin de `0` olmasına dayanıyordu; retag-to-field-number ile "alternatifi hiç değiştirmeden geçir" ayırt edilemiyordu. SDPCCR bunları ilk kez ayırdı.

**Denendi ve GERİ ALINDI:** "alan tag'ini hiç yazma, alternatifi olduğu gibi geçir" — kod değişikliği yapıldı, ama `ShippedFieldRulesRoundTripTest` gerçek ve daha ciddi bir hata yakaladı: `ProductFeeUsageCounter`'ın **iki** keyword'süz CHOICE alanı (`usageCounterChange[1]`, `usageCounterValueAfter[2]`) ikisi de aynı ilk alternatife (`usageCounterUnit[0]`) çözüldüğü için, tag'i atlayınca **ikisi de aynı `[0]` tag'ini** taşıyor — aynı SEQUENCE gövdesinde gerçek, self-check'in doğru yakaladığı bir duplicate-tag ihlali. Retag'in kendisi (kardeşleri ayrı tutan mekanizma) gerekliydi; asıl kusur **hangi alternatifin seçildiğiydi**.

**Uygulanan düzeltme** (`f4b6e9a`): resolver'da zaten var olan `choiceAlternatives` mekanizması (LTE-R10'un `pGWRecord` bağlaması için kullanılan aynı yapı) kullanılarak, `SDPCCR` modülünde `UsageCounterType → usageCounterMoney` bağlandı. Artık `usageCounterChange[1]`'in çözülen alternatifi (`usageCounterMoney`, kendi tag'i `[1]`) alanın kendi tag'iyle **örtüşüyor** — çakışma kalktı. `usageCounterValueAfter[2]` ve `UsageThreshold`'un `[2]`/`[3]` çifti hâlâ HİÇBİR alternatifin kendi numarasıyla örtüşmüyor — bu düzeltmeyle çözülmedi ama **kötüleşmedi** de (retag her zaman kardeşleri kendi numaralarında tutar, içindeki alternatif ne olursa olsun).

**Ölçüm:** STRICT self-check 0 hata / 13 uyarı (hepsi walker'ın retag'lenmiş CHOICE alt-ağaçlarını doğrulayamaması, artık `[2]`/`[3]` noktalarında da bekleniyor). 519 test, 0 hata. Korpus geneli deterministik fark: **yalnızca `SDPCCR`** değişti (8543 → 9182 bayt); `Array`/`LteReturnTypes`/`SMSCLookupStructures` farkı biçimsel, gerçek değil.

### 22. turda gönderilen dosya (21.08.2026)

| dosya | bayt | ne soruyor | SHA-256 |
|---|---|---|---|
| `SDPCCR.ber` | 13194 | tam dosya (izolasyon değil) — `usageCounterChange[1]` çakışması düzeldi mi, `usageCounterValueAfter[2]`/`usageThresholds`'un `[2]`/`[3]`'ü hâlâ açık soru | `4a22a82cb13f8d2d2de92f2d8d9c647bfceee8a942a4d8a33b9806463500259a` |

Bu artık **tam SDPCCR dosyası** (bisection değil) — `usageThresholds` geri eklendi (artık `usageCounterMoney`'e bağlı). Geçerse: `[n]` alternatifin kendi numarasıyla örtüşmediğinde bile EMM'in genel olarak tölere ettiği anlaşılır. Aynı hatayla (farklı offsette) tekrar reddedilirse: sorun kesinlikle `[n]`↔alternatif-numarası uyuşmazlığında, ve genel çözüm (alan tag'i alternatiflerden biriyle eşleşmiyorsa ne yapılmalı) yeni bir açık soru olur.

#### Gönderim öncesi bağımsız doğrulama — korpus geneli bayt karşılaştırması

"Hiçbir çalışan dosyayı bozmadık" iddiası akıl yürütmeyle değil **ölçümle** kanıtlandı. Yöntem: `329bfbe` (round 19 öncesi taban) için bir `git worktree` açıldı, aynı probe iki ağaçta da koşuldu ve 805 modülün çıktısı karşılaştırıldı. Üretimin rastgeleliği iki yerden nötrleştirildi — her yaprağa sabit bir değer veren bir `ValueSource`, ve `CdrRecordBuilder.random`'ın reflection ile tohumlanmış bir `Random(20260820)` ile değiştirilmesi (tekrar sayıları `random.nextInt` ile çekiliyor). Böylece iki taraf da **gerçek** `CdrRecordBuilder` + **gerçek** `BerEncoderService` kullandı, ama çıktı deterministik oldu.

| ölçüm | sonuç |
|---|---|
| taranan modül | **805** |
| gerçek bayt üretebilen | **799** (kalan 6'sı OBJECT IDENTIFIER sabitini reddetti — **iki tarafta da birebir aynı hata**, karşılaştırma bozulmuyor) |
| **bayt-bayt aynı kalan** | **802** |
| değişen | **3** — `ABSSDPXML`, `NRTRDEINFMSInput`, `NRTRDEINFMSInput_Intermediate` |

Değişen üçü de hedeflenen modüller; korpusta başka **tek bir bayt** değişmedi.

**Commit bazında atıf** (aynı probe `79bce2c` ve HEAD'de koşuldu):

| modül | `329bfbe` taban | `79bce2c` (Bulgu 6) | HEAD (+ `ed488a9`, Bulgu 8) |
|---|---|---|---|
| `NRTRDEINFMSInput_Intermediate` | 221 bayt | **218** ✓ | 218 (değişmedi) |
| `NRTRDEINFMSInput` | 169 bayt | **166** ✓ | 166 (değişmedi) |
| `ABSSDPXML` | 76 bayt | 76 (değişmedi) | **78** ✓ |
| `SDPCCR` | 8599 bayt | 8599 | 8599 (hiç dokunulmadı) |

NRTRDE'de **−3 bayt** = silinen iç `63 81 C8` sarmalayıcısı (tag + 2 baytlık uzun-form uzunluk). ABSSDPXML'de **+2 bayt** = eklenen dış `30 <len>`. İkisi de teşhiste öngörülen rakamla birebir. Ayrıca `ed488a9`'un NRTRDE dosyasını değiştirmediği kanıtlandı — yani `79bce2c`'de üretilip diske yazılan dosya hâlâ HEAD'in ürettiğiyle aynı, yeniden üretilmesi gerekmiyor.

**EMM'den geçmiş 36 modülün tamamı ayrı ayrı isimle teyit edildi** — `MMTelChargingDataTypes` (1725 bayt), `GGSNTurkcellCdrR7` (490), `LTE-R10` (1072), `IMSCDRS` (348), `TAP-0309` (2254), `EnrichedVerazCdr` (29537), `IMS-R8-2009-03` (2109) ve round 18'in 12 PASS'inin hepsi dahil: **hepsi bayt-bayt identik**. (`CHFChargingDataTypes16` o 6 OID modülünden biri; onun için ağaç imzası identik, bayt karşılaştırması yapılamadı.)

Buna ek olarak `ReferenceCaptureConformanceTest` **atlanmadan** koştu (`Skipped: 0`) — yani üretilen MMTel kodlaması, gerçek bir üretim ağının yazdığı ve EMM'in kabul ettiği yakalamaya karşı hâlâ uyumlu. Tam paket: **519 test, 0 hata**.

**Gönderilecek üç dosyanın TLV denetimi:**

```
NRTRDEINFMSInput_Intermediate.ber (201)  61 A-[1] len=198
                                           └─ 5F A-[34], 5F A-[33], 55 A-[21] ...   ← iç 63 YOK ✓
SDPCCR-no-usageThresholds.ber   (12420)  A0 C-[0] len=12416
                                           └─ creditControlRecord tag'leri [1..28] TAM
                                              bonusAdjustment[12] içi: [0..9]        ← [10] çıktı ✓
ABSSDPXML.ber                     (100)  30 U-[16] len=98
                                           └─ 30 U-[16] len=96                       ← dış sarmalayıcı VAR ✓
                                                └─ A0 C-[0], A1 C-[1]
```


### 22. tur gönderilmedi — bağlama geri alındı

`UsageCounterType → usageCounterMoney` bağlaması **type-global** olduğu için yalnızca sorunlu alanı değil, EMM'in üç turdur kabul ettiği `UsageCounter.*` (yazılı `EXPLICIT`) yapısını da değiştiriyordu: `A1 0A { 80 08 … }` → `A1 18 { A1 16 { … } }`. Kanıtlanmış bir yapıyı kanıtsız bir gerekçeyle bozmak bu projenin kuralına aykırı; bağlama çalışma ağacında geri alındı ve `f4b6e9a` sonradan `git revert` ile tarihten de temizlendi.

### 24. turda gönderilen dosya (21.08.2026, yanıt bekleniyor)

`ProductFeeUsageCounter`'ın CHOICE alanları, EMM'in **aynı tip için zaten kabul ettiği** sarmalayıcı formuna çevrildi (`tools/wrapChoiceField.py`, 28 site, tüm ata uzunlukları yeniden yazıldı):

```
ÖNCE  (round 21, reddedildi):  81 08 357360fd4c5771a7        @9427
SONRA (round 24)            :  A1 0A 80 08 357360fd4c5771a7  @9427
```

| dosya | bayt | SHA-256 |
|---|---|---|
| `SDPCCR-productFeeUsageCounters-wrapped.ber` | 12444 | `d897a8371fed91e9b6e955dcf0e92f1f446f73e9b81e8efdf58fd3ceae32a736` |

Dokunulmayanlar doğrulandı: `usageCounters[9]` (kanıtlı EXPLICIT) bayt bayt aynı, `chargingContextOutputFields[19].parameterValue` (round 21'de geçti) aynı, `usageThresholds[10]` hâlâ `[2]`/`[3]`'süz.

### 25. tur — stress set, 18 dosya (21.08.2026)

805 modül 15 eksende imzalandı: **55 davranış imzası**, bunların **26'sı hiç sınanmamıştı** (476 modül). Her sınanmamış imzadan temsilci seçilerek 18 dosya üretildi; hepsi normal üretim hattından, elle patch yok.

**Sonuç: 16 PASS · 2 red.** Tek turda kanıtlanan imza sayısı bu projenin rekoru.

Öne çıkan kabuller:

| dosya | ne kanıtladı |
|---|---|
| `Newchf` (3156 bayt, **555 TLV, 10 seviye**) | Korpusun en karmaşık yapısı — nested CHOICE + SEQ OF + DEFAULT + çok-baytlı tag birlikte |
| `SDP2CSN` (193 TLV, 192 yaprak) | En geniş düz CHOICE + BCD/timestamp |
| `CCNAniTest` (12 bayt) | Keyword'süz modülde **SET kökü** — korpusta tek örnek |
| `CHARGINGCDR_4_12` | `IMPORTS` ikinci bağımsız soyda |
| `NRTRDEAgreementActiveLookup`, `RevenueAssurance` | "0/2 uyarı" taşıyorlardı; **kod değiştirmeden** gönderildi ve geçtiler |

##### Bulgu 13 — `duplicate-tag` kuralında false positive (EMM tarafından kanıtlandı)

`NRTRDEErrorReport` self-check'ten **ERROR** ile geçmişti:

```
ERROR [duplicate-tag] NerFile.A-[4] appears 2 times
```

Sebebi vendored şemada: `Name ::= [APPLICATION 4]` ve `UTCCode ::= [APPLICATION 4]`, ikisi de `NerFile`'ın OPTIONAL alanı. "Şema kusuru, göndermeyelim" önerilmişti; yine de gönderildi ve **EMM kabul etti** — çünkü 10 alanın hepsi mevcut ve sırayla geldiği için pozisyonel çözüm mümkün.

Kural **şemanın** belirsizliğine bakıyor, **dosyanın** belirsiz olup olmadığına bakmıyor. Sonuç: STRICT mod, EMM'in kabul ettiği bir dosyayı üretmeyi reddediyor. Aynı mantık `RevenueAssurance` (106 alanın 106'sı mevcut) ve `CDRDatamartSDPColl` (212/212, round 18 PASS) için de geçerli. Düzeltme henüz yapılmadı; kapsamı ölçülmeli.

### 26. tur — `DbLookupTable_IA5` (21.08.2026)

```
Beklenen structure DbLookupTable_IA5.DBDataRecord
Invalid length 12 of field ""          ← 12 = tüm dosya
```

Bulgu 8'in aynısı, `ABSSDPXML` ile birebir. Bağlama eklendi (`DbLookupTable_IA5 → DBDataRecord`), korpus etkisi **tek modül** (12 → 14 bayt), ve EMM **kabul etti**:

```
ESKI:  30 0A 16 08 …           ← sarmalayıcı yok
YENI:  30 0C  30 0A  16 08 …   ← DBDataRecord → DBRecord → input_value
```

**Bulgu 8 artık iki bağımsız soyda kanıtlı** (XML ailesi + DB-lookup ailesi). Kalan ~70 BARE-INNER modül için genel temizlik turunun gerekçesi tamamlandı.

##### Bulgu 14 — C-sınıfı, ikinci bağımsız soyda kanıtlandı (`ATS_ONDER`)

25. turda `ATS_ONDER` şu hatayı verdi:

```
Duplicate Tag data found for ATS_ONDER.TASRecord.aTSRecord
```

İlk hipotez — "`listOfSubscriptionID [31] SET OF` koleksiyonu 2 eleman aldığı için `U-[17]` tekrarlıyor" — **çürütüldü**: `IMS-R8-2009-03` (round 17 **PASS**) tam olarak aynı şekli taşıyor (`[31]` içinde 2 × `U-[17]`, her biri `C-[0] C-[1]`). Eleman sayısını düşürmek boşuna olurdu.

Dosyada kalan tek anormallik C-sınıfıydı: `dialedPartyAddress [203] CalledPartyAddress` — keyword'süz CHOICE, alternatifleri `sipURI [0]` / `telURI [1]`, biz `[203]`'e retag ediyoruz ve bu hiçbir alternatife karşılık gelmiyor. 27. turda yalnızca bu alan çıkarıldı (`dropBerField.py`, tek değişken) ve **EMM kabul etti**.

| modül | alan | alternatifler | kapsayıcı | EMM |
|---|---|---|---|---|
| `SDPCCR` | `usageThresholdValueBefore [2]` | `{0,1}` | SEQUENCE | ❌ (20. tur) |
| `ATS_ONDER` | `dialedPartyAddress [203]` | `{0,1}` | **SET** | ❌ (25. tur) |
| `ATS_ONDER` | alan çıkarıldı | — | SET | ✅ **PASS** (27. tur) |

C-sınıfı artık **iki bağımsız soyda ve iki farklı kapsayıcı türünde** bozuk olduğu kanıtlanmış durumda. Korpusta bu sınıfta **7 modül / 34 alan** var: `SDPCCR`, `CreditControlDataTypes_EC22`, `CDRF-R7`, `MAVENIRTEST`, `NEWDS`, `TurkcellCDRCCNCS40`, `ATS_ONDER`. Genel çözüm 24. turun yanıtına bağlı.
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

| # | dosya | bayt | TLV | derin | kök | sonuç | SHA-256 |
|---|---|---|---|---|---|---|---|
| 1 | `TurkcellImsOmm.ber` | 446 | 38 | 1 | `PostCcnCdr` | ✅ PASS | `446fb98456d0e723e3e6f83e974ee5baf7e6e88feab18a005ba153d8b900fd65` |
| 2 | `TAP-0309.ber` | 3019 | 423 | 8 | `CallEventDetail` | ✅ PASS | `414caded90dd195ef77f8fb5cbd880dc845a117bd759792d0e1044fd2e1fae63` |
| 3 | `TAP0309.ber` | 2663 | 367 | 8 | `CallEventDetail` | ✅ PASS | `4284a416ccc3c161f8a213a3ba77f24cca0c6f7887545102a6eefb1515166977` |
| 4 | `EnrichedVerazCdr.ber` | 29.444 | 199 | 2 | `CDR` | ✅ PASS | `f8e8084f36f8b819d09c59d059952f0c3b0ca8aed7887611523fe8fd602a7fd2` |
| 5 | `CCNCS55_UpdatedCCR_CCN.ber` | 486 | 71 | 4 | `ChargingDataOutputRecord` | ✅ PASS | `de7a6171a99edcf4877a19d55e4026bdb3da1972de99e1ada0d16756e6907a25` |
| 6 | `GSN50.ber` | 2230 | 375 | 10 | `CallEventRecord` | ✅ PASS | `01058bb5276c09f4472b9fa2db0bfac0027cd907f96b6b2c717bb180936302fd` |
| 7 | `IMS-R8-2009-03.ber` | 2343 | 281 | 6 | `IMSRecord` | ❌ red | `0768b9ec9ce388c14e6158d8afdf1c6c751f94dfa46d230c7a5e10f74ab63d84` |

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

#### ✅ 15. tur sonucu (18.08.2026) — 6 PASS, 1 red

Tahmin 4-5 PASS'ti, **6** geldi. Daha önemlisi: **14. turun üç bulgusunun üçü de
EMM tarafından doğrulandı.** Her düzeltme, onu doğrulayan dosyayla birlikte:

| dosya | tahmin | sonuç | neyi kanıtladı |
|---|---|---|---|
| `TurkcellImsOmm` | ~%85 | ✅ | kök tip bağlaması (`emm-record-bindings.yml`) çalışıyor |
| `TAP-0309` | ~%60 | ✅ | **TAP ailesinin ilk kabulü** — 8 seviye, 423 TLV |
| `TAP0309` | ~%55 | ✅ | aynı, keyword'süz başlıkta |
| `EnrichedVerazCdr` | ~%75 | ✅ | **7. kural** — 29 KB dosya baştan sona çözüldü |
| `CCNCS55_UpdatedCCR_CCN` | ~%70 | ✅ | 7. kural, ikinci bağımsız soyda |
| `GSN50` | ~%55 | ✅ | **`IMPORTS` çözümü** — ithal edilen `SET` doğru okundu |
| `IMS-R8-2009-03` | akışa bağlı | ❌ | akış varmış; hata başka yerde (aşağıda) |

Yedi dosyanın altısı ilk denemede geçti. Tahminlerin sıralaması bu sefer de
tutmadı — en düşük iki tahmin (`TAP0309` %55, `GSN50` %55) geçti.

**EMM'in yanıtı (birebir).** Altı dosya için hiçbir mesaj dönmedi; sorumlunun
ifadesiyle *"7 dosyadan sadece bir tanesinde hata aldık"*. Tek hata:

```
Failed to decode received data.
A block of 'IMS-R8-2009-03.ber', originating from IMS-R8-2009-03, was corrupt
(this would have been record #0).
Invalid length 513 of field "recordExtensions"
```

Sorumlunun eklediği yönlendirme bilgisi:

```
Decoder tarafından beklenen structure: IMS-R8-2009-03.IMSRecord
```

Beklenen kök tip bizim kodladığımızın aynısı (`IMSRecord`, dosya başı
`BF 45 82 09 22` = `[69] aTSRecord`), yani bu turda **yönlendirme ekseninde hiç
kayıp yok** — altı kabul + bir alan-içi hata.

**Kabul edilen baytlar kayıt altında.** Yukarıdaki tablodaki altı SHA-256, EMM'in
kabul ettiği dosyaların kendisidir; dosyalar `target/emm-corpus-r15/` altında
üretildi ve SHA'ları gönderimden önce yazıldı. 14. turda bu yapılmamıştı ve
gönderilen baytlar geri getirilemedi; 15. turda `IMS-R8-2009-03`'ün teşhisi
doğrudan gönderilen dosya üzerinden yapılabildi (aşağıda), disiplin ilk turunda
işe yaradı.

**Kapsam:**

| | 13.08 | 17.08 | bugün |
|---|---|---|---|
| Kanıtlı davranış sınıfı | 8 / 32 | 15 / 32 | **21 / 32** |
| Kanıtlı modül | 641 (%79) | 695 (%86,7) | **776 (%96,8)** |
| EMM'den geçen yapı | 12 | 19 | **25** |

Kalan 11 sınıf toplam **26 modül**; ortalama 2,4 modül, hepsi tekil varyant.

##### 🔴 Bulgu 4 — şemanın kendisi eksik: gövdesi yorumlanmış tip

`IMS-R8-2009-03` bu turda ilk kez koşabildi ve gerçek bir hata verdi:

```
Invalid length 513 of field "recordExtensions"
```

**Bayt kesinliğinde teşhis — ilk kez.** Gönderilen dosya elde tutuldu ve
SHA'sı gönderimden önce yazıldığı için hata doğrudan baytla eşleştirildi:

| | |
|---|---|
| `recordExtensions [25]` başlangıç | ofset 501 |
| gönderilen | `B9 0A  04 08 4E 47 44 46 32 39 44 58` |
| bitiş ofseti | **513** — EMM'in bildirdiği sayının aynısı |

11. turda çıkarılan kural (*"Invalid length N" bir uzunluk değil, düşen düğümün
bitiş ofseti*) üçüncü kez doğrulandı.

**Kök neden encoder'da değil, vendor'lanan şemada.** Zincir:

```
recordExtensions [25] ManagementExtensions
ManagementExtensions ::= SET OF ManagementExtension
ManagementExtension  ::= SET
{
 -- ...
 -- operator specific record extensions
 -- ...
}
```

Tip tanımlı ama **gövdesi tamamen yorum**. Modülün `IMPORTS` satırı da
`--IMPORTS` diye yorumlanmış. Yani şemayı vendor'layan taraf paylaşılan
tanımları elemiş. Çözümleyici koleksiyonu doğru anlıyor (`repeated=true`,
`set=true`) ama elemanın alanı olmadığı için eleman yaprak olarak yazılıyor:
`04 08 <8 bayt>`. EMM ise orada bir `SET` bekliyor.

**Self-check bunu yakalayamaz** — üretilen baytları onları üreten alan ağacına
karşı denetler; boş bir tip kendisiyle tutarlıdır. Bu, günlüğün açılışındaki
uyarının bir örneği.

**Kopyalama yoluyla doldurmak tahmin olur — ölçüldü.** Korpusta
`ManagementExtension` **yedi farklı** gövdeyle tanımlı:

| kaç modül | tanım |
|---|---|
| 10 | `SEQUENCE { identifier OBJECT IDENTIFIER, significance [1] BOOLEAN DEFAULT FALSE, information [2] OCTET STRING }` |
| 4 | `SEQUENCE { identifier [UNIVERSAL 6] OCTET STRING, …, information [2] GprsCdrExtensions }` |
| 2 | `SEQUENCE { …, information [2] ProtocolEnhancements }` |
| 2 | **`SET`** { `totalNumberOfMessagesSent [1]`, `contributionId [2]`, `nodeId [3]`, … } — `UAGRecordsBer`, `UAGRecordsBer2` |
| 3 | diğer tekil varyantlar |
| 1 | boş — `IMS-R8-2009-03`'ün kendisi |

**18.08 — modülün tam metni geldi, aday sayısı ikiye indi.** Yorumlanmış
`IMPORTS` bloğu kaynağı adıyla veriyor:

```
--IMPORTS
--RecordType, IPAddress, ManagementExtensions, NodeAddress, LocalSequenceNumber,
--SubscriptionID, TimeStamp, ServiceContextID
--FROM GenericChargingDataTypes {itu-t (0) ... genericChargingDataTypes (0) ...}
```

`GenericChargingDataTypes` **bu veri setinde yok**, yani import kapatması onu
çözemez. Ama vendor'lama deseni artık görünür: o sekiz tipin **yedisi modüle
elle gömülmüş** (`RecordType`, `IPAddress`, `NodeAddress`,
`LocalSequenceNumber`, `SubscriptionID`, `TimeStamp`, `ServiceContextID` —
hepsinin gövdesi dolu). Boş bırakılan tek tip `ManagementExtension`, yani
tek bir aktarım boşluğu.

İki aday kaldı:

| aday | dayanak | zayıf yanı |
|---|---|---|
| **A —** `SEQUENCE { identifier OBJECT IDENTIFIER, significance [1] BOOLEAN DEFAULT FALSE, information [2] OCTET STRING }` | Yorumlanmış IMPORTS bu tipi **3GPP generic** modülden aldığını söylüyor; korpustaki **10 modül** birebir bu gövdeyi taşıyor (`LTE-R10`, `GGSNTurkcellCdrR7`, `CDRF-R7/R9`, `LTE-R8` …) | IMS-R8'in kendi stub'ı `SET` diyor, bu `SEQUENCE` |
| **B —** `SET { totalNumberOfMessagesSent [1], contributionId [2], nodeId [3], … }` | Stub'daki `SET` anahtar kelimesiyle uyuşuyor; `UAGRecordsBer` / `UAGRecordsBer2` aynı IMS/UAG soyundan | Yalnızca 2 modül; IMPORTS'un işaret ettiği generic modül değil |

⚠️ **A, EMM tarafından doğrulanmış DEĞİL.** `LTE-R10` ve `GGSNTurkcellCdrR7`
bu tipi tanımlıyor ama **hiçbir alanda kullanmıyor** — ölçüldü. Yani o iki
kabul edilmiş dosyada EMM hiç `ManagementExtension` çözmedi. A'nın gücü
korpus tutarlılığından ve IMPORTS satırından geliyor, dış kanıttan değil.

EMM'in hatası da ayırt etmiyor: `04 08` gönderdik, hem `30` hem `31` beklentisi
bunu reddederdi.

İki yol var ve ikisi birlikte de yürütülebilir: (1) Yasin'den **EMM'in
`GenericChargingDataTypes`'taki `ManagementExtension` tanımı**nı istemek,
(2) tek turda A/B çifti göndermek — `IMSCDRS` ve `CHF` tam olarak böyle
çözülmüştü. Üçüncü bir dosya (`recordExtensions` hiç yazılmadan) kalan
~1830 baytın doğruluğunu bağımsız olarak kanıtlar; EMM 2343 baytın yalnızca
513'üne kadar okuyabildi.

**Kapsam:** korpusta gövdesi boş yapısal tip **9 adet / 3 modülde** —
`IMS-R8-2009-03` (1), `NRTRDEFdrFile` (4), `NRTRDEFERFile` (4).

**Ara davranış olarak önerilen (henüz uygulanmadı):** alanı OPTIONAL ve tipi
alanı olmayan bir yapısal tipse, yaprak yazmak yerine **alanı hiç yazmamak**.
Bilerek yanlış bir opak değer koymaktan iyidir ve dosyayı çözülebilir kılar.
Bilinmeyen (registry'de olmayan) tip adı bunun dışında kalmalı: orada tipin
primitive olma ihtimali var, yaprak makul bir varsayılan.

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

### 16. turda gönderilen dosyalar

Sorumluya gönderildi. Bu turda, `IMS-R8-2009-03` dosyasındaki
`ManagementExtension ::= SET {}` boş gövdesi için `31 00` kodlaması test edildi.
Dosya, 15. turda gönderilen dosyanın üzerinden yalnızca `recordExtensions` kısmı
değiştirilerek oluşturuldu; geri kalan baytlar aynıdır.

| # | dosya | bayt | sonuç | SHA-256 |
|---|---|---|---|---|
| 1 | `IMS-R8-2009-03-A.ber` | 2335 | ❌ red — ama başka alanda | `a175b59782472e3cddcb94b4edf67109640fba98adeae37376e30b34d5474c8a` |

- **Kök uzunluğu**: `bf 45 82 09 1a`
- **recordExtensions byte'ları**: `b9 02 31 00`

#### ✅ 16. tur sonucu (19.08.2026) — `[25]` doğrulandı, hata ileri taşındı

Dosya yine reddedildi, ama **`recordExtensions` hatası ortadan kalktı**:

```
Invalid length 2065 of field "list-of-Call-Transfer-Info"
```

**Ne kanıtlandı.** EMM'in durduğu ofset **513 → 2065**'e taşındı. Aradaki
mesafe ölçüldü: 15. turda kök gövdesinin ilk 20 üst düzey bileşeni okunabilmişti,
16. turda **113**'ü okundu — yani **93 yeni bileşen / 1560 yeni bayt** çözüldü.

Bu, `ManagementExtension ::= SET {}` okumasının **EMM tarafından doğrulanması**
demek. `04 08 <8 bayt>` yaprağı yerine `31 00` (X.690 8.11: bileşeni olmayan bir
SET'in içerik okteti yoktur) yazmak doğruymuş. Aynı zamanda Yasin'in
"`GenericChargingDataTypes` kullanılmıyor, sadece yorumda var" bilgisini dolaylı
olarak teyit ediyor: aday A (`SEQUENCE` gövdeli 3GPP generic) ya da aday B
(`UAGRecordsBer`'in `SET` gövdesi) doğru olsaydı, boş bir `31 00` o noktada
reddedilirdi. Şema eksik değilmiş — boş `SET` tanımın kendisiymiş.

11. turda çıkarılan kural (*"Invalid length N" bir uzunluk değil, düşen düğümün
bitiş ofseti*) beşinci kez doğrulandı: `[428]` düğümü ofset 2031'de başlıyor,
**2065**'te bitiyor; TLV uzunluğu 30, dosya boyutu 2335 — ikisi de değil.

##### 🔴 Bulgu 5 — iç içe koleksiyonun orta katmanı yazılmıyor

Yeni hatanın kök nedeni `ManagementExtension`'dan bağımsız ve **şema kusuru
değil**; zincirin üç katmanı da tam gövdeli:

```
list-of-Call-Transfer-Info [428] SEQUENCE OF Call-Transfer-Info-List OPTIONAL
Call-Transfer-Info-List   ::= SEQUENCE OF Call-Transfer-Info
Call-Transfer-Info        ::= SET { call-Transfer-Type [0] OPTIONAL,
                                    call-Transfer-Data [1] UTF8String OPTIONAL }
```

**İki koleksiyon katmanı var, biz bir tane yazıyoruz.** `AsnField.repeated` bir
boolean — "koleksiyon mu?" sorusunu cevaplıyor, "kaç katman?" sorusunu değil.
`resolveAlias` ikinci `SEQUENCE OF`'u açıyor ama onu kaydedecek yer yok, ve
`children` doğrudan `Call-Transfer-Info`'nun alanlarını taşıyor.

| | bayt |
|---|---|
| gönderilen | `BF 83 2C 1E  31 0D 80 01 00 81 08 ..  31 0D 80 01 00 81 08 ..` |
| olması gereken | `BF 83 2C 20  30 1E  31 0D ..  31 0D ..` |

X.690 8.10: bir `SEQUENCE OF T`'nin içeriği **T'nin kendi kodlamalarının**
birleşimidir. Burada T = `Call-Transfer-Info-List`, o da bir `SEQUENCE OF` —
yani kendi `30` TLV'sini taşımak zorunda. `[428]` IMPLICIT olduğu için yalnızca
**dış** koleksiyonun universal tag'inin yerine geçiyor; orta katmana dokunmuyor.

**Kapsam ölçüldü.** Korpusta iç içe koleksiyon **36 site / 20 modül**, hepsi tam
olarak 2 katman (3 katman yok). Ama 36'dan **yalnızca 1'i üretilen ağaçta**:
`IMS-R8-2009-03.ATSRecord.list-of-Call-Transfer-Info`. Kalan 35'i
`ContextParameterValueType.groups` ve XML modüllerindeki muadilleri — hiçbiri
modülünün seçili kökünden erişilebilir değil. Bu önemli, çünkü aralarında
EMM'den geçmiş üç yapı var (`CHAD`, `TurkcellCDRCCNCS5`,
`CCNCS55_UpdatedCCR_CCN`); erişilemedikleri için baytları değişemez.

**Karşıt örnek — bozulmaması gereken tek katmanlı desen.** MMTel'in
`list-Of-Calling-Party-Address ::= ListOfInvolvedParties`,
`ListOfInvolvedParties ::= SEQUENCE OF InvolvedParty` sitesi EMM'den geçmiş ve
gerçek yakalamaya karşı doğrulanmış. Orada alan satırında **kendi** koleksiyonu
yok, tek katman var. Ayırt edici koşul tam olarak bu: alanın kendisi koleksiyon
**ve** adlandırdığı eleman tipinin de koleksiyon alias'ı olması.

### 17. turda gönderilen dosyalar

Tek dosya, tek değişken. 16. turun A dosyasının üzerinden **yalnızca `[428]`
düğümü** yamalanarak üretildi; `[25]` dahil diğer her bayt aynı.

| # | dosya | bayt | sonuç | SHA-256 |
|---|---|---|---|---|
| 1 | `IMS-R8-2009-03-A-round17.ber` | 2337 | ✅ **PASS** | `3c7f0b1940efef823d2e5176005a429eb47aa6bbe46b7ccdb9495ac304955ef3` |

- **Kök**: `bf 45 82 09 1c` (len=2332, dosya sonuyla örtüşüyor); 126 üst düzey
  bileşen, boşluksuz döşeme
- **`recordExtensions [25]`**: `b9 02 31 00` — **16. turun düzeltmesi korundu**
- **`list-of-Call-Transfer-Info [428]`**:
  `bf 83 2c 20  30 1e  31 0d 80 01 00 81 08 ..  31 0d 80 01 00 81 08 ..`
- 16. tur A dosyasına göre değişmeyen bölgeler: `[25]` öncesi ve kendisi,
  `[25]`–`[428]` arası **1526 bayt**, `[428]` sonrası **270 bayt** — hepsi birebir

Dosya `scratchpad/round17/` altında duruyor (`target/` değil: 14. turda dosyalar
`mvn clean` ile silinmiş ve üretici tohumsuz olduğu için geri getirilememişti).

#### ✅ 17. tur sonucu (19.08.2026) — PASS, `IMS-R8-2009-03` tam kabul

Sorumlunun ifadesiyle *"dosya başarıyla işlendi"* — önceki hiçbir hata mesajı
dönmedi. Bu, 14. turdan beri üç ayrı bulguyla uğraşılan `IMS-R8-2009-03`'ün
**ilk tam kabulü**:

| tur | hata | düzeltme |
|---|---|---|
| 14 | akış yok, koşulamadı | — |
| 15 | `Invalid length 513` @ `recordExtensions` | `ManagementExtension ::= SET {}` → `31 00` (16. tur ile doğrulandı) |
| 16 | `Invalid length 2065` @ `list-of-Call-Transfer-Info` | iç içe koleksiyonun orta katmanı → `30` sarmalayıcı (17. tur ile doğrulandı) |
| 17 | — | **PASS** |

İki ayrı kod değişikliği, iki ayrı EMM turunda, ikisi de tek-değişken disipliniyle
doğrulandı: her turda önceki turun kabul edilen baytları elde tutuldu, yalnızca
tartışmalı düğüm yamalandı, ve SHA gönderimden önce yazıldı. Bu, `IMSCDRS` ve
`CHF`'in altı-yedi tur süren eleme sürecine göre çok daha hızlı kapandı — çünkü
her iki bulgu da (boş `SET` gövdesi, iç içe koleksiyonun kayıp katmanı) tahmine
değil doğrudan X.690'ın ilgili maddesine dayanıyordu.

**Kapsam güncellendi:**

| | 18.08 (15. tur sonrası) | 19.08 (17. tur sonrası) |
|---|---|---|
| Kanıtlı davranış sınıfı | 21 / 32 | **23 / 32** |
| EMM'den geçen yapı | 25 | **26** |

**Bu turda bilerek çözülmeyen:** düzeltmeden sonra self-check `[428]` altında
4 uyarı veriyor (`duplicate-tag`, `set-ordering`, 2× `walker`). `BerVerifier`
yeni orta `30` düğümünü şemasız geziyor ve iki `31` elemanını "aynı tag iki kez"
sanıyor. `DuplicateTagRule` UNIVERSAL sınıf tekrarını zaten **her zaman WARNING**
sayıyor (120 modüllük "ALLOPTIONAL" emsali), `errors=0` kaldı ve STRICT modda
dosya üretilmeye devam ediyor. Verifier'ın koleksiyon farkındalığı ayrı bir iş.


## 4. Açık sorular

### 🔴 AÇIK — `PLMN-Id` kodlaması doğrulanmadı (19.08.2026)

`servingNodePLMNIdentifier` ve `pGWPLMNIdentifier` (`PLMN-Id ::= OCTET STRING
(SIZE (3))`, LTE-R10 ailesi + CHF) için **hiçbir bağımsız bayt kanıtı yok** ve
kodda hiçbir kodlama yolu tanımlı değil. Aranan yerler:

| kaynak | sonuç |
|---|---|
| MMTel referans yakalaması (29 MB, EMM-kabullü) | `MMTelChargingDataTypes` şemasında `PLMN-Id` tipi **yok** |
| `P4_*_ASCII` / `P5_*_ASCII` etiketli decode'lar | yalnızca `vplmnId [308] UTF8String` — farklı alan, düz metin |
| `target/emm-corpus-r15/` (SHA'lı kabul edilmiş dosyalar) | `PLMN-Id` taşıyan modül (LTE-R10, CHF) bu klasörde yok |

⚠️ **Validator bu alanı yanlışlıkla geçirebilir.** `matchesOctetString`'in son
adımı yalnızca "çift uzunlukta hex karakter mi" diye bakar. AI'ın ürettiği
`"28601"` (5 hane, tek sayı) reddedilirken `"286010"` (6 hane, çift sayı)
**geçiyor** — çünkü tüm karakterleri `[0-9]` olduğu için geçerli bir hex dökümü
sanılıyor. Yani alan "temiz" görünse de içerik doğrulanmış değil; 3GPP TS 24.008
10.5.1.13'ün MCC/MNC → 3 bayt paketlemesi **uygulanmıyor**.

Ders kitabı bilgisiyle encoder yazmak bilerek reddedildi: bu günlüğün kendi
tarihi (`IMSCDRS`, `CHF`, ve 8. bölümdeki "şema ile gerçeklik ayrışması")
doğrulanmamış standart varsayımının bu ailede tekrar tekrar yanlış çıktığını
gösteriyor.

**Kapanması için gereken:** ya Yasin'den EMM'in decode ettiği bir `PLMN-Id`
örneği, ya da A/B probe turu (paketli BCD vs. düz metin) — `IMSCDRS` ve `CHF`
tam olarak böyle çözülmüştü.

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
