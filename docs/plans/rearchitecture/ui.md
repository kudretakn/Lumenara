# Lumenara v0.6.0 — UI / Compose for TV denetimi

**Karar: YENİDEN DÜZENLE (kademeli refaktör).** Compose/TV temelini ve sayfalama politikasını koru; baştan yazma maliyeti ve odak/veri gerilemesi riski sağlayacağı yarardan büyük.
Kapsam: `ee262f12bb9731e700b49c86c6da8bdb73c59f8e`; CLAUDE.md, ARCHITECTURE.md, DECISIONS.md ve docs/STATUS.md önce okundu. Depo değiştirilmedi; Gradle, cihaz testi, commit ve ağdan güncelleme yapılmadı.
Bulgular statik kod incelemesidir; süre kazançları ölçülmüş sonuç değildir. Satırlar bu commit'e aittir; aşağıdaki kısa dosya adları `app/src/main/java/dev/lumenara/tv/` altındadır; diğer yollar ayrıca belirtilmiştir.

## Mevcut tasarım ve hedeflere uzaklık
- `LibraryScreen.kt:55–78,157–203`: metin tabanlı bölüm seçimi, ayrı detay/oynatma durumları, SaveableStateHolder ile ekran dönüşleri; oynatma sırasında kitaplık composition'dan çıkarılıyor.
- `Browse.kt:277–330,394–400`: Hub önce altı gezinme kartını, sonraki çizimde hazır geçmiş raflarını ekliyor; görseller ve ayrıntılar çizim kapısının ardından başlıyor.
- `CatalogPages.kt:10–34,63–89` + `PagingWindow.kt:3,15–40`: mutlak slotlar ve en çok beş sayfalık Item penceresi; poster 80, canlı 28, arama 20 öğe/sayfa. Komşu abonelikler korunuyor, eşit emisyonlar nesneleri değiştirmiyor.
- `LiveScreen.kt:319–336`: EPG/logo çözümü tutulan sayfa başına; `Browse.kt:578–597`, `LiveScreen.kt:267–291`: slot anahtarları ve contentType kullanılıyor. Arama LazyColumn içinde LazyRow rafları kullanıyor.
- `Artwork.kt:49–100,133–174`: fiziksel piksel boyutu, TMDB varyantı, HTTP iznini içeren cache anahtarı, RGB565, animasyonsuz Coil; `LumenaraApp.kt:84–96`: ortak bellek/disk cache ve SVG decoder.
- `docs/STATUS.md:20–22,34`: v0.5.5 soğuk ilk kare 0,69 sn; Hub tam 3,2 sn; ilk 12 sn CPU 23–24 sn. Bunlar güncel birleşik sürüm ölçümü değildir.
- `docs/STATUS.md:9–12`: A/B'de en uzun kare 3,2→2,4 sn, Hub +0,2 sn, CPU15 −%11; birleşik TV ölçümü ve güncel cihaz doğrulaması bekliyor. “2,4 sn” Hub tamamlanma süresi diye okunmamalı.
- Kabul: her ekran <1 sn, Hub tam <1,5 sn, CPU15 <5 sn; mevcut belgeler bu commit'in kabulü geçtiğini göstermiyor. Cihazda baseline profile üretimi ve kaldırılmış U3 kapsam dışıdır.

## Somut sorunlar
1. **[yüksek][doğruluk] Hub tam çizim kabulünü doğrulayan işaret yok.** `Browse.kt:283,286–290,326–329`: rowsReady yalnız recent/resume sonuçlarını denetliyor; toplamlar ve görsel sonuçları koşula katılmıyor.
   `LumenaraApp.kt:30–37`, `Browse.kt:191–208`: görseller bu işaretten sonra hazırlanıyor. Kabuk-önce tasarımı doğru; `startup_hub_first_draw` tek başına <1,5 sn “tam Hub” kanıtı olamaz. Ayrı tamamlanma ölçümü gerekir.
