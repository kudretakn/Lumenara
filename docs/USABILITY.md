# Kullanım kolaylığı (0.4.x)

Kullanıcının önceliği Canlı TV; kaynağı Xtream. Bu belge, 0.3.0 arayüz yenilemesinden sonraki kullanım iyileştirmelerini tanımlar. Genel kurallar `docs/UI-REDESIGN.md` ile aynıdır. Yeni bağımlılık eklenmez. Periyodik işler için `wallClockTicks` kullanılır. Room şeması yalnız U3'te değişir.

## U1 — Canlı yayın konforu

1. **Otomatik yeniden bağlanma (Media3 ve VLC)**
   - Ağ, HTTP veya kaynak kaynaklı oynatma hatalarında yayın sessizce yeniden denenir. Bekleme süreleri 1, 2, 4, 8 ve 15 sn; en fazla 5 deneme yapılır.
   - Denemeler sürerken tam ekranda küçük bir cam rozet görünür: "Yeniden bağlanıyor… (2/5)". Yayın yeniden başlayınca rozet kaybolur ve sayaç sıfırlanır.
   - `BEHIND_LIVE_WINDOW` hatasında canlı uca atlanır, deneme sayılmaz.
   - Codec hatası mevcut VLC'ye geçiş akışına gider; o hata yeniden denenmez.
   - 5 deneme de başarısız olursa mevcut hata paneli ("Yeniden dene") gösterilir.
   - Önizlemelerde (`preview`) yeniden deneme yalnız 2 kez yapılır ve rozet gösterilmez.
2. **Son kanala dönüş**
   - Ana Sayfa'da, son izlenen bir kanal varsa vitrin düğmelerinin solunda "▶ Son kanal: <ad>" düğmesi olur. Uygulama açılınca ilk odak bu düğmededir. OK doğrudan tam ekran oynatır.
   - Tam ekran canlı yayında `KEYCODE_LAST_CHANNEL` veya `0` tuşu bir önceki kanala geçer. Bir önceki kanal, son izlenenler listesinin 2. öğesidir.
3. **Numarayla kanal**
   - Tam ekran canlı yayında ve Canlı TV ekranında rakam tuşları (`KEYCODE_0..9`, numpad dahil) kanal numarası girişi başlatır. Ekranın sağ üstünde cam bir kutuda girilen numara görünür (en fazla 4 hane).
   - Son tuştan 1,5 sn sonra veya OK'a basılınca o numaralı kanala geçilir. Numara yoksa "Kanal yok" mesajı 2 sn gösterilir.
   - `0` tuşu numara girişinin ilk hanesiyse "son kanal" anlamına gelir. Diğer hanelerde normal rakamdır.
   - Kanal numarası: M3U `tvg-chno` varsa o, yoksa kaynağın "Tümü" sıralamasındaki 1 tabanlı konum. Canlı TV satırlarında ve bilgi bandında gösterilen numara bununla aynı olmalıdır.
   - `tvg-chno` ayrıştırıcıda yoksa eklenir. Saklama için Room şemasını değiştirme: mevcut alanlarda saklanamıyorsa yalnız konum kullan ve bunu raporla.

Kabul ölçütleri:
- JVM testleri: yeniden deneme zamanlama politikası (saf fonksiyon), numara çözümleme (`tvg-chno` ve konum), numara giriş durum makinesi.
- Cihaz testi: MockWebServer ilk iki isteğe 503 döner, sonra yayını verir. Oynatma başlar, rozet görünür ve sonra kaybolur. Rakam tuşlarıyla `2` girilince 2. kanala geçilir. `0` tuşu önceki kanala döner.

## S — Dizi deneyimi (U1'den sonra, U2'den önce)

Kullanıcının kaynağı Xtream; diziler API'den dizi, sezon ve bölüm olarak geliyor (`Xtream.kt`, Kind.SERIES ve Kind.EPISODE, `parent`). M3U kaynakları da aynı deneyimi almalı.

