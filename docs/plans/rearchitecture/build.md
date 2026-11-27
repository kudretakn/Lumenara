# Lumenara — derleme ve paketleme denetimi
Tarih: 27 Kasım 2026 · Taban: `ee262f12bb9731e700b49c86c6da8bdb73c59f8e` · Karar: **YENİDEN DÜZENLE**.
Kapsam: İstenen dört belge önce okundu; kaynaklar, önbellekteki AAR, GitHub CI günlükleri ve mevcut APK artifact’leri incelendi. Gradle/test çalıştırılmadı; depo değiştirilmedi.
Sınır: Bu SHA için imzalı release APK bulunamadı; 97 MB bilgisi `docs/STATUS.md:10` beyanıdır, bu denetimde doğrudan ölçülmüş sonuç değildir.

**1. Mevcut tasarım**
Yedi modül: app; Android bağımsız catalog; designsystem; Room/ağ/tercihleri toplayan data; Media3 player; VLC player-vlc; yalnız profil üreticisi baselineprofile (`settings.gradle.kts:7–10`).
Gradle 9.3.1/SHA-256, AGP 9.1.0, JDK 17, min/target API 26/36; AGP’nin yerleşik Kotlin’i ve Compose compiler 2.2.10 uyumlu (`gradle/wrapper/gradle-wrapper.properties:3–4`, `build.gradle.kts:4–9`, `app/build.gradle.kts:13–14`; AGP önbellek POM’u da KGP 2.2.10 bildiriyor).
Sürümler sabit: Compose BOM 2026.05.00, TV Material 1.1.0, Media3 1.10.1, VLC 3.7.7, Room 2.8.4, Hilt 2.59.2, KSP 2.3.6, OkHttp 4.12.0, Coil 2.7.0 (`app/build.gradle.kts:35–66`, `core/player/build.gradle.kts:10–14`, `core/player-vlc/build.gradle.kts:10`).
Release yalnız x86/x86_64 native dosyalarını dışlıyor; ARM32 ve ARM64 birlikte kalıyor. R8/kaynak küçültme etkinleştirilmemiş (`app/build.gradle.kts:19–27`).
Baseline üretimi otomatik değil; üretici bağlı cihaz kullanıyor, yönetilen emülatör tanımlı değil (`app/build.gradle.kts:28–32`, `baselineprofile/build.gradle.kts:30`).
Belgelenmiş komut yerel API36 emülatörünü hedefliyor; CI üretim yapmıyor. Depodaki profil elle yazılmış 8 sınıflık başlangıç kuralları; `startup-prof.txt` yok (`baselineprofile/README.md:3–15,26–32`, `app/src/main/baseline-prof.txt:1–17`).

**APK içinde ne var? — MB = 1.000.000 bayt**
Doğrudan incelenenler: aynı SHA’nın debug APK’sı (229.283.097 bayt, artifact 15223839102) ve aynı bağımlılık/yapılandırmalı önceki `0b77ef4` imzalı release’i (224.491.238 bayt, artifact 15153922457).
Önceki release ile bu tabanın Gradle farkı yalnız sürüm bilgisi ve x86 dışlamasıdır; kaynak kodu/DEX boyutları birebir aynı kabul edilmedi.
| İçerik | APK içindeki ölçüm | Yorum |
|---|---:|---|
| ARM64 native | 55,284 MB | VLC 45,875; C++ 9,290; VLC JNI 0,098; iki küçük AndroidX .so |
| ARM32 native | 40,460 MB | VLC 39,822; C++ 0,555; VLC JNI 0,067; iki küçük AndroidX .so |
| x86 + x86_64 native | 114,384 MB | 0.6.0 release dışlamasının kaldırdığı yük |
| DEX | Önceki release 12,319 MB / debug 17,128 MB | ZIP içi boyut; küçültme sonrası kazanç ölçülmedi |
| Dört Inter OTF | 2,477 MB ham / 0,981 MB ZIP | Dört ağırlık da kullanılıyor |
| VLC Lua + HRTF asset | 0,221 MB ham / ~0,101 MB ZIP | Ana boyut nedeni değil |
Native girdiler iki APK’da da aynı ve ZIP’te sıkıştırılmamış. AAR da aynı VLC byte boyutlarını doğruluyor; uygulamaya ait main/assets yok, test videoları androidTest altında.
Önceki release’den yalnız x86 payload çıkarılırsa ~109,88 MB + ZIP/imza hizalama gideri kalır; bu bir **tahmindir**, 0.6.0 ölçümü değildir. 97 MB için artifact SHA’sı ve MB/MiB birimi kayda alınmalı.

