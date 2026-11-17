# Durum

Son güncelleme: 17 Kasım 2026 13:05 · yerel (Windows) oturum kapandı → sunucu oturumuna devir

> **Devir (yerel → sunucu, 17 Kasım 13:05):**
> 1. **PR #27 (`release/0.5.3` → v0.5.3)** açık. Yerelde tüm modül testleri, lint, 2 APK ve emülatörde 76/76 cihaz testi geçti; CI'da `build` geçmişti, bu devir commit'iyle CI yeniden çalışır. `gh pr checks 27` tümü pass → `gh pr merge 27 --merge` → `git tag v0.5.3 && git push origin v0.5.3` (release.yml yayınlar) → `gh run watch`.
> 2. TV'ye kurulum ve ölçüm yalnız ev ağındaki Windows bilgisayardan (adb `192.168.1.14:5555`). Sunucudan yapılamaz: kullanıcıya uygulama içi güncellemeyi (Ayarlar → Uygulama güncellemeleri) söyle; sonra Windows oturumunda `adb logcat -s LumenaraPerf` ile soğuk açılış (yeni aşama işaretleri dahil) ve "Tüm kanallar" ölç; 0.5.0: 5,4 sn / 7,7 sn, 0.5.2: 5,9 sn.
> 3. Sıradaki iki iş kullanıcı "başla" deyince: kaydırdıkça yükleme (`docs/plans/lazy-grids.md`, Codex istemi hazır) ve filmlerin iki kez görünmesi (aşağıda "Sıradaki işler" 0 ve 0b).
> 4. Windows'ta kalan yerel worktree'ler (`lumenara-wt\*`) ve birleşmiş uzak dallar: silmek için kullanıcı onayı gerekir.

> **Başlangıç:** `git fetch --all --prune && git switch main && git pull`, sonra bu dosyayı ve `CLAUDE.md`'yi oku.
> **Çalışma düzeni:** sen ekip liderisin. İşi böl, her işi ayrı bir alt ajana ver: kod ve görsel için Codex (`gpt-6-luna` basit, `gpt-6.1-sol` orta/zor, `gpt-6-astra` çok zor), inceleme/log analizi/araştırma için Claude alt ajanları (haiku/sonnet/opus). Kendin kod yazma (yalnız birleştirme çakışması ve tek satırlık mekanik düzeltme). Kullanıcıyla Türkçe, kısa konuş; commit ve PR metinleri İngilizce.

