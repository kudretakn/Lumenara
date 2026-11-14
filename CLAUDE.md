# Lumenara — Claude Code talimatları

Kişisel kullanım için Android TV IPTV oynatıcısı (Kotlin, Compose for TV, Room, Media3 + VLC). Kullanıcının kaynağı Xtream; M3U, EPG ve TMDB de destekleniyor.

**Oturum başında:** `git pull` ile depoyu güncelle. Güncel durum, açık işler ve sıradaki adımlar için @docs/STATUS.md dosyasını oku.

**Her PR'da:** `docs/STATUS.md` dosyasını güncelle: biten iş, açık PR, sıradaki adım ve kullanıcıdan beklenenler. Başka cihazdaki oturumlar durumu buradan alır. Kalıcı bir kural ya da kullanıcı kararı öğrenirsen bu dosyaya ekle.

## Çalışma kuralları
- **İş bölümü:** Claude planlar, kısa spesifikasyon yazar, inceler, test eder, PR/CI/merge işini yürütür. Kodu ve görselleri Codex CLI üretir (Codex kurulu değilse kodu Claude yazar).
  - Çağrı biçimi: `codex exec -m <model> -c model_reasoning_effort='"<seviye>"' -s workspace-write -C <dizin> -o <sonuç> - < istem.md`. İstem dosyadan verilir, stdin kapalı olur.
  - Codex commit ya da push yapmaz.
  - Codex'in bildirdiği düzeltmenin diskte olduğunu her zaman doğrula.
- **Model seçimi:** Basit işte `gpt-6-luna` low/medium, çok dosyalı ya da kritik işte `gpt-6.1-sol` medium/high. İstem kısa olur: dosya yolları, kabul ölçütleri ve "en fazla N satır rapor" isteği içerir. Token tasarrufu her iki tarafta da önemli.
- **Merge:** CI'daki **tüm** işler (`build` + `device`) pass olmadan merge yapılmaz; tam `gh pr checks` çıktısına bak. Hepsi yeşilse sormadan merge et. `main` korumalı.
- **Kullanıcıya kalanlar:** Kimlik, ödeme ve imza anahtarı adımları kullanıcıya bırakılır. Geri alınamaz silme ya da para harcatan işlem için açık onay gerekir.
- **Gizli bilgiler:** API anahtarları, imza anahtarı ve şifreler repoya ve loglara girmez. Release imza anahtarı yalnız kullanıcının Windows bilgisayarında durur; GitHub'a yalnız kullanıcının kendi eklediği secret'larla ulaşır. Altyazı API anahtarlarını kullanıcı uygulamanın Ayarlar ekranına girer (Keystore ile şifreli). Altyazı sitesi kazıma yok.
- **İletişim:** Kullanıcıya kısa, Türkçe yanıt ver.

## Derleme ve test
- JDK 17, Android SDK (platform 36, build-tools 36.0.0), `local.properties` içinde `sdk.dir`.
- Standart kontrol: `./gradlew test lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`
- Cihaz testleri CI'da Android TV API 36 x86_64 emülatöründe `scripts/ci-device-tests.sh` ile çalışır.
  - Bir test düşerse bir kez tekrar denenir; geçerse RETRIED yazılır.
  - Uygulama çökerse kalan testler yine çalışır.
  - Hata dökümü için: `gh run download <run-id> -n android-tv-device-artifacts`, sonra `summary.txt`, `instrument*.txt` ve `logcat.txt` dosyalarına bak.
- Yayın: `v*` etiketi `.github/workflows/release.yml` iş akışını tetikler. İş akışı APK'yı imzalar, sertifikayı kontrol eder ve `kudretakn/Lumenara-releases` deposuna yayınlar. Sürüm notları CHANGELOG'daki `## <sürüm> · <tarih>` bölümünden alınır.
- Planlar: `docs/UI-REDESIGN.md` (bitti), `docs/USABILITY.md` (U1, S, A, U2, U3), `ROADMAP.md`.
