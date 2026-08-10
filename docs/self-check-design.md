# Yerel Doğrulama Döngüsü — Tasarım

Madde 8'in tasarımı. Kod yazılmadan önce okunacak.

## Problem

Bugün üretilen bir `.ber` dosyasının doğru olup olmadığını anlamanın tek yolu
onu EMM'e göndermek. `tools/` altındaki 10 Python scripti bu boşluğu kapatıyor
ama:

- elle çalıştırılıyorlar, üretim akışının parçası değiller
- `mvn test` onlardan haberdar değil, yani regresyon yakalanmıyor
- `mmtel_resolver_port.py` **868 satır** — `AsnTypeRegistryBuilder` +
  `AsnFieldTreeResolver`'ın Python kopyası. Yalnızca sandbox'ta JDK 21
  olmadığı için var. Gerçek uygulamada Java resolver zaten elimizde.

Sonuç: hatalar ancak EMM'e gönderilip reddedildikten sonra öğreniliyor. Her
tur bir iş günü.

## Fikir: gidiş-dönüş (round-trip) öz-denetimi

Encoder bir kaydı yazdıktan hemen sonra, **aynı `AsnField` ağacını** kullanarak
ürettiği baytları geri okuyup beklenen şekille karşılaştırırız.

```
buildRecordFromFields ──► encodeRecord ──► [bytes]
                                             │
                            List<AsnField> ──┴──► BerVerifier ──► BerVerificationResult
```

Kritik nokta: doğrulayıcı şemayı **yeniden yorumlamaz**. Üretimde kullanılan
`List<AsnField>` ağacını girdi olarak alır. Bu yüzden Python portundaki gibi
868 satırlık bir kopya gerekmez ve iki taraf birbirinden ayrı düşemez.

## Paket yapısı

```
service/verify/
├── TlvReader.java                 ham TLV okuyucu (TlvWriter'ın aynası)
├── TlvNode.java                   record: tagClass, tagNumber, constructed,
│                                          valueStart, valueEnd, children
├── BerFinding.java                record: severity, path, message
├── BerVerificationResult.java     record: List<BerFinding> findings
├── BerVerifier.java               ağacı yürür, kuralları uygular
└── rule/
    ├── VerificationRule.java      arayüz
    ├── DuplicateTagRule.java      EMM'in verdiği hatanın ta kendisi
    ├── SetOrderingRule.java       SET bileşenleri artan tag sırasında mı
    ├── TagShapeRule.java          sarmalayıcı katmanları AsnField'ı tutuyor mu
    ├── NamedNumberRule.java       {name(n)} kümesi dışında değer var mı
    └── IntegerRangeRule.java      (min..max) kısıtı ihlal edilmiş mi
```

`TlvReader` bugün eksik olan parça: `TlvWriter` var ama okuyucusu yok.
Doğrulama için zaten gerekiyor ve simetriyi tamamlıyor.

`VerificationRule` arayüzü SOLID'in açık/kapalı ilkesi için: yeni kontrol =
yeni sınıf, mevcut kod değişmez.

## Kural arayüzü

```java
public interface VerificationRule {
    void check(TlvNode node, AsnField field, VerificationContext ctx);
    String getName();          // yml'de tek tek açılıp kapatmak için
}
```

`VerificationContext` bulguları toplar ve o anki yolu (`mMTelRecord.recordExtensions...`)
tutar — EMM'in hata mesajıyla aynı biçimde, karşılaştırılabilsin diye.

## Kuralların kaynağı

| Kural | Nereden geliyor | Ne yakalar |
|---|---|---|
| `DuplicateTagRule` | `checkDuplicateTags.py`, `verifyGenericBer.py` | EMM'in "Duplicate Tag data found" hatası |
| `SetOrderingRule` | `verifyGenericBer.py` | DER SET sıralaması (commit `f1ef9fe`) |
| `TagShapeRule` | `verifyMmtelBerFull.py` | fazladan/eksik universal SEQUENCE-SET katmanı, EXPLICIT sarmalayıcı |
| `NamedNumberRule` | `checkNamedNumberValues.py` | `epf1-Role-of-Node=85038` sınıfı hatalar |
| `IntegerRangeRule` | bugünkü `7ce5a3e`/`7c7acab` commit'leri | `Milliseconds ::= INTEGER (0..999)` ihlali |