## Şu an
- **Açık PR `release/0.5.3` → v0.5.3:** açılış hızı (Hub sayımları/arka plan işlerini beklemiyor, Room ana thread dışında, açılış aşaması Perf işaretleri), "Tüm kanallar" ilk sayfa indeksli `LIMIT 28`, LibVLC ilk Hub karesinden sonra ısınıyor + `VlcInput` iptal yarışı düzeltildi (yalnız `call.cancel()`, temizlik pump thread'inde), detay sayfası odağı, Windows altyazı önbelleği dosya adı (`:` yok). CI yeşilse merge → `v0.5.3` → TV'ye ADB ile kur → soğuk açılış ve "Tüm kanallar" `LumenaraPerf` ile yeniden ölç (0.5.0: 5,4 sn ve 7,7 sn; 0.5.2: 5,9 sn).
- **Son yayınlanan sürüm v0.5.2** (17 Kasım, PR #26): canlı yayın yeniden bağlanma, CH+/− tuşları, geniş kartlar. **TV'de v0.5.2 kurulu** (ADB, 17 Kasım 12:28). TR canlı kanal 90 sn kesintisiz oynadı (2–2,8 Mbps, ilk kare 1,85 sn); sağlayıcı akışı kesmediği için yeniden bağlanma sahada henüz tetiklenmedi.
- v0.5.1 (PR #24): güncelleyici GitHub istek sınırı düzeltmesi.
- 0.5.1 ile gelenler: sürüm kontrolü istek sınırında "GitHub istek sınırına ulaşıldı, N dakika sonra tekrar deneyin" der; API yanıt vermezse web sayfasından (redirect + expanded_assets) sürümü ve SHA-256 özetini bulur, özet yoksa kurmaz; ETag önbelleği; açılıştaki otomatik kontrol saatte en çok bir kez; kontrol ve indirme hataları ayrı mesaj.

## Sıradaki işler (kullanıcı "başla" demeden başlama)
- **A. Kaydırdıkça yükleme (kullanıcı isteği, 17 Kasım 2026):** "Filmlere basınca 'Devamını göster' çıkıyor, basınca beni en yukarı atıyor. Böyle bir buton istemiyorum. Lazy loading yapsın, indikçe aşağıdakileri yüklesin; çok aşağı inersem yukarıdakiler önbellekte boş yer kaplamasın." Düğme dört yerde: `Browse.kt` (Filmler/Diziler), `SearchScreen.kt`, `LiveScreen.kt`, `Playback.kt` (oynatıcı listesi). Pencereli sayfalama (`LivePages` genelleştirilir), odak hiç zıplamaz, uzak sayfalar bellekten atılır. Codex istemi: `docs/plans/lazy-grids.md` (yerelde başlatıldı, dosya değiştirmeden durduruldu).
- **B. Filmler iki kez görünüyor (kullanıcı geri bildirimi, 17 Kasım 2026):** Filmler ızgarasında aynı film 2'şer adet. Olası neden: aynı filmin sürümleri (4K/HD/altyazılı/dublaj) ya da kategori kopyaları `versionKey`/`isRepresentative` ile tek karta indirilmiyor. Önce katalogda hangi kopyaların çıktığını ölç.
0. (PR #26'da yapıldı) **Kart genişliği (kullanıcı geri bildirimi, 16 Kasım, 0.5.0'ı TV'de gördükten sonra):** "Kartlar yatayda biraz büyüsün; bu sefer de isimler okunmuyor, alan çok küçük olmuş." Hedef: kategori, kanal ve içerik adları okunabilsin; dikeydeki sıklık korunsun, yalnız sütun sayısı biraz düşebilir. Kontrol edilecek yerler: kategori ızgarası (6 sütun, 60 dp), kompakt kanal kartı (6 sütun), afiş ızgarası (8 sütun, ad yalnız odakta), Hub raf kartları (110 dp), arama sonuçları (110 dp), bölüm kartları (140 dp). Öneri: kategori ve kanal 6 → 4–5 sütun ya da kanal adı için 2 satır; önce ekran görüntüsünde hangi adların kesildiğini ölç. Genel ölçek ayarı İSTENMİYOR.
1. (17 Kasım'da yapıldı; sonuçlar aşağıda "TV ölçümleri"nde. Remux testi kullanıcı isteğiyle atlandı. Yeni işler: soğuk açılış 5,4 sn ve "Tüm kanallar" 7,7 sn hızlandırılmalı.) **TV'de ölçüm (yalnız ev ağındaki bilgisayardan yapılabilir):** `adb shell dumpsys meminfo dev.lumenara.tv` (ana ekran, Canlı TV kategorisi, kanal oynarken) ve 1 dk `adb logcat -s LumenaraPerf`. Amaç 0.4.2'deki hız ve bellek işlerinin gerçek etkisini görmek. Ayrıca 4K yayın ve remux dosya denemesi (takılma var mı), sessiz kanalların sesi (0.4.0'daki VLC'ye otomatik geçiş).
2. **U3:** ebeveyn kilidi (PIN) ve kanal sıralama (`docs/USABILITY.md`). Şema şu an 10; değişiklik 10→11 olur, `MigrationTest` güncellenir. +18 filtresi PIN'siz geldi, PIN ile birleştirilebilir.
3. ROADMAP: izleme kaydını taşıma (sağlayıcı adresi/şifresi değişince), TMDB alternatif başlık, altyazı eşitleme.
4. Küçük: (0.5.3'te düzeldi: Windows `SubtitleApiTest`, önbellek dosya adındaki `:`). Altyazı arama ekranında oynatıcının arkasında kitaplık görünmesi büyük olasılıkla D5 ile çözüldü; doğrula.
5. Depo temizliği: GitHub'da birleşmiş ~40 uzak dal duruyor (design/*, perf*/*, mem/*, feat/f-*, fix/*); kullanıcıya sorup sil. Windows'taki `lumenara-wt\*` klasörleri silinebilir.

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
