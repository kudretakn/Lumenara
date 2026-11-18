# Durum

Son güncelleme: 18 Kasım 2026 01:55 · yerel (Windows) oturum

> **Başlangıç:** `git fetch --all --prune && git switch main && git pull`, sonra bu dosyayı ve `CLAUDE.md`'yi oku.
> **Çalışma düzeni (18 Kasım 2026, kullanıcı kararı):** sen ekip liderisin. Yalnız **Codex** ajanları kullan (`gpt-6-luna` basit, `gpt-6.1-sol` orta/zor, `gpt-6-astra` çok zor); Claude alt ajanı açma (inceleme ve log analizi de Codex'e). Kendin kod yazma (yalnız birleştirme çakışması ve tek satırlık mekanik düzeltme). Kullanıcıyla Türkçe, kısa konuş; commit ve PR metinleri İngilizce.

## Şu an
- **PR `release/0.5.5` → v0.5.5:** altyazı gecikmesi (100 ms/1 sn, ±10 sn, öğe+sürüm başına), izleme kaydını taşıma (kaynak adresi/şifresi değişince; şema 12), TMDB alternatif başlık araması, hafif migration + arka planda kaldığı yerden devam eden sürüm anahtarı doldurma ("Lumenara güncelleniyor…" ekranı), hızlı Hub (indeksli son kanal/devam sorguları, sayımlar ilk çizimden hemen sonra, ağır işler 20 sn sonra), TMDB kimliğiyle film gruplama ve yarım parantezli başlık düzeltmesi. Yerelde tüm modül testleri, lint, debug + release APK ve emülatörde 92/92 cihaz testi geçti. CI yeşilse merge → `v0.5.5` → TV'ye kur → ilk açılış (migration 11→12 artık hafif olmalı), Hub süreleri, 4K Filmler'de tek "Avatar" ve altyazı gecikmesini TV'de doğrula.
- **TV'de v0.5.4 kurulu** (18 Kasım 02:16). 0.5.4 TV ölçümü: güncelleme sonrası ilk açılış 57 sn boş ekran (10→11 migration, 0.5.5'te giderildi); normal soğuk açılış: ilk kare 2,5 sn, Hub tam 4,6 sn, son kanallar 1,9 sn, devam 3,0 sn, sayımlar 1,3 sn, ilk 15 sn CPU ~16 sn (0.5.3'te ~32 sn); "Tüm kanallar" 1,9 sn (0.5.3: 7,3 sn). Kaydırdıkça yükleme TV'de çalışıyor. 4K Filmler'de "Avatar" hâlâ iki kart (yıl 2009 / yıl boş, ikisi de TMDB 7.9) → 0.5.5'te TMDB kimliğiyle gruplama.
- v0.5.4 (PR #28), v0.5.3 (PR #27), v0.5.2 (PR #26) yayında.
- Temizlik yapıldı (18 Kasım, kullanıcı onayı): 46 birleşmiş uzak dal, 22 worktree, 31 yerel dal silindi; yedek `lumenara-wt-backup-2026-11-18`. `local-backup-2026-11-14` dalı birleşmemiş, dokunulmadı.

## Sıradaki işler
- 0.5.5 yayını ve TV doğrulaması.
- İzleme kaydı taşımada elle yol (kaynağı silip yenisini ekleyince) yapılmadı.
- Hız hedefleri: ilk kare < 1,2 sn, Hub tam < 1,5 sn sonra, ilk 15 sn CPU < 5 sn — 0.5.5 ölçümüne göre devam.
- U3 (ebeveyn kilidi/PIN, kanal sıralama): kullanıcı istemedikçe başlama. Remux testi: kullanıcı "geç" dedi. Baseline profile cihazda ÜRETİLMEYECEK (kullanıcı kararı, 18 Kasım).
- Kontrol: altyazı arama ekranında arkada kitaplık görünmesi, sessiz kanallarda VLC'ye geçiş, canlı yayın yeniden bağlanmanın sahada tetiklenmesi.
- `scripts/local-device-tests.ps1` RETRIED testi "Tests that never ran" diye yanlış sınıflandırıyor; düzeltilmeli.

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
- **Sunucu (Oracle ARM):** 2 CPU, dar disk. 17 Kasım'da temizlendi: Gradle önbelleği, tüm worktree'ler ve yerel dallar silindi (hepsi GitHub'da). Sunucuda ilk derleme ~2 GB bağımlılığı yeniden indirir. Sunucudaki `~/workspace/streamora` eski bir dalda; yerel oturum kendi klonunu kullanır.
- **Bilinen arada düşen testler** (tekrar denemede geçerse RETRIED yazılır): CinemaJourneyTest, FullscreenTest ("performMeasureAndLayout"), LiveComfortTest, AudioSubtitleTest (VLC altyazı).
- Sabit tarihli testlerden kaçın (bkz. CLAUDE.md "Tuzaklar").
