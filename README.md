# Lumenara

Kendi IPTV kaynağınızı Android TV / Google TV kumandasıyla izleyin: canlı kanallar, filmler ve diziler tek bir Türkçe arayüzde.

**Güncel sürüm: v0.5.5.** Android 8.0 ve üzeri TV cihazları gerekir (`minSdk 26`). Lumenara bir oynatıcı ve yerel katalog uygulamasıdır; kanal, film, abonelik veya hazır yayın listesi sağlamaz. Kullanma hakkına sahip olduğunuz Xtream hesabını veya M3U listenizi kendiniz eklersiniz.

## Özellikler

- **Kaynaklar:** Birden fazla Xtream kaynağı, M3U adresi veya dosyası; XMLTV/gzip ve Xtream EPG rehberi. İsteğe bağlı TMDB eşleştirmesiyle film/dizi bilgileri ve görseller.
- **Kartlı Hub:** Canlı TV, Filmler, Diziler, Listem, Ara ve Ayarlar; altında Son kanal ve Kaldığın yerden devam et rafları. Kategorilerde Türkçe içerik için TR öncelikli sıralama.
- **Canlı TV:** Önizlemesiz kanal ızgarası, mevcut/sonraki program bilgisi, yayın çizelgesi, mini rehber, program arama ve kaynak destekliyorsa arşivden oynatma. Numarayla veya CH+/− ile kanal değiştirme; tam ekran yayında bağlantı kesilmesine ve akışın takılmasına karşı otomatik yeniden bağlanma.
- **Çoklu izleme:** Cihazın decoder kapasitesi ve kaynağın bağlantı sınırı uygunsa iki veya dört kanal. Odaktaki kanalın sesi duyulur.
- **Filmler ve diziler:** Aynı yapımın dublaj, altyazılı ve kalite sürümleri eşleşebildiğinde tek kartta birleşir; sürüm detayda seçilir. Sezon/bölüm gezinme, izleme ilerlemesi, sonraki bölüm, favoriler ve izleme listesi bulunur. Eksik veya belirsiz metadata nedeniyle bazı kopyalar ayrı kalabilir.
- **Ses ve altyazı:** Türkçe öncelikli otomatik seçim, içerik için hatırlanan tercihler, gömülü ve harici altyazılar. Kullanıcı isteğiyle önce OpenSubtitles, sonuç bulunamazsa veya kota dolarsa yapılandırılmış Subdl üzerinden arama. İki motorda altyazı gecikmesi: 100 ms / 1 sn adımlarla ±10 sn; film/bölüm ve sürüm başına saklanır, yedeğe dahil edilir.
- **İki oynatıcı:** Media3 (ExoPlayer) ve libVLC; motor seçimi ve desteklenmeyen codec/ses durumlarında VLC'ye geçiş. Media3 ile HLS/DASH, VLC giriş katmanıyla doğrudan TS ve konumlandırılabilir dosya akışları.
- **TV konforu:** Desteklenen ekran modlarıyla kare hızı eşleme; 15/30/60/90 dakika veya bölüm sonunda uyku zamanlayıcısı. Android TV Watch Next (İzlemeye devam et) ve sistem destekliyorsa sesli aramayla kanal açma / katalogda arama.
- **Veri koruma:** Ayarlar, kaynaklar, Listem ve izleme geçmişi için parolayla şifreli yedekleme ve geri yükleme. Aynı kaynak düzenlenirken adres veya hesap bilgileri değişirse yalnız birebir eşleşen içeriklerin geçmişi, kaldığın yer ve oynatma tercihleri taşınır. Kaynağı silip yeniden eklemek için elle taşıma akışı yoktur.
- **İçerik filtresi ve güncelleme:** +18 kategorileri varsayılan gizlidir; filtre arama, rehber, raflar ve kanal gezinmesinde de uygulanır. GitHub Releases üzerinden uygulama içi APK güncellemesi bulunur.

## Kurulum ve güncelleme

İmzalı APK'lar herkese açık **[kudretakn/Lumenara-releases](https://github.com/kudretakn/Lumenara-releases/releases)** deposunun Releases bölümünde yayımlanır.

