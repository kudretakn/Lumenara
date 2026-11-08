# Streamora 0.2.0 kapsamı

| Aşama | Bu sürümde | Kalan doğrulama veya geliştirme |
|---|---|---|
| M0–M1 | Gradle projesi, TV manifesti, hızlı kurulum, açılır sol menü ve Android TV başlatıcısı | API 26 sınırında ve farklı üreticilerin başlatıcılarında cihaz matrisi |
| M2 | M3U URL/dosya, Xtream, süreli HTTPS telefon paneli, çoklu kaynak, şifreli katalog ve zamanlanmış güncelleme | Gerçek telefonda sertifika ve TV’de USB belge seçici uyumluluğu |
| M3 | Kategori, kanal, önizleme, tam ekran, kanal paneli, arama ve favoriler | Fiziksel Bluetooth kumanda tuşları, bağlantı kesme ve gerçek sağlayıcıyla uzun yayın testi |
| M4 | XMLTV/gzip ve Xtream rehberi, otomatik/manuel eşleştirme, saat çizelgesi, program arama ve arşiv bağlantıları | Gerçek sağlayıcının arşiv URL şablonu, saat dilimi ve yaz saati sınırları |
| M5 | Sinematik ana sayfa, odakla değişen arka plan, afiş ızgarası, oyuncu portreleri, raflar, isteğe bağlı TMDB, metadata filtreleri, sezon/bölüm, ilerleme, sonraki bölüm, sağlayıcı intro zamanları ve yerel öneriler | Gerçek TMDB erişim anahtarıyla kabul testi, alternatif başlık araması ve tüm sağlayıcı alanlarıyla VOD detay zenginleştirme |
| M6 | Media3, libVLC, kaynak sınırlarıyla iki/dört ekran, DVR penceresi, medya tuşları, sesli arama, harici altyazı | Fiziksel çoklu decoder/4K, Türkçe mikrofon, ASS biçim ayrıntıları ve iki motorun tüm kumanda davranışlarının eşitlenmesi |
| M7 | Kullanıcı kontrollü tanılama, codec listesi, kişisel release imzası ve GitHub APK doğrulaması | Yapılandırılmış gerçek GitHub deposunda güncelleme ve orta sınıf TV’de uzun süreli bellek/kare süresi ölçümü |

## Kabul sınırları

- Cihaz doğrulamasında Android TV API 36 x86_64 emülatörü ve kişisel içerik yerine yerel sahte sağlayıcı kullanılır. Son koşunun sonuçları CHANGELOG.md dosyasındadır.
- Çoklu ekran kararı AVC decoder bildirimi ile kaynağın bağlantı sınırına dayanır. Gerçek çözünürlük, kodek, eşzamanlı abonelik kullanımı ve cihaz sıcaklığı sonucu etkiler.
- VLC giriş katmanı doğrudan TS ve konumlandırılabilir MP4/MOV/MKV/WebM dosyalarını destekler. HLS/DASH oynatımı Media3 üzerinden yapılır.
- Altyazı gecikmesi VLC’de, harici dosya/URL seçimi Media3’te sunulur. Çevrimiçi altyazı sağlayıcısı yapılandırılmamıştır.
- Katalog ve daha önce alınmış bölümler bağlantı kesilince görüntülenebilir. Medya oynatma için sağlayıcıya erişim gerekir.
- İçerik kimliği kaynak ve yayın adresiyle oluşturulur. Adresini bütünüyle değiştiren sağlayıcılarda eski izleme kaydının yeni öğeye taşınması ayrıca ele alınmalıdır.
