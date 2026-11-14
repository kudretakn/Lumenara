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

## U2 — Program bulma

- **Ara:** "Programlar" rafı eklenir. Sorgu `Guide` program aramasından gelir (mevcut `instr(search, query)` sorgusu). Kartta program adı, kanal, gün ve saat bulunur. OK: yayın şu an sürüyorsa kanalı tam ekran açar; arşivi varsa arşivden oynatır; yoksa kanalın detayını açar.
- **Canlı TV:** Kategori sütununa "Şu an yayında" filtresi eklenir. Bu filtre EPG'si olan kanalları şu anki programın kalan süresine göre sıralar.

## U3 — Kişiselleştirme

- **Ebeveyn kilidi:** Ayarlar'da 4 haneli PIN tanımlanır ve seçilen kategoriler kilitlenir. Kilitli kategorideki kanal ya da içerik PIN ile açılır. Doğru PIN o oturum boyunca geçerlidir. PIN, Keystore ile şifrelenerek DataStore'da tutulur.
- **Kanal sıralama:** Canlı TV'de uzun OK işlemlerine "Yukarı taşı / Aşağı taşı / Başa al" eklenir. Özel sıra Room'da kaynak ve kanal anahtarıyla tutulur (şema 7→8, otomatik geçiş ve `MigrationTest` güncellenir).
