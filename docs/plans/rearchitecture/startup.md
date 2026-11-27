Lumenara başlangıç ve arka plan işleri denetimi — 27 Kasım 2026
Taban: ee262f12bb9731e700b49c86c6da8bdb73c59f8e (v0.6.0); salt okunur kaynak incelemesi.
CLAUDE.md, ARCHITECTURE.md, DECISIONS.md ve docs/STATUS.md önce okundu; kaynak değiştirilmedi, Gradle/test/cihaz komutu çalıştırılmadı.
Kanıt yolları depo köküne göredir. A = app/src/main/java/dev/lumenara/tv; D = core/data/src/main/java/dev/lumenara/data.

1) Mevcut tasarım
- Application hafif; Hilt repository'yi Lazy sağlar. LibraryModel kaynak/kurtarma açılışını IO'da, küçük Hub sorgularını ayrı IO akışlarında başlatır (A/LumenaraApp.kt:59, A/LibraryModel.kt:28,45,54).
- Repository süreç singleton'ıdır; kurucusu IO üzerinde runBlocking ile Room'u açıp geri alma günlüğünü tamamlar. Sonuç yayımlanmadan kurtarma bitmesi doğru bir veri tutarlılığı sınırıdır (D/Repository.kt:61,70,93,471).
- Önce iskelet/Hub kabuğu, sonra geçmiş rafları çizilir. İlk dolu çizimden 15 sn sessizlik veya en çok 20 sn sonra isteğe bağlı işler açılır; oynatma/arka plan kapıyı kapatır (A/LibraryScreen.kt:29, A/Browse.kt:283,317, D/BackgroundWork.kt:18,25,39).
- Kullanıcı içe aktarması, sürüm/türetilmiş alan doldurma, afiş ön yüklemesi ve Watch Next aynı mutex'i paylaşır. Yenilemeler WorkManager'da ağ kısıtıyla, ön planda/oynatmada ertelenerek çalışır (D/Repository.kt:392,1022,1050; A/WatchNext.kt:113).
- Türetilmiş katalog yenileme işlemi içinde hazırlanır; eski şemadan gelen veri kalıcı imleçlerle partiler halinde doldurulur (D/Repository.kt:864; D/VersionBackfill.kt:85; D/CatalogDerived.kt:95).

2) Somut bulgular — etiketler etki/kategori; gecikmelerin güncel TV büyüklüğü ölçülmedi
- B1 [yüksek/performans] Spekülatif afiş ağı kullanıcı işlemlerinin kilidini tutuyor: A/Artwork.kt:213-215 içindeki imageLoader.execute, D/BackgroundWork.kt:65-69 mutex'i bırakmadan bekleniyor; kullanıcı ekleme/yenileme D/SourceImport.kt:69 üzerinde sıraya giriyor. Yavaş afiş, katalog mutasyonlarını ağ süresince bekletebilir; tek şerit CPU çakışmasını azaltırken gereksiz ağ beklemesini de seri hale getiriyor.
  Çözüm: afiş ağı/decode için iptal edilebilir tek eşzamanlılık sınırı; katalog mutex'i yalnız tutarlılık gerektiren işlerde kalsın. Watch Next provider çağrılarının da bu kilidi tutması aynı riski taşır (A/WatchNext.kt:114,124,150).
- B2 [yüksek/performans] Boşta olma yalnız işe girişte sınanıyor; çalışan blok kapı kapanınca durmuyor (D/BackgroundWork.kt:62-69). Üstelik 20 sn sonrasında kumanda girişi kapıyı artık kapatmıyor (D/BackgroundWork.kt:20,39,45).
  Sürüm doldurma 500 satırlık, satır başına sorgulu transaction'lar yürütüyor; parti sonrasında yalnız 50 ms bekliyor (D/VersionBackfill.kt:85,118-133,139-144; D/Repository.kt:405-421). Yeni ekran/oynatma, başlamış partinin CPU/SQLite yükünü taşır. docs/STATUS.md:21 eski sürümde 0,3–2,4 sn partiler bildiriyor; bu güncel sürüm ölçümü değildir.
  Çözüm: işlem süresi bütçeli küçük partiler, etkileşim/oynatmada güvenli sınırda duraklama; 20 sn sınırı işin uygunluğunu açsın, sınırsız foreground yük izni vermesin.
