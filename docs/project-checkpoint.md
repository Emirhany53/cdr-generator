# Proje Durumu Checkpoint

**Tarih:** 16 Ağustos 2026
**Son Commit:** `8488d0a` (feature/web-ui) + bu oturumun değişiklikleri
**Önceki revizyon:** 13 Ağustos 2026 (`8959557`)

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
* **Toplam Modül:** 808 (`datastructure.json`); açılışta **805'i ayrıştırılıyor**, 3'ü hiç ASN.1 tipi beyan etmediği için atlanıyor.
* **Üretilebilir Yapı:** 802 (`Array`, `LteReturnTypes`, `SMSCLookupStructures` yalnızca tip takma adı tanımlar; her iki üretim ucu da bunları artık 400 ile reddeder).
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

*(Not: `ValidationSampleTest` bugün **21** aile için `.ber` + `.txt` örneği üretiyor — daha önce yazılan 34 rakamı eskimiştir. `target/` gitignore'da olduğu için orada kalan fazladan dosyalar eski koşulardan artıktır, `mvn clean` ile gider.)*

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

* **Test Sayısı:** 16.08.2026 itibarıyla **497 test, 0 başarısız, 0 atlanan** (`mvn test` ile ölçüldü). 13 Ağustos'ta 422'ydi; aradaki 75 test bu oturumda eklendi (TBCD locale regresyonu, `.txt` manifest/geçici dosya, alansız yapı reddi, Gemini adaptörü ve istem kurucu).
  **Uyarı:** `target/surefire-reports` altındaki dosyalar `mvn clean` yapılmadıkça birikir; kaynakta artık bulunmayan sınıfların raporları toplamı şişirir. Sayım öncesi `mvn clean` gerekir.
* **Audit (Validation) Metrikleri:** `AllModulesRoundTripTest` ve `ShippedFieldRulesRoundTripTest` sonucunda üretilen `audit.tsv` verileri:
  * **vErrors:** 45 (17 modülde yoğunlaşmıştır)
  * **vWarnings:** 229 (Bilinçli uyarılar, örn. implicit tagging nedeniyle kimliği silinen alt ağaçlar)
* **17 Strict-Fail Modül ve Duplicate-Tag Durumu:** 808 modülün 17'si `strict` modda dosya üretemez (vErrors > 0). Hatalar ağırlıklı olarak `DuplicateTagRule` (`dupOther` sütunu) kaynaklıdır. Sebep: `SDPAdjLikya` (20 hata), `DWHClearedDedicatedISO` (5 hata), `NotifyIsoCdr` gibi şemaların kendisinde aynı gövdede iki OPTIONAL alanın **aynı tag'i taşımasıdır** (Duplicate CONTEXT tag). Bu, ASN.1 şemasının çözülemez (ambiguous) olmasıyla ilgilidir, encoder hatası değildir.

## 5. .TXT (Eski .DAT) Eksikleri ve Yapay Zeka (AI) Üretimi

* **TXT (ASCII) Analizi:** EMM tarafında `.txt` için tek dış kanıt hâlâ `Multicloud`'dur. Ama iç doğrulama 16.08.2026'da genişletildi: 802 modül × 3 kayıt üretilip incelendi — 109.857 hücre, **802/802 modülde satır genişliği tutarlı**, ASCII dışı 0, ayraç sızıntısı 0. Kapatılan açıklar: geçici dosya sızıntısı (artık bellekte üretiliyor), işletim sistemine bağlı satır sonu (artık sabit `\n`), alansız yapının kabulü (artık 400), `|` / satır sonu / ASCII dışı karakterin sessizce dosyayı bozması (artık alanı adlandıran red). `POST /generate/manifest` metni kolon haritasıyla birlikte döner.
* **AI/Semantic Data Açıkları — artık ÖLÇÜLDÜ.** Rastgele üreticinin ürettiği değer, AI yolunu koruyan `FieldValueValidator`'a soruldu: **34.401 yapraktan 678'i kendi doğrulayıcısından geçemiyor** (249'u NULL alan; ~429'u gerçek). İki kök neden `emm-validation-log.md` §9b'de ayrıntılı: (A) kural eşleşmesi alan adında alt dize arıyor, ASN.1 tipine bakmıyor — `recipAddressTon` "ipAddress" kuralına, `camelDestinationNumberType` "calledNumber" kuralına takılıyor; (B) kural deseni alanın SIZE'ına sığmayınca değer kırpılıyor — `SIZE(6)` bir tarih alanına `"202601"` yazılıyor. Bu alanlarda AI'ın doğru ürettiği değer bile reddedilip rastgeleye düşüyor. **İkisi de `FieldValueGenerator`'ı, dolayısıyla üretilen baytları değiştirdiği için 14 dosyanın EMM yanıtı beklenmektedir.**

## 6. Açık Sorular ve Öncelikler

### Açık Sorular
1. ASN1VE gibi bağımsız bir çözücünün CLI/Batch modunda 585 yapıya genişletilip genişletilemeyeceği.
2. Atlanan implicit-CHOICE alanlarının (`skip-implicit-choice-fields: true`) iş birimi (Ericsson) tarafında gerekip gerekmediği.
3. 712 modülün (UNSPECIFIED) "alan tag'leri implicit'tir" kuralı IMSCDRS kanıtına dayanıyor. Diğer ailelerden (örn. CCN/OCC) daha fazla doğrulama alınması gerekiyor.

### Sıradaki İşlerin Öncelik Sırası
1. **EMM'den 14 dosyanın yanıtının beklenmesi.** Yanıt gelene kadar `BerEncoderService`, `TlvWriter`, parser tagging mantığı ve `FieldValueGenerator` dondurulmuştur.
2. **Yanıt gelince — değer üretimindeki iki kusur:** (A) kural eşleşmesini tip-duyarlı yapmak, (B) desen SIZE'a sığmayınca kırpmak yerine alana uygun değer üretmek. Ölçümü `emm-validation-log.md` §9b'de; ikisi de üretilen baytları değiştirir.
3. **TBCD locale düzeltmesinin gözden geçirilmesi:** kendi commit'inde duruyor, tek `git revert` ile geri alınabilir. 126 alan / 60 modülde abone-numarası baytları değişti.
4. **Duplicate Tag Modülleri:** 17 strict-fail modüldeki ambiguous şema tanımlarının iş birimiyle görüşülerek ASN.1 şemalarında düzeltilmesi veya yoksayılması.
5. **Ön yüzün testi yok** (949 satır React, 0 test) ve `WebConfig` CORS'u yalnızca `http://localhost:5173`'e izin veriyor — dağıtımdan önce bakılmalı.