2. **[orta][performans] Hub görünmezken sorguları yaşamaya devam ediyor.** `LibraryModel.kt:60–78` üç akışı Eagerly paylaştırıyor; `LibraryScreen.kt:159–175,185–189` Hub'ı kaldırsa da abonelikler kapanmıyor.
   `Playback.kt:341–345` beş saniyede ilerleme yazar; `core/data/src/main/java/dev/lumenara/data/Repository.kt:245–251` geçmiş sorguları marks verisine bağlıdır. Oynatma sırasında görünmeyen raflar yeniden sorgulanabilir; açılış ön ısıtması korunarak görünürlükle durdurulmalı.
3. **[orta][performans] Dizi türetmeleri hâlâ UI'da ve tam bölüm listeleri üzerinde.** `SeriesDetails.kt:34–36,51–53,106–109`: sıralama/gruplama ve devam bölümü hesapları; devam hesabı remember dışında her yeniden oluşumda çalışıyor.
   `core/data/src/main/java/dev/lumenara/data/SeriesState.kt:5–17` başlıkları regex ile ayrıştırıyor; `core/catalog/src/main/kotlin/dev/lumenara/catalog/Series.kt:38–52` yeniden sıralıyor. Çok bölümlü dizide Main CPU maliyeti; Hub da görünür dizi başına iki bölüm/mark aboneliği açıyor (`Browse.kt:144–153`).
4. **[orta][doğruluk] Aramadan detay/oynatmaya gidip dönünce uzak sonuç odağı kaybolabilir.** `SearchScreen.kt:58,107–120`: yalnız raf adı saklanıyor, pager'a returnId verilmiyor; yeni raf durumunda iki liste koşulsuz sıfıra kaydırılıyor.
   `SearchScreen.kt:141–149,357–361`: yalnız dikey raf geri getiriliyor; uzak kartın restoreFocus etkisi kart yeniden compose edilmeden çalışamaz. 60. sonucu aç→geri senaryosu gerekli; mevcut `app/src/androidTest/java/dev/lumenara/tv/SearchPlayerPagingTest.kt:147–203` ekranı terk etmeden ileri/geri gezinmeyi test ediyor.
5. **[orta][performans] Gizli tam ekran kontrolleri de sürekli UI işi üretiyor.** `FullscreenOverlay.kt:103–111` 250 ms'de now yazıyor; `Playback.kt:598–599`, `VlcScreen.kt:109` bunu üst kapsamda okuyor; `Playback.kt:477–486` kapalı uyku zamanlayıcısında dahi saniyelik state değiştiriyor.
   Görünürlük kontrollü 500 ms oynatıcı snapshot'ı iyi; fakat bu diğer saatler görünmez UI için de yeniden oluşum talep ediyor. Banner kapanışı/rehber zaman aşımı tek seferlik deadline, saat yalnız görünür bileşende olmalı; maliyet cihazda ölçülmeli.
6. **[orta][doğruluk] Slotta içerik değişince eski poster kısa süre yeni başlıkla kalabilir.** `Browse.kt:40` produceState anahtarları producer'ı yeniden başlatıyor ama image state'ini hemen null'a almıyor; `Browse.kt:578,637–639` aynı slotun Poster alt ağacını yeni Item için koruyor.
   Yenileme/favoriden çıkarma sıralamayı kaydırınca çözüm tamamlanana kadar önceki URL kullanılabilir. Dış slot anahtarını koruyup görsel alt ağacını kimlikle sıfırla; aramadaki `SearchScreen.kt:371–377` zaten bu ayrımı uyguluyor.
7. **[orta][performans] Hub poster yedeğine yanlış görsel rolü gönderiyor.** `Browse.kt:159–164` backdrop yoksa poster seçiyor; `Browse.kt:206–208` her ikisine backdrop rolü veriyor; `Artwork.kt:53–56` bunu TMDB w780/w1280 isteğine dönüştürüyor.
   TMDB poster URL'sinde w342 yerine daha büyük dosya indirilir ve diğer poster cache anahtarlarıyla paylaşım azalır. Çözülen adreste rolü de taşı; gerçek geniş görsellerin mevcut rolünü koru.
