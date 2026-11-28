Lumenara veri katmanı denetimi — 27.11.2026
Taban: ee262f12bb9731e700b49c86c6da8bdb73c59f8e (v0.6.0), Room 13.
Kapsam: import/Room/derived/Repository/backup/Vault/DataStore; belirtilen dört karar belgesi önce okundu.
Yöntem: salt okunur kaynak/şema/test incelemesi; Gradle, Android testi, ağ isteği ve commit yok.
Kanıt kısaltmaları: D=core/data/src/main/java/dev/lumenara/data; C=core/catalog/src/main/kotlin/dev/lumenara/catalog; A=app/src/main/java/dev/lumenara/tv; T=app/src/androidTest/java/dev/lumenara/tv.
SQLite kontrolleri yalnız bellekte, sunucunun SQLite 3.45.1'iyle yapıldı; sadeleştirilmiş sorgu planları Android 9 planı/süre ölçümü değildir.
STATUS'taki 0.5.5: 39.682 içerik, Hub 3,2 sn, ilk 12 sn CPU ~23–24 sn; birleşik 0.6.0 TV sonucu yok. <1 sn ekran, <1,5 sn Hub ve CPU15<5 sn sağlandı denemez.

**1. Mevcut tasarım**
Room kaynak→içerik→mark/collection/mapping zincirini CASCADE ile bağlıyor; içerik kimliği kaynak+URL SHA-256'sı, kaynaklar birbirinden ayrılmış (D/LibraryDb.kt:17–122; C/Playlist.kt:56–59).
items 36 alan ve 13 ikincil indeks taşıyor; kaynak/tür/kategori/sortKey/id indeksleri canlı keyset sayfalamasını, parent/versionKey indeksleri bölüm/sürüm erişimini sağlıyor.
Şema 1→7 otomatik, 7→13 açık geçişler; destructive fallback yok (D/LibraryDb.kt:630–749; D/Repository.kt:61–64).
Xtream altı katalog yanıtını disk dosyalarına indirip özetliyor; JsonReader satır satır okuyor. M3U satır akışı, şifreli NDJSON aşaması, 128 kayıtlık Room upsert ve kaynak kapsamlı prune kullanılıyor.
Yenilemede sürüm grubu, temiz başlık/sıra, yetişkin sınıfı, kategori/Hub sayımı ve kanal numarası hazırlanıyor; eski şema için kalıcı imleçli backfill/fallback var.
EPG: SAX, 128'lik yazım, geçmiş 12 saat/gelecek 3 gün penceresi, görünür kart odaklı sorgular. Ayarlar DataStore'da; hassas değerler Vault ile şifreli.
Yedek v1 katalog yerine kaynaklar ve kullanıcı durumunu taşır; PBKDF2+AES-GCM kullanır. Room/DataStore/TMDB anahtarı geri alma günlüğüyle koordine edilir.

