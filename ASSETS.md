# Lumenara görsel kaynakları

## Başlatıcı bannerı ve simgesi

Logo: design/logo/concept-1-*.svg, Lumenara için üretildi; uygulama içi ad yazılı logo `concept-1-banner.svg`, yalnız sahne ışığı sembolü içeren Android TV bannerı `concept-1-banner-mark.svg`.

`app/src/main/res/drawable-xhdpi/banner.png` (320×180) ve `app/src/main/res/drawable-xxhdpi/banner.png` (480×270), `concept-1-banner-mark.svg` kaynağından `rsvg-convert -w 320 -h 180` ve `rsvg-convert -w 480 -h 270` ile üretildi.

`app/src/main/res/drawable/mark.xml`, `concept-1-mark.svg` kaynağının 48dp×48dp, 512×512 viewport kullanan Android VectorDrawable karşılığıdır: yuvarlatılmış koyu zemin, altın rengi gradyanlı sahne ışığı ve sahne çizgisi.

## Uygulamadaki ortam görseli

`app/src/main/res/drawable-nodpi/lumenara_coast.jpg`, bu görevde yerleşik ImageGen aracıyla oluşturuldu. Kurulum, boş kitaplık ve görsel bulunamayan arka planlarda kullanılır. Bir filme ait afiş veya gerçek yapım bilgisi değildir. Sağlayıcının HTTPS arka planı, ardından eşleşmiş TMDB arka planı önceliklidir.

Üretim istemi:

> Create a single 16:9 cinematic still to be used as original ambient artwork in a premium Turkish home theater app. A weathered remote Art Deco coastal observatory and lighthouse embedded in steep black volcanic cliffs above the northern Aegean Sea at blue hour. Strong composition: the illuminated amber glass lantern and architecture occupy the RIGHT third, around 68% across frame, horizon just below center, the LEFT half is a dark tranquil expanse of sea and fog, clean negative space for later interface typography. One distant ferry slipping along a line of orange sunset reflection. Photoreal anamorphic film photography, poetic fine grain, subtle teal sea and warm burnished amber sunset sky, powerful shadows with legible detail, atmospheric depth, not excessively dark, handsome long shadows and rare golden light. A real location in an imagined thriller. Full bleed, no UI, no lettering, no titles, no borders, no watermark, no logos.

## Yalnızca görsel testte kullanılan afişler

`app/src/androidTest/assets/cinema/tile0.jpg`–`tile5.jpg` aynı görevde yerleşik ImageGen ile oluşturulan 3×2 sayfadan kırpıldı. `coast.jpg` ortam görselinin test kopyasıdır. Bu dosyalar yalnızca test APK’sına girer. CinemaJourneyTest, kurgusal başlıkları ve bilgileri yerel sahte Xtream sunucusundan yükler, test sonunda kaynağı siler. Kullanıcının gerçek kitaplığında örnek filmler oluşturulmaz.

Üretim istemi:

> Create a landscape 3:2 contact sheet of exactly SIX distinct movie poster artworks. Two rows, three equal columns, all six tiles flush with no gutters, margins, frames, text, letters, watermarks or logos. Each tile stands alone and is cropped separately for a TV application visual test. Frame like real photographic movie posters with deliberate visual hierarchy and human subjects, premium 35mm grain. Top left: lonely female astronaut wearing an ivory suit and large helmet against a rust-orange Martian mountain at dawn, visor in profile. Top middle: beautiful ancient caravan moving over a burnt sand dune, tiny riders silhouetted in ochre sunset and dust. Top right: a young female detective in a crimson coat staring through a rain-streaked window at midnight in Istanbul, teal neon bokeh. Bottom left: a white sailing yacht dwarfed by towering teal-blue Antarctic ice formations and blowing snow. Bottom middle: an elegant old sleeper train on a snowy forest railway, moody warm lamp at dusk, dark navy palette. Bottom right: clandestine jazz singer backlit with a single spotlight in a deep burgundy, intimate 1950s club. All 6 original imaginary productions, rich atmospheric color, photographic realism and visually distinct silhouettes. No words or titles anywhere.

Kullanıcının Apple TV / Netflix ekran görüntüleri yerleşim referansı olarak kullanıldı; görüntüler uygulama varlığı olarak kopyalanmadı. Menü simgeleri Compose Canvas ile çizilir. Gerçek oyuncu fotoğrafları yalnızca eşleşmiş TMDB kredisinden gelir; fotoğraf yoksa adın ilk harfi gösterilir.