8. **[düşük][bakım] Sunum metinleri gezinme ve kalıcı ayar sözleşmesi olmuş.** `LibraryScreen.kt:55–59,178–183,204–235` Türkçe ekran adlarını rota olarak; `Settings.kt:36–45,66,236–240` çevrilebilir seçenek metinlerini kayıt değeri olarak kullanıyor.
   `app/src/main/res/values/strings.xml:2–3` yalnız iki metin içeriyor; `SearchScreen.kt:97`, `Browse.kt:349` ses ve sayı yerelini sabitliyor. Türkçe kullanım bugün tutarlı; metin/çeviri değişikliği rota ve ayar davranışını bozabilir. Sabit kimlikler + kaynak metinler, eski kayıtları okuyan eşleme gerekir.
9. **[düşük][bakım] Tasarım sistemi ve ekran sorumlulukları parçalı.** `Timeline.kt:67–81,152–179`, `GuidePanel.kt:45–47,96–112` doğrudan Button/Surface ve yerel ölçüler kullanırken ana ızgaralar ortak bileşenlerden yararlanıyor.
   `Cinema.kt:117–261` Hero/MovieCard ve `ChannelCard.kt:175–200` ChannelShelf için üretim çağrısı bulunmadı; yinelenen odak/görsel yolları bakım alanını büyütüyor. Kullanılmayanları çağrı/test analiziyle temizle; APK kazancı ölçülmeden boyut iddiasında bulunma.

## İyi olanlar — korunmalı
- `core/designsystem/src/main/java/dev/lumenara/design/Components.kt:44–100`: odak büyümesi yalnız çizimde; layout ve odak sınırları sabit. `core/designsystem/src/main/java/dev/lumenara/design/FocusScrolling.kt:13–20` görünür hedefte gereksiz kaydırmayı engelliyor; blur/glow yok.
- `Browse.kt:73–93,641–644`: raf başlık/altyazısını birlikte görünür kılma, yatay geçişte sabit yükseklik; `GridFocus.kt:41–63` ekran dışındaki üst satırı önce oluşturup sonra odaklama. Bu davranışlar yeniden tasarımda bozulmamalı.
- Beş sayfalık sınır, slot anahtarı, türe göre contentType ve eşit sayfa nesnelerini koruma yerinde. Homojen LazyRow'larda contentType olmaması tek başına hata değildir; dış slot key'lerini Item kimliğine çevirmek placeholder odağını bozar.
- `core/data/src/main/java/dev/lumenara/data/LibraryDb.kt:48–86,100–106`: Item/Mark alanları val; `CatalogPages.kt:9–16` gözlenebilir @Stable durum; yaşam döngülü çoğu koleksiyon ve sayfa başına EPG. Ham List/Map ve başka modüldeki entity için skip oranı derleyici raporu olmadan varsayılmamalı.
- `Artwork.kt:60–66,177–218`: izin ayrımlı cache, doğru decode boyutu, tekli ve iptal edilebilir ertelenmiş prefetch; `core/designsystem/src/main/java/dev/lumenara/design/Typography.kt:14–18` asenkron font; `PlayerUiState.kt:45–66` Main'de snapshot ve gizliyken polling durması korunmalı.
- `app/src/androidTest/java/dev/lumenara/tv/WindowedGridTest.kt:63–118`, `HubFirstDrawTest.kt:76–110`, `ArtworkTest.kt:52–100`: pencere sınırı, yatay odak, kabuk sözleşmesi ve cache için değerli testler var; bu denetimde çalıştırılmadı.