- B3 [orta/performans] Hub kaynakları ve son kullanılan kaynak kapsamı ekran kapandıktan sonra da okunuyor: A/LibraryModel.kt:52-78 Eagerly abonelikleri ViewModel ömrünce sürüyor. collectAsStateWithLifecycle yalnız UI abonesini durdurur; eager üreticiyi durdurmaz.
  Kategori akışları ilk abonelikten sonra süreç boyunca açık kalıyor; kaynak kapsamları haritalarda tutuluyor (D/CatalogStreams.kt:25,27-33,37-47). Oynatma/arka plan yenilemeleri görünmeyen Hub ve eski kapsamlar için de sorgu/sonuç tahsisi üretir; D/Repository.kt:277 bu önbellekleri boşaltmaz.
  Çözüm: Hub görünürlüğü/foreground ile üreticiyi sınırla; kısa WhileSubscribed süresi, replay boşaltma ve sınırlı kapsam önbelleği kullan. İlk açılıştaki erken küçük sorguları koru.
- B4 [orta/performans] Bellek baskısı geri çağrısı Main'de repository yaratabilir veya kurucu kilidini bekleyebilir: A/LumenaraApp.kt:78-80 doğrudan Repository.get çağırıyor; D/Repository.kt:93-96,474-477 bloklayıcı açılış/kurtarma yapıyor. Normal başlangıç IO'dadır, fakat bu yan yol Main için garanti vermiyor.
  Ayrıca level >= UI_HIDDEN koşulu Android 9'un RUNNING_LOW=10 ve RUNNING_CRITICAL=15 olaylarını kapsamıyor (A/LumenaraApp.kt:78); 2 GB cihazda görünürken özel temizlik eksik. Çözüm: yalnız zaten oluşturulmuş örneğe eriş; çalışan süreç baskısını ayrıca ele al, disk/Room açma; canlı kullanılan kaynakları koru.
- B5 [orta/doğruluk] startup_hub_first_draw tam Hub hazır ölçümü değildir: A/Browse.kt:283,326 koşulu yalnız geçmiş raflarını bekler; toplamların hazır olmasını beklemez (aynı dosya:286-288). Görünür afiş yükleyicisi de bu çizimden sonra hazırlanır (A/LibraryScreen.kt:51-53; A/LumenaraApp.kt:30-37).
  Böylece toplamlar/afişler eksikken başarı işareti çıkabilir; kabuk kazanımı ile <1,5 sn tam Hub hedefi karışır. Çözüm: kabuk, raflar, toplamlarla etkileşime hazır Hub ve görünür afişler için ayrı işaretler; ağ afişlerini yerel UI kabul ölçüsünden açıkça ayır.
- B6 [orta/doğruluk] CPU15 örneği ilk başlangıç bölümünü dışarıda bırakıyor: CPU tabanı super.onCreate ve başlangıç işaretlerinden sonra alınıyor (A/LumenaraApp.kt:59-71). delay(15000) devamı gecikirse 15 sn'den uzun pencere ölçülüyor; pencere ayrıca loglansa da CPU15 adı tek başına kesin 15 sn sanılabilir.
  Çözüm: başlangıç noktasını tanımla, CPU ve gerçek duvar penceresini birlikte raporla; süreç doğumundan itibaren kabul için harici ölçümle eşleştir. Perf.observeFrames yalnız debug'dır (A/Perf.kt:78-80); release performansını onunla doğrulanmış sayma.
- B7 [orta/doğruluk] Meşgul yenileme 30 dk sonunda başarıyla atlanıyor; bekleyen iş korunmuyor (D/Repository.kt:1051-1067). Bu bilinçli oynatma korumasının sonucu: 30 dk TV izleyip Home'a çıkan kullanıcı için kaçan yenileme hemen geri gelmez; sonraki periyoda kalabilir. A/MainActivity.kt:32-36 yalnız foreground bayrağını değiştiriyor.
  Çözüm: oynatmaya dokunmadan, kaçan işi kalıcı tek bir bekleyen yenileme olarak sakla; arka plana geçişte/uygun WorkManager çalışmasında tüket. Mevcut 12 saat varsayılanında tazelik etkisini açık kabul ölçüsü yap (A/LibraryModel.kt:126).

3) Korunması gerekenler
- WorkManager otomatik initializer'ının kaldırılması ve ilk dolu çizim/boşta sonrası zamanlama (app/src/main/AndroidManifest.xml:14-20; A/LibraryModel.kt:123-131); cihazda baseline profile üretmek gerekmez.
- Hilt Lazy + IO açılışı, hemen çizilen iskelet ve sınırlı son-20 kanal sorgusu (A/LibraryScreen.kt:37-47; D/Repository.kt:252-262); açılışta katalog nesne listesi üretmeye dönülmemeli.
- Room açılmadan önce veri kurtarma sınırı, dayanıklı geri alma günlüğü, transaction içinde imleç+çıktı, yenilemede ön hesaplama (D/RestoreTransaction.kt:19-50; D/Repository.kt:70-96,864-889).
- İptalde soket kapatan ImportCalls, işlem zaman sınırı, ağ kısıtı ve oynatmayı zorlamayan worker politikası (D/SourceImport.kt:34-84; D/Repository.kt:1023,1074-1089).
- Tek, tembel LibVLC ve Main dışı edinim kontrolü (core/player-vlc/src/main/java/dev/lumenara/vlc/VlcRuntime.kt:31,60); sabit, hassas bilgi içermeyen performans etiketleri ve toplu debug kare raporu (A/Perf.kt:60,78).

