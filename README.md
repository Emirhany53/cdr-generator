# EMM CDR Generator

Turkcell'in Ericsson Mediation Manager (EMM) akışlarını beslemek için, kayıtlı
ASN.1 şemalarına uygun test CDR'ı üreten bir **Java 21 / Spring Boot**
uygulaması. Aynı şemadan üç çıktı üretir: **BER (`.ber`)**, byte-for-byte aynı ikili
kopyası **`.dat`**, ve **Token-Separated ASCII (`.txt`)**.

`datastructure.json` içinde **808 ASN.1 modülü** var; uygulama açılışta hepsini
ayrıştırır ve 802'si için alan ağacı çıkarır.

---

## Bu projede asıl mesele ne?

Geçerli BER yazmak kolay kısmı. Zor kısmı, **EMM'in kabul ettiği** BER'i yazmak:
şemadaki bir satırın tel üzerinde tam olarak hangi baytlara karşılık geldiği her
zaman şemadan okunamıyor. Bu yüzden proje iki şeyi ayrı tutar:

- **Doğruluk** — X.690'a uygun, kendi alan ağacıyla tutarlı baytlar. Kendi
  içinde kanıtlanabilir; `self-check` bunu her üretimde ölçer.
- **Uygunluk** — gerçek şebekenin yazdığı ve EMM'in okuduğu baytlar. Yalnızca
  dış kanıtla bilinebilir: EMM'in yanıtı ya da EMM'in kabul ettiği bir yakalama.

İkisini karıştırmamak önemli: self-check'in temiz olması EMM'in kabul edeceği
anlamına **gelmez** — üretilen baytları, onları üreten alan ağacına karşı
denetler, yani karşılaştırmanın iki tarafı da aynı şema yorumundan gelir.
`ReferenceCaptureConformanceTest` bu boşluğu kapatan tek testtir; beklentiyi
EMM'in kabul ettiği gerçek bir yakalamadan alır.

Ailelerin doğrulanma durumu için `docs/coverage-validation-plan.md`.

---

## Hızlı başlangıç

### Backend

```bash
./mvnw spring-boot:run
```

Uygulama `http://localhost:8080/cdr-generator` altında açılır
(`server.servlet.context-path`). Swagger arayüzü:

```
http://localhost:8080/cdr-generator/swagger-ui.html
```

### Frontend (opsiyonel)

```bash
cd frontend
npm install
npm run dev
```

Backend farklı bir adresteyse `frontend/.env.example` dosyasını `.env.local`
olarak kopyalayıp `VITE_API_BASE_URL` değerini düzeltin.

### Testler

```bash
./mvnw test
```

---

## REST API

Tüm uçlar `/cdr-generator/api/cdr` altında.

### Yapıları listele

```
GET /structures
```

### Bir yapının alan ağacı

```
GET /structures/{structureName}
GET /structures/{structureName}?rootType=TokensCSCF
GET /structures/{structureName}?TokenCDR=refillRecordV2
```

Ad dışındaki her query parametresi bir **CHOICE seçimi** sayılır: anahtar CHOICE
tipinin adı, değer alternatifin adı. `rootType` ayrı tutulur — modül birden çok
üst tip tanımlıyorsa hangisinin kayıt sayılacağını seçer.

### BER dosyası üret

```
POST /generate-ber
Content-Type: application/json
```

```json
{
  "structureName": "MMTelChargingDataTypes",
  "recordCount": 3,
  "fieldValues": {
    "servedMSISDN": "905321112233"
  },
  "choiceSelections": { "MMTelServiceRecord": "mMTelRecord" },
  "rootType": null
}
```

| alan | zorunlu | açıklama |
|---|---|---|
| `structureName` | ✔ (veya `contents`) | `datastructure.json`'daki modül adı |
| `contents` | — | Kayıtlı olmayan ham ASN.1 metni (inline mod) |
| `fieldValues` | — | Elle değer. Anahtar düz bir alan adı (`servedMSISDN`) ya da noktalı bir yol (`ust.alt`) olabilir |
| `choiceSelections` | — | CHOICE tipi adı → alternatif adı |
| `rootType` | — | Kayıt olarak kodlanacak tip; modülde yoksa yok sayılır |
| `recordCount` | — | Varsayılan 1, üst sınır `max-record-count` |

Yanıt `.ber` dosyası indirir. Self-check sonucu **`X-Cdr-Self-Check`** yanıt
başlığında döner.

Ham ASN.1 metnini JSON'a kaçırmadan göndermek için:

```
POST /generate-ber/raw?structureName=Demo&recordCount=1
Content-Type: text/plain
```

### ASCII (.txt) dosyası üret

```
POST /generate
```

