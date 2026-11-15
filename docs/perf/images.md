## Görev: images — görsel yükleme

Sorunlar:
1. `LumenaraApp.newImageLoader()` görselleri `Transport.client(false)` ile, yani yalnız HTTPS ile indiriyor.
   - Sağlayıcı logolarının ve afişlerinin çoğu HTTP; bunlar hiç yüklenmiyor ve her kartta boşuna istek atılıyor.
   - Çözüm: görseller için ayrı bir istemci kullan. Kaynağında HTTP uyumluluğu açık olan (`Source.allowHttp`) içeriklerin görsellerinde HTTP'ye izin ver, diğerlerinde verme.
   - En basit güvenli yol: `Item`'in kaynağı `allowHttp` ise görsel isteğine bir Coil `Parameters`/extra koy; özel bir `Interceptor` ya da iki ayrı `ImageLoader` HTTP'yi yalnız o durumda kabul etsin. Görsel istekleri çerez ya da kimlik bilgisi taşımasın.
2. Görseller kart boyutuna küçültülmeden indiriliyor ve çözülüyor.
   - Her `AsyncImage`/`ImageRequest` için kartın piksel boyutunu `size(...)` ile ver: afiş kartı, kanal logosu (36–48 dp), kategori kartı, detay arka planı.
   - TMDB'de uygun genişliği seç: afiş `w342`, arka plan `w1280` yerine ekran genişliğine göre `w780`, oyuncu `w185`.
   - `allowRgb565(true)` kullan (zayıf cihaz). `crossfade` kapalı ya da çok kısa olsun.
3. Önbellek:
   - Disk önbelleği `cacheDir/images` altında, 256 MB üst sınır.
   - Bellek önbelleği uygulama belleğinin %15'i.
   - Sunucu `no-cache` dese bile logo ve afişleri 7 gün tut (`respectCacheHeaders(false)`).
   - Ayarlar'daki "Görsel önbelleğini temizle · N MB" doğru dizini göstermeye devam etmeli.
4. Kaydırmada önceden yükleme: ızgarada görünen satırın bir altındaki satırın görsellerini önceden iste. Coil `enqueue` ya da LazyGrid prefetch yeterli; abartma.
5. Hatalı ya da 404 dönen görsel adresini oturum boyunca bir daha isteme (küçük bellek içi negatif önbellek).

Testler:
- `CinemaJourneyTest` sahte ImageLoader kullanıyor; kırma.
- HTTP görsel izni için JVM testi ekle: interceptor ya da karar fonksiyonu.