4) Hedef tasarım ve karar
- YENİDEN DÜZENLE — kademeli refactor. Ana veri modeli ve kurtarma protokolü doğru; sorunlar iş önceliği, yaşam döngüsü ve ölçüm sınırlarında. Baştan yazmak aynı veriyi taşıma/kurtarma riskini büyütür, kanıtlanmış bir hız kazancı sunmaz.
- Uygulama kapsamındaki koordinatör Hilt ile sağlansın: repository erişimi suspend awaitReady, açık Ready/Error başlangıç durumu; senkron kurucuya UI/trim yan yolu kalmasın. Kurtarma bitmeden Room/ayar durumunu yayımlama şartı korunsun.
- Üç yürütme politikası: görünür yerel sorgular hemen; tutarlılık gerektiren katalog mutasyonları seri; spekülatif ağ işleri ayrı, tekli ve iptal edilebilir. Ortak mutex ağ/decode/provider bekleme sınırı olmasın.
- Katalog doldurma süre bütçesi ve kalıcı imleçle devam etsin; arka plan/oynatma/etkileşim durumları parti sınırlarında yeniden değerlendirilsin. Tüm türetilmiş alanlar normal yenilemede hesaplanmaya devam etsin; açılış hesaplamasına geri dönülmesin.
- Kalıcı yenileme isteği tekilleştirilsin; görünmeyen akışlar kapansın, sıcak küçük sonuçlar sınırlı süre korunsun. Scope/dispatcher sahipliği koordinatör ve ViewModel arasında açık olsun.
- Risk: transaction/kurtarma sırası, iptal anı ve akış ilk değerleri değişirse geçmiş/favori görünürlüğü bozulabilir. Room dosyası/kimlikleri, Vault alias ve sarılı anahtar, DataStore, kaynaklar, favoriler, geçmiş ve ayarlar aynen korunmalı; destructive migration/uygulama verisi temizliği kullanılmamalı.
- Kabul: güncelleme ve işlem ortasında süreç ölümü/kurtarma, ilk kurulum, worker-only başlangıç, Home/geri dönüş, aktif oynatma ve yavaş afiş senaryoları; mevcut kullanıcı durumu aynı kalmalı. Cihazda baseline profile üretimi ve U3 kapsam dışıdır.
- Tahmin: tek geliştirici için 6–8 iş günü (uygulama, anlamlı regresyon kontrolleri ve cihaz ölçümü dahil); şema değişikliği beklenmez. Mi Box sahibi ölçümü için takvim beklemesi hariç.
- Performans henüz kabul edilmiş değil: docs/STATUS.md:10-11 birleşik 0.6.0 TV ölçümünü bekliyor; :21-23 eski 0.5.5 ölçümünde Hub 3,2 sn, ilk 12 sn CPU 23–24 sn. Kısmi A/B kazancı <1,5 sn Hub ve CPU15 <5 sn kanıtı değildir; ekran geçişleri ayrıca <1 sn ölçülmeli.

5) Etki/efor oranına göre ilk beş eylem
1. B1: Afiş ağı/decode ve Watch Next provider beklemesini katalog mutex'inden çıkar — yüksek etki / 1 gün; yavaş afiş altında kullanıcı işi beklememeli.
2. B5–B6: Kabul işaretlerini ve CPU penceresini düzelt; mevcut sürümü aynı Mi Box/katalogda ölç — yüksek karar değeri / 0,5–1 gün; başarının ölçütü önce güvenilir olmalı.
3. B2: Bakım partilerini süre bütçeli yap, yeni etkileşim/oynatmada güvenli sınırda duraklat — yüksek etki / 1–2 gün; 20 sn sonrasında da gezinme gecikmesi kontrol edilsin.
4. B3: Hub ve katalog akışlarını görünür abonelere bağla, kapsam/replay önbelleğini sınırla — orta etki / 1 gün; oynatma/arka planda gereksiz SQL ve tahsisler kesilsin.
5. B4: Trim'de singleton yaratmayı kaldır, RUNNING_LOW/CRITICAL yolunu ekle; repository hazır olmasını suspend sınırına taşı — orta etki / 1 gün; bellek baskısında Main bloklanmasın.