1. İlk kurulum için Releases'ten `Lumenara-0.5.5.apk` dosyasını indirin. TV'ye tarayıcı, USB veya dosya aktarımıyla taşıyıp açın. Android isterse dosyayı açan uygulamaya kurulum izni verin.
2. Uygulama içinde **Ayarlar → Güncellemeler → GitHub deposu** alanına önerilen depoyu `sahip/depo` biçiminde girin ve **Kaydet ve sürüm kontrolü yap** seçeneğini kullanın. Alan boşsa sürüm kontrolü kapalıdır.
3. Yeni sürüm bulunduğunda **Güncelle** ile devam edin. Gerekirse Android'de Lumenara için uygulama yükleme iznini açın ve kurulum onayını verin. İndirilen APK'nın SHA-256 özeti, paket adı, artan sürüm kodu ve yüklü uygulamayla imza eşleşmesi denetlenir; doğrulama özeti alınamazsa kurulum yapılmaz.

ADB ile kurulum için geliştirici seçeneklerinden hata ayıklamayı açın. Aşağıdaki yer tutucuları kendi cihaz seri numaranız ve indirdiğiniz APK'nın yoluyla değiştirin:

```sh
adb devices -l
adb -s "<TV_SERI_NUMARASI>" install -r "<APK_DOSYASI>"
adb -s "<TV_SERI_NUMARASI>" shell am start -n dev.lumenara.tv/.MainActivity
```

Release uygulama kimliği `dev.lumenara.tv`, debug kimliği `dev.lumenara.tv.debug` olur. Bu iki kurulumun katalogları ayrıdır.

## İlk açılış

1. **Liste adresi gir**, **Xtream ile giriş** veya **Dosyadan** yöntemini seçin. M3U için liste adresini/dosyasını; Xtream için sunucu adresini ve hesap alanlarını doldurup **Bağlantıyı sına ve ekle** düğmesine basın. Bir yedeğiniz varsa **Yedekten geri yükle** seçeneğini kullanabilirsiniz.
2. Kumandayla uzun adres girmek yerine **Telefonla ekle** panelindeki QR kodunu aynı ağdaki telefonda açabilirsiniz. TV'deki yerel sertifikanın SHA-256 parmak izini karşılaştırın ve üç dakika geçerli kodu kullanın.
3. HTTPS varsayılandır. Kaynağınız HTTP gerektiriyorsa yalnız o kaynak için **Gelişmiş → HTTP uyumluluğu** seçeneğini açın.
4. **Ayarlar → Kaynaklar → IPTV Kaynakları** üzerinden ek kaynak ve **EPG ekle** işlemlerini yapın. Xtream'de rehber adresi boşsa kaynağın rehberi kullanılır; kanal detayında elle EPG eşleştirmesi yapılabilir. Bağlantı sınırını aboneliğinizin izin verdiği değere göre ayarlayın.
5. Katalog hazır olduğunda Hub'dan Canlı TV, Filmler veya Diziler'e, ardından bir kategoriye girin. Kanalda OK yayını açar; film/dizi kartı detay ekranını açar.

TMDB ve altyazı servisleri isteğe bağlıdır. Dağıtılan release APK'sında yerleşik TMDB / OpenSubtitles erişimi bulunabilir; Ayarlar'da girdiğiniz değerler önceliklidir. Yerel derlemede bunlar varsayılan olarak boştur. Subdl erişimi ayrıca yapılandırılır.

## Kumanda kılavuzu

| Bağlam / tuş | İşlev |
|---|---|
| Ekranlarda yön tuşları / OK | Odağı taşı / seçili kartı aç |
| Kanal kartında uzun OK | Kanal işlemlerini aç |
| Tam ekran canlı yayında OK | Bilgi bandını göster; bant açıkken mini rehberi aç |
| Tam ekran canlı yayında Sol | Mini rehberi aç |
| Tam ekran canlı yayında Yukarı / Aşağı, CH+ / CH−, Page Up / Page Down | Önceki / sonraki kanala geç |
| Canlı TV'de rakamlar | Kanal numarası gir; OK veya kısa bekleme ile aç |
| Numara girişi başlamadan 0 / son kanal tuşu | Önceki izlenen kanala dön |
| Tam ekranda uzun OK / Menü | Oynatıcı seçeneklerini aç |
| Film/bölümde Sol / Sağ | 10 saniye geri / ileri sar |
| Oynat / duraklat medya tuşları | Oynatmayı yönet |
| Çoklu izlemede yön tuşları / OK | Sesli ekranı seç / o kanalı tam ekran aç |
| Geri | Önce açık paneli veya bandı kapat; ardından önceki ekrana dön |
| Hub veya ilk kurulumda iki saniye içinde iki kez Geri | Uygulamadan çık |

Tuşların iletilmesi kumanda ve cihaz yazılımına bağlıdır. Kare hızı eşleme, Watch Next ve sesli arama desteği de TV'nin ekran modlarına ve sistem entegrasyonuna göre değişir.