Ek olarak ileride `ChronologyRule` — madde 4'ün (zaman tutarlılığı) kontrolü
buraya doğal olarak oturur: `serviceDeliveryEnd >= serviceDeliveryStart`.

## Konfigürasyon

```yaml
app:
  cdr:
    self-check:
      mode: strict          # strict | warn | off
      rules:
        duplicate-tag: true
        set-ordering: true
        tag-shape: true
        named-number: true
        integer-range: true
```

- `strict` — bulgu varsa dosya **döndürülmez**, `422` ile bulgular döner
- `warn` — `log.warn`, dosya yine de döner
- `off` — doğrulama atlanır

**Varsayılan `strict` öneriliyor.** Amaç "tam anlamıyla çalışan proje" ise,
bozuk bir dosyanın API'den çıkmaması gerekir. Geliştirme sırasında `warn`'a
düşürülebilir.

## Bağlanacağı üç yer

**1. Üretim akışı.** `BerGeneratorController` her `encodeRecord` çağrısından
sonra doğrular. (Not: controller şu an döngüyü kendi içinde tutuyor; bu mantık
bir `BerGenerationService`'e taşınırsa controller ince kalır ve doğrulama
servisin içinde olur. Ayrı ve isteğe bağlı bir iyileştirme.)

**2. Yeni endpoint.** `POST /api/cdr/verify-ber` — multipart `.ber` + `structureName`
alır, `BerVerificationResult` döner. Sorumlunun geri gönderdiği dosyaları,
EMM'in ürettiği çıktıları elle script çalıştırmadan kontrol etmeyi sağlar.
`tools/` scriptlerinin yerini bu alır.

**3. Test.** `AllModulesRoundTripTest` — `scanAllModules.py`'nin JUnit hali.
808 modülün her biri için 1 kayıt üretir, doğrular, temiz olmasını bekler.
Bilinen ve kabul edilmiş istisnalar için bir allowlist tutulur; listeye yeni
bir modül eklemek bilinçli bir karar olur, sessiz bir regresyon değil.

Asıl kazanç bu üçüncüsü: `mvn test` artık bu hata sınıfını yakalar.

## Uygulama sırası (her adım = 1 commit)

1. `TlvReader` + `TlvNode` + testi — saf, bağımlılıksız, kolay test edilir
2. `BerFinding` / `BerVerificationResult` / `VerificationContext`
3. `DuplicateTagRule` + testi — EMM'in gerçek hatası, en yüksek değer
4. `BerVerifier` orkestrasyonu + yml konfigürasyonu + controller'a `warn` modunda bağlama
5. Kalan dört kural, teker teker, her biri kendi testiyle
6. `POST /api/cdr/verify-ber`
7. `AllModulesRoundTripTest`
8. Varsayılanı `strict`'e çevir

1-3 arası tek başına bile değerli: EMM'in bugüne kadar verdiği tek hata sınıfını
yerel olarak yakalar hale geliriz.

## Dikkat edilecekler

- **Maliyet.** Doğrulama kodlama işini iki katına çıkarır. `recordCount ≤ 100`
  için ihmal edilebilir, ama `AllModulesRoundTripTest` 808 modül gezeceği için
  test süresi ölçülmeli.
- **`tools/` scriptleri silinmemeli.** `compareBerStructure.py` şema bilgisi
  kullanmadan iki dosyayı karşılaştırıyor — yani "benim öngörmediğim hata"yı
  yakalayabilen tek araç. `BerVerifier` şemadan türediği için o yeteneğe sahip
  değil. İkisi birbirini tamamlıyor.
- **Doğrulayıcı encoder'ın varsayımlarını paylaşır.** Encoder ve doğrulayıcı
  aynı `AsnField` ağacını okuduğu için, ağacın kendisi yanlışsa ikisi de aynı
  şekilde yanılır. Bu bilinçli bir takas: gerçek referans dosyayla karşılaştırma
  (`compareBerStructure.py`) bu boşluğu kapatan ayrı araç olarak kalır.
