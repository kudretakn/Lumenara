# Lumenara — Claude Code talimatları

Kişisel kullanım için Android TV IPTV oynatıcısı (Kotlin, Compose for TV, Room, Media3 + VLC). Kullanıcının kaynağı Xtream; M3U, EPG ve TMDB de destekleniyor.

**Oturum başında:** `git pull` ile depoyu güncelle. Güncel durum, açık işler ve sıradaki adımlar için @docs/STATUS.md dosyasını oku.

**Her PR'da:** `docs/STATUS.md` dosyasını güncelle: biten iş, açık PR, sıradaki adım ve kullanıcıdan beklenenler. Başka cihazdaki oturumlar durumu buradan alır. Kalıcı bir kural ya da kullanıcı kararı öğrenirsen bu dosyaya ekle.

## Çalışma kuralları
- **İş bölümü:** Claude planlar, kısa spesifikasyon yazar, inceler, test eder, PR/CI/merge işini yürütür. Kodu ve görselleri Codex CLI üretir (Codex kurulu değilse kodu Claude yazar).
  - Çağrı biçimi: `codex exec -m <model> -c model_reasoning_effort='"<seviye>"' -s workspace-write -C <dizin> -o <sonuç> - < istem.md`. İstem dosyadan verilir, stdin kapalı olur.
  - Codex commit ya da push yapmaz.
  - Codex'in bildirdiği düzeltmenin diskte olduğunu her zaman doğrula.
- **Ekip düzeni (kullanıcı isteği, 15 Kasım 2026):** Claude ekip lideridir: işi böler, her parçayı ayrı bir Codex ajanına verir, sonuçları inceler, birleştirir, derler ve test eder. Kod düzeltmeleri dahil her iş ayrı bir ajana gider. Lider kodu kendisi yazmaz; yalnız birleştirme çakışması ve tek satırlık mekanik düzeltme istisnadır. Ajanın modeli işin zorluğuna göre seçilir: mekanik iş `gpt-6-luna` low, orta iş luna medium ya da sol medium, çok dosyalı, kök neden arama ya da güvenlik işi sol high. Sol high'ın çözemediği ya da çok zor işler `gpt-6-astra`'ya gider (kullanıcı kararı, 15 Kasım 2026).
- **Claude tarafı da alt ajanlarla (kullanıcı isteği, 15 Kasım 2026):** Claude'un yapması gereken işler de alt ajanlara verilir: diff inceleme, CI loglarından kök neden çıkarma, spesifikasyon ya da tasarım taslağı yazma, araştırma. Lider bu işleri kendi yapmaz, Claude alt ajanlarına (Agent aracı) dağıtır. Model işin zorluğuna göre seçilir: basit ve özetleme işi `haiku`, orta iş `sonnet`, zor analiz ya da mimari iş `opus`. Lider yalnız koordinasyonu, kararları, birleştirmeyi, derleme/test başlatmayı, PR/merge/yayın işini ve kullanıcıyla iletişimi yürütür.
- **Paralellik (kullanıcı isteği, 15 Kasım 2026):** İşleri küçük, bağımsız parçalara bölüp 8–12 Codex ajanıyla paralel çalıştır. Her ajan ayrı git worktree'de çalışır (`../lumenara-wt/<ad>`) ve Gradle çalıştırmaz. Derleme ve testleri Claude sırayla yapar, çünkü sunucuda 2 CPU var. Her ajanın modeli kendi görevinin zorluğuna göre seçilir.
- **Model seçimi:** Basit işte `gpt-6-luna` low/medium, çok dosyalı ya da kritik işte `gpt-6.1-sol` medium/high. İstem kısa olur: dosya yolları, kabul ölçütleri ve "en fazla N satır rapor" isteği içerir. Token tasarrufu her iki tarafta da önemli.
- **Merge:** CI'daki **tüm** işler (`build` + `device`) pass olmadan merge yapılmaz; tam `gh pr checks` çıktısına bak. Hepsi yeşilse sormadan merge et. `main` korumalı.
- **Kullanıcıya kalanlar:** Kimlik, ödeme ve imza anahtarı adımları kullanıcıya bırakılır. Geri alınamaz silme ya da para harcatan işlem için açık onay gerekir.
- **Gizli bilgiler:** API anahtarları, imza anahtarı ve şifreler repoya ve loglara girmez. Release imza anahtarı yalnız kullanıcının Windows bilgisayarında durur; GitHub'a yalnız kullanıcının kendi eklediği secret'larla ulaşır. Altyazı API anahtarlarını kullanıcı uygulamanın Ayarlar ekranına girer (Keystore ile şifreli). Altyazı sitesi kazıma yok.
  - İstisna (kullanıcı kararı, 14 Kasım 2026): OpenSubtitles API anahtarı ve TMDB Read Access Token, release derlemesinde `OPENSUBTITLES_API_KEY` ve `TMDB_READ_TOKEN` secret'larından `core/data` BuildConfig'ine gömülür. Repoda ve CI derlemelerinde boştur. Ayarlar'da girilen değer her zaman önce gelir. Herkese açık APK'dan çıkarılabilecekleri kabul edildi; sızarsa anahtar yenilenip secret güncellenir.
