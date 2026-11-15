# Durum

Son güncelleme: 15 Kasım 2026 · sunucu oturumu

> **Sunucudaki Claude için devir notu.** Bu dosya `feat/k-card-navigation` dalında (PR #18). `main`'deki STATUS eski.
>
> Başlangıç:
> ```
> git fetch --all --prune
> git switch feat/k-card-navigation
> git pull
> ```
> Sonra aşağıdaki "Açık" ve "Sıradaki" bölümlerine göre devam et.

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