1. **Dizi kartı:** Diziler bölümünde, Ana Sayfa'da ve aramada her dizi tek kart olarak görünür. Bölümler hiçbir listede tek başına kart olmaz; `LibraryDb.browse` içindeki `kind='EPISODE' AND parent=''` istisnası kalkar.
2. **Sezonlar ekranı:** Dizi kartına OK basılınca dizi ekranı açılır. Üstte arka plan, başlık, meta, özet ve "Devam et: S2 B5" ya da "Oynat (S1 B1)" düğmesi, altta sezon kartları bulunur. Sezon kartında "Sezon N", bölüm sayısı ve izlenme durumu ("3/10 izlendi" + ProgressLine) gösterilir; tümü izlendiyse ✓ konur.
3. **Bölümler ekranı:** Sezon kartına OK basılınca o sezonun bölümleri dikey liste olarak açılır. Her bölümde küçük resim (varsa), "B5 · Başlık", süre ve ProgressLine bulunur; izlendiyse ✓ konur. OK, yarım bölümü kaldığı yerden, bitmiş bölümü baştan oynatır. Uzun OK menüsünde "İzlendi olarak işaretle" ve "İzlenmedi olarak işaretle" seçenekleri vardır. Back bir önceki ekrana döner ve odak geri yüklenir.
4. **İzlenme kuralı:** `position >= duration * 0.90` olduğunda ya da kullanıcı işaretlediğinde bölüm izlenmiş sayılır. Sezonun tüm bölümleri izlendiyse sezon izlenmiş sayılır. Mevcut ilerleme kayıtları (`marks`) kullanılır. Elle işaretleme için şema değişikliği gerekirse U3'teki 7→8 geçişiyle birleştirilir; o zamana kadar işaretleme, ilerlemeyi süreye eşitleyerek ya da sıfırlayarak yapılır.
5. **Devam et:** Dizi düzeyinde son izlenen ya da yarım kalan bölüm bulunur. Yarım bölüm varsa o, kaldığı saniyeden açılır; yoksa sıradaki izlenmemiş bölüm açılır. Sezon bittiğinde sonraki sezonun ilk bölümüne geçilir. Ana Sayfa'daki "Kaldığın yerden devam et" rafında bölüm yerine dizi kartı ve "S2 B5" etiketi görünür. Bölüm sonunda gösterilen sonraki bölüm kartı sezon sınırını da aşar.
6. **M3U gruplama:** `/series/` yolundaki ya da başlığında SxxEyy geçen bölümler, başlıktan çıkarılan dizi adı ve sezon numarasıyla sanal bir diziye bağlanır. İçe aktarma sırasında SERIES satırı ve `parent` doldurulur; şema değişmez.
7. **Tür ipuçları:** M3U `group-title` içinde "VOD", "Film", "Movie", "Sinema" geçen kayıtlar MOVIE, "Dizi", "Series", "Sezon" geçenler EPISODE ya da SERIES sayılır. Uzantısız ya da `.ts` uzantılı film adresleri grup ipucuyla film olarak sınıflanır. Canlı kanal varsayılan olarak kalır.
8. **Testler:** Gerçek sağlayıcı biçimlerinden türetilmiş anonim M3U ve Xtream örnekleriyle JVM testleri yazılır (ayrıştırma, gruplama, sezon ve bölüm sıralama, devam et seçimi, izlenme kuralı). Cihaz testinde dizi kartı → sezon → bölüm → oynat → geri → ✓ ve "Devam et" akışı denenir.

## A — Dublaj ve altyazı (S'den sonra, U2'den önce)

Kullanıcı kararları (14 Kasım 2026): internetten altyazı önce OpenSubtitles'tan, bulunamazsa Subdl'dan alınır ve yalnız kullanıcı istediğinde çekilir; Türkçe dublaj varsa varsayılan odur; sağlayıcının ayrı "Dublaj" ve "Altyazılı" kayıtları tek kartta birleşir.