- **İletişim:** Kullanıcıya kısa, Türkçe yanıt ver.

## Derleme ve test
- JDK 17, Android SDK (platform 36, build-tools 36.0.0), `local.properties` içinde `sdk.dir`.
- Standart kontrol: `./gradlew test lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`
- **Cihaz testleri önce yerelde çalışır** (kullanıcı kararı, 16 Kasım 2026: "testleri yereldeyken CI'dan yapma"). CI yalnız son doğrulama içindir; düzeltme döngüsü CI'da dönmez.
  - Windows'ta: emülatörü başlat (`emulator -avd Streamora_TV_API36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save -port 5580`), sonra `.\scripts\local-device-tests.ps1 -Serial emulator-5580` (tek test: `-Class dev.lumenara.tv.FooTest`).
  - Sonuçlar `build\local-device\` altına yazılır: `summary.txt`, `instrument*.txt`, `logcat.txt`, ekran görüntüleri.
  - Kullanıcının TV'si de adb'ye bağlı olabilir (`192.168.1.14:5555`). Testleri asla TV'de çalıştırma; betik emülatör dışı cihazı reddeder.
  - Push yalnız yerel cihaz testleri yeşilken yapılır.
- Cihaz testleri CI'da Android TV API 36 x86_64 emülatöründe `scripts/ci-device-tests.sh` ile çalışır.
  - Bir test düşerse bir kez tekrar denenir; geçerse RETRIED yazılır.
  - Uygulama çökerse kalan testler yine çalışır.
  - Hata dökümü için: `gh run download <run-id> -n android-tv-device-artifacts`, sonra `summary.txt`, `instrument*.txt` ve `logcat.txt` dosyalarına bak.
- Yayın: `v*` etiketi `.github/workflows/release.yml` iş akışını tetikler. İş akışı APK'yı imzalar, sertifikayı kontrol eder ve `kudretakn/Lumenara-releases` deposuna yayınlar. Sürüm notları CHANGELOG'daki `## <sürüm> · <tarih>` bölümünden alınır.
- **Tuzaklar (öğrenilenler):**
  - Testlerde sabit tarih kullanma; zamanı `System.currentTimeMillis()`'ten türet. EPG saklama penceresi (12 saat) sabit tarihli bir fixture'ı zamanla sildi (BackupTest, 17 Kasım).
  - Test sınıfları JUnit 4 `@Test` olmalı; `object` + `main` testleri Gradle'da "no tests discovered" verir. Sınıf içinde `const val` olmaz.
  - Codex sandbox'ı git worktree'lerin ortak `.git` klasörüne yazamaz. Birleştirme işlerinde ajan ayrı bir klonda çalışıp bundle üretir; lider bundle'ı içe alır.
  - Windows'ta Codex CLI PATH'te yok: `%LOCALAPPDATA%\OpenAI\Codex\bin\<hash>\codex.exe` kullan. `~\.codex\.sandbox-bin\codex.exe` bozuk (code-mode-host bulunamıyor, ajan hiçbir şey yapamıyor).
  - Windows'ta `classes.jar ... başka bir işlem tarafından kullanılıyor` hatası: önceki Gradle/Kotlin daemon'u kilit tutuyor; `gradlew --stop` sonra yeniden derle. Derleme düşerse cihaz testlerini eski APK'larla çalıştırma.
  - Yerel doğrulamada yalnız `:app:test` yetmez; `test --continue` (tüm modüller) çalıştır. `core:designsystem` `SourceGlyphTest` üretim kodunda ✓ ★ ▶ • ← → karakterlerini yasaklar (0.5.2'de regex'teki "→" CI'da düştü); gerekirse `→` kaçışı kullan.
  - Ajan düzeltme bildirdiğinde testi gerçekten çalıştır: Windows altyazı hatasında ajanın ilk tahmini yanlıştı (asıl neden dosya adındaki `:`).
  - GitHub'ın kimliksiz API sınırı (saatte 60 istek, IP başına) sürüm kontrolünü 403 ile düşürebilir; güncelleyici bunu açık mesajla gösterir ve web sayfasından yedek yol dener (0.5.1).
- Planlar: `docs/UI-REDESIGN.md` (bitti), `docs/USABILITY.md` (U1, S, A, U2, U3), `ROADMAP.md`.
