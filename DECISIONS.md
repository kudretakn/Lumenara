# Lumenara teknik kararları
- Minimum API 26, derleme ve hedef API 36. Yerel JDK 17, SDK 36.0.0 ve Gradle 9.3.1 mevcut. AGP 9.1.0 yerleşik Kotlin desteği ile kullanılır.
- TV Material 1.1.0 kumanda odağını ve seçili durumlarını sağlar. İlk sürüm uyumluluğu Gradle derlemesiyle kontrol edilir.
- İlk açılış hızlı kuruluma, katalog oluşunca sonraki açılışlar ana sayfaya gider.
- Hazır IPTV kaynakları dağıtılmaz. Kimlik bilgisi içerebilen liste ve yayın adresleri hassas veri kabul edilir.
- Güvenilir tvg-id sağlayıcı kapsamında değerlendirilir. İsim tek başına kaynaklar arası içerik birleştirme gerekçesi olamaz.
- Riskler: üreticilerin codec kapasiteleri ve Bluetooth tuş eşlemeleri değişir. Cihaz bulunmadığında oynatma ve odak performansı doğrulanmış sayılmaz.
- Şifrelenmiş katalog anahtarı Android Keystore’daki AES-GCM anahtarıyla sarılır. Anahtar kaybı durumunda kaynakların yeniden eklenmesi gerekir. Yedekleme kapalıdır.
- Telefon kurulumu yalnızca açık kurulum ekranında, yerel Wi-Fi/Ethernet adresinde HTTPS dinler. TV oturum sertifikasının parmak izini gösterir. Tek kullanımlık kod üç dakika veya beş hatalı deneme sonunda geçersiz olur.
- Kaynak başına HTTP izni hem OkHttp yükleyicisini hem oynatıcıları kapsar. VLC’ye aktarılan ağ istekleri aynı istemciden geçer. HTTP üzerinden alınan medya sağlayıcı ile cihaz arasında açık taşınır.
- İzleme arşivi sağlayıcıda açıkça tanımlanmış zaman aralığıyla sınırlıdır. Xtream arşivi için sağlayıcı saat dilimini kullanırız. Canlı geri sarma yalnızca oynatıcı kaydırılabilir bir pencere bildirdiğinde sunulur.
- Yayın APK’sı kişisel RSA anahtarıyla imzalanır. Windows kullanıcı hesabına bağlı DPAPI parolası proje dışında saklanır. Gelecek yayınlar aynı sertifikayla imzalanmalıdır. Debug ve release farklı uygulama kimlikleri kullanır.

## Sürüm denetimi

Bağımlılıklar dinamik sürümler yerine derlenen sürümlere sabitlenmiştir. TV Material 1.1.0, Media3 1.10.1, Room 2.8.4 ve libVLC 3.7.7 kullanılır. Gradle dağıtımının SHA-256 değeri wrapper yapılandırmasında kayıtlıdır. Room Gradle eklentisi debug ve release şemalarını ayrı ara dizinlerde üretir. Bu, eşzamanlı derlemede aynı JSON dosyasının yarım okunmasını önler.

[Android TV Material sürümleri](https://developer.android.com/jetpack/androidx/releases/tv) · [Media3 sürümleri](https://developer.android.com/jetpack/androidx/releases/media3) · [Room sürümleri](https://developer.android.com/jetpack/androidx/releases/room) · [VideoLAN Android paketi](https://central.sonatype.com/artifact/org.videolan.android/libvlc-all/3.7.7) · [TMDB atıf koşulları](https://developer.themoviedb.org/docs/faq)
