# Lumenara mimarisi
TV arayüzü küçük durum modellerini gözlemler. Kumanda odağı ve geçici panel durumu Compose katmanında, katalog ve kullanıcı tercihleri veri katmanında yaşar. Disk ve ağ işlemleri ana iş parçacığı dışında çalışır.

| Modül | Sorumluluk |
|---|---|
| app | Leanback giriş noktası, hızlı kurulum, gezinme ve ekranların bağlanması |
| core/designsystem | TV renkleri, tipografi ve etkileşim |
| core/catalog | Android bağımsız içerik modeli, ayrıştırma ve arama kuralları |
| core/data | Room katalog ve rehber tabloları, Keystore, DataStore, OkHttp, WorkManager, Xtream, TMDB ve sürüm kontrolü |
| core/player | Media3 oluşturma, kaynak bazlı ağ politikası, hata sınıfları ve decoder kapasitesi |
| core/player-vlc | Alternatif motor için ağ üzerinden gelen TS akışını veya konumlandırılabilir MP4/MKV dosyasını yerel dosya tanımlayıcısı olarak sunma |

Hedef bağımlılık yönü sunum → alan modeli ← veri katmanıdır. Kaynak anahtarı içerik kimliğinin parçasıdır. Bir kaynağın yenilenmesi başka kaynakların kayıtlarına dokunamaz. Oynatıcıların yaşam döngüsü ekranın görünürlüğünü izler.

## Çalışma akışları

1. Hilt, `LibraryModel` için uygulama kapsamındaki repository ve tercihleri sağlar. Ekranlar Flow sonuçlarını yaşam döngüsüne bağlı izler. İlk açılış kararı Room’un ilk kaynak sonucuna göre verilir.
2. M3U ve Xtream içe aktarımı geçici bir uygulama dosyasına akış halinde yazılır. Yayın adresleri diske yazılmadan şifrelenir. Ağ işlemi bittikten sonra Room işlemi kayıtları 128 satırlık gruplarla ekler ve yalnızca ilgili kaynağın eski kayıtlarını kaldırır. Bir hata işlemi geri alır.
3. XMLTV SAX ile okunur. UTC zamanları, rehber kaynakları, kanal adları ve isteğe bağlı manuel eşleştirmeler Room’da tutulur. Çizelge, görünür kanal ve iki saatlik pencere için veri ister.
4. İçerik anahtarı kaynak kimliği ve adres özetinden üretilir. Favori, izleme listesi ve ilerleme kayıtları aynı içerik anahtarına bağlıdır. Birleşik görünümde yalnızca adres özeti, yayın kimliği, başlık ve tür eşleştiğinde tek satır gösterilir.
5. Media3 birincil motordur. Codec hatasında ayar izin veriyorsa VLC’ye bir kez geçilir. VLC modülü ağ adresini doğrudan native motora vermez. OkHttp üzerinden alınan baytlar bir boru veya konumlandırılabilir dosya tanımlayıcısıyla iletilir.

## Şema ve test

Room şemaları sürüme göre `core/data/schemas` altında saklanır. Migration testi ilk şemadan bir katalog kurar, güncel Room uygulamasıyla açar ve izleme konumunun korunduğunu denetler. JVM testleri `core/catalog`, cihaz ve kullanıcı akışı testleri `app/src/androidTest` altındadır.
