## Ortak bağlam (tüm performans ajanları)

Lumenara, Android TV IPTV oynatıcısı. Kotlin, Compose for TV, Room, Media3 ve VLC kullanıyor.

Hedef cihaz zayıf: Mi Box 4, Android 9, 4 çekirdek, 2 GB RAM. Kullanıcının kataloğu 39.682 içerik (Xtream).

Kullanıcı isteği: "Performanstan ve ağdan yana hiçbir sıkıntı yaşamak istemiyorum." Her ekran 1 saniyenin altında açılmalı. Gereksiz ağ trafiği olmamalı.

Önce `CLAUDE.md` ve `docs/USABILITY.md`'nin K bölümünü oku; arayüz yeni değişti.

Paralel çalışan diğer ajanlar ve dokunduğu dosyalar:
- **db:** `LibraryDb.kt`, Room şeması 7→8, `Versions.kt`, `MigrationTest`, `Repository.import` içindeki sürüm anahtarı.
- **images:** `LumenaraApp.kt` (ImageLoader), `AsyncImage` kullanan kart dosyaları, `Transport.kt`'ye yalnız yeni bir görsel istemcisi fonksiyonu.
- **network:** `Transport.read` koşullu istekleri, `Repository.refresh`/`refreshGuides` atlama mantığı, `Guide.kt`/`populateGuide` zaman penceresi, `Xtream.kt`.
- **live:** `LiveComfort.kt`, `Timeline.kt`, `FullscreenOverlay.kt`, `Playback.kt`, `core/player` (Media3 LoadControl, oynatıcıyı yeniden kullanma), yeni `Perf.kt`.

Kendi alanının dışındaki dosyalara yalnız zorunluysa ve en küçük değişiklikle dokun. Değişikliği raporda belirt.

Kurallar:
- Yeni bağımlılık ekleme.
- Gradle çalıştırma; derlemeyi, testleri ve cihaz ölçümünü Claude yapacak.
- Commit ya da push yapma.
- Davranışı değiştirmeden hızlandır.
- Her değişiklik için mümkünse JVM ya da cihaz testi ekle.
- Rapor en fazla 10 satır olsun: değişen dosyalar, beklenen kazanç, risk.