## “En hızlı, en optimize, en düzgün” hedef tasarım
- Ekran başına küçük UiState + olay arayüzü; Room entity'sini doğrudan tüm bileşenlere yaymak yerine gereken alanları taşıyan değişmez kart modelleri; doğrulanmış immutable koleksiyonlar. @Stable/@Immutable etiketi sözleşme sağlanmadan eklenmez.
- İlk yerel sayfa ve Hub geçmişi açılışta erkenden okunur; görünmeyen ekranların sorguları durur, son snapshot saklanır. Büyük katalog belleğe çekilmez; ortak pencere sürücüsü ve canlı keyset yaklaşımı korunur.
- Sezon/bölüm numarası, görüntü metni ve sırası yenileme/expand anında üretilir; devam özeti mark değişiminde güncellenir. UI yalnız hazır özeti okur; ağ metadata'sı ekranı bekletmez.
- Rota/source/filter + odaklı Item ID + mutlak indeks/offset birlikte saklanır; dönüşte ilgili sayfa hazırlanır, sonra kaydırma ve tek odak isteği yapılır. Slot yüzeyi sabit, kimliğe bağlı görsel state ayrı tutulur.
- ArtworkAddress adres+rol+HTTP iznini taşır; boyutlar ebeveynde hesaplanır, Hub/arama da mümkün olduğunca ölçüm sonrası ikinci turdan kaçınır. Font ve görsel animasyonlarının CPU/layout etkisi ölçülür; gerekçesiz animasyon eklenmez.
- Kabuk, yerel içerikle etkileşim, sayımlı Hub ve görünür görsel sonuçları ayrı işaretlenir; nihai kare başarısız görselin fallback'ini de tanımlar. CPU15 aynı açılışla birlikte ölçülür; <1 sn / <1,5 sn / <5 sn geçilmeden hız hedefi tamamlandı denmez.
- Kademeli ekran taşıma: önce Hub/arama, ardından dizi ve yardımcı ekranlar. Eski ve yeni yol aynı repository, içerik kimliği ve mark yazma işlemlerini kullanır.

## Risk, veri korunması ve efor
- Tahmin: tek geliştiriciyle **10–15 iş günü**, odaklı emülatör regresyonları ve sahibin Mi Box ölçüm döngüsü dahil; sağlayıcı/ağ gecikmesine bağlı takvim uzayabilir. Baştan yazma bu ölçekte haklı değil.
- Room/Vault/Preferences depoları ve içerik/source kimlikleri korunmalı; geçmiş, favoriler, Listem, kaynaklar ve ayarlar UI refaktöründen etkilenmemeli. Sıfırlama, uygulama verisi silme ve destructive migration kabul edilmez.
- Bölüm türetmeleri için gerekirse eklemeli şema + eski satır okuma yolu + parçalı doldurma; ayar kimliği değişirse eski Türkçe değerleri okuyan dönüşüm şart. Başlıca riskler yanlış resume özeti, odak yarışları ve artan yeniden sorgulama; güncelleme öncesi/sonrası aynı kullanıcı verisiyle karşılaştırılmalı.

## Etki / efor sırasıyla ilk 5 eylem
1. **Yüksek / 0,5–1 gün:** Hub tam çizim işaretini ve üç kabul bütçesini ayrı doğrula; 39.682 öğeli Mi Box ölçümünü güncel birleşik sürümde tekrarla. Kabuk-önce kapısını değiştirme.
2. **Yüksek / 1–2 gün:** Hub sorgularını görünürlükle sınırla, gizli banner/uyku saatlerini deadline'a çevir; açılış ön ısıtmasını koru, görünmeyen ekranda sorgu/yeniden oluşum sayısını karşılaştır.
3. **Yüksek / 3–4 gün:** Bölüm türetmelerini refresh/expand'a, devam özetini veri katmanına taşı; büyük dizide UI Main CPU maliyetini ve resume doğruluğunu denetle.
4. **Orta / 1–2 gün:** Aramada uzak sonuç→detay/oynatma→geri dönüşünü ID/anchor ile onar; silinmiş sonuç ve sorgu değişiminde tanımlı odak fallback'i ekle.
5. **Orta / 0,5–1 gün:** Slot görselini kimlikle sıfırla, Hub poster/backdrop rolünü düzelt; yanlış poster parlaması, indirme boyutu ve cache paylaşımını doğrula.
