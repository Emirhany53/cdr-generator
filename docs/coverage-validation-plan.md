# Kapsam Doğrulama Planı — 808 Yapı

Amaç artık hata bulmak değil, **generator'ın kapsamını doğrulamak**: "Bu program
sadece MMTel'de mi çalışıyor, yoksa diğer yüzlerce yapıda da doğru BER üretiyor mu?"

Kod yazılmadan önce okunacak.

## 0. Önce dürüst tespit: neyin zaten kanıtlı olduğu

`AllModulesRoundTripTest` **802 modülün tamamı** için şunu zaten koşuyor ve yeşil:

```
ASN.1 → Tree Resolver → Field Tree → Random Value → BER → Self Check
```

Yani planın 3. adımındaki akışın **ASN1VE hariç her halkası bugün otomatik test
altında.** `ShippedFieldRulesRoundTripTest` aynı 802 modülü gerçek yml kurallarıyla
tekrar koşuyor. Bunları yeniden inşa etmek yeni bilgi üretmez.

**Yeni bilgi tek bir yerden gelebilir: bağımsız bir çözücü.** `docs/self-check-design.md`
bunu baştan söylüyordu:

> Doğrulayıcı encoder'ın varsayımlarını paylaşır. Encoder ve doğrulayıcı aynı
> `AsnField` ağacını okuduğu için, ağacın kendisi yanlışsa ikisi de aynı şekilde
> yanılır.

Self-check "bizim ağacımıza göre tutarlı mı" der; ASN1VE "**yazılı ASN.1'e göre
doğru mu**" der. Kapsam doğrulamasının bel kemiği bu ikincisidir.

## 1. 808 yapının ölçülmüş profili

Gerçek resolver (`tools/mmtel_resolver_port.py`) ile 802 modül çözüldü
(6 modül boş gövdeli yardımcı, üretime girmiyor).

| Ölçü | min | medyan | p90 | maks |
|---|---|---|---|---|
| Yaprak alan | 1 | 19 | 65 | 378 |
| Derinlik | 1 | 1 | 3 | 7 |
| OPTIONAL alan | 0 | 19 | 64 | 378 |
| En büyük tag | 0 | 19 | 136 | 1002 |

| Özellik | Modül sayısı |
|---|---|
| Varsayılan **EXPLICIT TAGS** | **707** |
| Varsayılan IMPLICIT TAGS | 95 |
| Kök SEQUENCE / CHOICE / SET | 697 / 97 / 8 |
| İçinde CHOICE geçen | 129 |
| İçinde SEQUENCE OF geçen | 83 |
| İçinde SET geçen | 35 |
| Uzun-form tag (>127) | 83 |
| APPLICATION sınıfı tag | 8 |
| UNIVERSAL tag override | 6 |
| ≥10 yaprak (gerçek CDR adayı) | 585 |
| ≤3 yaprak (küçük/yardımcı) | 89 |
| DBLookupTable/ALLOPTIONAL türü | 27 |

### En kritik bulgu

**802 modülün 707'si varsayılan `EXPLICIT TAGS` kullanıyor. MMTel ise `IMPLICIT`.**

EMM'den geçirdiğimiz her şey (P4, P5, referans yakalamalar) IMPLICIT dünyasına ait.
EXPLICIT tagging tel üstünde **temelden farklı bir şekil** üretir: her etiketli alan
için fazladan bir sarmalayıcı TLV. Bu yol bugüne kadar **hiçbir bağımsız çözücü
tarafından doğrulanmadı** ve elimizde tek bir EXPLICIT referans yakalaması yok.

Ek risk: `AsnFieldTreeResolver`, MMTel ailesi için yazılı EXPLICIT'i bilinçli olarak
**etkisizleştiriyor** (`isVerifiedExplicitNeutralizationFamily`). Bu sezgi MMTel
dışına sızarsa self-check fark etmez — çünkü encoder ve doğrulayıcı aynı ağacı okur.
ASN1VE ise yazılı ASN.1'e göre çözdüğü için **tam olarak bu sınıf hatayı yakalar.**

Sınanacak somut hipotez: *EXPLICIT varsayılanlı modüllerde ürettiğimiz sarmalayıcı
katman sayısı, yazılı ASN.1'in gerektirdiğiyle birebir aynı mı?*

