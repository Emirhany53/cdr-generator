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
| `FDRInput` | BER | Tip-seviyesi `[APPLICATION 1]` **IMPLICIT** — `61 2D 42 12 …` kabul edildi |
| `Audit_Record_Collection_St` | BER | Tip-seviyesi `[APPLICATION 21]` **IMPLICIT** — `75 40 16 08 …` kabul edildi |
| `CGSN40ber` (qos ikilisi) | BER | **OCTET STRING semantiği çözüldü** — aşağıya bak |

## 2. EMM ile kanıtlanan tagging kuralları

1. `[n] EXPLICIT SEQUENCE OF <CHOICE>` → iç universal SEQUENCE **yok**
2. `[n] EXPLICIT <SET>` (skaler) → sarmalayıcı **yok**
3. `[n] EXPLICIT <BOOLEAN>` → **IMPLICIT** (`AB 03 01 01 FF` reddedildi, `8B 01 FF` istendi)
4. Başlığı `IMPLICIT TAGS` demeyen modülde **alan** tag'i → **IMPLICIT**
5. Başlığı `IMPLICIT TAGS` demeyen modülde **tip** tag'i de → **IMPLICIT** (9. tur, commit `f5ce531`)

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
| 7 | FDRInput, Audit_Record_Collection_St, IMSChargingDataTypes | 9. tur ile birlikte yanıtlandı |
| 8 | CGSN40ber, TurkcellCDRCCNCS5, CHAD, SMSCBerCdr | **4/4 PASS** |
| 9 | FDRInput, Audit_Record_Collection_St, IMSChargingDataTypes, GGSN-qos ikilisi, CHFChargingDataTypes16 | 4 red + 1 çift red — 2'si gerçek kodlama bulgusu, 3'ü yönlendirme |
| 10 | FDRInput, Audit (düzeltilmiş), CGSN40ber-qos ikilisi, CHF (rootType'lı) | **4 PASS** + qos ASCII decode döndü; CHF `Invalid length 102` |
| 11 | CHF: explicit-kept, explicit-collapsed, nfci-minimal | 3 red — ama üçü de bilgi verdi (aşağıda) |
| 12 | CHF NFI bisect: A/B/C | **HAZIR — gönderilmedi** |

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

| sonuç | çıkarım |
|---|---|
| A PASS, B PASS, C FAIL | Sorun `[n] EXPLICIT <CHOICE>` alanında → tek ve net hedef |
| A PASS, B FAIL | Sorun `[1]` IA5String ya da `[3]` PLMN-Id'de — muhtemelen değer düzeyinde |
| A FAIL | Sorun NFI'nin kendi çerçevesinde; daha yukarı bakılır |

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
