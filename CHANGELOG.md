# Lumenara sürüm kaydı

## 0.3.0 (geliştiriliyor)

- Uygulamanın adı Streamora'dan Lumenara'ya değişti: uygulama kimliği `dev.lumenara.tv`, paketler, veritabanı ve anahtar adları dahil. Streamora kurulumlarının üzerine güncellenmez (taşınacak kurulum yoktu). Yeni logo: sahne ışığı.

- Filmler/Diziler için kategori Pill satırı, filtre sayılı sağ cam panel ve altı sütunlu afiş ızgarası; Listem için favori/liste sekmeleri ve EPG bilgili kanal kartları eklendi. Ara, metin/ses girişi ve türe göre boş olmayan raflara ayrıldı. Detayda ilk odak oynat düğmesinde, sezon sekmeleri ve ilerlemeli yatay bölüm kartları gösterilir. Sinema yolculuğu filtre paneli, arama rafları ve `screens/movies.png`, `screens/details.png`, `screens/search.png` çekim adımlarıyla güncellendi.

- Media3 ve VLC tam ekranda ortak 4 saniyelik cam bilgi bandı ve 8 saniye işlemsizlikte kapanan, kategori seçicili sanallaştırılmış mini rehber eklendi. Sol / bant açıkken OK rehberi, uzun OK / Menü seçenekleri açar; VOD zaman çubuğu, ±10 saniye sarma ve sonraki bölüm kartı yenilendi. İki motor ve 10.000 kanallı sahte sağlayıcı için tam ekran yolculukları ve ekran görüntüsü adımları eklendi.

- Canlı TV üç sütuna ayrıldı: son izlenenler/favoriler ve sayılı kategoriler, görünür satırlarda EPG/sonraki program bilgili kanal listesi ve 600 ms odak beklemesiyle sesli önizleme. OK doğrudan tam ekran, uzun OK kanal işlemlerini açar; arama, sayfalama ve yayın çizelgesi korunur. Sahte sağlayıcı yolculuğu ve `screens/live.png` ekran görüntüsü adımı güncellendi.

- Canlı TV öncelikli ana sayfa, EPG ilerlemeli kanal rafları, DataStore’da son 20 kanal ve 9 saniyede dönen vitrin eklendi; ana sayfa yolculuk ve ekran görüntüsü adımları güncellendi.

- Cam tasarım sistemi, 72/240 dp açılır sol menü ve iki sekmeli Listem eklendi; IPTV Kaynakları Ayarlar’a taşındı. Kumanda testleri ve açık/kapalı menü ekran görüntüsü adımları güncellendi.

## 0.2.1 · 6 Kasım 2026

- HTTP uyumluluğu açık kaynaklarda HTTP↔HTTPS yönlendirmeleri artık takip edilir. Önceden protokol değiştiren 301 yanıtı liste aktarımını durduruyordu. Aynı istemciyi kullanan katalog, rehber ve oynatma bağlantıları düzeltmeden yararlanır.
- HTTPS-only istemciler HTTP başlangıç adresini ve yönlendirme zincirindeki HTTP’ye geçişleri reddetmeye devam eder. HTTP’ye yönlendiren sağlayıcı için kurulum ekranında kaynak uyumluluğu seçimini anlatan hata gösterilir. Geçersiz/eksik `Location` yanıtı ayrı açıklanır; adres veya hesap bilgileri hataya eklenmez.
- Beş yönlendirme testi eklendi: HTTP→HTTPS 301, açık izinle HTTPS→HTTP, HTTPS zinciri ardından HTTP’ye geçişin engellenmesi, 301/307/308 sonrası göreli M3U adresleri ve eksik yönlendirme başlığı. Bu testlerle birlikte katalog ve iki oynatma motorunu kapsayan 10 emülatör testi geçti. `lintDebug` ve release derlemesi başarılı.

## 0.2.0 · 6 Kasım 2026

- Apple TV / Netflix yerleşim referanslarına göre sinematik ana sayfa: tam ekran arka plan, okunaklı koyu geçişler, büyük başlık ve beyaz oynat düğmesi. Kumanda odağı afişler arasında ilerledikçe üstteki içerik değişir; üst bölüm raf kaydırılırken görünür kalır.
- Afiş rafları, ilerleme çizgili devam et kartları, film/dizi ızgarası ve içerik alanını kaydırmayan simgeli yan menü. Kategori ve gelişmiş filtreler korunur; bölüm değiştirirken sonuç sorgusu yenilenir.
- Film ve dizi detaylarında büyük görsel, metadata, liste/favori/fragman işlemleri ve mevcutsa TMDB oyuncu portreleri. Oyuncu satırı kumandayla kaydırılabilir. Dikey afişler geniş arka plan gibi kırpılmaz.
- Xtream `backdrop_path` alanı, şifreli saklama ve Room 6→7 otomatik geçişi. İçeri aktarmanın geçici dosyası intro aralıklarını da korur.
- Özgün ortam görseli ve yalnızca testte kullanılan kurgusal katalog: üretim istemleri ve varlık yolları ASSETS.md içinde.
- Genel doğrulama: 12 JVM testi ve 11 Android TV API 36 testi geçti. Lint hata vermedi; uygulama modülünde 20 uyarı bulunuyor. Sinematik test; şifreli arka plan aktarımı, afiş odağı, detaylar, izleme listesi ve yıl filtresini kapsar. Gerçek sağlayıcı veya TMDB hesabı kullanılmadı.
- Son arayüz koşusunda afiş adlarının ekran sınırları içinde kalması ve Filmler→Diziler→İzleme Listem sorgu geçişleri de doğrulandı. Görsel kontrol görüntüleri `dist/screenshots/` içinde; kurgusal içerik yalnızca bu test kataloğundadır.
- Release, mevcut kişisel anahtarla imzalandı; v2/v3 doğrulaması geçti. Emülatördeki imzalı 0.1.0 üzerine `install -r` ile 0.2.0 kuruldu ve Leanback başlatıcısıyla açıldı. APK özetleri `dist/SHA256SUMS.txt` içinde.

