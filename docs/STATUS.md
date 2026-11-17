# Durum

Son güncelleme: 17 Kasım 2026 · sunucu oturumundan yerel (Windows) oturuma devir

> **Başlangıç:** `git fetch --all --prune && git switch main && git pull`, sonra bu dosyayı ve `CLAUDE.md`'yi oku.
> **Çalışma düzeni:** sen ekip liderisin. İşi böl, her işi ayrı bir alt ajana ver: kod ve görsel için Codex (`gpt-6-luna` basit, `gpt-6.1-sol` orta/zor, `gpt-6-astra` çok zor), inceleme/log analizi/araştırma için Claude alt ajanları (haiku/sonnet/opus). Kendin kod yazma (yalnız birleştirme çakışması ve tek satırlık mekanik düzeltme). Kullanıcıyla Türkçe, kısa konuş; commit ve PR metinleri İngilizce.

## Şu an
- **Son sürüm v0.5.1** (17 Kasım, PR #24). Açık PR yok; `main` güncel ve CI yeşil.
- **Kullanıcının TV'sinde v0.5.0 kurulu** (ADB ile). 0.5.1'i kurmak için: uygulamada Ayarlar → Uygulama güncellemeleri → "Kaydet ve sürüm kontrolü yap" → Güncelle. Kullanıcının IP'sinde GitHub kimliksiz API sınırı doluydu (`api.github.com/rate_limit` → `remaining: 0`); saat başı yenilenir, o zaman 0.5.0'ın güncelleyicisi de çalışabilir. Çalışmazsa ADB: `gh release download v0.5.1 -R kudretakn/Lumenara-releases -p "*.apk"`, `adb connect 192.168.1.14:5555`, `adb install -r Lumenara-0.5.1.apk` (verileri silme, uninstall yok).
- 0.5.1 ile gelenler: sürüm kontrolü istek sınırında "GitHub istek sınırına ulaşıldı, N dakika sonra tekrar deneyin" der; API yanıt vermezse web sayfasından (redirect + expanded_assets) sürümü ve SHA-256 özetini bulur, özet yoksa kurmaz; ETag önbelleği; açılıştaki otomatik kontrol saatte en çok bir kez; kontrol ve indirme hataları ayrı mesaj.

## Sıradaki işler (kullanıcı "başla" demeden başlama)
0. **Kart genişliği (kullanıcı geri bildirimi, 16 Kasım, 0.5.0'ı TV'de gördükten sonra):** "Kartlar yatayda biraz büyüsün; bu sefer de isimler okunmuyor, alan çok küçük olmuş." Hedef: kategori, kanal ve içerik adları okunabilsin; dikeydeki sıklık korunsun, yalnız sütun sayısı biraz düşebilir. Kontrol edilecek yerler: kategori ızgarası (6 sütun, 60 dp), kompakt kanal kartı (6 sütun), afiş ızgarası (8 sütun, ad yalnız odakta), Hub raf kartları (110 dp), arama sonuçları (110 dp), bölüm kartları (140 dp). Öneri: kategori ve kanal 6 → 4–5 sütun ya da kanal adı için 2 satır; önce ekran görüntüsünde hangi adların kesildiğini ölç. Genel ölçek ayarı İSTENMİYOR.
1. **TV'de ölçüm (yalnız ev ağındaki bilgisayardan yapılabilir):** `adb shell dumpsys meminfo dev.lumenara.tv` (ana ekran, Canlı TV kategorisi, kanal oynarken) ve 1 dk `adb logcat -s LumenaraPerf`. Amaç 0.4.2'deki hız ve bellek işlerinin gerçek etkisini görmek. Ayrıca 4K yayın ve remux dosya denemesi (takılma var mı), sessiz kanalların sesi (0.4.0'daki VLC'ye otomatik geçiş).
2. **U3:** ebeveyn kilidi (PIN) ve kanal sıralama (`docs/USABILITY.md`). Şema şu an 10; değişiklik 10→11 olur, `MigrationTest` güncellenir. +18 filtresi PIN'siz geldi, PIN ile birleştirilebilir.
3. ROADMAP: izleme kaydını taşıma (sağlayıcı adresi/şifresi değişince), TMDB alternatif başlık, altyazı eşitleme.
4. Küçük: Windows'ta iki `SubtitleApiTest` düşüyordu (atomik taşıma 0.4.3'te düzeldi; yerelde doğrula). Altyazı arama ekranında oynatıcının arkasında kitaplık görünmesi büyük olasılıkla D5 ile çözüldü; doğrula.
5. Depo temizliği: GitHub'da birleşmiş ~40 uzak dal duruyor (design/*, perf*/*, mem/*, feat/f-*, fix/*); kullanıcıya sorup sil. Windows'taki `lumenara-wt\*` klasörleri silinebilir.

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
- **Sunucu (Oracle ARM):** 2 CPU, dar disk. 17 Kasım'da temizlendi: Gradle önbelleği, tüm worktree'ler ve yerel dallar silindi (hepsi GitHub'da). Sunucuda ilk derleme ~2 GB bağımlılığı yeniden indirir. Sunucudaki `~/workspace/streamora` eski bir dalda; yerel oturum kendi klonunu kullanır.
- **Bilinen arada düşen testler** (tekrar denemede geçerse RETRIED yazılır): CinemaJourneyTest, FullscreenTest ("performMeasureAndLayout"), LiveComfortTest, AudioSubtitleTest (VLC altyazı).
- Sabit tarihli testlerden kaçın (bkz. CLAUDE.md "Tuzaklar").
