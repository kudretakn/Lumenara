# Lumenara 0.5 — Tasarım yenileme özeti ("Quiet Cinema")

Hedef: sade, sakin, profesyonel bir 10-ft arayüz. Dekor yok; ekranı **içerik görselleri** ve **tipografi** taşır. Apple TV'nin sakinliği + Netflix'in yoğunluğu. Mi Box 4'te akıcı çalışmalı: blur yok, gerçek zamanlı efekt yok, en fazla 2 yarı saydam katman.
Değişmeyecek kararlar (USABILITY §K): Hub'da 6 kart (Canlı TV/Filmler/Diziler/Listem/Ara/Ayarlar), altta "Son kanal" ve "Kaldığın yerden devam et" rafları, kategorilerde TR-önce sıralama, +18 gizli, çift geri ile çıkış. Bu brief **yalnız görünümü** değiştirir; akış ve test etiketleri (`hub-*`, `category-*`, `live-channel-*`, `player-options`) korunur.
Ölçek: 1920×1080 px = 960×540 dp (1 dp = 2 px). Aşağıdaki tüm ölçüler dp/sp cinsindendir.

## 1. Eleştiri — neden amatör duruyor

**Her ekranda ortak sorunlar**
- **Odak hâli metni yok ediyor.** `FocusCard`/`NavigationCard` yalnız `containerColor` veriyor; tv-material3 odakta varsayılan açık renk dolguya geçiyor (≈#E6E2E8). Üstündeki `Ink.muted` metin okunmuyor: hub.png "1 kanal", live.png "Canlı Maç", season.png "1 bölüm · 0/1 izlendi". Odaktaki kart "seçili" değil, "devre dışı" gibi görünüyor. Bu tek başına en amatör izlenim.
- **Font ve tip ölçeği.** Uygulama Inter kullanmıyor, varsayılan Roboto ile çalışıyor (logo SVG'si Inter Display kullanıyor; marka ile arayüz uyuşmuyor). Ekran başlıkları ince/Regular 44 sp (movies.png "Sinema", live.png "Spor"), detay başlığı Bold 44 sp. Ağırlık hiyerarşisi rastgele. Caption 12 sp ve body 15 sp 3 metreden okunmuyor.
- **Hizalar tutmuyor.** Hub'da logo kutusu x≈116 px, kart ızgarası x≈96/116 px (odaklı kart büyüyünce kolon kayıyor). Setup'ta başlık 96 px, alan etiketleri 104 px. Details'ta "Oynat" 96 px, "Geri" 96 px, versions.png'de çipler 86–106 px arasında dalgalanıyor. Ortak bir kolon çizgisi yok.
- **Renk.** Arka plan mavimsi siyah (#080B0F), yüzeyler mavi-gri (#242B33). Tek bir vurgu rengi yok (`Ink.accent` #C9D6DD soluk gri-mavi), logo ise altın sarısı. Sonuç: renksiz ve "varsayılan tema" havası.
- **Cam panellerin altı görünüyor.** mini-guide.png ve subtitle-search.png'de yarı saydam paneller (`glassStrong` %88 + `glass` %10) arkadaki eski Ana Sayfa'yı, logoyu ve sol menüyü yazıların arasından gösteriyor. Üst üste üç panel bindiği için okunmuyor. Sol kenarda yarım kalmış beyaz bir odak parçası da var (x≈0–30, y≈265–370).
- **Kopyalanmış ipucu metinleri.** "OK / ← Mini rehber • Uzun OK / Menü: Seçenekler" (fullscreen-banner.png), "← −10 sn • +10 sn → • OK: Seçenekler" (subtitle-playing.png). TV uygulamalarında bu bilgi bir kez öğretilir, her seferinde yazılmaz.
- **Unicode "ikonlar".** ✓ (Pill, versions.png), ★ (details.png), ▶ (mini-guide.png), "+ Listem". Bunlar ikon setiyle uyuşmayan font glifleri.
- **"K" monogramları.** Logosu olmayan her şey (dizi afişi series.png, sezon kartları season.png, kanal logosu fullscreen-banner.png/mini-guide.png) tek bir gri harfle gösteriliyor. Bu harf hiçbir şeyi ayırt etmiyor; "Kanal 00001"…"00007" satırlarının hepsi aynı "K".

**Ekran ekran**
- **hub.png / home-shelves.png:** 6 kart neredeyse boş gri kutular; kartın %70'i boşluk. Simgeler farklı ailelerden geliyor: TV simgesi dolgulu antenli, ayarlar "güneş" gibi duruyor, Diziler simgesi anlaşılmıyor. Odaklanmayan kart 1 px kenarlıklı, odaklanan kart ise beyaz blok; iki hâl arasında zıtlık çok sert. Logo karo içinde koyu kare olarak duruyor (marka işareti zemin rengine yakın bir kutuya hapsolmuş). "Son kanal" kartında kanal logosu yerine film afişi (astronot) çıkıyor ve kartın alt yarısı boş.
- **live.png:** Tek kartlık ızgara sayfanın sol üst köşesinde kalıyor. "Kanal ara" alanı sağ üstte, etiketi alanın dışında, Material form gibi görünüyor. Kartta logo yok; "1 · Test Kanalı" ve ilerleme çizgisi kartın üst yarısına sıkışmış, alt %45 boş.
- **movies.png / cinema-focus.png:** Afiş oranı 2:3 değil (~0,85, kare gibi), görseller kırpılıyor. Başlık "Sinema" iken Hub kartı "Filmler"; isimler tutarsız. "Filtrele" butonu odakta beyaz pill, başlıktan büyük görünüyor. Son satırda tek afiş kalıyor; ızgara sol kenara 112 px, başlık 96 px'e hizalı.
- **details.png:** Ekran solda düz siyah, sağda keskin dikey bir kesikle başlayan görsel (x=840). Gradyan geçişi yok, iki ayrı resim gibi görünüyor. "Oynat" butonunda Canlı TV simgesi var. "Geri" butonu gereksiz (kumandada Geri tuşu var). "Oyuncular" başlığı özetten büyük. Özet tek satır, ritim yok.
- **series.png / season.png / versions.png:** Görseli olmayan içerikte arka plana uygulamaya gömülü yapay zekâ "deniz feneri" fotoğrafı (`lumenara_coast.jpg`) konuyor. İçerikle ilgisiz bir manzara, kullanıcıya bu dizinin görseliymiş gibi sunuluyor. "Giriş ekranındaki fotoğraflar"ın kaynağı bu ve test fixture'ları (`androidTest/assets/cinema/tile*.jpg`, yine yapay zekâ işi stok tarz). Sezon kartları 220 dp boyunda ve "K" harfiyle dolu. "Oynat (S1 B1)" kodu kullanıcı dili değil. Başlık ekranın üst kenarına yapışık (y≈20 px, güvenli alan dışı).
- **search.png:** Sistem klavyesi ekranın ortasını kaplıyor ve sonuçların üstüne biniyor ("2025" kesik). Tek sonuç 232 px genişlikte, sağda boş alan. Alan etiketi "yayın kimliği" teknik bir terim.
- **fullscreen-banner.png:** Bant ekranın %32'sini kaplıyor ve opak gri bir kutu (videonun üstüne kalkan gibi biniyor). Satırlar "1  K  Kanal 00001". "Sonra: —" boş veri olduğu hâlde gösteriliyor. İlerleme çizgisi gri üstüne gri.
- **mini-guide.png:** Her satır ayrı bir kart; aralarında çizgi + kart + ilerleme çizgisi var. 8 satırın 8'inde de "Program bilgisi yok" tekrarlanıyor. Başlık "Mini rehber" teknik bir isim. "Seçenekler" butonu outline, "Tümü" ise dolu: iki farklı buton dili kullanılıyor.
- **subtitle-search.png:** Seçenekler paneli + altyazı diyaloğu + arkadaki ekran üst üste biniyor. Diyalogda "Kapat" butonu içerikten (yükleniyor…) önce geliyor.
- **subtitle-playing.png:** Toast'ta "OpenSubtitles kalan indirme: 8" gibi geliştirici bilgisi var. Başlık "Kıyı 104439686826 (Dublaj)" ham sağlayıcı adı olarak duruyor.
- **cinema-setup.png (karşılama/kurulum):** Kurulum adımı bir form. Karşılama yok, değer önerisi yok, adım göstergesi yok. "IPTV Kaynakları" ince font. Seçili sekme "✓ M3U" beyaz bir pill. "HTTP uyumluluğu · Kapalı" ana akışta duruyor. Pasif "Bağlantıyı sına" butonu neredeyse görünmüyor. Ekranın sağ yarısı boş.
- **Ayarlar (Settings.kt):** "Türkçe · Antrasit tema · Lumenara 0.1" gibi eski ve yanlış bilgi var. Başlık-satır-buton karışık bir liste; gruplama yok.
- **Logo / banner:** Altın "lamba" işareti iyi bir fikir ama gradyanı bulanık ve 48 dp'de detay kayboluyor. Launcher banner'ında (banner.png, 320×180) yazı yok; Android TV ana ekranında yalnız bir koni görünüyor.

## 2. Tasarım dili

**Renk token'ları** (`Ink` → `LumenColors`; nötr, mavi tonu yok)
| Token | Değer | Kullanım |
|---|---|---|
| `bg` | #0B0B0D | Uygulama zemini |
| `surface1` | #141518 | Panel, bant, mini rehber (opak) |
| `surface2` | #1C1D21 | Kart dolgusu, buton (odaksız) |
| `surface3` | #26282D | Giriş alanı, seçili satır |
| `divider` | #FFFFFF @ 8% | Ayraç çizgileri (kartlarda kenarlık yok) |
| `textPrimary` | #F4F4F5 | Başlık, gövde |
| `textSecondary` | #A3A5AC | Meta (surface2 üzerinde ≥ 4.5:1) |
| `textTertiary` | #6C6F76 | Yalnız pasif durum |
| `accent` | #E8B65A "Lumen altını" | **Tek vurgu rengi:** ilerleme dolgusu, seçili göstergesi (3 dp çizgi), Ayarlar güncelleme noktası. Büyük alan boyamaz. |
| `focus` | #FFFFFF | Odak halkası; odaktaki butonun dolgusu |
| `onFocus` | #0B0B0D | Odaktaki butonun metni |
| `live` | #E5484D | CANLI rozeti, kayıt |
| `success` | #46A758 | Bağlantı başarılı |
| `error` | #E5484D + ikon | Hatalar (`live` ile aynı ton, her zaman ikonla birlikte) |
| `scrim` | #0B0B0D @ 0→92% | Görsel üstündeki gradyanlar |

**Tipografi:** Inter (OFL), `res/font/inter_{regular,medium,semibold,bold}.ttf`. Satır aralığı 1.25. En küçük boyut 14 sp.
| Rol | sp / ağırlık | Nerede |
|---|---|---|
| `display` | 48 / Bold, -0.5 harf aralığı | Detay başlığı |
| `headline` | 32 / SemiBold | Ekran başlığı (Filmler, Spor) |
| `title` | 22 / SemiBold | Hub kartı, bant kanal adı |
| `cardTitle` | 18 / Medium | Afiş/kanal kartı adı |
| `body` | 18 / Regular | Özet (en fazla 3 satır, 560 dp genişlik) |
| `label` | 16 / SemiBold | Buton, çip |
| `meta` | 15 / Regular, `textSecondary` | Yıl · tür · süre |
| `caption` | 14 / Medium, büyük harf, +0.8 harf aralığı | Bölüm etiketleri ("DEVAM ET") |

**Boşluk:** 8 dp ızgara (4 dp yalnız ikon-metin arası). Ekran kenarı yatay 48, dikey 27 (overscan güvenli alanı). Kartlar arası 16, raflar arası 32, başlıktan içeriğe 24. Tüm ekranlar x=48 kolonuna hizalanır; odakta büyüme kolonu kaydırmaz (scale merkezden, layout'u etkilemez).
**Köşe yarıçapları:** kart 12, buton/çip 8 (pill yok), panel 16, kanal logosu karosu 8, ilerleme çubuğu 2.
**Yükselti ve katmanlar:** gölge yok, blur yok. Görsel üstü okunurluk için yalnız önceden hesaplanmış `Brush` gradyanları (sol→sağ 0.95→0 ve alt→üst 1→0). Paneller **opak** `surface1` kullanır. Arka planı karartmak gerekirse tek katman `bg @ 60%`. Yarı saydam panel üst üste binmez; ikinci panel açılınca birincisi kapanır ya da gizlenir.
**Odak:** tek kural, her yerde aynı.
- Kart/afiş: `scale` 1.06 (Hub kartı 1.04), 3 dp `focus` halkası, halka ile kart arası 0 boşluk. Dolgu **değişmez**, metin rengi değişmez. Glow yok.
- Buton/çip: odakta `focus` dolgu + `onFocus` metin, scale 1.0. Odaksız `surface2` dolgu + `textPrimary`.
- Liste satırı (mini rehber, ayarlar, seçenekler): odakta `surface3` dolgu + solda 3 dp `focus` çubuğu. Scale yok.
- Süre: odak girişi 150 ms `FastOutSlowIn`, çıkışı 100 ms.
**Hareket:** kısa ve tek tip. Ekran geçişi 200 ms crossfade. Panel açılışı 220 ms (slide 24 dp + fade). Arka plan görseli değişimi 300 ms crossfade, odak 250 ms durduktan sonra. Bant otomatik gizlenmesi 4 s, fade 200 ms. Otomatik dönen vitrin yok. Parallax, shimmer ve sonsuz animasyon yok; yükleniyor iskeletleri statik `surface2`.
**İkonlar:** tek set, **Material Symbols Rounded, outline, weight 300**, vector drawable olarak `core/designsystem/res`'e kopyalanır. Boyutlar: 24 satır/buton, 32 Hub kartı, 20 meta. Renk `textPrimary`, odakta `onFocus`. Unicode ✓ ★ ▶ + • ← → kullanılmaz; yerine `check`, `star`, `play_arrow`, `add`, `arrow_back` ikonları gelir.
**Görsel kuralları:**
1. Yalnız gerçek içerik görseli kullanılır: sağlayıcı afişi/logosu, TMDB poster/backdrop/logo. Stok, yapay zekâ veya dekoratif fotoğraf yok. `lumenara_coast.jpg` silinir.
2. Afiş her zaman 2:3, backdrop 16:9, kanal logosu karosu 16:9 (logo `Fit`, %70 iç alan, `surface3` zemin).
3. Görsel yoksa: **tipografik yedek**. Afişte `surface2` zemin, başlık `cardTitle` ile sol altta en fazla 3 satır, sağ üstte tür ikonu (film/dizi) %40 opaklıkla. Kanalda karonun ortasında kanal adının ilk 2 kelimesi SemiBold 16 sp, en fazla 2 satır. Ad hash'inden seçilen 6 sessiz tonlu zeminden biri kullanılır (#1E2430, #2A1F24, #1F2A24, #2B2619, #24202E, #1C2A2B). Tek harf monogramı kullanılmaz.
4. Backdrop yoksa detay ekranında poster büyütülüp blur'lanmaz; düz `bg` zemin, sağda 2:3 poster 360 dp yükseklikte gösterilir.
5. Test fixture'ları (`androidTest/assets/cinema`) da bu kurala uyar: 2:3 afişler başlık tipografisiyle tasarlanır, manzara fotoğrafı kullanılmaz. Ekran görüntüleri gerçek kullanımı temsil etmelidir.

## 3. Ekran yerleşimleri (960×540 dp)

**Hub (açılış)**
```
48                                                                   912
27 [◆ Lumenara]                                         [kaynak çipleri]   ← logo 28 dp + kelime 22 sp
   ┌─────────────────┐┌─────────────────┐┌─────────────────┐   y=88, h=168
   │ (tv)            ││ (movie)         ││ (series)        │   kart: surface2, 32 dp ikon sol üst
   │                 ││                 ││                 │   alt-sol: title 22 + meta 15
   │ Canlı TV        ││ Filmler         ││ Diziler         │   arka plan: sağ yarıda son izlenen
   │ 412 kanal       ││ 21.304 film     ││ 3.120 dizi      │   içerikten backdrop, %35 opak + scrim
   └─────────────────┘└─────────────────┘└─────────────────┘   (görsel yoksa düz surface2)
   ┌──────────┐┌──────────┐┌──────────┐                        y=272, h=64, satır kart (ikon+ad yan yana)
   │(bm) Listem││(srch) Ara ││(gear) Ayarlar•│                       3 eşit kolon, üstteki ızgaraya hizalı
   └──────────┘└──────────┘└──────────┘
   SON KANAL                                                   caption 14
   [16:9 kanal karosu 200×112 + ad + program + ilerleme] ×N   yatay raf
   KALDIĞIN YERDEN DEVAM ET
   [16:9 backdrop 240×135 + ilerleme 3 dp accent + "S1 · B4 · 12 dk kaldı"] ×N
```
Dikey kaydırmada başlık kaybolur ve raflar yukarı kayar. İlk odak Canlı TV kartındadır.

**Canlı TV — kategori ızgarası:** başlık `headline` "Canlı TV" (x=48, y=27). Altında 4 kolon kart, 200×88 dp, `surface2`. Kartta ad `cardTitle` (2 satır) ve sayı `meta` bulunur. Özel kartların ("Tüm kanallar", "Son izlenenler", "Favoriler", "Şu an yayında", "Yayın akışı") başında 20 dp ikon olur. Sağ üstte `Ara` ikon butonu bulunur (açılınca başlığın yerine arama alanı gelir).
**Canlı TV — kanal ızgarası:**
```
Spor  · 48 kanal                                    (search) (filter)
┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐  4 kolon, 204 dp
│  [ LOGO 16:9 ]│ │              │ │              │ │              │  logo karosu 204×115
└──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘
 101  beIN Sports 1                                                  meta 15 numara + cardTitle 18
 Süper Lig: GS–FB · 21:45                                            meta, 1 satır
 ▬▬▬▬▬▬▬────── (accent, 2 dp)
```
Odakta yalnız logo karosu büyür ve halka alır; metin karonun altında sabit kalır (Netflix/Apple TV kalıbı).

**Tam ekran bilgi bandı (canlı):** kutu yok. Alttan 200 dp yüksekliğinde `scrim` gradyanı (0→92%) gelir. İçerik x=48'den başlar:
```
[logo 96×54]  101  beIN Sports 1                         ● CANLI   21:47
              Süper Lig: Galatasaray – Fenerbahçe        21:45 – 23:45
              ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬──────────────────────── (accent 3 dp, 560 dp)
              Sonra  23:45  Maç Sonu                      (yoksa satır gizlenir)
```
İpucu metni yok. Sadece ilk 3 kullanımda sağ altta tek satır `meta` gösterilir: "◀ Kanallar  ·  Basılı tut: Seçenekler".
**Mini rehber:** soldan 400 dp opak `surface1` panel, tam yükseklik. Video sağda görünmeye devam eder; arka plan karartması yok. Üstte kategori adı ve sağ/sol ok ikonları (kategori değiştir) bulunur; çip satırı yok. Satırlar 64 dp: numara 15 sp, 40×22 logo, ad 18 sp, program 15 sp. İlerleme çizgisi yalnız odaktaki satırda görünür. EPG yoksa ikinci satır boş kalır ("Program bilgisi yok" yazılmaz). Oynayan kanalda accent renkli 3 dp sol çubuk olur.
**Oynatıcı seçenekleri paneli:** sağdan 400 dp opak `surface1`. Gruplar `caption` başlıklarla ayrılır: SES · ALTYAZI · GÖRÜNTÜ · OYNATICI. Satırda sol tarafta ikon + ad, sağ tarafta mevcut değer `textSecondary` + chevron bulunur. Satıra girince alt seçim aynı panelde kayarak açılır (ikinci panel/diyalog yok). "VLC ile aç" → OYNATICI › "Motor: Media3/VLC".
**VOD bandı:** alt scrim, başlık `title` (temiz ad: sağlayıcı önekleri ve kimlik sayıları atılır), ilerleme 3 dp `accent`, sol tarafta geçen süre, sağ tarafta kalan süre `meta`. Toast'lar sağ üstte `surface1`, 2.5 s, kısa Türkçe metin ("Türkçe altyazı eklendi").

**Filmler / Diziler ızgarası:** başlık `headline` "Filmler" (Hub ile aynı isim) + `meta` sayı, sağda `Filtrele` ikon+etiket butonu. 7 kolon 2:3 afiş (112×168 dp, 16 aralık). Başlık ve yıl afişin altında; yalnız odaktaki afişte görünür (Apple TV). Diğer afişlerde metin gizlenir, böylece ızgara sakin durur. Görsel yoksa tipografik yedek kullanılır.
**Detay (film):**
```
backdrop tam ekran (Crop) + sol gradyan 0.95→0 (x 0→60%) + alt gradyan
x=48 y=200  [TMDB logo görseli maks 360×120, yoksa display 48 başlık]
            2025 · Macera · 1 sa 52 dk · ★ 8.2 (ikon)   meta
            Özet, body 18, 3 satır, 560 dp
            [▶ Oynat] [＋ Listem] [♡]  [Dublaj ▾]        butonlar 48 dp yükseklik, 8 aralık
            OYUNCULAR  (caption)  Deniz Aral · Ece Yalın · Kerem Sönmez  (meta, tek satır)
```
"Geri" butonu ve "SİNEMA" üst etiketi kalkar. Dublaj/Altyazı tek bir açılır buton olur.
**Dizi / Sezon:** detay ile aynı üst blok; "Oynat" butonu → "S1 B1'i oynat" yerine "Oynat · 1. Bölüm" ya da "Devam et · S1 B4". Altta sezon sekmeleri `label` 16 metin; seçili sekmenin altında 3 dp accent çizgi var. Altında yatay bölüm rafı: 16:9 küçük resim 240×135, "4. Necati'nin dönüşü" cardTitle, süre meta, ilerleme. Sezon kartları kalkar.
**Ara:** üstte tam genişlik arama alanı (x=48, 56 dp, `surface3`, sol tarafta ara ikonu, sağ tarafta mikrofon ikon butonu). Klavye açıkken sonuçlar alanın altında **tek yatay raf** olarak gösterilir. Klavye kapanınca raflar "Kanallar / Filmler / Diziler / Programlar" olur. Boş durumda "Son aramalar" çipleri gösterilir. Etiket: "Kanal, film veya dizi ara".
**Ayarlar:** iki kolon. Solda 280 dp grup listesi: Kaynaklar, Oynatıcı, Altyazı, İçerik (+18), Depolama, Güncellemeler, Hakkında. Sağda seçili grubun satırları: başlık 18 + açıklama 15 + sağda değer/anahtar. Anahtar (toggle) 40×24, açıkken `accent`. Hatalı "Antrasit tema · 0.1" metni silinir; sürüm Hakkında'da `BuildConfig`'den okunur.
**Kurulum / karşılama:** 2 adım, sol hizalı. Sağ yarı boş bırakılmaz:
```
Adım 1  (sol 56%)                           │ (sağ 44%)
[◆ Lumenara]  display 48 "Hoş geldiniz"     │ Telefonla ekle
body: "IPTV listenizi ekleyin; kanallar,     │ [QR 200×200]
filmler ve diziler tek yerde."               │ "Telefonunuzla okutun,
[Liste adresi gir] [Xtream ile giriş]        │  adresi oradan yapıştırın"
[Dosyadan] [Yedekten geri yükle] (ikincil)   │
```
Adım 2: seçilen yöntemin formu. Alanlar 560 dp genişlikte, etiket alanın içinde (floating). "Gelişmiş" katlanır grubunda HTTP uyumluluğu bulunur. Birincil buton accent değil beyaz (`focus`) dolgu olur; pasifken `surface2` + `textTertiary`. Bağlantı denemesi sırasında satır içi ilerleme gösterilir ve sonuç `success`/`error` ikonuyla bildirilir.

## 4. Kaldırılacaklar
- `Glass.kt` tamamen (`glass()`, `GlassPanel`, `Modifier.blur`, `Ink.glass/glassStrong`). Yerine opak `Panel(surface1)` gelir.
- `lumenara_coast.jpg` ve Cinema.kt'deki fallback; `drawable-nodpi` boşalır.
- Tüm Unicode ikon glifleri (14 yer: ✓ ★ ▶ • ← →), "+ Listem" artısı, `Pill` içindeki "✓ ".
- Kalıcı ipucu satırları (bant, VOD bandı, mini rehber başlığı "Mini rehber").
- Detay/Sezon ekranlarındaki "Geri" butonları, "SİNEMA/FİLMLER" üst etiketi, "Sinema" başlığı.
- Tek harf monogram yedeği (`Artwork` fallback "K").
- Odakta dolgu rengi değiştiren tüm `ClickableSurfaceDefaults.colors` kullanımları (kartlar için).
- Kart kenarlıkları (1 dp `stroke`). Ayrım dolgu tonu ile sağlanır.
- Geliştirici metinleri: "OpenSubtitles kalan indirme", "yayın kimliği", "(S1 B1)".
- Pill (yuvarlak 50%) şekli → 8 dp köşeli buton/çip.

## 5. Uygulama planı (her satır ayrı, yayınlanabilir PR)
Her PR: ilgili yolculuk testi yeni ekran görüntüsünü `screens/` altına yazar. Kabulde CI artifact'ı incelenir. Lint, birim testleri ve tüm cihaz testleri yeşil olmalıdır. `testTag`/`contentDescription` korunur.

| # | Görev | Dosyalar | Kabul | Paralel |
|---|---|---|---|---|
| D0 | **Token + tipografi + bileşenler.** `LumenColors`, `LumenType` (Inter fontları `core/designsystem/src/main/res/font`), `Spacing`, `Radii`, `Motion` objeleri. `FocusCard` odak spec'i (dolgu sabit, 3 dp halka, 1.06), `LumenButton` (birincil/ikincil, 8 dp), `Chip`, `ListRow`, `Panel`, `ProgressLine(accent, 3 dp)`. Material Symbols drawable'ları + `LumenIcon` enum. `Ink`'i deprecated alias olarak tut (diğer PR'lar derlenebilsin). | `core/designsystem/**` | Birim/screenshot: odaktaki kartta metin kontrastı ≥ 4.5:1 (Compose test, renk okuma). hub.png'de odaklı kart koyu kalır. | Önce tek başına |
| D1 | **Görsel yedeği + logo.** `Artwork.kt` tipografik yedek (afiş/kanal, 6 ton hash). `lumenara_coast.jpg` + Cinema fallback silinir. Logo: düz altın işaret (gradyansız), 28 dp + Inter SemiBold kelime işareti, karo yok. `banner.png` yeniden üretilir (işaret + "Lumenara"). Test fixture afişleri 2:3 tipografik afişlerle değiştirilir. | `Artwork.kt`, `Cinema.kt` (yalnız fallback bloğu), `LumenaraLogo.kt`, `res/drawable*`, `design/logo/`, `androidTest/assets/cinema/` | series.png'de "K" yok, season.png'de deniz feneri yok. ArtworkTest yeşil. | D0'dan sonra; D2–D4 ile paralel |
| D2 | **Hub + kategori ızgarası.** Yeni Hub kart yerleşimi (üst 3 büyük kart + 3 satır kart), raflar (16:9 kanal/devam kartları), `NavigationCard` → D0 bileşenleri, kategori kartı 200×88. | `Browse.kt` (yalnız `Home`/Hub kısmı), `CategoryGrid.kt`, `LibraryScreen.kt` | hub.png, home-shelves.png: tüm kolonlar x=48'de. Odak başında Canlı TV. LibraryJourneyTest yeşil. | D3, D4, D5 ile paralel |
| D3 | **Canlı TV kanal ızgarası + tam ekran bant + mini rehber.** `ChannelCard` (16:9 logo karosu + alt metin), arama/filtre ikon butonları, scrim bant, opak mini rehber satırları. İpucu metni "ilk 3 kullanım" sayacı (DataStore). | `LiveScreen.kt`, `ChannelCard.kt`, `FullscreenOverlay.kt`, `LivePages.kt` | live.png, fullscreen-banner.png, mini-guide.png. Mini rehber arkasında başka ekran görünmez (piksel testi: panel alanı tek tonda). FullscreenTest/LiveComfortTest yeşil. | D2, D4, D5 ile paralel |
| D4 | **Filmler/Diziler/Detay/Sezon.** 7 kolon 2:3 ızgara, metni yalnız odakta göster, detay gradyanı (keskin kenar yok), buton satırı, Dublaj açılır menüsü, bölüm rafı, "Geri"/üst etiket kaldır, başlık temizleyici (`displayTitle()`: sağlayıcı önekleri ve 6+ haneli sayı dizilerini at; saf Kotlin, JVM testi). | `Browse.kt` (yalnız `Poster`/`PosterGridContent`), `Details.kt`, `SeriesDetails.kt`, `Cinema.kt` (gradyan), `core/catalog` (`displayTitle`) | movies.png, details.png, series.png, season.png, versions.png. CinemaJourneyTest/SeriesJourneyTest yeşil. | D2 ile `Browse.kt`'nin farklı fonksiyonları; çakışırsa D2'den sonra rebase et |
| D5 | **Oynatıcı seçenekleri + altyazı + toast + VOD bandı.** Sağ opak panel, gruplu `ListRow`, alt seçim aynı panelde, altyazı araması panel içinde (diyalog yok), kısa toast metinleri. | `Playback.kt`, `Subtitles.kt`, `VlcScreen.kt` (yalnız overlay çağrıları) | subtitle-search.png'de tek panel. subtitle-playing.png. AudioSubtitleTest yeşil. VLC eşdeğeri aynı. | D2, D3, D4 ile paralel |
| D6 | **Ara.** Üst arama alanı, tek raf/çoklu raf, son aramalar. | `SearchScreen.kt` | search.png: sonuç kesilmez. Arama testi yeşil. | D2–D5 ile paralel |
| D7 | **Ayarlar + Kurulum.** İki kolonlu ayarlar, sürüm `BuildConfig`'den; 2 adımlı karşılama, QR sağda, Gelişmiş grubu. | `Settings.kt`, `Setup.kt`, `Pairing.kt` (yalnız QR görünümü), `BackupScreen.kt` (stil) | Yeni settings.png, setup.png ekran görüntüsü testi eklenir. "0.1" metni yok. | D2–D6 ile paralel |
| D8 | **Temizlik.** `Glass.kt`, `Ink` alias'ı, kullanılmayan `NavIcon` silinir; Unicode glif taraması (grep testi: `✓★▶` kaynakta 0). Perf: Mi Box sınıfı için Macrobenchmark/`Perf.kt` frame süresi kontrolü (CI emülatöründe jank < %5). | `core/designsystem`, grep testi | Tüm ekran görüntüleri güncel; `glass(` 0 kullanım. | En son |

Sıra: D0 → (D1, D2, D3, D4, D5, D6, D7 paralel; dosyaları ayrık, yalnız D2/D4 `Browse.kt`'yi farklı fonksiyonlarda paylaşır) → D8.

## 6. Onay için mockup görselleri (1920×1080 istem metinleri)
Ortak stil eki (her isteme eklenir): *"Android TV app UI screenshot, flat UI render, not a photo of a TV. Background #0B0B0D near-black neutral, cards #1C1D21, text #F4F4F5, secondary text #A3A5AC, single accent colour warm gold #E8B65A used only for thin progress bars. Font Inter. No glass, no blur, no glow, no emoji, 96 px left and right margins, 54 px top and bottom margins, generous spacing, Apple TV / Netflix level polish, minimal and calm."*

1. **Hub:** "Top-left a small gold lamp logo mark and the word 'Lumenara' in Inter SemiBold. Below, three large equal cards in one row (each ~560×336 px, 24 px radius): 'Canlı TV / 412 kanal', 'Filmler / 21.304 film', 'Diziler / 3.120 dizi', each with a thin outline icon top-left and label bottom-left. The 'Filmler' card has a dim cinematic film still faded into its right half. The first card is focused: slightly larger with a 6 px pure white outline, fill unchanged. Below, a row of three short wide cards with icon + label: 'Listem', 'Ara', 'Ayarlar' (small gold dot on Ayarlar). Below, small caps label 'SON KANAL' and a row of 16:9 channel tiles with channel logos on dark tiles, channel name, current programme and a thin gold progress line."
2. **Canlı TV kanal ızgarası:** "Title 'Spor' with '48 kanal' in grey at top-left, search and filter outline icons top-right. A 4-column grid of 16:9 dark tiles each showing a centered TV channel logo (generic fictional sports channel logos), under each tile: channel number in grey, channel name in white, current programme 'Süper Lig: Galatasaray – Fenerbahçe · 21:45', thin gold progress line. One tile without logo shows the channel name typeset in bold on a muted teal tile. Second tile focused: enlarged with white outline, text below unchanged."
3. **Tam ekran + mini rehber:** "Full-screen live football broadcast still. Left side an opaque #141518 panel 800 px wide, full height, header 'Spor' with small left/right chevrons, list of 10 channel rows 128 px tall: number, small logo, channel name, programme title in grey; the focused row has a #26282D fill and a white 6 px left bar; the currently playing row has a gold left bar. Bottom of the video area: dark gradient with channel logo, '101 beIN Sports 1', programme title, time range, gold progress bar, red '● CANLI' badge and clock '21:47' at right. No box around the bottom info."
4. **Film detay:** "Full-bleed cinematic movie backdrop on the right two thirds, smoothly fading to black on the left (no hard edge). On the left from 96 px: a large movie title logo in white, meta line '2025 · Macera · 1 sa 52 dk · ★ 8.2' in grey with an outline star icon, three-line synopsis in white, buttons row: white filled 'Oynat' with play icon, dark 'Listem' with plus icon, dark heart icon button, dark 'Dublaj' dropdown button; small caps 'OYUNCULAR' with names on one line. No back button."
5. **Dizi sezon/bölümler:** "Same layout as the movie details for a Turkish drama series, button 'Devam et · S1 B4' with a gold mini progress. Below, season tabs 'Sezon 1  Sezon 2  Sezon 3' with a gold underline under Sezon 1, and a horizontal row of 16:9 episode thumbnails (480×270 px) with episode number and title below, thin gold progress on watched episodes, third episode focused with white outline."
6. **Karşılama/kurulum:** "Welcome screen split layout. Left 56%: small gold lamp logo, large 'Hoş geldiniz' headline, one-line Turkish subtitle 'IPTV listenizi ekleyin; kanallar, filmler ve diziler tek yerde.', two primary choices as large buttons 'Liste adresi gir' (white, focused) and 'Xtream ile giriş' (dark), smaller secondary text buttons 'Dosyadan' and 'Yedekten geri yükle'. Right 44%: a #141518 rounded panel with a white QR code, title 'Telefonla ekle' and short instruction. No photos, no illustrations."