Gövde `generate-ber` ile aynı şekildedir. Her satır bir kayıt, alanlar `|` ile
ayrılır, kayıt ayracı her makinede `\n`'dir.

Bir değer `|`, satır sonu ya da US-ASCII dışında bir karakter taşıyorsa istek
**reddedilir** ve hata hangi kolonun sorunlu olduğunu söyler. Bu biçimde kaçış
sözleşmesi yoktur; sessizce bozuk bir dosya döndürmek yerine reddedilir.

### ASCII üret ve kolon haritasıyla dön

```
POST /generate/manifest
```

`/generate` ile aynı üretimi yapar, dosya yerine JSON döner: `text` ve onu
açıklayan `columns` listesi. `.txt`'de başlık satırı yoktur ve kolon kümesi
üretilen kayıtlara bağlıdır (bir `SEQUENCE OF` eleman sayısı kadar kolon grubu
ekler), bu yüzden aynı yapıdan üretilen iki dosya farklı genişlikte olabilir.
İkisi tek üretimden geldiği için harita metni her zaman doğru anlatır.

### Tek kayıt önizle (dosya üretmeden)

```
GET /generate-test/{structureName}
```

### Kayıtlı olmayan bir şemayı ayrıştır

```
POST /structures/parse-inline
```

```json
{ "structureName": "Demo", "contents": "Demo DEFINITIONS ::= BEGIN ... END", "rootType": null }
```

### Dışarıdan gelen bir .ber dosyasını doğrula

```
POST /verify-ber/{structureName}
Content-Type: multipart/form-data   (alan adı: file)
```

Bu servisin üretmediği bir dosyayı — bir referans yakalama ya da EMM'in geri
gönderdiği bir dosyayı — verilen yapının alan ağacına göre denetler. Üretim
akışından bağımsızdır, `self-check.mode` ayarından etkilenmez, her zaman tüm
bulguları döner.

---

## Yapılandırma

`src/main/resources/application.yml`:

```yaml
app:
  cdr:
    data-structure-path: "src/main/resources/datastructure.json"
    default-record-count: 1
    max-record-count: 100
    skip-implicit-choice-fields: true

    self-check:
      mode: strict        # strict | warn | off
      rules:
        duplicate-tag: true
        set-ordering: true
        tag-shape: true
        named-number: true
        integer-range: true

    ai:
      enabled: true
      provider: gemini
      max-fields-per-request: 80
      gemini:
        api-key: ${GEMINI_API_KEY:}
      field-rules:
        - name: callingNumber
          match: [ "msisdn", "callingnumber", ... ]
          description: "Turkiye GSM abone numarasi..."
          pattern: "^(\\+?90|0)?5(0|3|4|5|6)[0-9]{8}$"
          examples: [ "05301234567" ]
```