**2. Somut sorunlar (önem etiketleri hedef cihaz için; ölçülmemiş süre iddiası yok)**
B1 [yüksek][performans] Normal film/dizi liste toplamı hazır category_counts/catalog_stats yerine yeniden COUNT yapıyor; minRating=0 bile sürüm uygunluk alt sorgusunu açıyor (D/LibraryDb.kt:555–568; D/CatalogQuery.kt:27,43–65,139–142; A/CatalogPages.kt:113–115).
Plan: tür indeksinden tüm adayları gezme + versionKey ile ilişkili kardeş aramaları; sayım LIMIT'te duramaz. catalogCount ayrıca marks'ı her durumda gözlediğinden ilgisiz mark yazımları da bu işi yineler (D/LibraryDb.kt:220–224). Hazır sayım yalnız semantiği eşleşen filtresiz yolda kullanılmalı.
B2 [yüksek][performans] Önceden normalize edilmiş search, arama indeksi değildir: instr(search,?) B-tree aralığına dönüşmez. Karma arama iki kol, canlı GROUP BY ve son ORDER BY çalıştırır; browseKinds tüm sonuç türlerini ayrıca okur (D/CatalogQuery.kt:23,83–88,115–124,139–147).
Bellek içi planda karma aramanın sadeleştirilmiş film kolu SCAN + TEMP B-TREE; az bulunan/olmayan kelimede LIMIT 80 tüm taramayı kurtarmaz. EPG araması da instr+CASE sıralamasıyla SCAN+TEMP B-TREE üretti (D/Guide.kt:97–101).
B3 [yüksek][doğruluk] Altı saat sonra doğrudan dizi genişletmede aynı bölümün URL/uzantısı değişirse yeni id üretiliyor; yalnız yeni id ve başka sürümdeki mark'lar okunuyor, mevcut ebeveynin eski bölümleri eşleştirilmeden prune ediliyor (D/Repository.kt:954–989; D/SeriesExpansionCache.kt:13–26).
CASCADE eski favori/ilerlemeyi siliyor (D/LibraryDb.kt:88–107,513–514). Şema 13 bellekte aynı ebeveyn+S1E1/eski-yeni id ile yeniden üretildi: eski ve yeni mark sayısı 0. Ayrıca episodes alanı olmayan yanıt boş liste sayılıp aynı silmeye gidebilir (D/Xtream.kt:209–215).
B4 [yüksek][doğruluk] Yedek restore yalnız source+eski URL hash'iyle öğe/ebeveyn buluyor; dışa aktarılan providerId eşleştirmede kullanılmıyor (D/Backup.kt:279–289,297–307). Sağlayıcı adres/uzantı değiştirirse kayıt skipped olur; ilgili favori/geçmiş geri gelmez, recent_live eşleşmeyenleri atar (D/Preferences.kt:186–188).
Kaynak yenilemede bulunan güvenli kimlik/alias taşıma mantığı restore ve doğrudan bölüm yenilemede ortak kullanılmalı; belirsiz/eşleşmeyen kullanıcı durumu kaybolmadan saklanmalı.
B5 [orta][performans] Restore bütün kaynakların ağ indirme, bölüm açma ve katalog hazırlamasını açık Room transaction içinde yapıyor (D/Backup.kt:261–281; D/RestoreTransaction.kt:38–41; D/Repository.kt:445–447).
Bu sırada Room yazma kuyruğu/diğer transaction'lar bekler; WAL'deki bağımsız okumaların tamamı bloklanır denemez. Normal yenilemede de 128'lik gruplar ayrı commit değildir: tüm import+gruplama+derived tek dış transaction'dır (D/Repository.kt:786–856).
B6 [orta][performans] Akış ayrıştırma toplam import belleğini sabitlemiyor: previousRows, incoming, old, eşleme map/set'leri tüm katalog boyutunda; series listesi ayrıca tam Item tutuyor (D/Repository.kt:705–710,716–721,772–779; D/HistoryCarryOver.kt:41–54).
Xtream bölüm yanıtı 64 MiB'ye kadar readText+JSONObject+Listing listesi, ardından distinct/map ile Item listeleri oluşturuyor (D/Xtream.kt:209–244; D/BoundedInput.kt:6; D/Repository.kt:960–963). 300 bin kayıt sınırı bellek bütçesi değildir; 39.682'de OOM olduğu kanıtlanmadı.
B7 [orta][doğruluk] M3U gövdesi ayrıştırılıp URL'ler Vault'a girmeden önce refresh-*.body dosyasına düz yazılıyor; mimarinin “yayın adresleri diske yazılmadan şifrelenir” sözleşmesi bozuluyor (D/Transport.kt:146–176; D/Repository.kt:664–667,741–746,900).
Dosya yalnız normal close/hata yolunda siliniyor; süreç ölünce temizlik çalışmaz (D/Transport.kt:177–179,249–251). Kaynak taramasında başlangıçta bu dosyaları temizleyen yol bulunmadı; app-private cache olması disk üstü şifreleme sağlamaz.
B8 [orta][performans] Gövde tamamen değişmemiş olsa da prepareCatalog sürüm staging'ini silip tüm normalize/resolve/apply/derived işini yeniden yapıyor (D/Repository.kt:678–683,864–889).
VersionBackfill resolve/apply kayıt başına 3–5 DAO çağrısı; grup mark yayılımı temsilci başına ek sorgular yapıyor (D/VersionBackfill.kt:118–144; D/Repository.kt:873–880). Değişmeyen 39.682 katalogda iş sıfıra yakınlanmıyor; yerel TMDB değişikliği için kirli kimlik/sürüm takibi gerekli.
B9 [orta][performans] Derived counts her 250 kayıtta tüm mevcut kategori/sayım tablolarını yeniden okuyup tüm kategori satırlarını upsert ediyor (D/CatalogDerived.kt:95,114–135); maliyet kayıt+değişen kategori yerine parti×kategori sayısıyla büyüyor.
B10 [orta][performans] 12→13 “yalnız DDL” olsa da yedi yeni items indeksi mevcut kataloğu DB açılışında tarayarak kurulur (D/LibraryDb.kt:675–682). Bu iş repository hazır olma/ilk kaynak kararının önündedir (D/Repository.kt:93–96; A/LibraryModel.kt:45–49).
Bu, ana thread'de 40 bin satır backfill yapıldığı iddiası değildir; başlangıç IO'da. STATUS'taki 11→12=16 ms ölçümü 12→13 maliyetini doğrulamaz. İndeksleri körlemesine kaldırmak, INDEXED BY sorgularını kırar.
B11 [orta][performans] EPG ad fallback'i WHERE feed=? AND name=?; epgNames anahtarı (feed,channel,name), ek (feed,name,channel) indeksi yok (D/Guide.kt:46–59; D/GuidePageQuery.kt:20–21; D/LiveNowQuery.kt:26–27).
Plan yalnız feed önekini kullanıyor, COUNT(DISTINCT) için geçici B-tree var: her ad fallback'i feed'in adlarını tarayabilir. Görünür sayfa sayısı sınırlı olsa da bu eksik indeks maliyeti büyütür.
B12 [orta][doğruluk] EPG aramasında LIMIT 400, kanal eşleme/yetişkin görünürlüğünden önce uygulanıyor (D/Guide.kt:97–101; D/GuideStore.kt:40–46; D/GuideResolver.kt:74–79). İlk 400 ham sonuç eşleşmeyen/gizli kanallardaysa sonraki geçerli sonuçlar hiç görünmez.
B13 [düşük][bakım] Repository.kt 1.125 satırda DB kurma/kurtarma, kaynak/EPG import, carry, sürümler, oynatım seçimi, yedek, güncelleme ve WorkManager'ı topluyor (D/Repository.kt:60,70,392,445,650,864,954,1022,1050).
Preferences'ın serbest metin anahtarları ve prefix tabanlı taşıması yeni ayarda veri koruma yükünü artırıyor (D/Preferences.kt:89–94,105–126,175–196). Bunlar yeniden yazım gerekçesi değil; sınırları çıkarma gerekçesi.

