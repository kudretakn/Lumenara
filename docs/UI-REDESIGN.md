# Lumenara arayüz yenileme (0.3.0)

Hedef: Apple TV / Netflix kalitesinde, kumandayla hızlı ve kullanışlı bir TV arayüzü. Kullanıcı kararları (14 Kasım 2026):

- Gezinme: **sol açılır menü** (Netflix TV tarzı).
- Ana sayfa vitrini: **otomatik dönen**.
- Öncelik: **Canlı TV**.
- Canlı TV: **kategori + kanal listesi + canlı önizleme**.
- Tam ekranda **mini rehber**.
- Tek kullanıcı, profil yok.
- Görünüm: **koyu, cam efekti** (Apple TV'ye yakın).

Gerçek cihaz yok. Kabul, CI'daki Android TV emülatöründe alınan ekran görüntüleri ve otomatik testlerle yapılır.

## Genel kurallar

- 1920×1080 (960×540 dp) TV için tasarla. Ekran kenar güvenli alanı: yatay 48 dp, dikey 27 dp.
- Her odaklanabilir öğede belirgin odak olsun: kartlar 1.08 kat büyür, beyaz 2 dp kenarlık ve hafif gölge alır. Odak kaybolmamalı, D-pad ile her öğeye ulaşılabilmeli.
- Back davranışı tutarlı olsun: açık panel → kapanır. Alt sayfa → bir önceki sayfa. Bölüm → Ana Sayfa. Ana Sayfa → uygulamadan çıkış.
- Bölüm değişip geri gelindiğinde odak ve kaydırma konumu korunur.
- Metinler Türkçe kalır. Mevcut test etiketleri ve `contentDescription` değerleri korunur ya da testler buna göre güncellenir.
- Mevcut işlevler kaybolmaz: kaynak/kategori filtreleri, gelişmiş filtreler, arama, sesli arama, multi-view, VLC, altyazı, DVR, güncelleme bildirimi.

## 1. Tasarım sistemi — `core/designsystem`

- `Ink` renkleri korunur. Cam yüzeyleri için yeni token'lar eklenir: `glass` (beyaz %8–12 alfa), `glassStrong` (koyu %72), `stroke` (beyaz %14), `focusRing` (beyaz).
- `Modifier.glass(shape)` eklenir. API 31 ve üzerinde arka plan bulanıklığı için `RenderEffect`/`Modifier.blur` kullanılır, mümkün olan yerlerde. Altında yarı saydam koyu dolgu ve ince kenarlık. Performans için bulanıklık yalnız sabit paneller ve menüde.
- Tipografi ölçeği: Display 44 sp, Title 28 sp, Headline 20 sp, Body 15 sp, Caption 12 sp.
- Ortak bileşenler: `FocusCard` (büyüme + kenarlık), `GlassPanel`, `Pill` (filtre/kaynak çipi, seçili hâliyle), `ProgressLine`.

## 2. Gezinme — sol açılır menü

- Kapalıyken 72 dp genişliğinde bir simge şeridi. Odak menüye geçince 240 dp'ye animasyonla açılır, etiketler görünür ve içeriğin üstüne cam panel olarak biner (içeriği itmez). Açıkken içerik karartılır.
- Sıra: **Ara**, Ana Sayfa, Canlı TV, Filmler, Diziler, **Listem**, Ayarlar.
  - Listem, Favoriler ile İzleme Listem'i iki sekmede birleştirir.
  - IPTV Kaynakları, Ayarlar altında ilk satır olur. Kaynak yoksa ilk açılış doğrudan kurulum ekranına gider (mevcut davranış).
- Seçili bölümde soluk vurgu ve sol kenarda ince çizgi gösterilir. İçerikte en sola basınca menü açılır, sağa basınca içerikteki son odağa dönülür.
- Üstteki "LUMENARA + bölüm adı" satırı kaldırılır. Güncelleme bildirimi menüde Ayarlar simgesinde nokta olarak görünür.
- Birden fazla kaynak varsa kaynak seçimi içerik üstünde `Pill` satırı olarak kalır.

## 3. Ana Sayfa — Canlı TV öncelikli

Yukarıdan aşağı:

1. **Vitrin (hero)**: ekran yüksekliğinin yaklaşık %55'i. Arka planı ve görseli olan film/dizilerden en fazla 6 yapım. 9 saniyede bir yumuşak çapraz geçişle değişir. Altta sayfa noktaları vardır. Odak raflara inince vitrin, odaktaki öğenin arka planını gösterir (mevcut davranış). Odak üst bölgeye dönünce otomatik dönüş sürer. Kullanıcı bir tuşa bastıktan sonra 9 saniye dönmez. Düğmeler: Oynat / Detaylar / + Listem.
2. **Son izlenen kanallar**: geniş kanal kartları. Kartta logo, kanal adı, EPG'de şu an yayında olan program ve ilerleme çizgisi bulunur. OK doğrudan tam ekran oynatır.
3. **Şu an yayında — favori kanallar**: aynı kart. Favori yoksa bu raf, son izlenenlerden sonra gelen ilk kategorinin kanallarını gösterir.
4. Kaldığın yerden devam et, Son eklenen filmler, Yüksek puanlı filmler, Diziler, İzleme listem, koleksiyonlar, İzlediklerinize göre.

- Boş raflar hiç gösterilmez.
- "Canlı TV'ye git" düğmesi kaldırılır.
- Son izlenen kanallar: oynatılan her LIVE öğesinin kimliği, en yenisi başta olmak üzere en fazla 20 kayıt olarak `Preferences` (DataStore) içinde tutulur. Bunun için Room migration'ı gerekmez.

## 4. Canlı TV ekranı

Üç sütun:

- **Kategoriler** (yaklaşık 200 dp). En üstte "Son izlenenler", "Favoriler", "Tümü", ardından sağlayıcı kategorileri ve her birinin kanal sayısı.
- **Kanal listesi** (yaklaşık 420 dp). Satırda numara, logo, kanal adı, şu anki program ve kalan süre ile ince ilerleme çizgisi bulunur. Altında soluk renkte "Sonra: …" satırı yer alır. Liste sanallaştırılmıştır (10.000 kanalda akıcı olmalı).
- **Önizleme** (kalan alan). Odaktaki kanalda 600 ms bekleyince sessiz değil sesli önizleme başlar. Altında program başlığı, saat aralığı, açıklama ve "Tam ekran için OK" ipucu bulunur.

Kumanda:

- OK → tam ekran.
- Uzun OK → mevcut kanal işlemleri (favori, EPG eşleştirme vb.).
- Sağdaki önizlemeye ayrıca odak gerekmez.
- "Yayın çizelgesi" (mevcut EPG ızgarası) kategori sütununun altındaki düğmeyle açılır.

## 5. Tam ekran ve mini rehber

- Kanal değişince ya da OK'a basılınca üst/alt cam bilgi bandı 4 saniye görünür: kanal numarası/adı/logosu, şu anki program, ilerleme, sonraki program.
- **Sol tuş veya OK (bant açıkken)**: yayın durmadan soldan cam **mini rehber** açılır. Solda kategori seçici, sağda kanal listesi bulunur (Canlı TV satır tasarımının aynısı). Yukarı/aşağı ile gezinilir, OK seçilen kanala geçer ve rehberi kapatır. Back ya da 8 saniye işlem yapılmaması rehberi kapatır.
- Yukarı/aşağı ile kanal değiştirme ve mevcut panel/işlem/multi-view/altyazı/motor seçenekleri korunur. Mevcut panel yeni banda ya da "Seçenekler" alt menüsüne taşınabilir.
- Film/dizide (VOD) Apple TV tarzı alt zaman çubuğu, sol/sağ ile ±10 sn sarma, oynatma bitince sonraki bölüm kartı (mevcut özellik görsel olarak yenilenir).

## 6. Filmler / Diziler / Listem / Ara / Detay

- Filmler/Diziler: üstte kategori `Pill` satırı, gelişmiş filtreler tek bir "Filtrele" düğmesinin açtığı cam panelde, altta 6 sütunlu afiş ızgarası.
- Detay: tam ekran arka plan, sol tarafta başlık/meta/özet, düğmeler (Oynat / Devam et / Listem / Fragman), diziler için sezon sekmesi ve yatay bölüm kartları (küçük resim, ilerleme), oyuncu satırı.
- Ara: solda ekran klavyesi ya da metin alanı ve sesli arama düğmesi, sağda sonuçlar türe göre raflar hâlinde (Kanallar, Filmler, Diziler, Programlar).

## Aşamalar (her biri ayrı PR)

| PR | Kapsam |
|---|---|
| A | CI: `test lintDebug assembleDebug` ve Android TV emülatöründe cihaz testleri + ekran görüntüsü artifact'ı |
| B | §1 tasarım sistemi + §2 gezinme |
| C | §3 ana sayfa + son izlenen kanallar |
| D | §4 Canlı TV ekranı |
| E | §5 tam ekran bilgi bandı + mini rehber |
| F | §6 filmler/diziler/listem/ara/detay |

Her PR'da `CinemaJourneyTest`'e benzeyen ve sahte sağlayıcıyla çalışan bir yolculuk testi bulunur. Bu test ilgili ekranların ekran görüntüsünü `getExternalFilesDir(null)/screens/` altına yazar. CI bu görüntüleri artifact olarak yükler.