## Ayarlar

| Grup | Başlıca seçenekler |
|---|---|
| **Kaynaklar** | IPTV kaynaklarını ekleme/düzenleme, EPG ve bağlantı sınırları |
| **Oynatıcı** | Media3/VLC, codec hatasında geçiş, Media3 tampon profili, ses dili, kare hızı eşleme, Android TV ana ekranı entegrasyonu |
| **Altyazı** | Dil, boyut, renk, OpenSubtitles ve Subdl erişimi |
| **İçerik (+18)** | +18 kategorilerini gösterme ve TMDB erişimi |
| **Depolama** | Şifreli yedek/geri yükleme, 6/12/24/48 saat otomatik yenileme, şimdi yenileme, görsel önbelleğini temizleme |
| **Güncellemeler** | GitHub deposu, sürüm kontrolü, doğrulanmış APK indirme/kurulum |
| **Hakkında** | Uygulama sürümü, gelişmiş yerel tanılama, codec bilgileri ve rapor dışa aktarma |

Altyazı gecikmesi, ses/altyazı izi ve uyku zamanlayıcısı oynatıcı seçeneklerindedir. +18 filtresi kategori adlarından çalışır; PIN korumalı ebeveyn kilidi değildir.

## Performans ve gizlilik

Katalog, ayarlar ve izleme geçmişi cihazda tutulur. Daha önce alınmış katalog ve bölüm bilgileri çevrimdışı görüntülenebilir; yayın oynatmak için kaynağa erişim gerekir. Watch Next açıksa ilgili devam kartları Android TV ana ekranına aktarılır. Yayın, rehber, metadata, altyazı ve güncelleme istekleri seçilen dış servislere gider.

Kaynak bağlantıları, hesap bilgileri ve kullanıcı tarafından girilen servis anahtarları AES-GCM ile şifreli saklanır; şifreleme anahtarının korunmasında **Android Keystore** kullanılır. Room veritabanının tamamı şifreli değildir. Android'in otomatik uygulama yedeği kapalıdır; taşınabilir yedek, uygulamanın parola korumalı dosyasıyla alınır. Geri yükleme yarım kalırsa önceki duruma dönmek için kurtarma mekanizması bulunur. Tanılama raporu kullanıcı seçimiyle yerel dosyaya yazılır.

Büyük kataloglarda indeksli sorgular, pencereli sayfalama, görsel önbelleği ve görünür kanallarla sınırlı EPG sorguları kullanılır. Başlangıçta ağır işler ertelenir; şema sonrası sürüm gruplama verisi arka planda parça parça tamamlanır. Media3 tamponu cihaz belleğine göre uyarlanır, VLC ortak çalışma zamanı kullanır. Açılış ve ızgara akıcılığı için geliştirmeler sürüyor; hız, 4K ve çoklu izleme kapasitesi cihaz, codec, ağ ve kaynak sınırlarına bağlıdır.

## Mimari

Kotlin/JDK 17, Jetpack Compose for TV, Hilt, Room, DataStore, WorkManager, OkHttp ve Coil kullanılır. Oynatma Media3 ve libVLC katmanlarına ayrılmıştır.

| Modül | Sorumluluk |
|---|---|
| `:app` | TV ekranları, Hub, kumanda, kurulum, sistem entegrasyonları ve oynatıcı arayüzü |
| `:core:catalog` | Android'den bağımsız katalog modelleri, ayrıştırma ve seçim kuralları |
| `:core:data` | Room kataloğu, kaynak/EPG aktarımı, metadata, tercihler, şifreleme, yedek ve güncelleme denetimi |
| `:core:designsystem` | Renkler, Inter tipografisi, odak ve ortak Compose bileşenleri |
| `:core:player` | Media3 oynatma, tampon ve decoder kapasitesi, altyazı zamanlaması |
| `:core:player-vlc` | libVLC çalışma zamanı ve akış giriş katmanı |
| `:baselineprofile` | Başlangıç/gezinme için baseline profile üretim altyapısı |

v0.5.5'te **Room şeması 12**'dir. Sürüm geçmişi JSON şemaları `core/data/schemas/` altında tutulur; veritabanı geçişleri eski katalogları yükseltir. Şema sürümü uygulama sürümünden bağımsızdır. Modül listesi [settings.gradle.kts](settings.gradle.kts) içindedir.

## Derleme, test ve CI

