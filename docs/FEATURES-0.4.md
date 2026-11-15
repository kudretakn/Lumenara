# 0.4.x özellikleri (U3 yerine; kullanıcı kararı 15 Kasım 2026)

Ortak bağlam: Lumenara, Android TV IPTV oynatıcısı (Kotlin, Compose for TV, Room, Media3 + VLC). Hedef cihaz Mi Box 4: Android 9 (API 28), 2 GB RAM. `minSdk` değerini `app/build.gradle.kts` dosyasından kontrol et. Önce `CLAUDE.md` ve `docs/USABILITY.md` "K" bölümünü oku; menü yeni değişti ve artık kartlı Hub var. Arayüz metinleri Türkçe, kod yorumları İngilizce.

Ajan kuralları:
- Gradle çalıştırma; derleme ve testleri Claude yapar.
- Commit ya da push yapma.
- Yalnız kendi alanındaki dosyalara dokun. Başka dosyaya dokunman gerekirse en küçük değişikliği yap ve raporda belirt.
- Yeni bağımlılık yalnız bölümde izin verildiyse eklenir.
- Her özelliğe JVM testi, mümkünse bir cihaz testi (`app/src/androidTest`, mevcut testlerin stilinde) ekle.
- Rapor en fazla 10 satır.

## W — Android TV ana ekranı "İzlemeye devam et"
- `androidx.tvprovider:tvprovider:1.1.0` eklenebilir.
- Yarım kalan film ve bölümler Watch Next programı olarak yayınlanır (`WatchNextProgram`, `TYPE_CONTINUE`). Gösterilecek alanlar: başlık, afiş, kalan süre, son izlenme zamanı. Bölümlerde dizi adı ve "S2 B5" de gösterilir.
- Sıradaki bölüm `TYPE_NEXT` olarak yayınlanır. Biten ya da listeden kaldırılan içerik ana ekrandan silinir.
- Kayıt: izleme ilerlemesi yazılınca (`model.progress`/`started`) en fazla 30 saniyede bir. Çalışma ana iş parçacığını tıkamaz.
- Ana ekrandaki karta basınca uygulama `lumenara://play/<itemId>?position=<ms>` deep link'iyle açılır ve doğrudan oynatır. `MainActivity` intent işleme bu bölüme aittir.
- Kullanıcının ana ekranda kaldırdığı içerik (`browsable=0`) tekrar eklenmez.
- Ayarlar'a "Android TV ana ekranında göster" (Açık/Kapalı) seçeneği eklenir.
- Dosyalar: yeni `app/.../WatchNext.kt`, `AndroidManifest.xml`, `MainActivity.kt` (intent), `Settings.kt` (tek satır).

## G — Google Asistan / sesli arama
- `MainActivity` şu intent'leri karşılar: `android.intent.action.SEARCH` (`SearchManager.QUERY`), `com.google.android.gms.actions.SEARCH_ACTION` ve `android.media.action.MEDIA_PLAY_FROM_SEARCH`. Sorgu Ara ekranında açılır (`SearchScreen` sorgusu doldurulur).
- MEDIA_PLAY_FROM_SEARCH'te tek bir kanal adı tam eşleşirse kanal doğrudan oynar. Örnek: "Lumenara'da TRT 1 aç".
- `res/xml/searchable.xml` eklenir ve manifestte tanımlanır.
- Intent sorgusunu uygulama durumuna aktaran saf bir fonksiyon (ör. `SearchIntents.kt`) yazılır ve JVM testi eklenir.
- `MainActivity` intent işlemesinde W ajanıyla çakışmamak için yalnız ayrı bir `handleSearchIntent(intent)` fonksiyonu eklenir ve tek satırla çağrılır.

## F — Kare hızı eşleme
- Ayarlar'a "Kare hızını eşle" seçeneği eklenir: Kapalı / Yalnız film ve dizi / Her zaman. Varsayılan "Yalnız film ve dizi".
- Media3: ilk video formatının `frameRate` değeri alınır (`onTracksChanged` ya da `onVideoSizeChanged`).
  - API 30 ve üstünde `Surface.setFrameRate` / `setFrameRateWithChangeStrategy` kullanılır.
  - API 23–29'da (Mi Box: API 28) `Display.supportedModes` içinden aynı çözünürlükte, yenileme hızı kare hızının tam katı olan mod seçilir ve `window.attributes.preferredDisplayModeId` ayarlanır. 23,976 için 23,976/24/47,95/48 Hz; 25 için 50 Hz; 29,97 ve 30 için 59,94 ya da 60 Hz.
  - Oynatıcıdan çıkınca eski moda dönülür.
