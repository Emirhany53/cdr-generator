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
| 8 | CGSN40ber, TurkcellCDRCCNCS5, CHAD, SMSCBerCdr | **YANIT BEKLENİYOR** (7 ile paralel gönderildi) |

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

### 🟡 Tip-seviyesi `[APPLICATION n]` tagging — 7. turda gönderildi

- **Problem:** `FDRInput` (`NrFile ::= [APPLICATION 1] SEQUENCE`) ve
  `Audit_Record_Collection_St` (`LogEntry ::= [APPLICATION 21] SEQUENCE`)
- **Kritik ölçüm:** EMM'den geçen 4 dosyanın **hiçbirinde APPLICATION sınıfı tag
  yok** (hepsi 0; tümü CONTEXT kullanıyor). Bu sınıf hiç sınanmadı.
- **Mevcut davranış:** X.680 31.2.7 gereği sarmalayıcı — `61 37 30 35 …`
- **Alternatif:** `61 35 …` (universal SEQUENCE kalkar, 2 bayt kısa)
- **Reddedilirse:** `AsnFieldTreeResolver.readLeadingTag` içinde tek satır değişir

#### ⚠️ Yanıt geldiğinde okuma sırası — bu olmadan sonuç yanlış yorumlanır

**1. Önce `Audit_Record_Collection_St`'e bak. Temiz deney odur.**
İç alanlarının **hiçbirinde tag yok** — hepsi çıplak `16` (IA5String) ve `02`
(INTEGER). Tek tip-seviyesi tag kökteki `[APPLICATION 21]`. Yani tek değişken:
sonuç doğrudan `readLeadingTag`'i yanıtlar.

**2. `FDRInput` tek başına delil DEĞİLDİR.** Şemasında ayrı bir kusur var:

```
FileReceivedTime ::= [APPLICATION 4] IA5String (SIZE(6))
UTCCode          ::= [APPLICATION 4] IA5String (SIZE(5))
```

İki farklı tip **aynı tag'i** taşıyor ve üretilen BER'de yan yana duruyorlar
(`6408 …`, `6407 …`). Audit bunu WARNING olarak yakalıyor
(`FDRInput.NrFile.A-[4]`, "position resolves it: X.680 25.6"). Ayrıca FDRInput
soruyu tek noktada değil **5 noktada** soruyor: kök `[APPLICATION 1]` + iç içe
`[APPLICATION 2/3/4]` (alanlar tiplerinden tag miras alıyor).

Sonuç: **FDRInput reddedilirse sebep üç ayrı şey olabilir** — tip-seviyesi
tagging, duplicate tag, ya da iç içe miras. Audit'in sonucuna bakmadan ayırt
edilemez.

| Audit | FDRInput | Çıkarım |
|---|---|---|
| PASS | PASS | `readLeadingTag` doğru, iç içe miras da tolere ediliyor — iş biter |
| PASS | REJECT | Tagging doğru; sorun duplicate tag ya da iç içe miras. **Kodu değiştirme**, önce hangisi olduğunu ayır |
| REJECT | — | X.680 31.2.7 okuması yanlış → `readLeadingTag` değişir, ama önce etki analizi (aşağı bak) |

**3. `IMSChargingDataTypes` bunlardan bağımsızdır.** ENUMERATED sorusu; ayrı
değerlendirilir, diğer ikisinin sonucundan etkilenmez.

#### Etki alanı — bu sorunun ağırlığı gönderilen dosyalarda değil

`audit.tsv` üzerinden ölçüldü (12.08.2026, commit `184c717`):

| ölçü | değer |
|---|---|
| UNSPECIFIED modüllerde toplam EXPLICIT alan | **426** (23 modülde) |
| bunlardan yazılı `EXPLICIT` keyword'ü olmayan (saf tip-seviyesi) | **331** |
| **`TAP0309` tek başına** | **298** — 426'nın %70'i, 331'in %90'ı |

`TAP0309`'da yazılı `EXPLICIT` keyword'ü **sıfır**; 298 sitenin tamamı 316 adet
`[APPLICATION n]` tip tanımından geliyor (`TransferBatch ::= [APPLICATION 1]
SEQUENCE` gibi), 7 seviye derinlikte. Somut bedel: `TAP0309` 8484 bayt,
IMPLICIT ikizi `TAP-0309` 5622 bayt — sarmalayıcılar dosyayı **%51 büyütüyor.**

Karşılaştırma: gönderilen iki probe dosyası bu yapıyı **4 ve 1** noktada sınıyor
(57 ve 69 bayt). EMM'in bu iki küçük dosyaya vereceği yanıt, farkında olmadan
298 siteye genellenecek.

**Bu yüzden:** `readLeadingTag` değiştirilecekse önce `TAP0309` üzerinde etki
analizi yapılmalı. Ve Yasin'e sorulacak: **EMM'de TAP/TAP3 için tanımlı çözücü
var mı?** Varsa `TAP0309` 8. turun en yüksek öncelikli kalemidir — çünkü sorunun
gerçek yükü orada.

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