1. **Sürüm birleştirme:** Aynı yapımın sağlayıcıdaki "Dublaj", "TR Dublaj", "Türkçe Dublaj" ve "Altyazılı", "TR Altyazı" kayıtları başlık, yıl ve TMDB numarası normalize edilerek tek kartta birleşir. Detayda ve oynatıcı panelinde "Dublaj / Altyazılı" seçimi bulunur. İzleme ilerlemesi ve liste/favori durumu sürümler arasında ortaktır. Dizilerde aynı birleştirme dizi düzeyinde yapılır.
2. **Varsayılan dil:** Dublajlı sürüm ya da yayında Türkçe ses izi varsa o seçilir ve altyazı kapalı kalır. Yoksa orijinal ses ve Türkçe altyazı (yayındaki ya da daha önce indirilmiş) açılır. Kullanıcının bir yapım için yaptığı seçim hatırlanır.
3. **İz seçimi:** Media3 ve VLC'de ses ve altyazı izleri dil adıyla listelenir. Altyazı kaydırma (±0,5 sn adımlarla) iki motorda da vardır.
4. **Altyazı ara:** Oynatıcı panelindeki düğmeyle açılır. Arama TMDB/IMDb numarası (Xtream `get_vod_info` / `get_series_info` içinden) ve sezon/bölüm bilgisiyle, numara yoksa başlık ve yılla yapılır. Önce OpenSubtitles REST API v1 sorgulanır; sonuç yoksa ya da kota dolduysa Subdl API. Sonuç listesinde dil, sürüm adı, indirme sayısı ve kaynak görünür. Seçilen altyazı indirilir, kodlaması algılanır (UTF-8 / Windows-1254 / ISO-8859-9 → UTF-8), SRT/VTT/ASS olarak oynatıma eklenir ve uygulama önbelleğinde tutulur. Aynı içerik tekrar açılınca önbellekten gelir.
   - API notları (14 Kasım 2026 araştırması): OpenSubtitles REST v1 isteklerinde `Api-Key` ve tanımlayıcı bir `User-Agent` (ör. `Lumenara v0.4`) başlığı gönderilir. Arama `GET /api/v1/subtitles` (tmdb_id/imdb_id, season_number, episode_number, languages=tr) ile, indirme `POST /api/v1/download` (file_id) ile yapılır; ikincisi geçici bir bağlantı döndürür. Girişsiz kota çok düşük olduğundan `POST /api/v1/login` ile alınan token kullanılır. Yanıttaki `remaining` değeri kullanıcıya gösterilir. 429 ya da 406 yanıtında "OpenSubtitles günlük sınırı doldu" bilgisi verilip Subdl'a geçilir. Subdl'da arama `GET https://api.subdl.com/api/v1/subtitles?api_key=…&tmdb_id|imdb_id|film_name&type=movie|tv&season_number&episode_number&languages=TR` ile yapılır; indirme `https://dl.subdl.com/subtitle/<url>` adresinden ZIP olarak gelir ve `java.util.zip` ile açılır. Subdl'ın güncel resmî belgesi bulunamadığı için yanıt esnek ayrıştırılır, bilinmeyen alanlar yok sayılır.
5. **Anahtarlar:** Ayarlar'da "OpenSubtitles API anahtarı", isteğe bağlı "OpenSubtitles kullanıcı adı / parola" ve "Subdl API anahtarı" alanları bulunur. Anahtarlar Keystore ile şifrelenip DataStore'da saklanır, loglara ve tanılamaya yazılmaz. Anahtar yoksa "Altyazı ara" düğmesi kısa bir yönlendirme gösterir.
6. **Testler:** Sürüm birleştirme normalizasyonu, kodlama algılama ve iki API'nin yanıt ayrıştırması (MockWebServer) için JVM testleri yazılır. Cihaz testinde "Dublaj / Altyazılı" geçişi ve sahte API ile altyazı arama → seçme → ekranda altyazı akışı denenir.

## U2 — Program bulma

✓ tamamlandı

- **Ara:** "Programlar" rafı eklenir. Sorgu `Guide` program aramasından gelir (mevcut `instr(search, query)` sorgusu). Kartta program adı, kanal, gün ve saat bulunur. OK: yayın şu an sürüyorsa kanalı tam ekran açar; arşivi varsa arşivden oynatır; yoksa kanalın detayını açar.
- **Canlı TV:** Kategori sütununa "Şu an yayında" filtresi eklenir. Bu filtre EPG'si olan kanalları şu anki programın kalan süresine göre sıralar.

## U3 — Kişiselleştirme

- **Ebeveyn kilidi:** Ayarlar'da 4 haneli PIN tanımlanır ve seçilen kategoriler kilitlenir. Kilitli kategorideki kanal ya da içerik PIN ile açılır. Doğru PIN o oturum boyunca geçerlidir. PIN, Keystore ile şifrelenerek DataStore'da tutulur.
- **Kanal sıralama:** Canlı TV'de uzun OK işlemlerine "Yukarı taşı / Aşağı taşı / Başa al" eklenir. Özel sıra Room'da kaynak ve kanal anahtarıyla tutulur (şema 7→8, otomatik geçiş ve `MigrationTest` güncellenir).

## K — Kartlı gezinme ve +18 filtresi (0.4.0)