- VLC: `Media.VideoTrack.frameRateNum / frameRateDen` ile aynı mantık uygulanır.
- Mod geçişi sırasında görüntü 1–2 saniye kararabilir; bu sürede "Kare hızı ayarlanıyor" bilgisi gösterilir.
- Mod seçimi saf bir fonksiyon (`FrameRateMatch.kt`) olur ve JVM testinde farklı mod listeleriyle denenir.
- Dosyalar: yeni `app/.../FrameRate.kt`, `Playback.kt` ve `VlcScreen.kt` (küçük kancalar), `Settings.kt`.

## Z — Uyku zamanlayıcısı
- Oynatıcı Seçenekler panelinde (Media3 ve VLC) "Uyku zamanlayıcısı" bulunur: Kapalı / 15 / 30 / 60 / 90 dk / Bölüm sonunda.
- Sürenin son 60 saniyesinde ekranda geri sayımlı bir kart görünür: "Lumenara 60 sn sonra duracak · İptal (OK) / +15 dk". Süre dolunca oynatma durur ve uygulama Hub'a döner.
- Zamanlayıcı kanal değişse de sürer; uygulama kapanınca sıfırlanır.
- Zamanlayıcı mantığı saf bir sınıf (`SleepTimer.kt`, sahte saat ile test edilebilir) olur ve JVM testi eklenir.
- Dosyalar: yeni `app/.../SleepTimer.kt`, `Playback.kt` ve `VlcScreen.kt` (panel satırı ve kart).

## B — Yedekle / geri yükle
- Ayarlar'a "Yedekle" ve "Geri yükle" eklenir.
- İçerik: ayarlar (DataStore), kaynak tanımları (adres ve Xtream kullanıcı bilgisi), EPG adresleri, eşleştirmeler, Listem/favoriler, izleme ilerlemesi ve son kanallar. Katalog yedeklenmez; geri yüklemeden sonra kaynaklar yenilenir.
- Biçim JSON'dur ve kullanıcının girdiği 6+ karakterli parolayla şifrelenir: PBKDF2-HMAC-SHA256 (210k tur, rastgele tuz) + AES-256-GCM. Gizli bilgiler düz metin olarak hiçbir yere yazılmaz. Dosya başlığında sürüm alanı bulunur.
- Hedef:
  1. `Download/Lumenara/lumenara-yedek-<tarih>.lmb` (MediaStore Downloads; API 28'de `WRITE_EXTERNAL_STORAGE` gerekiyorsa maxSdkVersion=28 ile).
  2. Ayrıca "Telefona gönder": mevcut `Pairing.kt` yerel ağ eşleştirme mekanizmasını incele; uygunsa yedeği aynı yolla telefona indirilebilir yap. Uygun değilse yalnız dosya yolu yeterli.
- Geri yükleme önce özet gösterir ("3 kaynak, 120 izleme kaydı…") ve onay ister. Kimlikler kaynak adresine bağlı olduğundan izleme kayıtları kaynakla birlikte eşlenir.
- Geri yükleme: tüm dosya bellekte doğrulanır (sürüm, türler ve kaynak/öğe/EPG referansları); Room yazımları tek işlemde yapılır. Eski ayarlar ve TMDB anahtarı cihaz anahtarıyla şifreli bir Room günlüğünde saklanır. Günlük yalnız başarılı Room işlemiyle silinir; hata veya kesinti sonrası açılışta önceki ayarlar geri alınır. DataStore ayarları tek atomik düzenlemeyle yazılır.
- Testler: şifreleme gidiş-dönüş, yanlış parola, bozuk dosya, eski sürüm biçimi (JVM).
- Dosyalar: yeni `core/data/.../Backup.kt`, `app/.../BackupScreen.kt`, `Settings.kt` (giriş satırı).

## X — Küçük düzeltme
- `SubtitleCache.save` içindeki `File.renameTo` Windows'ta başarısız oluyor. `java.nio.file.Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)` kullanılır; ATOMIC_MOVE desteklenmezse ATOMIC_MOVE olmadan tekrar denenir.
