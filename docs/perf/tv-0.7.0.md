# 0.7.0 TV ölçümü (Mi Box 4)

Protokol: `docs/plans/rearchitecture/PLAN.md` bölüm 4 ve `docs/perf/common.md`. TV'de uninstall, veri/cache silme, downgrade, instrumentation, kesinti enjeksiyonu ve profil üretimi yapılmaz. Kaynak adresi, kullanıcı adı/parola ve içerik başlıkları loglara/notlara yazılmaz.

## A — referans (mevcut kurulu sürüm, yükseltmeden önce)
1. `adb shell pm path dev.lumenara.tv` ile kurulu APK'yı bul, `adb pull` ile al. versionCode, Android sürümü, ABI ve `apksigner verify --print-certs` sertifika SHA-256'sını kaydet.
2. Aynı kaynak/filtre, 60 Hz, LAN, mevcut önbellek ve not edilmiş termal durumla:
   ```sh
   for i in 01 02 03 04 05; do
     python3 scripts/perf/wp05_capture.py --serial SERIAL --apk installed.apk \
       --output ~/lumenara-olcum/A-cold-$i --mode cold --label 'A versionCode=NN' \
       --certificate-sha256 CERT --catalog-items 39682 \
       --conditions 'same source/filter; 60Hz; LAN; warm disk cache; thermal noted'
   done
   ```
   Aynısını `--mode warm` ve `A-warm-$i` ile tekrarla.
3. 3 devam noktası, favori/Listem sayıları ve ayarları (başlıksız) not al.

Eski sürüm WP05 işaretlerini (`startup_hub_*_draw`) içermez; bu alanlar referansta "ölçülemedi" kalır.

## B — 0.7.0
4. GitHub release `v0.7.0` → `app-armeabi-v7a-release.apk` (Mi Box 4: 32-bit ARM kullanıcı alanı). Sertifika SHA-256 referansla aynı olmalı. `adb install -r`.
5. Güncelleme sonrası ilk açılış (13 → 15 migration dahil): `B-first`, ayrı raporlanır.
6. 5 soğuk + 5 sıcak: `B-cold-01..05`, `B-warm-01..05`; `--label` = v0.7.0 tam git SHA + CI run URL.
7. Kaynaklar açılıyor mu; devam noktaları, favori/Listem, ayarlar A ile aynı mı.

## Rapor
Her klasöre `python3 scripts/perf/wp05_report.py <klasör>`. A/B tablosu: Hub shell/shelves/counts/local/artwork/full draw p50/p95/max, `startup_cpu_diagnostic` (CPU15 değildir), `ui_frames`, APK MB/MiB+SHA, PSS. Ölçülemeyen değer "ölçülemedi" yazılır, tahmin edilmez. CPU15 ve tam-Hub kabulü için harici sched/atrace + kare kanıtı gerekir (`common.md`).