Kullanıcı kararları (15 Kasım 2026):
- Soldaki menü tamamen kalkar. Açılış ekranı kartlardan oluşur.
- Bugünkü Ana Sayfa'dan yalnız "Son kanal" ve "Kaldığın yerden devam et" rafları kartların altında kalır.
- +18 içerik varsayılan olarak gizlidir ve PIN istenmez.
- Canlı TV'de kategori seçilince kanallar kart ızgarası olarak görünür; önizleme yoktur.

### 1. Açılış ekranı (Hub)
1. Sol menü (`LibraryScreen.kt` içindeki `GlassPanel` rail, `sections`, `railFocus`, `nav-open`/`nav-closed`) kaldırılır. İçerik sol kenardan 48 dp güvenli alanla başlar.
2. `section == "Ana Sayfa"` yerine Hub gösterilir.
   - Üstte Lumenara logosu.
   - Altında iki satır büyük cam kart:
     - 1. satır: **Canlı TV**, **Filmler**, **Diziler**. Bu kartlar 3 eşit genişlikte, yaklaşık 200 dp yükseklikte olur; simge, ad ve içerik sayısı gösterir (örnek: "412 kanal", "21.304 film").
     - 2. satır: **Listem**, **Ara**, **Ayarlar**. Daha alçak kartlar (~110 dp).
   - Her kart `Surface(onClick)`; odakta büyür, odak halkası alır.
   - Her kartta `testTag("hub-<Ad>")` ve `contentDescription = <Ad>` bulunur. Ayarlar kartında güncelleme varsa `stateDescription = "Güncelleme hazır"` ve nokta işareti olur.
   - Sayılar +18 filtresine uyar.