**3. İyi olan ve korunacaklar**
Kaynak kapsamlı anahtarlar/prune, upsert'in mark'ları koruması; mevcut PK/FK ilişkileri, parent/versionKey ve sıralı keyset erişimi korunmalı (D/Repository.kt:835–839; D/LiveQuery.kt:24–60).
Canlı sayfa planı kaynak/tür/kategori+(sortKey,id) aralığını indeksten okudu; tüm katalog sıralaması gerekmiyor. Hazır Hub/kategori/kanal numarası, küçük projection ve görünür ID sorguları doğru yön.
Ağ indirme/parse hatasında eski kataloğun transaction rollback ile kalması; conditional fetch/digest, soket iptali, stall ve boyut sınırları korunmalı (D/SourceImport.kt:34–85; D/Transport.kt:117–209).
XMLTV'nin dış entity engeli, 8.192 karakter alan sınırı ve pencere/batch yazımı; sayfa EPG'sinin abonelik bitince bırakılması korunmalı (C/XmlTv.kt:31–35,74–76; D/GuideStore.kt:113–120).
Cihaz anahtarıyla sarılmış yazılım AES veri anahtarı ve GCM; parola yedeği doğrulaması, kurtarma günlüğü+checkpoint ve updater durumunun rollback dışında tutulması korunmalı.
Şema geçmişi/migration testleri, carry belirsizlik kuralları, mark bayraklarının birleşmesi ve 30 günlük yerel kurtarma havuzu önemli veri güvenliği yatırımlarıdır.
Bellekte 12→13 DDL uygulandı: dışa aktarılan 13 ile sütun/FK/indeks tanımları eşleşti. T/MigrationTest.kt:18–36 eski sürümleri; T/SeriesExpansionTest.kt:15–60 aynı URL'li cache/kardeş ilerlemesini kapsıyor; B3'ün URL değişimi burada sınanmıyor.

