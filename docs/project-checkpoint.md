# Proje Durumu Checkpoint

**Tarih:** 13 Ağustos 2026
**Son Commit:** `8959557b458828ac17a0cb07f7c8736396461f6f` (feature/web-ui)

Bu doküman, projenin en güncel teknik durumunu, alınan EMM (Ericsson Mediation Manager) doğrulama sonuçlarını ve mimari kararları özetler. Yeni bir oturumda bağlamı hızlıca kavramak için tasarlanmıştır.

## 1. Projenin Amacı ve Mevcut Çıktı Mimarisi

Uygulama, Turkcell'in EMM akışlarını beslemek için `datastructure.json` içindeki kayıtlı ASN.1 şemalarına uygun test CDR'ları üretir.

Mevcut çıktı mimarisi üç formattan oluşur:
* **`.ber` (Binary BER):** X.690 standartlarına ve EMM'in kabul ettiği kanıtlanmış (round-trip) kurallara göre üretilen binary dosya.
* **`.dat` (Binary BER):** `.ber` ile **byte-for-byte aynı** binary içeriktir. `GenerateBerRequest` üzerinden `extension="dat"` parametresiyle çağrıldığında aynı BER byteları `.dat` uzantısıyla (Content-Disposition) sunulur. (Son commit ile binary eşdeğerliği sağlanmıştır).
* **`.txt` (ASCII Metin):** Mevcut pipe-separated (`|`) metin çıktısıdır. Eskiden `.dat` olarak adlandırılan bu çıktı, binary `.dat` ile karışmaması için `.txt` olarak yeniden adlandırılmıştır. `/generate` endpoint'i üzerinden üretilir.

## 2. Son Frontend Değişiklikleri (Commit 8959557)

Frontend (React/Vite) tarafında UI yerleşimi ve indirme mekanizması güncellenmiştir:
* **3 Ayrı İndirme Butonu:** Eski tek buton yerine "BER", "DAT", ve "TXT" formatları için ayrı butonlar eklendi.
* **Cache Mekanizması:** BER ve DAT butonları backend'deki aynı encoder'ı tetikler ve aynı blob'u kullanır.
* **TXT Çıktısı:** TXT butonu `/generate` endpoint'ini tetikleyerek bağımsız pipe-separated metin formatını çeker.
* **Oluşturuluyor Durumu:** İndirme işlemi sırasında `generatingType` ("ber" | "dat" | "txt") statine bağlı olarak sadece tıklanan butonda "Oluşturuluyor..." yazar, diğer butonlar disabled kalır.
* **Layout İyileştirmesi:** `.download-choice` sınıfında dikey boşluklar azaltıldı, flexbox (row, gap) kullanılarak butonlar yatayda hizalandı ve responsive davranması sağlandı.

## 3. BER Tarafındaki Mevcut Durum

