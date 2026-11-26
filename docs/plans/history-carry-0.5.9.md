# Kaynak silme / yeniden eklemede geçmiş kurtarma (0.5.9)

Kaynağı düzenleme yolundaki mevcut taşıma korunur. Silme yolunda, silmeden önce kullanıcı durumu (izleme/kaldığı yer, favori, Listem/koleksiyon, son kanallar ve oynatma tercihleri için kimlikler) cihaz Vault'u ile şifrelenir. Eşleşmeyi belirsiz yapabilecek işaretsiz kopyalar, sürüm grubu temsilcileri ve bölüm ebeveynleri de kimlik görünümünde tutulur. Tam katalog, açık veya şifreli yayın adresleri bu kayda konmaz; M3U için yalnız tam yayın URL'sinin SHA-256 özeti tutulur. Başlıklar ve kullanıcı kayıtları da diskte şifrelidir.

## Eşleşme kuralı

Otomatik kurtarma aynı kaynak türü + küçük harfe çevrilmiş sunucu adı + etkin port kapsamında yapılır. Kullanıcı adı/şifre kapsamın parçası değildir; aynı sunucudaki yeni hesap eski geçmişi kurtarabilir. Başka sunucuya sil/ekle ile otomatik taşıma yapılmaz; sunucu değişikliğinde mevcut kaynağı düzenleme yolu kullanılmalıdır. Yerel M3U belgesinde aynı `content://` adresinin özeti kapsamdır.

Her silme anlık görüntüsü bağımsız eşleştirilir. İçerik türü her aşamada aynıdır; öncelik sırası:

1. Xtream akış/dizi/bölüm sağlayıcı kimliği (`providerId`; eski satırlarda bilinen Xtream yollarından çıkarılır).
2. M3U'da tam yayın URL'sinin özeti: kaynak UUID'si değişse de aynı URL aynı özeti verir; URL'deki kimlik bilgisi değişirse bu aşama eşleşmez.
3. M3U `tvg-id` + normalize grup/kategori, ardından `tvg-name` + normalize grup/kategori.
4. Normalize başlık + kategori; film/dizide yıl da eşit olmalı. Bölümde dizi başlığı + sezon/bölüm numarası + kategori kullanılır; yalnız bölüm adıyla tahmin yapılmaz.

Bir anahtarın iki tarafında da tek aday olmalıdır. Güçlü kimlikte belirsizlik varsa daha zayıf ad/başlık anahtarıyla aşılmaz. İşaretsiz kopyalar bu denetimden çıkarılmaz. Sil/ekle kurtarmasının kategori denetimi, mevcut kaynağı düzenleme/yenileme yolunun önceki başlık eşleştirmesini değiştirmez.

## Saklama ve tutarlılık

- Kurtarma penceresi silmeden itibaren 30 gündür; cihaz saati geriye giderse kayıt hemen silinmez. Bu özellik kalıcı yedek yerine geçmez.
- Eşleşen kullanıcı kayıtları taşındıktan sonra anlık görüntüde yalnız bekleyen durum kalır; belirsizlik için gereken kimlik satırları ve ebeveynler son kayıt çözülene kadar korunur. Kısmi/boş katalog eşleşmeyen geçmişi tüketmez; sonraki yenileme tekrar dener.
- İlerleme/süre için `updated` değeri en yeni olan kayıt, favori/Listem için birleşim alınır. Var olan koleksiyon üyeliği korunur. Tercih anahtarları (kaynak UUID'si içeren altyazı gecikmesi dahil) ve Android TV kimlik yönlendirmeleri birlikte taşınır. Daha önceki kaynak düzenlemelerinden kalmış yönlendirmeler de silmeden önce saklanır; yeni içerik bulununca eski TV bağlantıları korunur.
- Başarısız içe aktarma eski katalog/anlık görüntüyü korur. Katalog yazıldıktan sonra kaynak ekleme iptal edilirse, yeni kaynak silinmeden önce kurtarılan durum tekrar saklanır.
- Room yazıları ve DataStore değişiklikleri mevcut şifreli `RestoreTransaction` günlüğü altındadır. Yedek dışa aktarımı kurtarma kaydını içermez; yedek içe aktarımı cihazdaki kaydı kullanmaz veya silmez.
- Şema 13 değişmez. Son kayıtlar taşındıktan sonra mevcut `prepareCatalog` sürüm gruplarını ve türetilmiş katalog verilerini yeniden hazırlar; 0.5.8 katalog fallback sorguları aynen kalır. Açılışa kurtarma taraması eklenmez.

JVM kapsamı: `OrphanHistoryTest`, mevcut `HistoryCarryOverTest` ve `RestoreTransactionTest`. Gradle/Room kod üretimi ve cihaz kabulü lider tarafından yapılır.