## 0.1.0 · 5–6 Kasım 2026

- M0 ve M1. Boş Windows çalışma alanından JDK 17, Android SDK 36, Gradle Wrapper ve TV başlatıcısı ile ilk APK oluşturuldu. İlk altı katalog testi ve uygulama lint görevi geçti.
- M2. M3U, SAF, Xtream ve telefon üzerinden kurulum, çoklu kaynak, Keystore koruması, atomik yenileme, WorkManager ve çevrimdışı katalog eklendi. Sahte Xtream API, reddedilen HTTP isteği, 503 sonrası katalog koruması ve iki kaynağın ayrılması Android TV emülatöründe sınandı.
- M3 ve M4. Media3 önizleme/tam ekran, TV kumandası, arama, favoriler, XMLTV/gzip, manuel eşleştirme, iki saatlik kanal çizelgesi ve program arşivi eklendi.
- M5. Sağlayıcı metadata alanları, kullanıcı tarafından yapılandırılan TMDB, koleksiyonlar, sezonlar, bölüm önbelleği, izleme listesi ve konum takibi eklendi.
- M6. libVLC, kontrollü codec fallback, çoklu kanal sınırları, canlı pencere içinde geri sarma, sesli arama ve altyazı ayarları eklendi. Her iki motor aynı yerel MP4 ile test edildi.
- M7. İmzalı release, imza denetimli GitHub güncellemesi, yerel tanılama, kurulum ve anahtar yedekleme adımları eklendi.

Milestone ara doğrulamasında sekiz emülatör testi geçti. Bu koşu Media3 oynatma, gerçek libVLC JNI oynatma, kişisel TLS sertifikasıyla eşleştirme, kodun ikinci kullanımının reddedilmesi ve eski Room kataloğunun yükseltilmesini içerdi. Son teslim doğrulaması aşağıda kayıtlıdır.

### Teslim öncesi düzeltilen sorunlar

- Önizleme ekranında ExoPlayer’ın oluşturulması ve durumunun okunması ana iş parçacığına bağlandı. Kaynak formundan oynatmaya ulaşan kumanda testi bu hatayı bağımsız motor testlerinin dışında yakaladı.
- Room şemalarının eşzamanlı debug/release derlemesindeki yazma çakışması giderildi. Şema sürümleri 1–6 için geçiş zinciri korunur.
- Kumanda testi gerçek D-pad olaylarını gönderir ve tam ekran odağının hazır olmasını bekler. libVLC testi videoyu bağlı bir Android görüntü yüzeyinde oynatır.

### 6 Kasım teslim doğrulaması

| Denetim | Sonuç |
|---|---|
| Gradle | `test lintDebug :app:assembleDebug :app:assembleRelease :app:connectedDebugAndroidTest` başarılı |
| JVM | 12 test geçti. 20.000 girdili M3U, Türkçe arama, XMLTV, arşiv zamanı, intro sınırları ve çoklu kanal kapasitesi kapsandı. |
| Android TV API 36 emülatörü | 10 test geçti, atlanan test yok. Cihaz koşusu 44,5 saniye sürdü. |
| Lint | Android modüllerinde hata yok. Uygulama modülünde 19 uyarı kaldı. |
| İmzalı release | APK Signature Scheme v2 ve v3 doğrulandı. Dağıtımdaki APK emülatöre kuruldu ve Leanback başlatıcısıyla hızlı kurulum ekranı açıldı. |
| Başlangıç | Temiz başlatılan release için tek ADB ölçümü 492 ms. Kurulum ekranında toplam PSS 50.585 KiB ölçüldü. |
| Büyük katalog | 10.000 kanallı sahte HTTP kaynağı 5.334 ms içinde içe aktarıldı. Tek kanalı bulan filtre sorgusu 11 ms sürdü. |

APK dosya özetleri `dist/SHA256SUMS.txt` içindedir. Kişisel imza sertifikasının SHA-256 parmak izi `2a4804f05adc3206055fd91777c6cc7c370ee1b3a23c9822eda3156aac9a80eb` değeridir. Performans sayıları bu emülatördeki tek koşuya aittir. Fiziksel Bluetooth kumanda, 4K ve gerçek sağlayıcı doğrulamaları ROADMAP.md içinde ayrıca listelenmiştir.