## 2. Temsilci yapılar (22)

808'i tek tek denemek yerine üç katman. **Negatif kontrol şart**: her şeyi kabul eden
bir oracle hiçbir şey kanıtlamaz.

### Katman A — özellik kapsamı (12)

| Yapı | Yaprak | Derinlik | Maks tag | Tagging | Kök | Neden seçildi |
|---|---|---|---|---|---|---|
| `MMTelChargingDataTypesV3` | 185 | 6 | 566 | IMPLICIT | CHOICE | Kanıtlanmış taban çizgisi; iç içe CHOICE, SET, UNIVERSAL override |
| `RepositoryBroadSoftIN` | 350 | 1 | 1001 | EXPLICIT | SEQUENCE | Çok-baytlı tag + çok geniş + EXPLICIT yoğun |
| `RepositoryNextoneIN` | 189 | 1 | 1002 | EXPLICIT | SEQUENCE | En büyük tag numarası |
| `BroadSoft` | 378 | 1 | 378 | EXPLICIT | SEQUENCE | En geniş yapı |
| `TAP0309` | 145 | 7 | 218 | EXPLICIT | CHOICE | APPLICATION sınıfı + en derin + SEQUENCE OF yoğun |
| `TAP-0309` | 144 | 7 | 218 | IMPLICIT | CHOICE | TAP0309'un IMPLICIT ikizi — **A/B karşılaştırması** |
| `SDPCCR` | 229 | 7 | 29 | IMPLICIT | CHOICE | En çok SEQUENCE OF (42) |
| `Newchf` | 191 | 7 | 399 | IMPLICIT | CHOICE | Derin + CHOICE + SET karışımı |
| `IMS-R8-2009-03` | 47 | 1 | 46 | IMPLICIT | SET | Kök SET |
| `GPRS-Charging-Extensions` | 65 | 4 | 12 | IMPLICIT | SET | Kök SET + orta karmaşıklık |
| `TELENITY_SMSC` | 37 | 1 | 37 | EXPLICIT | SEQUENCE | Tipik orta boy EXPLICIT |
| `LDAPLookup` | 5 | 1 | 0 | EXPLICIT | SEQUENCE | Uç: çok küçük, tag'siz |

`TAP0309` / `TAP-0309` çifti planın en değerli kalemi: **aynı şema, farklı varsayılan
tagging.** İkisinin çıktısı arasındaki fark, EXPLICIT/IMPLICIT yolunun doğru ayrıştığını
tek başına gösterir.

### Katman B — popülasyon örneği (8, tohumlu rastgele)

Eksenlerin modellemediğini yakalamak için: `CCNGTPforPSS`, `RepositoryBDSSIN`,
`FCMSKktcellVoice`, `EmergencyTCELLCSCF`, `MMTelChargingDataTypesV2`,
`CommonLPDetailTest`, `GSN50`, `NextoneEnriched`.

### Katman C — negatif kontrol (2)

`SMSCMatching`, `FciGgsn` — vendored ASN.1'inde aynı CONTEXT tag'i iki kez bildiren
18 modülden ikisi. **Oracle bunları reddetmeli.** Kabul ederse ayrım gücü yok demektir
ve A/B sonuçları da güvenilmez olur.

Toplam kapsama: EXPLICIT 13 / IMPLICIT 7, kök CHOICE 7 / SET 2 / SEQUENCE 11,
tag>127 11 tanesinde, tag≥1000 3 tanesinde, APPLICATION 3 tanesinde.

## 3. Mevcut altyapı ne kadar destekliyor

| İhtiyaç | Durum |
|---|---|
| 802 yapıda üret + kodla + self-check | ✅ `AllModulesRoundTripTest` |
| Gerçek yml kurallarıyla aynısı | ✅ `ShippedFieldRulesRoundTripTest` |
| Dışarıdan gelen `.ber` doğrulama | ✅ `POST /api/cdr/verify-ber` |
| Şema bilgisi kullanmadan iki dosyayı karşılaştırma | ✅ `tools/compareBerStructure.py` |
| Yapı özellik profili çıkarma | ✅ `tools/mmtel_resolver_port.py` (bu analiz onunla yapıldı) |
| **N yapı için toplu `.ber` üretimi** | ❌ API çağrı başına tek yapı; toplu yol yok |
| **Modül başına `.asn1` dışa aktarımı** | ❌ Şema metni `datastructure.json` içinde gömülü; ASN1VE dosya ister |
| **Bağımsız çözücü sonucunun kaydı** | ❌ Yok |
| **EXPLICIT modüller için referans yakalama** | ❌ Hiç yok — en büyük boşluk |
| Yapı içi varyasyon (çok kayıtlı dosya) | ❌ Testler modül başına 1 kayıt üretiyor |