Gereksinimler: **JDK 17**, **Android SDK Platform 36**, **Build Tools 36.0.0** ve Android SDK Platform Tools. SDK yolunu `local.properties` içinde `sdk.dir` ile belirtin; [örnek dosya](local.properties.example) depodadır. Proje Gradle Wrapper içerir; ilk derleme bağımlılıkları indirir.

Standart birim testi, lint ve debug APK kontrolü:

```sh
./gradlew test lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Windows'ta `./gradlew` yerine `.\gradlew.bat` kullanın. Çıktılar:

- Uygulama: `app/build/outputs/apk/debug/app-debug.apk`
- Test APK'sı: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

Cihaz testlerini **Android TV API 36 emülatöründe** çalıştırın. Yerel Windows akışı, önceden oluşturulmuş bir Android TV AVD'sini başlatıp seri numarasını açıkça seçer:

```powershell
emulator -avd "<ANDROID_TV_AVD_ADI>" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save -port 5580
.\scripts\local-device-tests.ps1 -Serial emulator-5580
```

Tek sınıf için betiğe `-Class dev.lumenara.tv.FooTest` eklenebilir. Betik gerçek cihaz seri numaralarını reddeder; sonuçları `build/local-device/` altına yazar. Genel Gradle cihaz testi komutu `./gradlew :app:connectedDebugAndroidTest` de kullanılabilir; yalnız test emülatörünün bağlı olduğundan emin olun. Testler yerel sahte kaynaklar kullanır.

[Android CI](.github/workflows/android.yml), `main` gönderimlerinde ve pull request'lerde standart kontrolleri çalıştırır; ardından Android TV API 36 x86_64 emülatöründe [ci-device-tests.sh](scripts/ci-device-tests.sh) ile cihaz testlerini yürütür. APK'lar, cihaz günlükleri, özetler ve ekran görüntüleri artifact olarak saklanır; başarısız derlemelerde lint/test raporları yüklenir. Cihaz testleri önce yerelde, CI'da son doğrulama için çalıştırılır.

Release APK üretimi:

```sh
./gradlew :app:assembleRelease
```

Bu komutun çıktısı `app/build/outputs/apk/release/app-release-unsigned.apk` olur. **Release imza anahtarı gizli tutulur.** `v*` etiketi [release.yml](.github/workflows/release.yml) iş akışını tetikler: etiket ile `versionName` eşleşir, birim testleri ve release derlemesi çalışır, APK imzalanıp sertifikası denetlenir ve Lumenara-releases deposuna yayımlanır. Sürüm notları CHANGELOG'daki ilgili `## <sürüm> · <tarih>` bölümünden alınır. Yeni sürümde `versionCode` ve `versionName` birlikte artırılır.

## Belgeler

- [CHANGELOG.md](CHANGELOG.md): tüm sürümlerin değişiklikleri; güncel yayın v0.5.5.
- [docs/STATUS.md](docs/STATUS.md): güncel durum, bilinen sorunlar ve sıradaki işler.
- [ARCHITECTURE.md](ARCHITECTURE.md) ve [DECISIONS.md](DECISIONS.md): mimari ve teknik kararlar.
- [docs/DESIGN-0.5.md](docs/DESIGN-0.5.md): Quiet Cinema tasarım dili.
- [docs/USABILITY.md](docs/USABILITY.md): kumanda, canlı TV, dizi, altyazı ve Hub kullanım akışları.
- [ROADMAP.md](ROADMAP.md): kapsam ve kabul sınırları.
- [ASSETS.md](ASSETS.md): görsel kaynak kayıtları.
- [docs/perf/](docs/perf/): veritabanı, ağ, görsel ve canlı yayın performans notları.
- [CLAUDE.md](CLAUDE.md): geliştirme, test ve yayın çalışma kuralları.

Tasarım, kullanım ve varlık belgelerinde geçmiş sürümlere ait kararlar da bulunur; mevcut davranış için sürüm kaydı, güncel durum ve kod birlikte değerlendirilmelidir.

## Katkı ve proje durumu

Lumenara kişisel bir projedir. Güncel yayın **v0.5.5**; **0.5.6 geliştirme aşamasındadır**. Açılışta işlemci yükü ve ızgara gezinmesindeki titreme açık işler arasındadır; yayımlanmış özellik olarak sunulmaz.

Hata bildirimlerinde uygulama/Android sürümünü, cihaz modelini, oynatıcı motorunu ve tekrar adımlarını paylaşın. Katkılarda ilgili birim testlerini, lint'i ve emülatör testlerini doğrulayın. Herkese açık issue, PR ve günlüklerde gerçek kaynak adresi, hesap bilgisi, servis anahtarı veya kişisel veri paylaşmayın.
