# Durum

Son güncelleme: 16 Kasım 2026 01:10 · yerel (Windows) oturum kapandı

> **Sonraki oturum için devir notu.** Başlangıç: `git fetch --all --prune && git switch main && git pull`.
> - **v0.5.0 yayınlandı** (PR #22 merge `1d1bb86`) ve kullanıcının TV'sine ADB ile kuruldu (16 Kasım 01:05). Kullanıcıdan TV'deki görünüm hakkında geri bildirim al.
> - Açık PR: `chore/local-device-tests`. İçinde `scripts/local-device-tests.ps1` ve CLAUDE.md'deki "cihaz testleri önce yerelde" kuralı var (kullanıcı kararı). CI yeşilse merge et. Betik emülatörde yarım bir çalıştırmayla denendi, tam tur tamamlanmadı; ilk kullanımda sonucu doğrula.
> - Çalışma düzeni: sen ekip liderisin. İşleri Codex alt ajanlarına dağıt; kendin kod yazma (yalnız birleştirme çakışması ve mekanik düzeltme).
> - Kullanıcı istekleri (16 Kasım):
>   - "Kartlar çok büyük, daha çok şey sığsın" → 0.5.0'da yapıldı (ızgaralar 6/6/8 sütun, kartlar küçüldü).
>   - Genel ölçek ayarı İSTENMİYOR.
>   - Devamında daha fazla sıklık isteyebilir.
> - Yerel worktree'ler `C:\Users\Kudret\Documents\projeler\lumenara-wt\*`. Hepsi birleşti; silinebilir.

## Biten (0.5.0)
- **PR #22 — Tasarım 0.5.0** (dal `design/0.5`, merge edildi). İçerik:
  - D0–D8 (D8 temizlik: `Glass.kt`, `Ink` alias'ı, `NavIcon` ve Unicode glifler kaldırıldı; `SourceGlyphTest`).
  - İlk CI turunda düşen 9 cihaz testinin düzeltmesi. Ayarlar testleri iki sütunlu Ayarlar'a uyarlandı (`openSettingsGroup`). Hub hizası, bölüm görseli 16:9 yuva, `ArtworkFallbackDeviceTest` ana iş parçacığı ağ çağrısı, altyazı paneli odağı düzeltildi.
  - Kullanıcı isteğiyle sık ızgaralar: kategori ve kanal 6 sütun/60 dp kompakt, afiş 8 sütun (`DenseGridTest`).
  - Küçültülmüş kartlar: Hub 100/40 dp, raf 110 dp, arama 110×165 dp, bölüm 140 dp, sezon çipi 40 dp.
  - Kullanıcı genel ölçek ayarı İSTEMEDİ ("yalnız kartları küçült").
  - Yerelde test, lint ve iki APK geçti (yalnız bilinen 2 Windows `SubtitleApiTest` hatası).
  - CI'da yeşil olunca merge → `v0.5.0`. TV'ye ADB ile kur. Ekran görüntüleri artifact'ta `files/screens/`.
- Yerel worktree'ler: `C:\Users\Kudret\Documents\projeler\lumenara-wt\{d8,dense,fix-settings,fix-layout,compact,scale}`. scale atıldı. Dallar birleşti; merge sonrası silinebilir.
- Kullanıcının TV'sinde 0.4.3 kurulu (ADB ile, 15 Kasım 23:46).
- Onaylı maketler: `docs/DESIGN-0.5.md` §6 istemleri; PNG'ler sunucudaydı, gerekirse SVG istemleriyle yeniden üretilir.

## Yayınlanan sürümler (Lumenara-releases)
- v0.4.0 kartlı Hub, +18 filtresi, sessiz yayında VLC'ye geçiş (kullanıcının canlı kanallarında ses yoktu).
- v0.4.1 performans paketi, yüklenme iskeletleri, Android 9 güncelleme imza düzeltmesi, açıklayıcı hata mesajları.
- v0.4.2 derin performans + bellek (Opus taramaları; uyarlanabilir Media3 tamponu 32–96 MB, tek LibVLC, onTrimMemory). Şema 9.
- v0.4.3 Watch Next, sesli arama, kare hızı eşleme, uyku zamanlayıcısı, şifreli yedek/geri yükleme (her şey ya da hiç; şema 10).
- **Kullanıcının TV'sinde 0.4.0 kurulu** (ADB ile). 0.4.0'ın güncelleyicisi Android 9'da imza kontrolüne takılıyor; ilk güncelleme bir kez ADB ile gerekebilir (`gh release download vX -R kudretakn/Lumenara-releases -p "*.apk"`, `adb connect 192.168.1.14:5555`, `adb install -r`). 0.4.1+ güncelleyicisi düzeltildi.

## Kullanıcı bilgileri ve kararları (bu oturum)
- Mi Box 4 (Android 9, 2 GB), Xtream 39.682 içerik, Mi Box'ta 100–150 Mbps; 4K ve yüksek bit hızlı (remux) dosyalar oynatacak.
- TV saati yanlıştı → GitHub TLS hatası; otomatik saat açıldı.
- "Uygulama çok hızlı olmalı", RAM kullanımı yüksekti → 0.4.2. Kullanıcıdan ölçüm (`adb shell dumpsys meminfo dev.lumenara.tv`, `adb logcat -s LumenaraPerf`) henüz alınmadı; TV'ye kurduğunda iste.
- U3 (ebeveyn kilidi, kanal sıralama) ertelendi; şema değişikliği artık 10→11 olur.
- Depo 15 Kasım'da **herkese açık** yapıldı (Actions dakikaları bitmişti; kullanıcı kararı). Geçmiş gizli bilgi açısından tarandı, temiz.
- Codex sandbox'ı worktree'lerin ortak `.git`'ine yazamaz: birleştirme işlerinde ajan `/tmp` altına klon açıp bundle üretir, lider bundle'ı içe alır. Codex'e "testler JUnit 4 `@Test` sınıfı olmalı, `const val` sınıf içinde olmaz" kuralını her istemde ver (aksi halde Gradle "no tests discovered" verir).
- Sunucu diski dar (~6 GB boş); her derlemeden sonra build klasörleri silindi.

## Biten
- 0.3.x: arayüz, Lumenara adı, güncelleme, U1, S, A, U2 (#1–#17). Yayınlar v0.3.0–v0.3.2.
- **PR #18 → v0.4.0:** kartlı Hub, TR öncelikli kategoriler, +18 filtresi, çift geri çıkış; sessiz yayınlarda VLC'ye otomatik geçiş (kullanıcı canlı kanallarda ses yok dedi); VLC altyazı ve açılış yönlendirme düzeltmeleri.
- GitHub secret'ları tamam.

## Açık

### 1. (bitti) PR #18 — Kartlı gezinme ve +18 filtresi (0.4.0)
- Spesifikasyon `docs/USABILITY.md` sonundaki "K" bölümünde. Kullanıcı kararları:
  - Sol menü kalkar; açılışta 6 kart: Canlı TV, Filmler, Diziler, Listem, Ara, Ayarlar.
  - Kartların altında yalnız "Son kanal" ve "Kaldığın yerden devam et" kalır.
  - Kategoriler kart ızgarası olarak görünür. "TR" ile başlayanlar önce, sonra alfabetik, +18 en sonda.
  - Canlı TV'de kategori içi kanal kartı ızgarası, önizleme yok.
  - "+18 içerikleri göster" varsayılan kapalı, PIN yok.
  - Hub'da çift geri ile uygulama kapanır.
- Kodu Codex yazdı. Yerelde lint ve iki APK derlendi; birim testleri yalnız bilinen 2 Windows hatasıyla geçti.
- CI: `build` pass. `device` durdurulduğunda sürüyordu; `gh pr checks 18` ile bak. Düşen test olursa `gh run download <id> -n android-tv-device-artifacts` ile incele. 9 cihaz testi sol menüden Hub'a çevrildiği için kırılma beklenebilir.
- Bu dosyadaki ve docs/perf altındaki değişiklik PR'a yeni commit olarak eklendi; CI yeniden çalışır.
- TV'de henüz denenmedi.
- Yeşil olunca merge et ve `v0.4.0` etiketini at.

### 2. Performans aşaması (0.4.1): 4 paralel Codex ajanı, dallar `perf/*`
- Hepsi `feat/k-card-navigation` (78f7486) üzerine kurulu. Spesifikasyonlar `docs/perf/{common,db,images,network,live}.md`.
- Windows'ta `C:\Users\Kudret\Documents\projeler\lumenara-wt\p-*` worktree'lerinde çalıştılar. Kullanıcı durdurunca yarım işler WIP commit olarak push edildi. **Hiçbiri derlenmedi ve test edilmedi.**

| Dal | Durum | İçerik |
|---|---|---|
| `perf/images` (55611ad) | Codex bitirdi (rapor: `docs/perf/images-report.md`) | Kaynak bazlı HTTP görsel izni, kart boyutunda görsel, RGB565, 256 MB disk ve %15 bellek önbelleği, 7 gün saklama, negatif önbellek, sonraki satırı önceden yükleme. Yeni dosyalar: `Artwork.kt`, `ArtworkTest`, `ImageTransportTest`. |
| `perf/db` (7442586) | Yarıda kesildi | İndeksler, `versionKey`/`isRepresentative`, şema 7→8, `CatalogQuery.kt`, `CatalogQueryTest`. Eksikleri spesifikasyonla karşılaştır. `MigrationTest` ve `8.json` muhtemelen eksik. |
| `perf/network` (1899622) | Yarıda kesildi | `RefreshGate.kt`, koşullu istek (ETag/Last-Modified), EPG zaman penceresi. Testler: `ConditionalRefreshTest`, `GuideWindowTest`, `RefreshGateTest`. |
| `perf/live` (3d2f47b) | Yarıda kesildi | `LivePages.kt`, `Perf.kt` (LumenaraPerf ölçüm logları), `core/player/LiveBuffers.kt`, `LiveBuffersTest`. |

Devam yöntemi:
1. Her yarım dal için Codex'i aynı spesifikasyonla çalıştır. İstem: "`docs/perf/<ad>.md` + `docs/perf/common.md`; dalda yarım iş var, eksikleri tamamla". Dalları paralel worktree'lerde çalıştırmak hızlı oldu.
2. Her dalı ayrı derle: `./gradlew test lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`.
3. PR #18 merge olunca dalları sırayla tek bir `perf/0.4.1` dalında birleştir: önce db, sonra live, network, images. `LibraryDb.kt`, `Repository.kt`, `Transport.kt` ve `FullscreenOverlay.kt`'de çakışma beklenir.
4. Şema 7→8 bu aşamada kullanılıyor. U3'ün şema değişikliği bundan sonra 8→9 olur.
5. Tek PR, CI yeşil, merge, `v0.4.1`. CHANGELOG'a ölçülen kazançları yaz.
6. Kullanıcıdan TV'de güncelleyip `adb logcat -s LumenaraPerf` çıktısı ya da gözlem iste.

## 0.4.3 (PR #21)
- Özellik dalları `feat/f-*` + `fix/atomic-restore` birleştirildi. Şema 10 (geri yükleme günlüğü) → U3 artık 10→11.
- Sonraki: Tasarım 0.5 — `docs/DESIGN-0.5.md` (Opus) ve maketler kullanıcı onayında. Onaydan sonra D0 (tasarım sistemi) → D1–D7 paralel → D8.

## 0.4.2 (PR #20)
- Opus performans ve bellek taramalarının bulguları: P1–P6 (hız), M1–M3 (bellek), şema 9. Birleştirme ayrı klonda yapıldı (Codex sandbox, worktree'lerin ortak .git'ine yazamıyor; birleştirme işi için bağımsız klon kullan).
- Kullanıcı 4K ve yüksek bit hızlı dosya oynatacak; Mi Box'ta 100–150 Mbps alıyor. Tampon uyarlanabilir: cihaz bütçesi = memoryClass'ın %33'ü (32–96 MB), içerikte VOD 20 sn / canlı 10 sn.
- Sırada: özellik dalları (`feat/f-*`) → 0.4.3, sonra tasarım 0.5.0.

## 0.4.1 (PR #19)
- Performans paketi + yüklenme iskeletleri + Android 9 güncelleme imza düzeltmesi + açıklayıcı hata mesajları + VLC kısa okuma (altyazı düşmesi) düzeltmesi. Kullanıcının TV saati yanlıştı (GitHub TLS hatası); 0.4.0 ADB ile kuruldu.
- Sonraki: 0.4.2 derin performans (ajanlar P1–P6, dallar `perf2/p1..p6`, Opus taraması bulguları; şema 8→9 → U3 artık 9→10).

## Paralel dallar (Codex, hepsi yerelde derlendi ve birim testleri geçti; cihaz testi henüz yok)
- PR #19 `perf/0.4.1`: CI'da LivePagesTest ve LiveComfortTest (VLC) düşüyor; Codex düzeltiyor.
- Özellik dalları (9519d9e tabanlı, `docs/FEATURES-0.4.md`): `feat/f-watchnext`, `feat/f-assistant`, `feat/f-framerate`, `feat/f-sleep`, `feat/f-backup`, `feat/f-subfix`. #19 merge olunca tek `feat/0.4.2` dalında birleştirilecek. Çakışma beklenen dosyalar: Playback.kt, VlcScreen.kt, Settings.kt, MainActivity.kt.
- Sunucu diski dar (~6 GB boş): her derlemeden sonra build klasörlerini sil.

## Sıradaki
1. (bitti) PR #18 → v0.4.0.
2. Performans 0.4.1 (yukarıda).
3. Kullanıcı kararı (15 Kasım 2026): U3 yerine sırayla şunlar yapılacak:
   - **Android TV ana ekranı:** "İzlemeye devam et" (Watch Next) satırı ve Google Asistan sesli arama.
   - **Kare hızı eşleme + uyku zamanlayıcısı:** Filmlerde 24/25/50 Hz geçişi; 30/60/90 dk uyku zamanlayıcısı.
   - **Yedekle / geri yükle:** Ayarlar, kaynaklar, Listem ve izleme geçmişi.
4. **Tasarım yenilemesi (0.5.0)** — kullanıcı isteği (15 Kasım 2026), yukarıdaki üç işten sonra: "şık, sade, minimalist, kullanıcı odaklı; çok amatör duruyor, daha profesyonel görünsün". Giriş ekranındaki fotoğraflar, tema, renk, tipografi, kartlar, boşluklar dahil her şey gözden geçirilecek. Yöntem: CI ekran görüntüleriyle mevcut durumun eleştirisi → `docs/DESIGN-0.5.md` tasarım dili (az renk, tek vurgu, net tipografi ölçeği, gerçek içerik görselleri, süs yok) → maket görseller (Codex) → ekran ekran PR'lar.
5. **U3** (`docs/USABILITY.md`) ertelendi; kullanıcı istemedikçe başlama.
   - Ebeveyn kilidi.
   - Kanal sıralama. Şema artık 8→9 olur.
   - +18 filtresi K'de PIN'siz geldi; U3'te PIN ile birleştirilebilir.
4. ROADMAP eksikleri: izleme kaydı taşıma, TMDB alternatif başlık, altyazı eşitleme.
5. Kozmetik: altyazı arama ekranında oynatıcının arkasında kitaplık görünüyor.
6. Windows'ta `SubtitleCache.save` düşüyor (`File.renameTo`, `SubtitleCache.kt:17`). İki `SubtitleApiTest` yalnız Windows'ta düşüyor. Çözüm: `Files.move(..., REPLACE_EXISTING)`.

## Kullanıcıdan beklenenler
- TV'de yeni sürümleri Ayarlar → Uygulama güncellemeleri (listenin en altında) → "Kaydet ve sürüm kontrolü yap" → Güncelle ile kurmak. GitHub deposu `kudretakn/Lumenara-releases` olarak kayıtlı; "bilinmeyen kaynak" izni verildi.
- Subdl API anahtarı isteğe bağlı.

## Kalıcı kurallar ve kararlar (bu oturumdan)
- Tüm projelerde commit mesajları ve GitHub metinleri (PR, issue) **İngilizce**; kullanıcıyla konuşma Türkçe.
- Lumenara arayüzü Apple TV / Netflix tarzında, sinematik olmalı.
- Kullanıcının bilgileri ve oturum özetleri Obsidian vault'unda (github.com/kudretakn/Obsidian, private; "beyin" sistemi; yazım yalnız `beyin.py` ile).

## Bilinen arada bir düşen testler
- CinemaJourneyTest.
- FullscreenTest: bir kez Compose "performMeasureAndLayout called during measure layout" çökmesi.
- LiveComfortTest: #16'da düştü, yeniden çalıştırınca geçti.
- AudioSubtitleTest: #15'te VLC'ye geçişte 30 sn zaman aşımı (`AudioSubtitleTest.kt:169`); yeniden çalıştırınca geçti.

Tekrar denemede geçerlerse RETRIED olarak raporlanır.
