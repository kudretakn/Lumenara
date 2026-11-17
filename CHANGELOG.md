# Lumenara sürüm kaydı

## 0.5.1 · 17 Kasım 2026

- Uygulama içi güncelleme kontrolü GitHub'ın istek sınırına (saatte 60 istek) takılınca artık "GitHub istek sınırına ulaşıldı, N dakika sonra tekrar deneyin" diyor; "Sağlayıcı HTTP 403" mesajı kalktı.
- GitHub API yanıt vermezse sürüm GitHub'ın web sayfasından bulunuyor; SHA-256 doğrulama özeti alınamazsa hiçbir şey kurulmuyor.
- Son kontrol sonucu önbellekte tutuluyor (ETag); açılıştaki otomatik kontrol saatte en fazla bir kez yapılıyor.
- Hata mesajları sürüm denetimi ile APK indirmeyi ayırıyor.

## 0.5.0 · 16 Kasım 2026

- Yeni tasarım ("Quiet Cinema"): koyu nötr zemin, tek altın vurgu, Inter yazı tipi, 8 dp ızgara, cam efekti ve bulanıklık yok.
- Odak düzeltmesi: odaklanan kart artık açık renge dönmüyor; beyaz çerçeve ve hafif büyüme, yazılar her zaman okunur.
- Ana ekran, kategori ızgarası, Canlı TV kanal ızgarası, tam ekran bilgi bandı ve mini rehber, Filmler/Diziler, detay ve sezon ekranları, oynatıcı seçenekleri, Ara, Ayarlar ve karşılama ekranı yeniden tasarlandı.
- Görseli olmayan içerik ve kanallar için tipografik afişler; stok manzara fotoğrafları ve tek harfli kutular kaldırıldı. Yeni düz altın logo.
- Sağlayıcı başlıklarındaki önek ve uzun sayı dizileri temizleniyor.
- Daha sık ızgaralar: kategoriler ve kanallar 6 sütun (ekranda 6×6), afişler 8 sütun.
- Ana ekran, raf, arama ve bölüm kartları küçültüldü; ekrana yaklaşık iki kat içerik sığıyor.

## 0.4.3 · 15 Kasım 2026

