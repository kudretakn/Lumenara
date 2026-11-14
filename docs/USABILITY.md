# Kullanım kolaylığı (0.4.x)

Kullanıcının önceliği Canlı TV. Bu belge, 0.3.0 arayüz yenilemesinden sonraki kullanım iyileştirmelerini tanımlar. Genel kurallar `docs/UI-REDESIGN.md` ile aynıdır. Yeni bağımlılık eklenmez. Periyodik işler için `wallClockTicks` kullanılır. Room şeması yalnız U3'te değişir.

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

## U2 — Program bulma

- **Ara:** "Programlar" rafı eklenir. Sorgu `Guide` program aramasından gelir (mevcut `instr(search, query)` sorgusu). Kartta program adı, kanal, gün ve saat bulunur. OK: yayın şu an sürüyorsa kanalı tam ekran açar; arşivi varsa arşivden oynatır; yoksa kanalın detayını açar.
- **Canlı TV:** Kategori sütununa "Şu an yayında" filtresi eklenir. Bu filtre EPG'si olan kanalları şu anki programın kalan süresine göre sıralar.

## U3 — Kişiselleştirme

- **Ebeveyn kilidi:** Ayarlar'da 4 haneli PIN tanımlanır ve seçilen kategoriler kilitlenir. Kilitli kategorideki kanal ya da içerik PIN ile açılır. Doğru PIN o oturum boyunca geçerlidir. PIN, Keystore ile şifrelenerek DataStore'da tutulur.
- **Kanal sıralama:** Canlı TV'de uzun OK işlemlerine "Yukarı taşı / Aşağı taşı / Başa al" eklenir. Özel sıra Room'da kaynak ve kanal anahtarıyla tutulur (şema 7→8, otomatik geçiş ve `MigrationTest` güncellenir).