**2. Somut problemler**
- **P1 [yüksek][performans]** Release’de `isMinifyEnabled/isShrinkResources` ve optimize ProGuard yapılandırması yok: `app/build.gradle.kts:19–27`. Kullanılmayan JVM kodu/kaynaklar kalıyor; inlining ve diğer R8 iyileştirmeleri alınmıyor. Native VLC’yi R8 küçültmez; hız kazancı için süre garantisi verilemez. [R8 davranışı](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization/).
- **P2 [yüksek][boyut]** Tek APK iki ARM ABI’sinin toplam 95,743 MB native yükünü taşıyor: `app/build.gradle.kts:22–24`, `core/player-vlc/build.gradle.kts:10`. Tek ABI dağıtımı cihaz başına yaklaşık 40–55 MB payload tasarrufu sağlayabilir; ABI, işlemci adından değil desteklenen Android ABI listesinden seçilmeli.
- **P3 [orta][boyut]** Native strip başarılı değil: release kurulum adımı yalnız platform-tools/build-tools istiyor (`.github/workflows/release.yml:35–37`); [release günlüğü](https://github.com/kudretakn/Lumenara/actions/runs/45896666644/job/140172151368) 21:16:16’da “Unable to strip” diyor. APK ARM64 C++ ELF’inde debug/sembol bölümleri toplam **7.915.227 bayt**; sabit NDK/strip araç zinciri ve çıktı denetimi eksik. Kesin kök neden için strip görevinin ayrıntılı günlüğü gerekir.
- **P4 [yüksek][doğruluk]** Etiket yayını aynı SHA için build+device başarısını veya main soyunu denetlemiyor: `.github/workflows/release.yml:9,70,114–121`. Etiket/sürüm adı uyuşması ve JVM testleriyle doğrudan yayın mümkün; “tüm CI yeşil” sahibi kuralı workflow tarafından uygulanmıyor.
- **P5 [orta][doğruluk]** Yerel imzalayıcı APK imzasının geçerliliğini denetliyor, beklenen kişisel sertifika özetini karşılaştırmıyor: `scripts/sign-release.ps1:47–60`. Yanlış anahtar geçerli APK üretir fakat mevcut TV kurulumunu güncelleyemez; kaldırıp kurma çözümü kullanıcı verisini kaybettirir. CI’daki sabit sertifika kontrolü yerelde de uygulanmalı.
- **P6 [orta][performans]** data modülünde Compose compiler çalışmıyor; UI’ya verilen `Item/LiveCategoryCount` bu modülde tanımlı (`core/data/build.gradle.kts:1–5`, `core/data/src/main/java/dev/lumenara/data/LibraryDb.kt:48–86,124`, `app/src/main/java/dev/lumenara/tv/ChannelCard.kt:75–86`). Stability/metrics yapılandırması yok. Kotlin 2.2.10’da strong skipping varsayılan olduğundan “atlama yok” denemez; yeni eşdeğer nesneler kimlik karşılaştırmasıyla yeniden compose edilebilir. Raporla ölçülmeden geniş stable beyaz listesi eklenmemeli. [Compose kuralları](https://developer.android.com/develop/ui/compose/performance/stability/fix).
- **P7 [orta][bakım]** Oyuncu modülleri bütün data modülüne bağlı; tek ihtiyaç bile sınırı büyütüyor (`core/player/build.gradle.kts:15`, `core/player/src/main/java/dev/lumenara/player/Capacity.kt:6,19`; VLC’de `core/player-vlc/build.gradle.kts:11`, `VlcInput.kt:8`). UI doğrudan Room entity’si kullanıyor; data değişimleri oynatıcı/UI derleme bağımlılığını genişletiyor.
- **P8 [düşük][boyut]** `media3-datasource-okhttp` tanımlı ama üretim kodunda kullanılmıyor (`core/player/build.gradle.kts:14`; gerçek fabrika `core/player/src/main/java/dev/lumenara/player/Players.kt:36–39,64` DefaultHttpDataSource). AAR yalnız ~9,8 KB; kaldırma küçük temizliktir. `core/data/build.gradle.kts:22` room-ktx AAR’ı da boş classes.jar içeriyor; çalışma API’leri runtime’dan geliyor.
- **P9 [orta][bakım]** Sürümler modüllere kopyalanmış; lockfile/dependency-verification metadata yok (`build.gradle.kts:2–9`, `app/build.gradle.kts:37–38,50,64`, `core/data/build.gradle.kts:4,20–22`, `settings.gradle.kts:2–5`). Wrapper checksum’u bağımlılık byte’larını doğrulamaz; sürüm kataloğu + kilitleme/doğrulama tekrarlanabilirliği güçlendirir.
- **P10 [düşük][performans]** Build/configuration cache proje ayarlarında açık değil; wrapper `-all.zip` indiriyor (`gradle.properties:1–4`, `gradle/wrapper/gradle-wrapper.properties:4`). Mevcut setup-gradle bağımlılık önbelleği zaten çalışıyor; bunu yok sayan yeniden kurulum önerilmemeli.
- **P11 [orta][bakım]** Baseline üretimi devre dışıyken altyapı “ölçülmüş açılış optimizasyonu” sanılabilir; başlangıç dosyası yok ve dexLayoutOptimization tek başına kanıt değil (`app/build.gradle.kts:28–32`, `app/src/main/baseline-prof.txt:1`, `baselineprofile/README.md:26–32`). Üretici eksik yolculukları hata yerine log ile atlıyor (`BaselineProfileGenerator.kt:62–95,101–102,140`); mevcut katalog varsa fixture da eklemiyor. Cihazda profil üretme yasağı korunmalı.

**CI süresi ve kapsamı**
[ee262f1 koşusu](https://github.com/kudretakn/Lumenara/actions/runs/46051104251): başarılı; toplam **17:02**, build **4:43**, Gradle adımı **3:50**, device **12:11**, emülatör/test adımı **11:27**.
[Önceki v0.5.9 yayını](https://github.com/kudretakn/Lumenara/actions/runs/45841594143): toplam **5:15**, release işi **5:10**, derleme **4:15**; 0.6.0 yayın süresi olarak sunulamaz.
Device işi build artifact’ini yeniden kullanıyor; yeniden Gradle derlemesi yok (`.github/workflows/android.yml:48,56–64`). Soğuk emülatör `-no-snapshot` ile başlıyor; AVD önbelleği yok (`android.yml:70–84`).
API36 x86_64/debug doğrulaması, Android9/ARM/R8’li release performansı ve uyumluluğunun kanıtı değil (`android.yml:76–84`, `scripts/ci-device-tests.sh:11,25–26`). Snapshot/ABI daraltma/cache kazançları ayrı ölçülmeli.

**3. İyi olan ve korunacaklar**
- Android bağımsız catalog, ayrı iki motor, Compose compiler/Kotlin hizası, KSP ve sabit sürümler; HLS/DASH otomatik format keşfi için korunmalı. Coil SVG (`LumenaraApp.kt:95`), ZXing/TLS eşleme (`Pairing.kt:15–16,32–33`), Media3 UI (`Playback.kt:26`) gerçekten kullanılıyor; bağımlılık tekrarları APK’da otomatik çift kopya anlamına gelmez.
- x86’ların release’den ayrılması; native mmap için mevcut sıkıştırılmamış paketleme, artımlı Room şemaları; async dört Inter ağırlığı (`Typography.kt:12–18`). VLC’yi kaldırmak gerçek cihazdaki codec kapsamını riske atar; font/asset avı öncelik değil.
- CI APK paylaşımı, başarısız test tekrarı ve eksik test denetimi (`scripts/ci-device-tests.sh:33–78`); CI sabit sertifika kontrolü ve geçici anahtar temizliği (`release.yml:80–99`).
- Güncelleyicide hash/paket/sürüm/imza denetimi (`core/data/src/main/java/dev/lumenara/data/Updates.kt:73–101`); kaynak/ayar sırlarının mevcut saklama biçimi. Release’e servis token’ı gömme sahibin kabul ettiği karardır, kusur diye geri açılmadı.

**4. En hızlı, optimize ve düzgün hedef**
**YENİDEN DÜZENLE:** İskelet ve araç zinciri uygun; eksikler paket politikası, release optimizasyonu ve sınırların daraltılmasıyla giderilebilir. Baştan yazmanın performans garantisi yok; veri/codec regresyonu ve doğrulama maliyeti büyük.
Release: R8 + resource shrinking + optimize varsayılan kurallar; yalnız gerekli JNI/callback keep kuralları; mapping ve boyut raporu artifact’i. Debug rahat kalırken aynı optimizasyonlu x86 QA varyantıyla API28/API36 emülatör doğrulaması yapılmalı.
Paket: önce native strip; sonra arm32/arm64 APK’lar ve güncelleyicide ABI-aware asset seçimi. Bugünkü seçim tek APK veya “universal” bekliyor (`Updates.kt:46–55`); yalnız split eklemek güncellemeyi bozar. Geçişte tek ARM universal yedeği ve aynı uygulama/sertifika sürdürülmeli.
Sınırlar: küçük ağ/arka plan sözleşmelerine bağımlı oyuncular; UI için küçük değişmez projeksiyonlar. Mevcut yedi modülü gerekçesiz çoğaltma; Compose raporuyla seçilmiş ucuz modelleri düzelt, `List/**` genel stable ilan etme.
Build: ortak sürüm kataloğu/Android convention’ı, dependency kilidi/doğrulaması, denetlenmiş task/config cache; CI aynı SHA’nın zorunlu işlerini doğruladıktan sonra yayınlasın. Baseline üretimi otomatik açılmasın, TV’de üretilmesin; elle profil ölçülmüş diye sunulmasın.
**Veri koruma:** `dev.lumenara.tv`, kişisel sertifika, artan versionCode, `lumenara.db`, migration zinciri, Keystore alias’ları ve DataStore adları korunmalı (`app/build.gradle.kts:12–16`, `Repository.kt:61–69`). Paket optimizasyonu şema/kimlik değişimi gerektirmez.
Yükseltme doğrulaması eski imzalı sürümün üzerine kurulumla yapılmalı: geçmiş/ilerleme, favoriler/Listem, kaynaklar, ayarlar ve şifreli kimlikler aynı kalmalı; uninstall/data-clear/fallbackToDestructiveMigration çözüm kabul edilmemeli.
Riskler: R8’de JNI/Worker/Room yolları, ABI asset seçimi, stable sözleşmesi ihlali, yanlış sertifika. Tahmin: bir geliştirici **7–10 iş günü**, ardından sahibin Mi Box ölçümü; native özel VLC derlemesi bu tahmine dahil değil.
Kabul: ekranlar <1 sn, Hub tam <1,5 sn, CPU15 <5 sn; 39.682 Xtream içerikli Mi Box 4’te optimize release ile ölçülmeli. STATUS’taki 0.5.5 Hub 3,2 sn/CPU12 23–24 sn ve 0.6.0 A/B CPU −%11 sonuçları CPU15 hedefinin sağlandığını göstermez (`docs/STATUS.md:11,21–22,34`).

**5. Etki/emek sırasıyla ilk beş eylem**
1. **R8 + kaynak küçültme, minimal JNI kuralları ve optimize release smoke** — yüksek performans/boyut; **2–3 gün**. Önce/sonra APK ve Hub/CPU ölç; ölçümsüz kazanç yüzdesi verme.
2. **Native strip’i düzelt; ardından ABI paketleri + güncelleyici geçişi** — yüksek indirme/depolama etkisi; **strip 0,5 gün, ABI 1–2 gün**. ~7,9 MB sembol ve cihaz başına ~40–55 MB ikinci ABI yükü hedeflenebilir.
3. **Yayını aynı SHA’nın yeşil build+device sonucuna bağla; yerel sertifika kontrolünü eşitle** — yüksek doğruluk, **0,5–1 gün**; mevcut imzalı yükseltme/veri koruma denetimi ekle.
4. **Compose compiler raporu çıkar; ölçülen modelleri ve oyuncu/data sözleşmelerini daralt** — orta/yüksek CPU potansiyeli, **2–3 gün**; strong skipping’i koru, yanlış stable sözleşmesinden kaçın.
5. **CI cache/AVD süresini ölçerek azalt; sürüm kataloğu/kilit/doğrulama ekle** — orta bakım ve CI etkisi, **1 gün**; 12 dakikalık device aşamasını öncelikle incele, test kapsamını düşürme.
