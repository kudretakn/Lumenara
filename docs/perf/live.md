## Görev: live — canlı TV listeleri, oynatma başlangıcı ve ölçüm işaretleri

Sorunlar:
1. Açılışta ve canlı ekranlarda bütün canlı kanallar okunuyor; Mi Box'ta 2 MB CursorWindow taşıyor. Kaynakları:
   - `browse(..., "LIVE", ..., Int.MAX_VALUE)`: `LiveComfort.kt` numara girişi ve önceki kanal, `FullscreenOverlay.kt` mini rehber.
   - `liveChannelNumbers` / `liveChannelOrder`.
   - `LiveScreen.kt` geri dönüş indeksi.
2. Tam ekran yayının açılması yavaş. Kullanıcı "önizlemeler çok yavaş açılıyor" dedi; K ile önizleme kalktı ama tam ekran açılışı hâlâ önemli.

Yapılacaklar:
1. Kanal numarası ve sırası:
   - `liveChannelOrder` yalnız `id` döndürüyor; bu iyi. Sonucu `Repository` içinde kaynak başına bellekte tut (`StateFlow`); içe aktarma ve gizli liste değişince yenilensin. Her ekran yeniden sorgulamasın.
   - Numaradan kanala geçişte tüm listeyi değil `SELECT ... ORDER BY ... LIMIT 1 OFFSET :n-1` ile tek satır al.
   - "Önceki kanal" son izlenenlerden gelir; tüm listeyi okumaya gerek yok.
   - Mini rehber ve Timeline: yalnız görünen kategori ve sayfa okunsun; `Int.MAX_VALUE` yerine sayfalı LIMIT/OFFSET kullan. Odak ilerledikçe sonraki sayfa yüklensin.
   - Oynatmadan dönüşte odak aynı kanala gelsin: `returnToChannel` indeksini tüm listeyi okumadan, ORDER BY kuralına göre `COUNT(*) WHERE (title,id) < (...)` ile bul.
   - Yeni DAO fonksiyonları `LibraryDb.kt`'ye eklenecek; db ajanı da bu dosyayı değiştiriyor. Yalnız yeni fonksiyon ekle, mevcutları değiştirme. Hepsi `hidden` (+18) listesine uymalı.
2. Oynatma başlangıcı (Media3, `core/player/Players.kt` ve `Playback.kt`):
   - Canlı yayın için `DefaultLoadControl`: `bufferForPlaybackMs` yaklaşık 1000, `bufferForPlaybackAfterRebufferMs` yaklaşık 2500. Kullanıcının "Media3 tamponu" seçimi (Dengeli/Hızlı/Kararlı) korunsun ve değerleri buna göre ayarlansın; "Hızlı" en düşük olsun.
   - Kanal değiştirirken ExoPlayer örneğini yeniden oluşturma: aynı oynatıcıda `setMediaItem` + `prepare` kullan, surface'i koru.
   - HLS ve TS için `DefaultHttpDataSource` bağlantı ve okuma zaman aşımlarını makul tut (bağlantı 8 sn, okuma 15 sn). Yeniden bağlanma mantığı (U1) bozulmasın.
3. Ölçüm işaretleri (`app/.../Perf.kt`):
   - Hafif bir yardımcı yaz: `Perf.mark(name)` ve `Perf.since(name, label)`. Çıktı `Log.i("LumenaraPerf", "<label> ms=<n>")`.
   - Şu anları işaretle:
     - Uygulama açılışı → Hub'ın ilk veriyle çizimi.
     - Kategori kartına basma → ızgaranın ilk dolu çizimi.
     - Kanala basma → ilk video karesi (Media3 `onRenderedFirstFrame`, VLC'de `Vout`).
   - İçerik adı ya da adres loglanmasın; yalnız süre ve sayı loglanır.
   - Release'te de açık kalsın; maliyeti ihmal edilebilir.
4. Testler:
   - `LiveComfortTest` ve `FullscreenTest` geçmeli.
   - Numara ve OFFSET sorgusu için `LargeCatalogTest`'e 10 bin kanallı bir ölçüm ekle: numara 9999'a geçiş 200 ms altında.