Son satır ayrı bir eksen: rastgelelik (tekrar sayısı 1–2, CHOICE seçimi, AI değerleri)
yüzünden **tek kayıt, o yapının uzayını temsil etmez.**

## 4. EMM'de hangi yapılar test edilebilir

**Bu soru koddan cevaplanamaz.** EMM'in çözücü/akış tanımları EMM tarafında yaşıyor,
bu repoda değil. Elimizdeki kanıt:

- Hata mesajları kaynağı `originating from MAV_TAS` ve çözücüyü
  `MMTelChargingDataTypes` olarak veriyor → EMM **kaynak+akış bazında** bir çözücüye
  yönlendiriyor.
- Sahip olduğumuz referans yakalamaların hepsi MMTel.

Sonuç: farklı bir yapıyı aynı akıştan göndermek onu MMTel sanılarak reddedilmesine yol
açar; bu **bizim encoder'ımız hakkında hiçbir şey söylemez.** Başka tip test etmek için
sorumlunun o tip için çözücü+akış tanımlaması gerekir.

**Sorumluya sorulacak:** EMM'de halihazırda hangi CDR tipleri için çözücü tanımlı ve
bunlardan hangisine test dosyası yönlendirilebilir?

## 5. Adım adım plan

**Adım 0 — Oracle'ı sına (negatif kontrol).** Katman C'deki 2 modülü ASN1VE'ye ver.
Reddetmeli. Kabul ederse plan durur, önce oracle sorgulanır.
*Not: bu 18 modülün şeması X.680'e göre kusurlu; ASN1VE **şemayı** derlerken de
reddedebilir. Bu "veri hatalı" ile karıştırılmamalı — iki ayrı başarısızlık kipi.*

**Adım 1 — Toplu dışa aktarım.** 22 yapı için (a) modülün `.asn1` metnini dosyaya
yaz, (b) `.ber` üret. Bugün ikisi de yok; küçük bir araç yeterli.

**Adım 2 — ASN1VE ile çöz.** Her yapı için kaydet: temiz çözülüyor mu, alan sayısı
beklenenle uyuşuyor mu, değerler ürettiğimizle aynı mı.

**Adım 3 — Bulguları sınıflandır.** Üç kovaya ayır: *şema kusuru* (vendored ASN.1
zaten bozuk), *resolver hatası* (ağaç yanlış), *encoder hatası* (bayt yanlış).
Sınıflandırma olmadan düzeltme önceliklendirilemez.

**Adım 4 — Yapı içi varyasyon.** Katman A'nın en karmaşık 3 yapısı için 100'er
kayıtlık dosya üret, self-check + ASN1VE. Tek kaydın göremediği rastgelelik uzayını
tarar.

**Adım 5 — Genişlet.** 22 temiz geçerse aynı akışı 585 "gerçek CDR adayı" modüle
yay. ASN1VE'de toplu/CLI modu varsa otomatik, yoksa katman B'yi büyütüp örneklemle.

**Adım 6 — EMM.** Yalnızca sorumlunun çözücüsü olduğunu doğruladığı tiplerle, ve
tercihen tek turda çok kayıtlı dosyayla (tur başına ~1 iş günü).

## 6. Açık sorular

1. **ASN1VE'nin toplu/CLI modu var mı?** Varsa adım 5 otomatikleşir; yoksa elle
   yapılabilir sayı ~22'de kalır ve örneklem stratejisi buna göre kurulur.
2. EMM'de hangi tipler için çözücü tanımlı? (bkz. bölüm 4)
3. Atlanan 9 implicit-CHOICE alanı iş tarafında gerekiyor mu? Gerekiyorsa Ericsson
   TR'si kovalanmalı; gerekmiyorsa skip kalıcı çözüm olur.