3. Kartların altında yalnız şu iki raf kalır (Home'daki mevcut kod yeniden kullanılır):
   - "Son kanal": son izlenen canlı kanallar.
   - "Kaldığın yerden devam et".
   - Vitrin (`homeFeatures`), sinematik arka plan değişimi, diğer film rafları, koleksiyonlar ve öneriler Hub'dan kalkar. DAO fonksiyonları silinmez.
4. Açılışta ilk odak Canlı TV kartındadır.
5. Birden çok kaynak varsa kaynak seçici (`Pill` satırı) Hub'ın en üstünde kalır.
6. Geri tuşu: alt ekranlardan bir üst seviyeye döner. Dönüşte odak, girilen kartta olur.
7. **Çift geri ile çıkış** (kullanıcı kararı, 15 Kasım 2026): Hub'da ilk geri basışta altta cam bir bildirim görünür: "Çıkmak için tekrar geri'ye basın".
   - 2 saniye içinde ikinci geri basışta uygulama tamamen kapanır: `finishAndRemoveTask()`, oynatıcılar serbest bırakılır.
   - Süre dolarsa bildirim kaybolur ve sayaç sıfırlanır.
   - Kaynak eklenmemiş ilk açılış ekranında da aynı davranış geçerlidir.

### 2. Kategori kartları
1. Canlı TV, Filmler ve Diziler'e girince önce kategori kartları ızgarası gelir.
   - 4 sütun, kart yüksekliği ~120 dp.
   - Kartta kategori adı (en çok 2 satır) ve içerik sayısı bulunur.
   - Üstte ekran başlığı ("Canlı TV" vb.) yer alır. Üstte veya yanda sekme ya da Pill satırı yoktur.
2. Kart sırası:
   - Önce özel kartlar:
     - Canlı TV: "Tüm kanallar", "Son izlenenler", "Favoriler", "Şu an yayında", "Yayın akışı" (Timeline).
     - Filmler ve Diziler: "Tümü".
   - Ardından sağlayıcı kategorileri gelir. Sıralama (kullanıcı kararı, 15 Kasım 2026):
     1. Adı "TR" ile başlayanlar önce gelir. Baştaki köşeli parantez ve boşluk gibi simgeler yok sayılır; "TR"den sonra harf olmamalıdır. Örnekler: `TR | Ulusal`, `TR: Spor`, `[TR] Belgesel`, `TR-Haber`. "Trabzonspor" ve "Travel" TR sayılmaz.
     2. Sonra diğerleri gelir.
     3. Her iki grup kendi içinde alfabetik sıralanır (Türkçe collation).
     - Bu kural saf Kotlin `categoryOrder` karşılaştırıcısı olarak `core/catalog`'a yazılır ve JVM testiyle doğrulanır.
   - +18 kategorileri, gösteriliyorsa, her zaman en sonda yer alır (TR ile başlasalar bile).
   - Boş özel kartlar gizlenir (örnek: favori yoksa "Favoriler").
3. Kart seçilince içerik ekranı açılır; ekran başlığı kategori adıdır. Geri tuşu kategori kartlarına, odak seçilen karta döner.
4. Test etiketleri: `testTag("category-<ad>")` ve `contentDescription = "<ad> · <sayı>"`.

### 3. İçerik ekranları
- **Canlı TV kategori içi:** Kanal kartı ızgarası.
  - 4 sütun. Kartta logo, kanal numarası, ad ve şu anki EPG başlığı ile ilerleme bulunur; mevcut `ChannelCard` yeniden kullanılır.
  - OK tam ekran oynatır. Uzun OK mevcut kanal işlemlerini açar.
  - Sesli önizleme yoktur.
  - Rakam tuşlarıyla numara girişi (`rememberLiveNumberControl`) korunur.
  - Oynatmadan dönünce odak aynı kanala gelir (`returnToChannel`).
  - "Devamını göster" sayfalama korunur. Kanal araması ekranın üstündeki küçük bir "Kanal ara" alanıyla yapılır.
- **Filmler/Diziler kategori içi:** Mevcut 6 sütunlu afiş ızgarası.
  - "Filtrele" düğmesi başlığın sağında kalır; filtre paneli mevcut haliyle açılır.
  - Kategori Pill satırı kalkar.
- **Listem:** Önce iki kart: "Favoriler" ve "İzleme listem". Seçince mevcut ızgara açılır.
- **Ara** ve **Ayarlar** mevcut ekranlardır; yalnız sol menüye bağımlılıkları kaldırılır.

### 4. +18 filtresi
1. `core/catalog` içine saf Kotlin `fun isAdultCategory(name: String): Boolean` eklenir.
   - `searchable()` ile normalize edilir.
   - Kelime/simge eşleşmesi: "adult", "adults", "for adults", "xxx", "18+", "+18", "(18)", "yetiskin", "erotik", "porn", "sex".
   - "Sextant" gibi yanlış eşleşmeleri önlemek için kelime sınırı kullanılır.
   - JVM testi yazılır: olumlu, olumsuz ve Türkçe karakterli örnekler.
2. Ayarlar → Oynatıcı bölümünün altına "+18 içerikleri göster · Kapalı/Açık" düğmesi eklenir.
   - DataStore anahtarı `show_adult`, varsayılan `false`.
   - Değişiklik anında uygulanır.
3. Gizliyken +18 kategorisindeki öğeler hiçbir yerde görünmez: kategori kartları, kanal/afiş ızgaraları, Hub sayıları ve rafları, arama, "Şu an yayında", mini rehber, numara girişi ve kanal sırası, "Son kanal".
   - Uygulama yolu: `Repository` +18 kategori adlarını `categories`/`liveCategoryCounts` üzerinden Kotlin'de hesaplar ve tercihle birleştirip `hidden: List<String>` akışı üretir.
   - Liste döndüren DAO sorgularına `AND category NOT IN (:hidden)` eklenir.
   - Boş liste geçerlidir. Şema değişmez.
4. Açıkken +18 kategorileri kategori kartlarında en sonda durur. Raflarda ve aramada normal sırada görünür.

### 5. Testler
- **JVM:** `isAdultCategory` testleri.
- **Cihaz testleri:**
  - Sol menüye dayanan bütün testler Hub kartları ve geri tuşuyla güncellenir: `RemoteNavigationTest`, `CinemaJourneyTest`, `LibraryJourneyTest`, `SeriesJourneyTest`, `AudioSubtitleTest`, `LiveComfortTest`, `FullscreenTest`, `ProgramDiscoveryTest`.
  - Kaldırılan vitrin, raf ve koleksiyon beklentileri silinir; kategori kartı → ızgara → oynat akışına çevrilir.
  - `RemoteKeys.kt`'ye `openHubCard(name)` ve `openCategory(name)` yardımcıları eklenir.
- **Yeni cihaz testi `HubAndAdultFilterTest`:**
  - Sahte M3U'da "Haber", "Spor" ve "For Adults" canlı kategorileri ile "Aksiyon" ve "XXX" film kategorileri bulunur.
  - Varsayılan durumda +18 kartları ve kanalları görünmez; Hub sayıları buna göre hesaplanır.
  - Ayarlardan açınca +18 kartları en sonda görünür.
  - Kumandayla Hub → Canlı TV → Haber → kanal → oynat → geri akışında odak aynı kanala döner.