**`self-check.mode`** — `strict`: ERROR bulgusu varsa dosya döndürülmez;
`warn`: loglanır, dosya döner; `off`: kapalı. Şu an 808 modülün **17'si**
`strict` modda dosya üretemiyor; çoğunda sebep şemanın kendisinin çözülemez
olması (aynı gövdede iki OPTIONAL alan aynı tag'i taşıyor).

**`skip-implicit-choice-fields`** — IMPLICIT etiketli OPTIONAL CHOICE alanlarını
üretime katmaz. EMM'in bir çözücü kusuru için konmuş bir geçici çözümdür ve
MMTel soyuyla sınırlıdır. Kapatılması EMM'den yeni bir yanıt gerektirir.

**`ai.field-rules`** — Alan adına göre eşleşen değer kuralları (desen, açıklama,
örnekler, OCTET STRING içeriğinin ASCII mi hex mi olduğu). Hem yapay zekaya
verilen isteme hem de rastgele üretime kaynaklık eder. İlk eşleşen kural kazanır.

**Yapay zeka** — `GEMINI_API_KEY` tanımlı değilse üretim sessizce rastgeleye
düşer; ağ çağrısı yapılmaz. Üretilen her değer `FieldValueValidator`'dan geçer,
SIZE/desen kurallarına uymayan değer atılıp rastgele üretilir — yani yapay zeka
çıktının geçerliliğini bozamaz.

---

## Mimari

```
Controller ── StructureParserService ──┬── AsnTypeRegistryBuilder   (metin → tip kaydı)
                                       └── AsnFieldTreeResolver     (tip kaydı → alan ağacı)
           ── CdrRecordBuilder ────────┬── UserProvidedValueSource
                                       ├── AiValueSource + FieldValueValidator
                                       └── RandomValueSource + FieldValueGenerator
           ── BerEncoderService ───────── TlvWriter                 (alan ağacı + değer → bayt)
           ── CdrFileWriterService ─────  (.txt: duzlestir, kolon birlestir, yaz)
           ── BerVerifier ─────────────── TlvReader + 5 kural       (bayt → bulgu)
```

```
src/main/java/com/turkcell/cdrgenerator1
├── config          Spring yapılandırması, ayar sınıfları
├── controller      REST uçları
├── parser          ASN.1 metin → tip kaydı → alan ağacı
├── generator       Kayıt kurma, değer kaynakları, doğrulama
├── service         Kodlayıcı, dosya yazıcı, yapı servisi
│   └── verify      TLV okuyucu, walker ve doğrulama kuralları
├── ai              Sağlayıcıdan bağımsız yapay zeka arayüzü ve istem kurucu
├── infrastructure  Sağlayıcıya özgü uygulama (Gemini)
├── model           Alan/yapı modeli, istek-yanıt DTO'ları
└── exception       Hata tipleri ve global işleyici
```

Katman kuralı: `parser` yalnızca `model`'e bağımlıdır. Yapay zeka tarafında
`ai` paketi sağlayıcıdan bağımsız arayüzü (`AiFieldValueProvider`, istem
kurucu) tutar; HTTP çağrısı, istek/yanıt şeması ve sağlayıcıya özgü her şey
`infrastructure/ai/gemini` altındadır.

---

## Doğrulama ve testler

497 test. Öne çıkanlar:

| test | ne yapar |
|---|---|
| `AllModulesRoundTripTest` | 808 modülün tamamı için üret → geri oku → alan ağacına karşı doğrula |
| `ReferenceCaptureConformanceTest` | Ürettiğimiz MMTel kaydını **EMM'in kabul ettiği gerçek yakalamayla** katman katman karşılaştırır |
| `ValidationSampleTest` | 21 aile için `target/validation/` altına bir örnek `.ber` + `.txt` + `manifest.tsv` yazar |
| `AsciiOutputConformanceTest` | 802 modülün `.txt` çıktısını üretir; satır sayısı, kolon hizası ve ayraç sızıntısı denetlenir |
| `TbcdCodecLocaleTest` | TBCD alan-adı eşleşmesini açıkça Türkçe locale altında koşturur |
| `ArchitectureAuditTest` | 808 modülü tarar, `target/audit/audit.tsv` ve `findings.tsv` üretir |

`ReferenceCaptureConformanceTest` referans dosyayı sırasıyla
`-Dcdr.referenceCapture=...`, `$CDR_REFERENCE_CAPTURE` ve kullanıcının
`Downloads` klasöründeki `*CDR_MMTEL*.ber` üzerinden arar; bulamazsa kendini
atlar. **Atlanan koşu geçen koşu değildir** — bu kontrol yalnızca dosyanın
bulunduğu makineyi korur. Yakalama gerçek abone verisi taşıdığı için repoya
konulamaz; test yalnızca tag ve uzunluk baytlarını okur.

`tools/` altında, üretilen dosyaları elle incelemek için Python betikleri var
(TLV dökümü, yapısal karşılaştırma, alan silme/sarma).

---

## Bilinen açık konular

- **Varsayılan-EXPLICIT modüller — bu madde ESKİDİ.** 712 modülün alan *ve* tip
  tag'lerinin IMPLICIT olduğu 5906e76 / f5ce531 ile değişti ve EMM tarafından
  `IMSCDRS`, `FDRInput`, `Audit_Record_Collection_St`, `CHFChargingDataTypes16`
  ile doğrulandı. Güncel durum için `docs/emm-validation-log.md` esastır.
- **6 modül sıfır alan üretir.** Üçü yalnızca tip takma adı tanımlar
  (`SMSCLookupStructures`, `LteReturnTypes`, `Array`), üçü hiç tip beyan etmez.
- **17 modül `strict` modda dosya üretemez.** Çoğunda sebep şemanın X.680
  25.6'ya göre çözülemez olması.
- **`.txt` yolunun tek dış kanıtı `Multicloud`.** İç doğrulama 802 modülü
  kapsıyor (`AsciiOutputConformanceTest`) ama EMM'in bu biçim hakkında verdiği
  tek verdict hâlâ o dosyadır.
- **Üretilen değerlerde iki ölçülmüş kusur.** Kural eşleşmesi ASN.1 tipine
  bakmıyor ve desen SIZE'a sığmayınca değer kırpılıyor; 34.401 yaprağın 678'i
  kendi doğrulayıcısından geçemiyor. Ayrıntı ve rakamlar
  `docs/emm-validation-log.md` §9b'de. Düzeltmesi üretilen baytları
  değiştirdiği için EMM yanıtı bekleniyor.
- **Ön yüzün testi yok** (949 satır React, 0 test).

---

## Yazar

**Emirhan Yıldız** — https://github.com/Emirhany53
