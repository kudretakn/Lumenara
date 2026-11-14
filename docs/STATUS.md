# Durum

Son güncelleme: 14 Kasım 2026 · PR #14

## Biten
- 0.3.0 arayüz yenilemesi (#1–#6), core/data testleri (#7), Lumenara adı ve logo (#8), tek tuşla güncelleme ve release.yml (#9), CHANGELOG 0.3.0 (#10).
- U1 canlı TV konforu (#11), S dizi deneyimi (#12), A dublaj ve altyazı (#13).

## Açık
- **PR #14 — U2** (`feat/u2-program-search`): Ara ekranına "Programlar" rafı, Canlı TV'ye "Şu an yayında" filtresi.
  - Kanal–EPG eşleştirmesi `core/data/src/main/java/dev/lumenara/data/GuideResolver.kt` içinde. Büyük listelerde SQL uzunluk sınırına takılmamak için Kotlin tarafında yapılıyor.
  - Cihazda arama kısmı geçti. `ProgramDiscoveryTest` Canlı TV'ye geçişte `live-category-airing` etiketini bulamamıştı; test, sayfayı host durumuyla değiştirecek ve hata olursa ekran ağacını loga ("U2" etiketi) yazacak şekilde güncellendi. CI sonucu bekleniyor.

## Sıradaki
1. PR #14 yeşil olunca merge.
2. **U3** (`docs/USABILITY.md`):
   - Ebeveyn kilidi: 4 haneli PIN, kategori kilidi, PIN oturum boyunca geçerli, Keystore ile şifreli DataStore'da saklanır.
   - Kanal sıralama: uzun OK menüsüne Yukarı/Aşağı/Başa al eklenir. Room şeması 7→8 olur ve `MigrationTest` güncellenir; tvg-chno da saklanır.
3. ROADMAP eksikleri: izleme kaydı taşıma, TMDB alternatif başlık, altyazı eşitleme, performans ölçümü.
4. Kozmetik: altyazı arama ekran görüntüsünde oynatıcının arkasında kitaplık görünüyor (opaklığı kontrol et).

## Kullanıcıdan beklenenler
- GitHub secret'ları (`kudretakn/Lumenara`): `LUMENARA_KEYSTORE_B64`, `LUMENARA_KEYSTORE_PASSWORD`, `LUMENARA_RELEASES_TOKEN`. Sonuncusu yalnız Lumenara-releases deposunda Contents okuma-yazma yetkisi olan fine-grained bir PAT. Eklendiğini `gh secret list -R kudretakn/Lumenara` ile kontrol et, sonra `git tag v0.3.0 && git push origin v0.3.0`.
- TV'deki uygulamada:
  - Ayarlar → GitHub deposu = `kudretakn/Lumenara-releases`.
  - OpenSubtitles ve Subdl API anahtarları.

## Bilinen arada bir düşen testler
- CinemaJourneyTest.
- FullscreenTest: bir kez Compose "performMeasureAndLayout called during measure layout" çökmesi.
- LiveComfortTest.

Tekrar denemede geçerlerse RETRIED olarak raporlanır.