### Sayısal Metrikler (Repo üzerinden doğrulanmıştır)
* **Toplam Modül:** 808
* **Üretilebilir Yapı:** 802 (6'sı sadece tip/alias tanımladığı veya boş olduğu için atlanıyor).
* **Tagging Modları:**
  * `UNSPECIFIED` (Başlıkta mod belirtilmeyenler): 712 modül (~22.133 yaprak alan).
  * `IMPLICIT TAGS`: 96 modül.
  * `EXPLICIT TAGS` / `AUTOMATIC TAGS`: 0 modül.
* **BER Behavior Class (Davranış Sınıfı):** Repoda veya dokümanlarda "32 BER behavior class" veya "behavior_class" şeklinde bir sınıflandırma **bulunmamaktadır**. Yapılar, tagging modları (Unspecified/Implicit) ve kök şekilleri (Choice, Set, Sequence) üzerinden sınıflandırılmaktadır.

### EMM Coverage ve Doğrulanmış Yapılar
Şu ana kadar **12 modül** EMM tarafından doğrulanmış ve kabul edilmiştir:
1. `MMTelChargingDataTypes`
2. `GGSNTurkcellCdrR7`
3. `LTE-R10` (pGWRecord)
4. `Multicloud` (.dat ASCII formatında)
5. `IMSCDRS` (TokensCSCF)
6. `CGSN40ber`
7. `CHAD`
8. `TurkcellCDRCCNCS5`
9. `SMSCBerCdr`
10. `CHFChargingDataTypes16`
11. `FDRInput`
12. `Audit_Record_Collection_St`

*(Not: İstemde belirtilen "14 yeni representative BER dosyası EMM'e gönderildi" ifadesi, repo logları ve `emm-validation-log.md` tur kronolojisi (Tur 1-13) ile eşleşmemektedir. Toplamda 34 `.ber` dosyası `target/validation/` altında referans olarak üretilmektedir.)*

### Kanıtlanmış Encoding Kuralları ve Verifier Durumu
`emm-validation-log.md` Tur 13 sonu itibarıyla EMM tarafından kanıtlanmış kurallar (özetle **CHOICE dışında her şey IMPLICIT** kuralı geçerlidir):
* `[n] EXPLICIT SEQUENCE OF <CHOICE>` → iç universal SEQUENCE üretilmez.
* `[n] EXPLICIT <SET>` (skaler) → sarmalayıcı üretilmez.
* `[n] EXPLICIT <BOOLEAN>` → IMPLICIT kodlanır.
* `UNSPECIFIED` modüllerde alan tag'i IMPLICIT'tir.
* `UNSPECIFIED` modüllerde **tip** seviyesi tag de IMPLICIT'tir (FDRInput ve Audit ile kanıtlandı).
* `UNSPECIFIED` modüllerde CHOICE alanı üzerinde **yazılı EXPLICIT keyword'ü yoksa** IMPLICIT kodlanır.

Bu kurallar `BerVerifier` (Yerel Doğrulama Döngüsü) içindeki `VerificationRule`'lar (örn. `FieldTagImplicitRule`, `DuplicateTagRule`, `TypeTagImplicitRule` vb.) ile güvence altındadır.

## 4. Test Sayısı ve Validation Metrikleri

* **Test Sayısı Açıklaması:** İstemde sorulan "399 / 440 test sayısı" gerçeği yansıtmamaktadır. Repo üzerinden yapılan statik analiz (`grep -r '@Test'`) sonucunda tam olarak **49 test sınıfında toplam 422 test metodu** bulunmaktadır. Bu sayı `TextCharsetTest`, `TextColumnStabilityTest`, `TextLiteralAndCodeTest` gibi yeni .txt testlerinin eklenmesiyle artmıştır.
* **Audit (Validation) Metrikleri:** `AllModulesRoundTripTest` ve `ShippedFieldRulesRoundTripTest` sonucunda üretilen `audit.tsv` verileri:
  * **vErrors:** 45 (17 modülde yoğunlaşmıştır)
  * **vWarnings:** 229 (Bilinçli uyarılar, örn. implicit tagging nedeniyle kimliği silinen alt ağaçlar)
* **17 Strict-Fail Modül ve Duplicate-Tag Durumu:** 808 modülün 17'si `strict` modda dosya üretemez (vErrors > 0). Hatalar ağırlıklı olarak `DuplicateTagRule` (`dupOther` sütunu) kaynaklıdır. Sebep: `SDPAdjLikya` (20 hata), `DWHClearedDedicatedISO` (5 hata), `NotifyIsoCdr` gibi şemaların kendisinde aynı gövdede iki OPTIONAL alanın **aynı tag'i taşımasıdır** (Duplicate CONTEXT tag). Bu, ASN.1 şemasının çözülemez (ambiguous) olmasıyla ilgilidir, encoder hatası değildir.

## 5. .TXT (Eski .DAT) Eksikleri ve Yapay Zeka (AI) Üretimi

* **TXT (ASCII) Analizi:** `docs/emm-validation-log.md`'de belirtildiği üzere `.txt` (eski adıyla ASCII `.dat`) yolu `.ber`'e göre **çok daha az doğrulanmıştır**. Bağımsız oracle veya EMM tarafında parse edilebilirlik doğrulamasından geçmemiş, dışarıdan incelenecek örnek çıktılar (`target/validation/` altında) sınırlı kalmıştır.
* **AI/Mock Semantic Data Açıkları:** AI (`GEMINI_API_KEY`) kapalıysa üretim rastgele değerlere (`RandomValueSource`) düşer. AI devrede olsa bile, üretilen veriler `FieldValueValidator` tarafından SIZE ve desen kurallarına karşı doğrulanır. Uymayan değer reddedilip yerine rastgele veri üretilir. Sorun, AI'ın her zaman sınır kısıtlarına uyan mantıklı "semantik" veriler üretememesidir (özellikle enum veya birbirine bağımlı conditional alanlarda).

## 6. Açık Sorular ve Öncelikler

### Açık Sorular
1. ASN1VE gibi bağımsız bir çözücünün CLI/Batch modunda 585 yapıya genişletilip genişletilemeyeceği.
2. Atlanan implicit-CHOICE alanlarının (`skip-implicit-choice-fields: true`) iş birimi (Ericsson) tarafında gerekip gerekmediği.
3. 712 modülün (UNSPECIFIED) "alan tag'leri implicit'tir" kuralı IMSCDRS kanıtına dayanıyor. Diğer ailelerden (örn. CCN/OCC) daha fazla doğrulama alınması gerekiyor.

### Sıradaki İşlerin Öncelik Sırası
1. **EMM'den yeni kanıtların beklenmesi:** Round 8'de gönderilen `CGSN40ber`, `TurkcellCDRCCNCS5`, `CHAD`, `SMSCBerCdr` vb. bağımsız dosyaların (4 farklı aile) sonuçlarının analiz edilmesi.
2. **ASCII (TXT) Doğrulaması:** TXT çıktısı için kapsamlı örnek setinin (validation manifest'i gibi) üretilip manuel olarak doğrulanması.
3. **AI Validator İyileştirmesi:** Yapay zekanın reddedilen "geçersiz" semantik verilerinin oranını azaltmak için `field-rules` (regex/pattern) yapılandırmasının genişletilmesi.
4. **Duplicate Tag Modülleri:** 17 strict-fail modüldeki ambiguous şema tanımlarının iş birimiyle görüşülerek ASN.1 şemalarında düzeltilmesi veya yoksayılması.
