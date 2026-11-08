# Streamora

Streamora, kendi IPTV kaynağınızı Android TV veya Google TV kumandasıyla izlemeniz için hazırlanmış Türkçe bir uygulamadır. Android 8.0 ve üzerini destekler. İlk açılışta kaynak ekleme ekranı, katalog oluşturulduktan sonraki açılışlarda Ana Sayfa gösterilir.

## TV’ye kurulum

Kişisel kullanım için imzalı **Streamora-0.2.1-release.apk** dosyasını kullanın. Dosya proje klasöründeki `dist` dizinindedir. TV’de dosya yöneticisiyle açıp sistemin kurulum adımlarını izleyin. TV kurulum izni isterse kullandığınız dosya yöneticisi için izin verin.

ADB ile kurmak için TV’nin geliştirici seçeneklerinde hata ayıklamayı açın, bilgisayarın bağlantısını TV’de onaylayın ve proje klasöründe çalıştırın.

```powershell
adb devices -l
adb -s TV_SERI_NUMARASI install -r .\dist\Streamora-0.2.1-release.apk
adb -s TV_SERI_NUMARASI shell am start -n dev.streamora.tv/.MainActivity
```

Debug sürümünün uygulama kimliği `dev.streamora.tv.debug` olarak ayrılmıştır. İki sürümün katalogları bağımsızdır.

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
- TMDB bilgileri için kendi API Read Access Token değerinizi Ayarlar’da girin. Eşleştirme başlık, tür ve doğrulanabilir yıl ister. GitHub sürüm kontrolü için kendi herkese açık yayın deponuzu `sahip/depo` biçiminde girin.

## Projeyi derleme

Android Studio’da `settings.gradle.kts` dosyasının bulunduğu klasörü açın. JDK 17, Android SDK Platform 36 ve Build Tools 36.0.0 gereklidir. SDK konumunu `ANDROID_HOME` ile veya yerel `local.properties` dosyasıyla belirtin. Gradle Wrapper bağımlılıkları ilk çalıştırmada indirir.

```powershell
.\gradlew.bat test lintDebug :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:assembleRelease
```

Cihaz testleri bağlı bir Android TV veya Android TV AVD gerektirir. Varsayılan debug çıktısı `app/build/outputs/apk/debug/app-debug.apk`, imzalanacak release çıktısı `app/build/outputs/apk/release/app-release-unsigned.apk` konumundadır. Test APK’sında yerel sahte sağlayıcı ve Streamora ekranından oluşturulan iki saniyelik MP4 vardır.

## Kişisel imza ve sonraki sürümler

Bu bilgisayarda oluşturulan PKCS#12 anahtarı ve DPAPI ile korunan parola, Windows kullanıcı profilinizin `.streamora\signing` klasöründedir. Sonraki release’i aynı Windows hesabında oluşturmak için:

```powershell
.\gradlew.bat :app:assembleRelease
.\scripts\sign-release.ps1
```

Betik mevcut anahtarı kullanır, APK v2/v3 imzasını denetler ve sonucu `dist` altına yazar. İlk anahtar üretimi için kullanılan açık seçenek `-CreateKey`’dir. Güncelleme yayımlarken Gradle’daki `versionCode` ve `versionName` değerlerini artırın.

**Anahtarı yedekleyin.** DPAPI parolayı bu Windows hesabına bağlar. Taşınabilir yedek için aynı hesapta aşağıdaki komutlarla kişisel anahtarın ayrı bir kopyasını alın. `keytool` yeni yedek için sizden ayrı bir parola ister. Hedefi kendi güvenli yedekleme ortamınız olarak seçin. Bu parolayı parola yöneticinizde saklayın.

```powershell
$keyDir = Join-Path $env:USERPROFILE '.streamora\signing'
$secure = (Get-Content -Raw (Join-Path $keyDir 'password.dpapi')) | ConvertTo-SecureString
$credential = [System.Net.NetworkCredential]::new('', $secure)
$env:STREAMORA_BACKUP_SOURCE = $credential.Password
try {
    keytool -importkeystore -srckeystore (Join-Path $keyDir 'streamora-personal.p12') -srcstoretype PKCS12 -srcstorepass:env STREAMORA_BACKUP_SOURCE -destkeystore 'X:\GuvenliYedek\streamora-yedek.p12' -deststoretype PKCS12
} finally {
    Remove-Item Env:\STREAMORA_BACKUP_SOURCE
    $secure.Dispose()
}
```

GitHub Releases’te kararlı bir sürüm etiketi ve imzalı evrensel APK yayımlayın. Uygulama kullanıcı seçimiyle APK’yı indirir, GitHub varlık özetini, paket adını, artan sürüm kodunu ve yüklü uygulamanın imzasını denetler. Doğrulamadan sonra dosyayı seçtiğiniz konuma kaydeder. Kurulumu Android üzerinden başlatın.

## Geliştirme kayıtları

Mimari ve bağımlılıklar **ARCHITECTURE.md**, teknik tercihler **DECISIONS.md**, test sonuçları **CHANGELOG.md**, kapsam ve açık işler **ROADMAP.md** içinde yer alır.
