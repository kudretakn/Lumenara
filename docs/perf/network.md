## Görev: network — yenileme ve EPG trafiği

Sorunlar:
1. Otomatik yenileme (`RefreshWorker`, varsayılan 12 saat) ve "Şimdi yenile", içerik değişmemiş olsa bile kataloğun ve EPG'nin tamamını yeniden indirip yeniden yazıyor. Xtream'de bu 40 bin satır ve birkaç büyük JSON; EPG ise onlarca–yüzlerce MB XML olabiliyor.
2. EPG'nin tamamı (geçmiş ve uzak gelecek dahil) veritabanına yazılıyor.

Yapılacaklar:
1. Koşullu istek: `Transport.read` (ya da yeni bir yardımcı), son başarılı yanıttaki `ETag` ve `Last-Modified` değerlerini kaynak ya da feed başına `Preferences`'ta saklasın.
   - Sonraki yenilemede `If-None-Match` ve `If-Modified-Since` göndersin.
   - 304 gelirse içe aktarma tamamen atlanır: veritabanına yazılmaz, `source.refreshed` güncellenir.
   - M3U ve XMLTV adreslerinde uygula. Xtream API'de sunucular genelde bu başlıkları vermez; vermezse davranış değişmez.
2. İçerik özeti: başlık gelmiyorsa indirilen gövdenin SHA-256'sını hesapla. Önceki özetle aynıysa veritabanı yazımını (transaction, prune, sürüm işlemleri) atla. Akış hâlinde hesapla, belleğe toplama.
3. gzip: OkHttp şeffaf gzip'i kullanılsın. `Accept-Encoding`'i elle `identity` yapan yerleri (VLC hariç) kontrol et. XMLTV `.gz` desteği zaten var.
4. EPG zaman penceresi: `populateGuide` yalnız şu andan 12 saat önce ile 3 gün sonrası arasındaki programları yazsın; dışındakileri ayrıştırırken atla.
   - Yenilemede pencere dışında kalan eski programları sil.
   - Bu değer sabit olsun (`GUIDE_PAST_HOURS = 12`, `GUIDE_FUTURE_DAYS = 3`). U2 "Şu an yayında" ve Timeline davranışı bozulmasın; Timeline en çok "+1 gün" gösteriyor.
5. Yenileme zamanlaması:
   - `RefreshWorker` yalnız ağ bağlıyken ve cihaz boştayken (`setRequiresDeviceIdle` TV'de çalışmayabilir; uygulama ön plandayken ağır yenilemeyi ertele) ve kullanıcı oynatma yapmıyorken çalışsın.
   - Oynatma sırasında başlarsa en çok 30 dk ertelensin.
   - Uygulama açılışında "startup-refresh" yalnız gerçekten bayatsa çalışsın; bu zaten var, koru.
6. Testler (JVM, MockWebServer, `core/data/src/test`):
   - 304 → içe aktarma atlanır.
   - ETag saklanır ve gönderilir.
   - Özet aynı → yazım atlanır.
   - EPG penceresi dışındaki programlar yazılmaz.
