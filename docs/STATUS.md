# Durum

Son güncelleme: 27 Kasım 2026 (PR #41 cihaz testi düzeltmeleri)

> **Başlangıç:** `git fetch --all --prune && git switch main && git pull`, sonra bu dosyayı ve `CLAUDE.md`'yi oku.
> **Çalışma düzeni (18 Kasım 2026, kullanıcı kararı):** sen ekip liderisin. Yalnız **Codex** ajanları kullan (`gpt-6-luna` basit, `gpt-6.1-sol` orta/zor, `gpt-6-astra` çok zor); Claude alt ajanı açma (inceleme ve log analizi de Codex'e). Kendin kod yazma (yalnız birleştirme çakışması ve tek satırlık mekanik düzeltme). Kullanıcıyla Türkçe, kısa konuş; commit ve PR metinleri İngilizce.

## Şu an
- **PR #41 CI cihaz testi düzeltmeleri** (`fix/0.6.0-verify`): build geçti; device 110 geçti, 3 test ilk koşuda ve tekrarda kaldı. `HubFirstDrawTest.kt:75` ilk çizimde gate=true bekliyordu (logcat 47088–47092); sahibin bilinçli kabuk-önce sözleşmesine göre test ilk karede raf/gate yok, sonraki üç kare içinde raflar/gate hazır ve canlı/film rafı odağı geri geliyor koşullarını doğruluyor. `SearchPlayerPagingTest.kt:97` yeni sonuç yerine null aldı (47650–47655); TV Surface için dokunma yerine kumanda OK kullanılıyor, odak ve yeni Item eşitliği korunuyor. `WindowPagesDriverTest.kt:40` 10 sn beklemede kaldı (48246–48249): SnapshotStateMap anahtar görünümünün kimlik eşitliği nedeniyle karşılaştırmalar `keys.toSet()` oldu; tutulan sayfa güncellemesi, yalnız eksik sayfa yükleme ve iptal/yeniden yükleme kontrolleri korunuyor. Üretim kodu değişmedi. Gradle çalıştırılmadı; gerçek Compose 1.11.1 sınıflarıyla Gradle dışı JVM örneği anahtar eşitliği sorununu doğruladı, `git diff --check` temiz. Yeniden derlenmiş APK ile emülatör ve ardından build+device CI doğrulaması bekliyor; commit/push yapılmadı.
- **0.6.0 hazırlığı** (`release/0.6.0`, doğrulama düzeltmeleri `fix/0.6.0-verify`; sürüm `0.6.0`, versionCode `21`): `perf/fast-search` arama sayfalama/sonuç koruma, `perf/fast-grid` sınırlı sayfa penceresi, `perf/fast-cpu` boşta arka plan kapısı, `perf/fast-marks` tek seferlik zap/ilk kare ölçümü ve `exp/hub-shell-first` önce Hub kabuğu çizimi birleşti; yalnız ARM APK **97 MB**.
- **0.6.0 TV A/B (sahibin PC koşusu):** Hub en uzun kare **3,2 → 2,4 sn** (Hub **+0,2 sn**), CPU15 **−%11**, arama yavaş kareleri **−%40**, kanal ızgarası >32 ms kareleri **−%30**. Birleşik sürümün TV ölçümü henüz yapılmadı; `hub-shell-first` kaynaklı `HubFirstDrawTest` sözleşme değişikliği doğrulanmalı.
- **0.6.0 doğrulama:** arama testi güncel `installPage` API'sine uyarlandı; tuşla boşta kapısını erteleme desteklenen `Window.Callback` üzerinden korunuyor; zap başlangıcı `DisposableEffect` kullanıyor. Gradle olmadan önbellekteki Kotlin/JUnit ile mevcut `PagingWindowTest`, `PerfTransitionsTest` (saf sınıf çıkarılarak) ve `BackgroundWorkTest`: **18 test geçti**; `git diff --check` temiz. Android derlemesi/lint/APK ve emülatör doğrulaması liderde; CI kırmızıysa yayın yok, force push yok. Commit/push yapılmadı.
- **0.5.9 hazırlığı** (`feat/0.5.9-carry-zap`, yerel değişiklikler; commit/push yok): 18 Kasım carry/zap/PowerShell yamaları güncel v0.5.8 tabanına taşındı. Sürüm `0.5.9`, versionCode `20`; Room şeması **13** kaldı. Katalog yenilemede mevcut `prepareCatalog` (sürüm grupları + türetilmiş veriler), 0.5.8 eski alanlardan okuma yolu ve performans işaretleri korunuyor. Gradle, APK ve cihaz doğrulaması liderde.
- **Geçmiş kurtarma:** kaynak silinmeden önce yalnız kullanıcı durumu ve eşleşme için gerekli kimlikler cihaz Vault'u ile şifrelenir; Room silme ve DataStore yazma mevcut dayanıklı geri alma günlüğüyle birlikte yapılır. Favori/Listem bayrakları birleştirilir, ilerlemede en yeni kayıt kazanır. Eşleşmeyen/belirsiz kayıtlar 30 gün boyunca saklanır ve sonraki katalog yenilemelerinde yeniden denenir. Yedek dışıdır; yedek içe aktarması yerel kurtarma kayıtlarını tüketmez. Ayrıntılı eşleşme kuralı: `docs/plans/history-carry-0.5.9.md`.
- **Hızlı kanal geçişi:** CH/PAGE/DPAD aynı 80 ms son hedef kuyruğunu kullanır; eski sorgu ve VLC HTTP açılışı iptal edilir. VLC oynatıcı/görüntü bağlantısı canlı oturum boyunca, Media3 renderers canlı geçişte korunur; komşu URL/DNS hazırlığı ek IPTV akışı açmaz. Sessiz kanalda otomatik VLC geçişi oturumda korunur; desteklenmeyen canlı akışta otomatik Media3 dönüşü döngüye girmez. Yeniden bağlanma politikası değişmedi. `zap_start` / `zap_first_frame` mevcut `channel_first_frame_*` ölçümlerine eklendi (VLC'de Vout sinyali).
- **Yerel doğrulama (Gradle çalıştırılmadı):** önbellekteki Kotlin/JUnit ile 66 benzersiz JVM testi geçti: 13 kurtarma, 10 mevcut taşıma, 11 geri alma günlüğü, 14 zap, 12 yeniden bağlanma, 6 VLC veri pompası. Güncel Kotlin/Compose kaynak kontrolü de geçti (geçici kaynak kimlikleriyle; Android paketleme/kod üretimi değil). Room/KSP, lint, APK ve cihaz doğrulaması yapılmadı.
- **0.5.9 kabul kontrolü:** lider tüm modül JVM testlerini/lint/APK derlemesini ve emülatör testlerini çalıştırmalı. Mi Box'ta hızlı ve ters yönlü tuş serileri, liste sınırı, numara/rehberden doğrudan seçim, sessiz kanalda VLC geçişi, HLS kanalına dönüş, yeniden bağlanma ve kaynak sil/ekle + kısmi eşleşme denenmeli; zap süreleri önceki 1,0–1,7 sn ile karşılaştırılmalı.
- **Son sürüm v0.5.8** (21 Kasım, PR #36 birleşti ve yayımlandı): türetilmiş katalog arka planda doldurulurken eski alanlardan okuma, backfill'in hemen başlaması, Hub raflarının ilk çizime katılması ve alt raf başlıklarının kesilmesi düzeltmesi (`HubShelf`, başlık ve alt yazılar dahil tüm raf için `bringIntoView` istiyor). `fix/derived-fallback` ve Hub raf başlığı işleri tamamlandı. v0.5.7 (PR #34/#35) yayımlandı; açık PR yok.
- **Doğrulama bekleyenler:** 0.5.6/0.5.7/0.5.8 için TV ölçümü henüz yok; aşağıdaki ölçümler 0.5.5'e ait. `HubFirstDrawTest` / `DenseGridTest` son raf senaryosu cihazda çalıştırılmadı. Mi Box'ta güncelleme sonrası ilk açılış, +18, kanal sırası/numarası, DPAD raf odağı (alt raf/sağ-sol gezinmesi dahil) ve açılış CPU/süre ölçümü bekliyor.
- 0.5.5 TV ölçümü (Mi Box 4, 39.682 içerik):
  - Güncelleme sonrası ilk açılış: `migration_11_12` 16 ms, `room_open` 212 ms, ilk kare 0,82 sn (0.5.4: 57 sn boş ekran). Sürüm anahtarı doldurma arka planda ~80 parti × 0,3–2,4 sn ≈ 45 sn, ekranı bekletmedi.
  - Normal soğuk açılış: ilk kare 0,69 sn (0.5.4: 2,5), son kanallar 0,28 sn (1,9), devam 0,30 sn (3,0), **Hub tam çizim 3,2 sn** (4,6), **sayımlar 2,6 sn** (1,3), **ilk 12 sn CPU ≈ 23–24 sn** (0.5.4: ~16 sn).
  - 4K Filmler'de "Avatar" hâlâ iki kart (yıl 2009 / yıl boş). **Kullanıcı kararı (18 Kasım): "Avatar kalabilir, sıkıntı yok"** — kopya film konusu kapandı, tanılama raporu eklenmeyecek.
- v0.5.4 (PR #28), v0.5.3 (PR #27), v0.5.2 (PR #26) yayında.
- Temizlik yapıldı (18 Kasım, kullanıcı onayı): 46 birleşmiş uzak dal, 22 worktree, 31 yerel dal silindi; yedek `lumenara-wt-backup-2026-11-18`. `local-backup-2026-11-14` dalı birleşmemiş, dokunulmadı. Windows'ta çalışma klasörü: `lumenara-wt\_work` (doğrulama/yayın betikleri: `verify.ps1`, `ship.ps1`).

## Sıradaki işler
- **0. Kaynak eklerken "Kaynak işleniyor…"da sonsuz bekleme:** tamamlandı, v0.5.7'de yayımlandı (PR #34/#35); TV'de kısa URL ile deneme bekliyor.
- **1. Açılış hızı ve türetilmiş veriler:** v0.5.6'da tamamlandı (PR #32, şema 13); Hub tam çizim ve açılış CPU hedeflerinin TV'de doğrulanması açık.
- **2. Kaydırırken titreme:** v0.5.6'da tamamlandı (PR #32, sabit odak sınırları ve başlık alanı); TV'de alt satırlarda sağ/sol gezinmesi kontrol edilmeli. Ayrıntı: `docs/plans/grid-jitter.md`.
- **Ana ilke (kullanıcı kararı, 18 Kasım): IPTV listesi zaten TV'de (Room) duruyor; ondan türetilen her şey (temiz başlık/sıralama anahtarı, sürüm grupları, +18 sınıfı, kategori sırası ve sayımları, Hub toplamları, arama anahtarları, kanal numaraları) liste yenilenirken bir kez hesaplanıp saklansın, açılışta yalnız hazır sonuç okunsun. Gerekirse şema 12→13 (yalnız DDL), mevcut satırlar arka planda parça parça doldurulur.**
- Açılış inceleme notları: `docs/plans/startup-cpu-0.5.6.md` (gpt-6.1-sol high). TV'de kabul: alt satırlarda sağ/sol gezerken liste dikeyde hiç kıpırdamasın; yalnız satır değişince kaysın.
- Not: genel OkHttp istemcisine `callTimeout` EKLENMEZ — aynı istemci VLC oynatma, güncelleme indirme, OpenSubtitles ve TMDB tarafından kullanılıyor (incelemede yakalandı).
- Hız hedefleri: ilk kare < 1,2 sn (sağlandı), Hub tam < 1,5 sn, ilk 15 sn CPU < 5 sn. Hub ve CPU hedefleri açık; 0.5.5 ölçümü Hub tam 3,2 sn, ilk 12 sn CPU ≈ 23–24 sn.
- U3 (ebeveyn kilidi/PIN, kanal sıralama) kullanıcı kararıyla planlardan SİLİNDİ (18 Kasım); önerme, yapma. Remux testi: kullanıcı "geç" dedi. Baseline profile cihazda ÜRETİLMEYECEK (kullanıcı kararı, 18 Kasım).
- Kontrol: altyazı arama ekranında arkada kitaplık görünmesi, sessiz kanallarda VLC'ye geçiş, canlı yayın yeniden bağlanmanın sahada tetiklenmesi, altyazı gecikmesinin TV'de denenmesi.

### Sunucudaki commit edilmemiş işler (18 Kasım, Oracle sunucusu)

- Yalnız `tools/perf-harness`: `scripts/perf/` işi eski `6c43fa5` tabanında commit edilmemiş durumda kaldı; bu porta dahil değil.
- Eski yama yedekleri `~/lumenara-wip-backup-2026-11-24/*.patch` altında korunuyor; silinmedi.

## TV ölçümleri (17 Kasım 2026, Mi Box 4, v0.5.0, Wi-Fi)
- Bellek (PSS): Hub boşta 72 MB, 10.645 kanallı ızgara 90 MB, canlı (VLC) 87 MB, 4K film 109 MB. Sorun yok.
- Soğuk açılış `startup_hub_first_draw` **5,4 sn** (iki ölçüm; Android `TotalTime` 1,1 sn).
- "Tüm kanallar" (10.645) `category_grid_first_draw` **7,7 sn**, 4 sn iskelet; 95 filmlik kategori 1,2 sn.
- 4K film (TR ➾ 4K Filmler): 3840×2160 Amlogic donanım kodlayıcı, VLC, ilk kare 6,0 sn, ortalama 7 Mbps, takılma yok; kare hızı eşleme 23,976 Hz'e geçti. CPU uygulama %22, kodlayıcı %10.
- Canlı TR (DA VINCI FHD): ilk kare 1,0–1,7 sn (VLC, 1080p H.264); ~11 sn sonra sağlayıcı akışı kapatıyor (`Decoder is draining` → `Stopped`), görüntü donuyordu (kullanıcı da gördü) → PR #26.
- Bir AF kanalında VLC hazırlığı 21 sn sürdü ve `fd://` açılamadı hatası (tekrarlanmadı). Film detay sayfasında adb `DPAD_CENTER` işlenmedi (`Unhandled key`); kumandayla çalıştı, takip et.
- Windows'ta Codex CLI: `%LOCALAPPDATA%\OpenAI\Codex\bin\<hash>\codex.exe`. `~\.codex\.sandbox-bin\codex.exe` bozuk (code-mode-host bulunamıyor, ajan hiçbir şey yapamıyor).

## Sürüm geçmişi (Lumenara-releases)
- v0.5.1 güncelleyici düzeltmesi · v0.5.0 "Quiet Cinema" tasarımı (D0–D8, sık ızgaralar) · v0.4.3 Watch Next, sesli arama, kare hızı eşleme, uyku zamanlayıcısı, şifreli yedek (şema 10) · v0.4.2 derin performans ve bellek (şema 9; uyarlanabilir Media3 tamponu 32–96 MB, tek LibVLC, onTrimMemory) · v0.4.1 performans paketi, iskeletler, Android 9 güncelleme imza düzeltmesi · v0.4.0 kartlı Hub, +18 filtresi, sessiz yayında VLC'ye geçiş · v0.3.x arayüz, Lumenara adı, güncelleme, U1/S/A/U2.

## Kullanıcı ve cihaz
- Xiaomi Mi Box 4 (Android 9, 2 GB RAM), Xtream kaynağı, 39.682 içerik. Mi Box'ta en az 100–150 Mbps alıyor; 4K ve yüksek bit hızlı (remux) dosya oynatacak. "Uygulama çok hızlı olmalı; performans ve ağdan yana sıkıntı istemiyorum."
- Tasarım: Apple TV / Netflix tarzı, sinematik, sade; tek altın vurgu, Inter, cam yok. `docs/DESIGN-0.5.md`.
- TV saati yanlıştı (GitHub TLS hatası); otomatik tarih/saat açıldı.
- Depo 15 Kasım'da herkese açık yapıldı (Actions dakikaları bitmişti); geçmiş gizli bilgi için tarandı, temiz. İmza anahtarı yalnız kullanıcının Windows bilgisayarında; release iş akışı GitHub secret'larıyla imzalar (`v*` etiketi).
- Gizli/maliyetli işler kullanıcıya kalır (kimlik, ödeme, anahtar). Yerleşik OpenSubtitles anahtarı ve TMDB token release'te secret'tan gömülür (CLAUDE.md "Gizli bilgiler").
- Kullanıcının başka projesi "Bugün Ne Yapsak" Oracle sunucusunda canlı Docker konteynerleriyle çalışıyor (`/var/lib/containerd` ~24 GB); onay almadan dokunma.
- Bilgi ve oturum özetleri Obsidian vault'unda (`github.com/kudretakn/Obsidian`, private; yazım yalnız `beyin.py` ile). Ekip kuralı ayrıntısı: `🔮 850-Companion/Kurallar-Detay.md`.

## Çalışma notları
- **CI:** `main` korumalı (build + device zorunlu). Hepsi yeşil olmadan merge yok; yeşilse sormadan merge. Cihaz testleri artık yerelde de çalıştırılabilir: `scripts/local-device-tests.ps1` (emülatör; ilk tam turu henüz doğrulanmadı). Release: `v*` etiketi → imzalı APK → `kudretakn/Lumenara-releases` (CHANGELOG'da `## <sürüm> · <tarih>` başlığı şart).
- **Sunucu (Oracle ARM):** 2 CPU, dar disk. 17 Kasım'da temizlendi: Gradle önbelleği, o tarihteki worktree'ler ve yerel dallar silindi (hepsi GitHub'da). Sunucuda ilk derleme ~2 GB bağımlılığı yeniden indirir. Sunucudaki `~/workspace/streamora`, `main` dalında (`origin` gerisinde); yerel oturum kendi klonunu kullanır. Eski worktree'ler var: carry, cpu, harness, jit, ps1, readme, subapi, win, zap. cpu/jit/readme/subapi/win içinde commit edilmemiş veya birleşmemiş iş yok; carry/zap/ps1 işleri 0.5.9 dalına taşındı; yalnız harness işi yukarıdaki alt bölümde açık.
- **Bilinen arada düşen testler** (tekrar denemede geçerse RETRIED yazılır): CinemaJourneyTest, FullscreenTest ("performMeasureAndLayout"), LiveComfortTest, AudioSubtitleTest (VLC altyazı).
- Sabit tarihli testlerden kaçın (bkz. CLAUDE.md "Tuzaklar").
