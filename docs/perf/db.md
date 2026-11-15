## Görev: db — veritabanı sorguları ve indeksler

Sorun: kategori seçince listenin gelmesi Mi Box'ta birkaç saniye sürüyor. 30 bin filmde 120 kartlık ızgara 5,4 sn sürüyor.
- `items` tablosunda yalnız `Index("source")` ve `Index("kind","title")` var.
- `browseRows` her çağrıda `GROUP BY CASE ... HAVING items.id=MIN(items.id)` ve `ORDER BY title` yapıyor. Bu yüzden indeks kullanılamıyor, tablonun tamamı taranıp sıralanıyor.
- Sürüm birleştirme (`mergeVersions`) her listede Kotlin'de yeniden hesaplanıyor ve SQL'de `VERSION_HEADROOM = 4` kat fazla satır okunuyor.

Yapılacaklar:
1. `Item`'a `versionKey: String` sütunu ekle (varsayılan '').
   - İçe aktarmada (`Repository.import` → `item(...)` / `writeBatch`) mevcut `versionGroups` kuralıyla aynı anahtarı hesapla. Film ve dizide anahtar: `source|kind|tmdb:<id>` ya da normalize başlık/yıl; diğer türlerde `id`.
   - Temsilci seçimini de içe aktarmada yap: grubun temsilcisinde `isRepresentative = 1` olsun (yeni sütun). Birleştirilmiş başlık ve yıl `displayTitle` ve `displayYear` sütunlarına yazılsın; ya da mevcut `title` korunup ayrı sütun eklensin, sen seç.
2. Room şeması 7→8.
   - `Migration(7,8)`: yeni sütunları ve indeksleri ekle. Mevcut kayıtlar için sürüm anahtarını doldurmak gerekir; en basit güvenli yol, geçişte sütunları varsayılanla ekleyip bir sonraki yenilemeye kadar sorguların eski satırlarda da doğru çalışmasını sağlamak.
   - Örnek: `isRepresentative` varsayılan 1 olsun; böylece eski satırlar birleştirilmeden görünür. Ya da geçişten sonra ilk açılışta arka planda tek seferlik hesaplama yap; sen seç ve gerekçesini yaz.
   - `core/data/schemas/.../8.json` üretilecek; Claude derlerken oluşacak, sen elle yazma.
   - `MigrationTest`'i 7→8 için güncelle.
3. İndeksler (Room `@Entity indices`):
   - `(source, kind, category, title)`
   - `(kind, isRepresentative, title)`
   - `(parent)`
   - `(tvgId)` gerekiyorsa
   - Kullanılmayan indeks ekleme. Her sorgunun planını düşün; `EXPLAIN QUERY PLAN` mantığıyla yorum satırında belirt.
4. `browseRows`'u yeniden yaz:
   - Film ve dizide `isRepresentative=1` filtresi kullan, GROUP BY olmasın. Kotlin `mergeVersions` yalnız görüntü başlığı için gerekmiyorsa kalksın, `VERSION_HEADROOM` kalksın.
   - LIVE'da çok kaynaklı (`source=''`) tvgId tekilleştirmesi gerekiyorsa yalnız o durumda ayrı sorgu kullan. Tek kaynakta GROUP BY olmasın.
   - `shelf`, `query`, `year`, `genre`, `language`, `minRating` ve `hidden` (K'deki +18 listesi) davranışları birebir korunsun.
   - `instr(search, :query)` aramasının indeks kullanmadığını kabul et; aramada LIMIT erken bitirsin.
5. Diğer film/dizi sorgularını da `isRepresentative` ile sadeleştir: `homeFeaturesRows`, `continueWatchingRows`, `suggestionsRows`, `membersRows`, `categories`, kategori sayıları.
6. Testler:
   - `LargeCatalogTest` hâlâ geçmeli. Sürelerin belirgin düşmesi beklenir; logları koru.
   - Sürüm birleştirme cihaz testleri değişmeden geçmeli: `AudioSubtitleTest`, `CinemaJourneyTest`.
   - Sürüm anahtarı hesaplaması için JVM testi ekle (`core/data/src/test`, `VersionsTest` genişletilebilir).
