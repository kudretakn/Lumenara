# Lumenara

Lumenara, kendi IPTV kaynağınızı Android TV veya Google TV kumandasıyla izlemeniz için hazırlanmış Türkçe bir uygulamadır. Android 8.0 ve üzerini destekler. İlk açılışta kaynak ekleme ekranı, katalog oluşturulduktan sonraki açılışlarda Ana Sayfa gösterilir.

## TV’ye kurulum

İmzalı sürümler herkese açık **[kudretakn/Lumenara-releases](https://github.com/kudretakn/Lumenara-releases/releases)** deposunda yayımlanır.

1. **İlk kurulum:** Son sürümün `Lumenara-X.Y.Z.apk` dosyasını TV’ye indirin (ör. TV tarayıcısı, “Downloader” uygulaması veya USB bellek) ve dosya yöneticisiyle açın. TV kurulum izni isterse ilgili uygulamaya izin verin.
2. **Güncellemeler:** Lumenara’da **Ayarlar → GitHub deposu** alanına `kudretakn/Lumenara-releases` yazın. Yeni sürüm çıktığında menüdeki Ayarlar simgesinde nokta görünür; **Güncelle** düğmesi APK’yı indirir, imzasını ve SHA-256 özetini doğrular ve Android kurulum onayını açar. İlk güncellemede Android, Lumenara için “bilinmeyen uygulamaları yükleme” izni ister.

ADB ile kurmak için TV’nin geliştirici seçeneklerinde hata ayıklamayı açın ve bilgisayardan çalıştırın:

```powershell
adb devices -l
adb -s TV_SERI_NUMARASI install -r .\Lumenara-0.3.0.apk
adb -s TV_SERI_NUMARASI shell am start -n dev.lumenara.tv/.MainActivity
```

Uygulamanın 0.3.0 öncesi adı Streamora idi. 0.3.0 ile uygulama kimliği `dev.lumenara.tv` oldu; Streamora kurulumları bu sürümle güncellenmez, ayrı bir uygulama olarak kurulur.

Debug sürümünün uygulama kimliği `dev.lumenara.tv.debug` olarak ayrılmıştır. İki sürümün katalogları bağımsızdır.

## Sinematik arayüz

Ana sayfada büyük arka plan, içerik başlığı ve oynat düğmesi bulunur. Afiş raflarında kumandayla odak değiştirdiğinizde üstteki içerik değişir. Menü, içeriği yana itmeden açılır. Filmler ve diziler afiş ızgarasında gösterilir; kategori, yıl, tür, dil ve puan filtreleri korunur. Kaldığın yerden devam et rafında izleme ilerlemesi görünür.

Xtream kaynağının HTTPS `backdrop_path` görselleri saklanır. TMDB ayarlanırsa eşleşen yapımların arka planı ve oyuncu fotoğrafları detay ekranına eklenir. Görsellerin kaynağı ve test kataloğu [ASSETS.md](ASSETS.md) içindedir. 0.2.0 aynı kişisel anahtarla imzalanır; mevcut sürümün üzerine kurulabilir ve kitaplık veritabanı otomatik taşınır.

## Sağlayıcı yönlendirmeleri

0.2.1, kaynağın HTTP uyumluluğu açıksa HTTP↔HTTPS yönlendirmelerini izler. HTTPS-only kaynaklarda HTTP’ye geçiş kapalı kalır; kurulum ekranı gerektiğinde HTTP uyumluluğunu açmanızı belirtir. 301/302/307/308 yanıtları otomatik izlenir ve göreli M3U yayın adresleri son liste adresine göre çözülür.

## Kaynak ekleme ve izleme

- **M3U** için liste adını ve sağlayıcının liste URL’sini girin. **Dosya** seçeneği sistem belge seçicisini kullanır. HTTPS varsayılandır. HTTP gerektiren eski sağlayıcılarda yalnızca ilgili kaynağın uyumluluk seçeneğini açın.
- **Xtream** için sunucu kök adresi, kullanıcı adı ve şifre girin. Canlı yayın, film ve diziler API’den alınır. Dizinin detay sayfasında sezon ve bölümleri açın.
- **Telefon** seçeneğini açtığınızda TV üç dakikalık bir HTTPS oturumu başlatır. Aynı ağdaki telefonda QR kodunu tarayın. Yerel sertifikanın SHA-256 parmak izini TV’deki değerle karşılaştırın. Ekrandaki altı haneli kodla formu gönderin. İşlem bitince TV bağlantıyı kapatır.
- **EPG ekle** altında XMLTV adresini girin. Xtream kaynağında adres boş bırakılırsa sağlayıcının rehberi kullanılır. Canlı TV’de **Yayın çizelgesi** üzerinden saat, gün ve program seçebilirsiniz. Kanal detayında manuel eşleştirme bulunur.
- **Canlı TV** ekranında kanal seçmek önizlemeyi başlatır. **Tam ekran** ile yayına geçin. OK paneli, uzun OK kanal işlemlerini açar. Yukarı/aşağı kanal değiştirir. Back önce paneli kapatır. Multi-view içinde odağı alan kanal seslidir, OK ile tam ekrana dönülür.
- **Ayarlar** üzerinden varsayılan motoru, Media3 ses/altyazı dilini, tampon profilini, otomatik yenileme aralığını ve tanılamayı yönetebilirsiniz. Kaynak bağlantı sınırını sağlayıcınızın izin verdiği değere göre ayarlayın.
- Xtream sağlayıcısı bölüm bilgilerinde açıkça `intro_start_ms` ve `intro_end_ms` alanlarını verirse, oynatıcı paneli yalnızca o aralıkta **İntroyu atla** seçeneğini gösterir. Her iki zamanın da gerçek bölüm süresine uyması gerekir.
- TMDB bilgileri için kendi API Read Access Token değerinizi Ayarlar’da girin. Eşleştirme başlık, tür ve doğrulanabilir yıl ister. GitHub sürüm kontrolü için Ayarlar → GitHub deposu alanına `kudretakn/Lumenara-releases` girin (`sahip/depo` biçimi).

## Projeyi derleme

Android Studio’da `settings.gradle.kts` dosyasının bulunduğu klasörü açın. JDK 17, Android SDK Platform 36 ve Build Tools 36.0.0 gereklidir. SDK konumunu `ANDROID_HOME` ile veya yerel `local.properties` dosyasıyla belirtin. Gradle Wrapper bağımlılıkları ilk çalıştırmada indirir.

```powershell
.\gradlew.bat test lintDebug :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:assembleRelease
```

GitHub Actions, `main` dalına gönderimlerde ve pull request’lerde birim testlerini, lint’i ve debug derlemesini çalıştırır. Ardından Android TV API 36 emülatöründe cihaz testlerini yürütür; APK, test raporları ve ekran görüntüleri iş akışı artifact’ları olarak saklanır.

Cihaz testleri bağlı bir Android TV veya Android TV AVD gerektirir. Varsayılan debug çıktısı `app/build/outputs/apk/debug/app-debug.apk`, imzalanacak release çıktısı `app/build/outputs/apk/release/app-release-unsigned.apk` konumundadır. Test APK’sında yerel sahte sağlayıcı ve Lumenara ekranından oluşturulan iki saniyelik MP4 vardır.

## Kişisel imza ve sonraki sürümler

**Otomatik yayın (önerilen):** `main` üzerinde `versionName` artırılıp `vX.Y.Z` etiketi gönderildiğinde `.github/workflows/release.yml` testleri çalıştırır, release APK’yı GitHub secret’larındaki kişisel anahtarla imzalar, sertifika SHA-256 değerini (`2a4804f0…`) denetler ve APK’yı CHANGELOG notlarıyla `kudretakn/Lumenara-releases` deposuna yayımlar. Gerekli secret’lar: `LUMENARA_KEYSTORE_B64`, `LUMENARA_KEYSTORE_PASSWORD`, `LUMENARA_RELEASES_TOKEN` (yalnızca Lumenara-releases için Contents yazma izinli ince ayarlı token).

**Elle imzalama:** PKCS#12 anahtarı ve DPAPI ile korunan parola Windows kullanıcı profilinizin `.lumenara\signing` klasöründedir (0.3.0 öncesinden kalan `.streamora\signing` klasörü de tanınır; anahtar içindeki takma ad `streamora` olarak kalır). Release’i aynı Windows hesabında elle oluşturmak için:

```powershell
.\gradlew.bat :app:assembleRelease
.\scripts\sign-release.ps1
```

Betik mevcut anahtarı kullanır, APK v2/v3 imzasını denetler ve sonucu `dist` altına yazar. İlk anahtar üretimi için kullanılan açık seçenek `-CreateKey`’dir. Güncelleme yayımlarken Gradle’daki `versionCode` ve `versionName` değerlerini artırın.

**Anahtarı yedekleyin.** DPAPI parolayı bu Windows hesabına bağlar. Taşınabilir yedek için aynı hesapta aşağıdaki komutlarla kişisel anahtarın ayrı bir kopyasını alın. `keytool` yeni yedek için sizden ayrı bir parola ister. Hedefi kendi güvenli yedekleme ortamınız olarak seçin. Bu parolayı parola yöneticinizde saklayın.

```powershell
$keyDir = Join-Path $env:USERPROFILE '.lumenara\signing'
$secure = (Get-Content -Raw (Join-Path $keyDir 'password.dpapi')) | ConvertTo-SecureString
$credential = [System.Net.NetworkCredential]::new('', $secure)
$env:LUMENARA_BACKUP_SOURCE = $credential.Password
try {
    keytool -importkeystore -srckeystore (Join-Path $keyDir 'lumenara-personal.p12') -srcstoretype PKCS12 -srcstorepass:env LUMENARA_BACKUP_SOURCE -destkeystore 'X:\GuvenliYedek\lumenara-yedek.p12' -deststoretype PKCS12
} finally {
    Remove-Item Env:\LUMENARA_BACKUP_SOURCE
    $secure.Dispose()
}
```

GitHub Releases’te kararlı bir sürüm etiketi ve imzalı evrensel APK yayımlayın. Uygulama kullanıcı seçimiyle APK’yı indirir, GitHub varlık özetini, paket adını, artan sürüm kodunu ve yüklü uygulamanın imzasını denetler. Doğrulamadan sonra dosyayı seçtiğiniz konuma kaydeder. Kurulumu Android üzerinden başlatın.

## Geliştirme kayıtları

Mimari ve bağımlılıklar **ARCHITECTURE.md**, teknik tercihler **DECISIONS.md**, test sonuçları **CHANGELOG.md**, kapsam ve açık işler **ROADMAP.md** içinde yer alır.
