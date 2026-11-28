package dev.lumenara.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Wp15UpdatesTest {
    private val arm32 = "Lumenara-2.0-armeabi-v7a.apk"
    private val arm64 = "Lumenara-2.0-arm64-v8a.apk"
    private val universal = "Lumenara-2.0-universal.apk"
    private val arm32Hash = "3".repeat(64)
    private val arm64Hash = "6".repeat(64)
    private val universalHash = "a".repeat(64)

    private fun asset(name: String, hash: String = universalHash) = JSONObject()
        .put("name", name)
        .put("browser_download_url", "https://github.com/o/r/releases/download/v2.0/$name")
        .put("digest", "sha256:$hash")

    private fun release(vararg assets: JSONObject) = JSONObject()
        .put("tag_name", "v2.0")
        .put("body", "notes")
        .put("assets", JSONArray().also { array -> assets.forEach { array.put(it) } })

    private fun transitionRelease() = release(
        asset(arm64, arm64Hash), asset(universal), asset(arm32, arm32Hash),
        JSONObject().put("name", "SHA256SUMS.txt"),
    )

    private fun selected(release: JSONObject, vararg abis: String) =
        checkNotNull(Updates.parseRelease(release, "o/r", "1.9", abis.toList()))

    private fun assertRejected(release: JSONObject, vararg abis: String) {
        assertTrue(runCatching { selected(release, *abis) }.exceptionOrNull() is SourceFailure)
    }

    @Test fun miBox32BitAndroidOn64BitCpuSelectsArm32() {
        // The CPU's 64-bit capability is intentionally absent from Android's ABI list.
        val update = selected(transitionRelease(), "armeabi-v7a", "armeabi")
        assertTrue(update.url.endsWith("/$arm32"))
        assertEquals(arm32Hash, update.sha256)
        assertFalse(update.url.contains("arm64"))
    }

    @Test fun arm64AndroidSelectsArm64BeforeCompatibleArm32() {
        val update = selected(transitionRelease(), "arm64-v8a", "armeabi-v7a", "armeabi")
        assertTrue(update.url.endsWith("/$arm64"))
        assertEquals(arm64Hash, update.sha256)
    }

    @Test fun androidPreferenceOrderWinsOverAssetOrder() {
        for (fixture in listOf(transitionRelease(), release(asset(arm32), asset(arm64), asset(universal)))) {
            assertTrue(selected(fixture, "armeabi-v7a", "arm64-v8a").url.endsWith("/$arm32"))
            assertTrue(selected(fixture, "arm64-v8a", "armeabi-v7a").url.endsWith("/$arm64"))
        }
    }

    @Test fun missingPreferredAbiTriesNextSupportedAbiBeforeUniversal() {
        val update = selected(release(asset(universal), asset(arm32)), "arm64-v8a", "armeabi-v7a")
        assertTrue(update.url.endsWith("/$arm32"))
    }

    @Test fun missingCompatibleAbiUsesUniversalFallback() {
        val update = selected(release(asset(arm64), asset(universal)), "armeabi-v7a", "armeabi")
        assertTrue(update.url.endsWith("/$universal"))
        assertEquals(universalHash, update.sha256)
    }

    @Test fun emptyAndroidAbiListUsesUniversalFallback() {
        assertTrue(selected(transitionRelease()).url.endsWith("/$universal"))
    }

    @Test fun legacySingleApkStillUpdatesArm32AndArm64() {
        for (abis in listOf(arrayOf("armeabi-v7a", "armeabi"), arrayOf("arm64-v8a", "armeabi-v7a"))) {
            val update = selected(release(asset("Lumenara-2.0.apk")), *abis)
            assertTrue(update.url.endsWith("/Lumenara-2.0.apk"))
            assertEquals(universalHash, update.sha256)
        }
    }

    @Test fun singleExplicitCompatibleAbiApkIsAccepted() {
        assertTrue(selected(release(asset(arm32)), "armeabi-v7a").url.endsWith("/$arm32"))
    }

    @Test fun singleExplicitIncompatibleAbiApkIsRejected() {
        assertRejected(release(asset(arm64)), "armeabi-v7a", "armeabi")
        assertRejected(release(asset(arm32)), "arm64-v8a")
    }

    @Test fun unmatchedMultipleApksWithoutUniversalAreRejected() {
        assertRejected(release(asset("app-x86.apk"), asset(arm64)), "armeabi-v7a")
    }

    @Test fun ambiguousPreferredAbiIsRejectedWithoutFallingBack() {
        assertRejected(release(asset(arm64), asset("other-arm64-v8a.apk"), asset(arm32), asset(universal)),
            "arm64-v8a", "armeabi-v7a")
    }

    @Test fun ambiguousUniversalFallbackIsRejected() {
        assertRejected(release(asset(arm64), asset(universal), asset("other-universal.apk")), "armeabi-v7a")
    }

    @Test fun abiAssetSuffixIsCaseInsensitive() {
        assertTrue(selected(release(asset("Lumenara-2.0-ARMEABI-V7A.APK")), "armeabi-v7a")
            .url.endsWith("/Lumenara-2.0-ARMEABI-V7A.APK"))
    }

    @Test fun selectedApkMustHaveItsOwnValidDigest() {
        for (hash in listOf("", "bad", "a".repeat(63))) {
            assertRejected(release(asset(arm32, hash), asset(universal)), "armeabi-v7a")
        }
    }

    @Test fun compatibleAssetOutsideRepositoryOrTagCannotBeSelected() {
        for (url in listOf(
            "https://github.com/other/repo/releases/download/v2.0/$arm32",
            "https://github.com/o/r/releases/download/v3.0/$arm32",
            "https://example.com/$arm32",
        )) {
            val fixture = release(asset(arm32).put("browser_download_url", url), asset(universal))
            assertTrue(selected(fixture, "armeabi-v7a").url.endsWith("/$universal"))
            assertRejected(release(asset(arm32).put("browser_download_url", url)), "armeabi-v7a")
        }
    }

    @Test fun abiSelectionStillRequiresNewerReleaseVersion() {
        for (current in listOf("2.0", "2.1", "dev")) {
            assertEquals(null, Updates.parseRelease(transitionRelease(), "o/r", current, listOf("armeabi-v7a")))
        }
    }

    @Test fun preWp15ApiSelectorCanUpgradeFromThreePublishedApks() {
        // Frozen pre-WP15 selection contract: protects clients already installed on TVs.
        val fixture = transitionRelease()
        val assets = fixture.getJSONArray("assets")
        val prefix = "https://github.com/o/r/releases/download/${fixture.optString("tag_name")}/"
        val targets = (0 until assets.length()).mapNotNull(assets::optJSONObject).filter {
            it.optString("browser_download_url").startsWith(prefix, true) &&
                it.optString("name").endsWith(".apk", true)
        }
        val legacyChoice = checkNotNull(targets.singleOrNull()
            ?: targets.singleOrNull { it.optString("name").contains("universal", true) })
        assertEquals(universal, legacyChoice.getString("name"))
        assertEquals(universalHash, Updates.digest(legacyChoice))
    }
}
