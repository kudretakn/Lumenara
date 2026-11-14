# Durum

Son güncelleme: 15 Kasım 2026 · Büyük katalog düzeltmesi (0.3.2)

## Biten
- 0.3.0 arayüz yenilemesi (#1–#6), core/data testleri (#7), Lumenara adı ve logo (#8), tek tuşla güncelleme ve release.yml (#9), CHANGELOG 0.3.0 (#10).
- U1 canlı TV konforu (#11), S dizi deneyimi (#12), A dublaj ve altyazı (#13).
- U2 program arama ve "Şu an yayında" filtresi (#14, `e7dfefc`).
  - Kanal–EPG eşleştirmesi `core/data/src/main/java/dev/lumenara/data/GuideResolver.kt` içinde. Büyük listelerde SQL uzunluk sınırına takılmamak için Kotlin tarafında yapılıyor.
- STATUS güncellemesi (#15).
- **v0.3.0 yayınlandı** (14 Kasım 2026): https://github.com/kudretakn/Lumenara-releases/releases/tag/v0.3.0. Etiket #14 sonrası `main` üzerinde, yani U1–U2 dahil.
- GitHub secret'ları tamam: `LUMENARA_KEYSTORE_B64`, `LUMENARA_KEYSTORE_PASSWORD`, `LUMENARA_RELEASES_TOKEN`, `OPENSUBTITLES_API_KEY`, `TMDB_READ_TOKEN`.
- Yerleşik OpenSubtitles anahtarı ve TMDB token (#16). **v0.3.1 yayınlandı.** Kullanıcı kararı; ayrıntısı CLAUDE.md "Gizli bilgiler" maddesinde.

## Açık
- **Büyük katalog düzeltmesi** (`fix/large-catalog-queries`, sürüm 0.3.2).
  - Sorun: kullanıcının Mi Box 4'ünde (Android 9) 39.682 içerikli Xtream kaynağı eklenince Ana Sayfa, Canlı TV ve Filmler boş kaldı, uygulama donuyordu (%341 CPU, sürekli `CursorWindow: Window is full`).
  - Neden: film/dizi sorguları `mergeVersions` öncesi bütün tabloyu okuyordu.
  - Düzeltme: `LibraryDb.kt`'de SQL LIMIT eklendi (`VERSION_HEADROOM = 4`; vitrin 60, öneriler/devam et 96, koleksiyon 240).
  - Yeni test: `LargeCatalogTest.homeAndMovieShelvesStayFastWithThirtyThousandMovies`. Kullanıcının TV'sinde geçti: vitrin 1,4 sn, son eklenenler 2,9 sn, yüksek puanlılar 0,7 sn, film listesi 5,4 sn. Mi Box'ta 30 bin satırın içe aktarılması ~6 dk sürdü.
  - Merge sonrası `v0.3.2` etiketi atılacak.

## Sıradaki
1. **U3** (`docs/USABILITY.md`). Kullanıcı başlama talimatı verene kadar bekliyor.
   - Ebeveyn kilidi: 4 haneli PIN, kategori kilidi, PIN oturum boyunca geçerli, Keystore ile şifreli DataStore'da saklanır.
   - Kanal sıralama: uzun OK menüsüne Yukarı/Aşağı/Başa al eklenir. Room şeması 7→8 olur ve `MigrationTest` güncellenir; tvg-chno da saklanır.
2. ROADMAP eksikleri: izleme kaydı taşıma, TMDB alternatif başlık, altyazı eşitleme, performans ölçümü.
3. Kozmetik: altyazı arama ekran görüntüsünde oynatıcının arkasında kitaplık görünüyor (opaklığı kontrol et).
4. Büyük katalog performansı devamı: 120 kartlık film listesi Mi Box'ta hâlâ 5,4 sn (bütün tabloda GROUP BY + başlığa göre sıralama). Sürüm anahtarını içe aktarmada bir sütuna yazmak ve indekslemek bunu kısaltır; U3'teki 7→8 şema geçişiyle birleştirilebilir. İçe aktarma süresi de ölçülmeli.
5. Windows'ta `SubtitleCache.save` düşüyor: `File.renameTo` hedef dosya varken başarısız oluyor (`SubtitleCache.kt:17`). Bu yüzden `SubtitleApiTest`'ten iki test yalnız Windows'ta düşüyor. CI (Linux) ve Android etkilenmiyor. Düzeltme yolu: `Files.move(..., REPLACE_EXISTING)`.

## Kullanıcıdan beklenenler
- TV'deki uygulamada Ayarlar → GitHub deposu = `kudretakn/Lumenara-releases`, ardından "Kaydet ve sürüm kontrolü yap" ve güncelle.
- Subdl API anahtarı isteğe bağlı; Ayarlar → İnternetten altyazı alanına girilir. 0.3.1'den sonra OpenSubtitles ve TMDB için anahtar girmek gerekmiyor.

## Bilinen arada bir düşen testler
- CinemaJourneyTest.
- FullscreenTest: bir kez Compose "performMeasureAndLayout called during measure layout" çökmesi.
- LiveComfortTest.
- AudioSubtitleTest: #15'te VLC'ye geçişte "İndirilen altyazı etkin" durumu 30 sn içinde gelmedi (`AudioSubtitleTest.kt:169`). Doküman değişikliğiydi; yeniden çalıştırınca geçti.

Tekrar denemede geçerlerse RETRIED olarak raporlanır.