**4. Hedef tasarım ve karar**
Karar: YENİDEN DÜZENLE — artımlı refactor. Room/SQLite, kaynak izolasyonu ve akışlı importer temeli uygundur; baştan yazım ölçülmüş sorunu çözmeden veri kaybı ve geçiş maliyeti ekler.
Repository ince bir facade olsun: CatalogReader, ImportCoordinator, UserStateCarry, GuideImporter, BackupService ve tipli SettingsStore sınırları; ortak kimlik/eşleme kuralları tek yerde.
Import: şifreli geçici gövde/staging → satır akışı ve disk tabanlı kimlik join'leri → derived çıktılar → kısa atomik yayın. Büyük katalog değişimini atomik tutarken kullanıcı durumunu tek kopyada koruyan generation/staging tasarımı gerekir.
Restore ağdan hazırlığı commit öncesi bitirsin; kısa Room yayını sırasında mevcut dayanıklı Room/DataStore geri alma protokolü korunsun. EPG parse/yazımı da staging üzerinden yayınlanabilsin.
Okuma: hazır sayımlar uygun filtrede doğrudan; kaynak/tür/kategori keyset; küçük kart projection; arama için yenilemede üretilmiş indeks. Alt metin semantiği gerekiyorsa n-gram aday indeksi+instr son doğrulama; FTS tek başına eşdeğer değildir.
EPG ad indeksi ve importta hazırlanmış eşleme; görünürlük/eşleme LIMIT'ten önce. Sürüm/grup/derived yalnız değişen kimlikler veya hesaplama sürümü değişince yenilensin.
Geçiş riski: URL hash/alias, mark/FK ve DataStore anahtarlarını aynı anda kırmamak; mevcut id'leri korumak, eklemeli şema ve yeniden başlatılabilir doldurma kullanmak; destructive migration/sil-yeniden kur yok.
Kabul: eski→yeni tüm şemalar, elektrik/süreç kesintisi, URL/parola/uzantı değişimi ve belirsiz eşleme sonrasında izleme geçmişi, favoriler, kaynaklar ve ayarlar korunmalı; yedek v1 okunmalı.
Mi Box'ta 39.682 Xtream ile soğuk açılış, ilk güncelleme, arama/EPG ve yenileme ölçülsün; <1 sn ekran, Hub<1,5 sn, CPU15<5 sn birlikte aranmalı. Cihaz baseline profile ve kaldırılmış U3 önerilmiyor.
Tahmin: bir geliştiriciyle 15–25 iş günü + 2–3 gün emülatör/TV kabulü; en büyük risk kısa commit için katalog/staging geçişi. Süreler tasarım/uygulama tahmini, doğrulanmış teslim taahhüdü değil.

**5. Etki/efora göre ilk 5 eylem**
1. Hazır liste sayımlarını kullan; filtresiz count gözleminden marks'ı çıkar (B1). Etki yüksek / 1–2 gün; sayım-görüntü semantiği ve yetişkin/sürüm fallback'i korunmalı.
2. EPG (feed,name,channel) indeksini eklemeli migration ile ekle (B11). Etki orta, fallback yoğun listede yüksek / ~0,5–1 gün; Android 9 planını kontrol et.
3. Bölüm yenileme ve backup restore'a ortak güvenli carry+eşleşmeyen kayıt koruması getir (B3–B4); eksik episodes yanıtını hata say. Etki yüksek, veri kaybını önler / 2–4 gün.
4. Arama aday indeksini yenilemede üret; tam sayım/tür taramasını azalt, EPG görünürlüğünü LIMIT önüne taşı (B2,B12). Etki yüksek / 3–5 gün; Türkçe ve gerçek alt metin eşdeğerliğini test et.
5. Şifreli spool+yarım iş temizliği, restore hazırlığını transaction dışına çıkarma, bellek/sorgu bütçesi ve unchanged kirli-kayıt yolu (B5–B9). Etki yüksek / 5–8 gün; atomiklik ve süreç kesintisi testleriyle aşamalı uygula.