- Android TV ana ekranında "İzlemeye devam et": yarım kalan film ve bölümler, sıradaki bölüm; karttan doğrudan kaldığın yerden oynatma (Ayarlar'dan kapatılabilir).
- Google Asistan ve sesli arama: "Lumenara'da TRT 1 aç" gibi isteklerle kanal açma, diğer sorgular Ara ekranında.
- Kare hızı eşleme (Kapalı / Yalnız film ve dizi / Her zaman): TV, içeriğin 24/25/50/60 Hz kare hızına geçer.
- Uyku zamanlayıcısı (15/30/60/90 dk, bölüm sonunda): son 60 saniyede geri sayım, +15 dk ya da iptal.
- Yedekle / geri yükle: ayarlar, kaynaklar, Listem ve izleme geçmişi parolayla şifreli dosyaya; geri yükleme ya tamamen olur ya hiç (yarıda kalırsa bir sonraki açılışta eski hâle döner).
- Altyazı önbelleği yazımı her platformda güvenli.

## 0.4.2 · 15 Kasım 2026

- Hız (40 bin içerikli katalog, Mi Box 4): +18 filtresi ve kategori sayıları bir kez hesaplanıp paylaşılıyor; "Tümü" görünümü ve kategori listeleri yeni indeksle çalışıyor (şema 8→9).
- Canlı TV: indeksli, imleçli sayfalama; bir kategoriyi açmak en fazla üç sorgu.
- Yayın rehberi artık 30 saniyede bir yeniden kurulmuyor; boştayken işlemci yükü düşüyor. Kanal kartları görünür sayfa için tek sorgu kullanıyor.
- Arama yazmayı bitirmeni bekliyor (300 ms); dizi bölümleri 6 saat önbellekte, bölüm sorguları tabloyu taramıyor.
- Bellek: Media3 tamponu cihazın belleğine ve yayının bit hızına göre ayarlanıyor (Mi Box'ta en çok ~64 MB, 4K için yeterli); çoklu izlemede oynatıcı başına 8 MB.
- VLC tek örnekle çalışıyor (daha hızlı kanal geçişi, daha az bellek); sistem belleği azalınca önbellekler boşaltılıyor.
- Görseller: bellekteki görsel için disk erişimi yok; kartlar oynatma ilerlemesiyle gereksiz yere yenilenmiyor.

## 0.4.1 · 15 Kasım 2026

- Büyük kataloglar (40 bin içerik) için performans: yeni veritabanı indeksleri, önceden hesaplanan sürüm anahtarları (şema 7→8), sayfalı canlı kanal listesi, kart boyutunda ve önbellekli görseller, değişmeyen liste ve EPG'nin yeniden indirilmemesi.
- Listeler yüklenirken "İçerik yok" yerine kart iskeletleri; boş uyarısı yalnız liste gerçekten boşsa.
- Uygulama içi güncelleme Android 9'da imza denetimine takılmıyor.
- Hata mesajları nedeni söylüyor (ör. yanlış cihaz saati, DNS, bağlantı).
- VLC'de akış okuma hatası yüzünden altyazının düşmesi ve durması düzeltildi; numarayla kanal girişinde takılan zamanlayıcı düzeltildi.

## 0.4.0 · 15 Kasım 2026

- Sol menü yerine altı cam kartlı açılış ekranı; son kanal ve devam et rafları.
- Canlı TV, film ve dizilerde TR öncelikli kategori kartları; önizlemesiz kanal ızgarası ve geri dönüşte odak koruma.
- Ana ekranda ve ilk kurulumda iki saniye içinde çift geriyle çıkış.
- +18 kategorileri varsayılan gizli; Ayarlar’dan anında açılıp kapanan filtre arama, rehber, kanal numaraları ve listelerde de geçerli.
- Hub ve +18 testleri eklendi; kumanda yolculukları kartlı gezinmeye uyarlandı.
- Sesi çözülemeyen yayınlar (ör. MP2/AC-3 sesli canlı kanallar) artık sessiz oynamıyor; Media3 otomatik olarak VLC'ye geçiyor.
- VLC'ye geçince indirilen altyazının kaybolması düzeltildi.
- Açılıştan hemen sonra eklenen kaynağın kurulum ekranında görünmemesi düzeltildi.

## 0.3.2 · 15 Kasım 2026

- Büyük kataloglarda (~40.000 içerik) Ana Sayfa, Filmler ve Diziler boş kalıyor, uygulama donuyordu. Film/dizi sorguları sürüm birleştirmeden önce bütün tabloyu okuyordu. Sonuç 2 MB'lık CursorWindow'a sığmayınca sorgu defalarca yeniden çalışıyor, Mi Box 4'te işlemcinin %340'ı harcanıyordu. Raflar, vitrin, öneriler ve listeler artık veritabanı düzeyinde sınırlanıyor; dublaj/altyazılı sürümleri birleştirmek için yeterli pay bırakılıyor.
- 30.000 filmlik yeni cihaz testi eklendi. Mi Box 4'te ölçülen süreler: vitrin 1,4 sn, son eklenenler 2,9 sn, yüksek puanlılar 0,7 sn, 120 kartlık film listesi 5,4 sn.

## 0.3.1 · 15 Kasım 2026

- Yerleşik OpenSubtitles anahtarı ve TMDB erişimi: release APK, GitHub secret'larından gelen anahtarlarla derlenir. Ayarlar'da anahtar girilmemişse internetten altyazı araması ve film/dizi bilgileri bunlarla kendiliğinden çalışır; Ayarlar'da girilen anahtar her zaman önce gelir. Ayarlar ekranı yerleşik erişim olduğunu kısa bir notla gösterir.

## 0.3.0 · 14 Kasım 2026

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
