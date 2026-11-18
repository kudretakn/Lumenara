package dev.lumenara.data

import dev.lumenara.catalog.contentKey
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupTest {
    private val password = "güçlü-parola".toCharArray()
    private val source = "source-a"
    private val address = "https://provider.invalid/movie/user/private-password/17.mp4"
    private val id = contentKey(source, address)
    private val feed = contentKey(source, "https://provider.invalid/epg?token=private-token")
    private val data = BackupData(
        listOf(BackupSetting("show_adult", "boolean", "false"),
            BackupSetting("secret_open_password", "string", "private-password"),
            BackupSetting("recent_live", "string", id)),
        listOf(BackupSource(source, "Örnek kaynak", SourceSpec("https://provider.invalid", false, "user", "private-password"))),
        listOf(BackupFeed(feed, source, "https://provider.invalid/epg?token=private-token")),
        listOf(BackupItem(id, source, address, "", providerId = "17", tvgName = "Channel", tvgGroup = "Group")),
        listOf(Mark(id, favorite = true, listed = true, position = 1234, duration = 5678, updated = 999)),
        listOf(Mapping(id, feed, "channel-1")), "private-tmdb-token", listOf(ItemRedirect("old-item", id)),
    )

    private fun rejected(bytes: ByteArray, pass: CharArray = password): IllegalArgumentException {
        val error = runCatching { BackupCodec.decrypt(bytes, pass) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        return error as IllegalArgumentException
    }

    @Test fun roundTripIncludesSecretsSettingsAndSourceBoundState() {
        val bytes = BackupCodec.encrypt(data, password)
        assertEquals(data, BackupCodec.decrypt(bytes, password))
        val text = bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains("private-password"))
        assertFalse(text.contains("provider.invalid"))
        assertFalse(text.contains("private-tmdb-token"))
        val header = JSONObject(text)
        assertEquals(1, header.getInt("version"))
        assertEquals(210_000, header.getInt("iterations"))
        assertEquals("AES-256-GCM", header.getString("cipher"))
        assertEquals(16, Base64.getDecoder().decode(header.getString("salt")).size)
        assertEquals(12, Base64.getDecoder().decode(header.getString("iv")).size)
    }

    @Test fun everyBackupUsesFreshSaltAndNonce() {
        val a = JSONObject(BackupCodec.encrypt(data, password).toString(Charsets.UTF_8))
        val b = JSONObject(BackupCodec.encrypt(data, password).toString(Charsets.UTF_8))
        assertNotEquals(a.getString("salt"), b.getString("salt"))
        assertNotEquals(a.getString("iv"), b.getString("iv"))
        assertNotEquals(a.getString("data"), b.getString("data"))
    }

    @Test fun updateKeysAreExcludedFromEncodedBackupsAndPreviewCounts() {
        val settings = data.settings + BackupSetting("update_o/r", "string", "cached release")
        val withUpdate = data.copy(settings = settings)
        assertEquals(data, BackupCodec.decrypt(BackupCodec.encrypt(withUpdate, password), password))
        assertEquals(BackupPreview(data).summary, BackupPreview(withUpdate).summary)
    }

    @Test fun wrongPasswordDoesNotReturnAnyData() {
        val bytes = BackupCodec.encrypt(data, password)
        assertEquals("Parola yanlış veya yedek dosyası bozuk.", rejected(bytes, "yanlış-parola".toCharArray()).message)
    }

    @Test fun changedCiphertextFailsAuthentication() {
        val header = JSONObject(BackupCodec.encrypt(data, password).toString(Charsets.UTF_8))
        val bytes = Base64.getDecoder().decode(header.getString("data"))
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        header.put("data", Base64.getEncoder().encodeToString(bytes))
        rejected(header.toString().toByteArray(Charsets.UTF_8))
    }

    @Test fun corruptTruncatedAndOversizedFilesAreRejected() {
        rejected("not JSON".toByteArray())
        rejected(byteArrayOf())
        val bytes = BackupCodec.encrypt(data, password)
        rejected(bytes.copyOf(bytes.size / 2))
        rejected(ByteArray(BackupCodec.MAX_FILE_BYTES + 1))
    }

    @Test fun legacyAndFutureVersionsAreRejectedExplicitly() {
        for (version in listOf(0, 2)) {
            val header = JSONObject().put("format", "lumenara-backup").put("version", version)
            assertEquals("Bu yedek sürümü desteklenmiyor.", rejected(header.toString().toByteArray()).message)
        }
    }

    @Test fun changedKdfParametersAreRejectedWithoutUnboundedWork() {
        val header = JSONObject(BackupCodec.encrypt(data, password).toString(Charsets.UTF_8))
        header.put("iterations", Int.MAX_VALUE)
        rejected(header.toString().toByteArray())
    }

    @Test fun shortPasswordsAreRejected() {
        assertTrue(runCatching { BackupCodec.encrypt(data, "12345".toCharArray()) }.isFailure)
    }

    @Test fun invalidSourceReferencesAndSettingsAreRejectedBeforePreview() {
        val invalid = listOf(
            data.copy(items = listOf(data.items.single().copy(source = "another-source"))),
            data.copy(marks = listOf(data.marks.single().copy(position = -1))),
            data.copy(settings = listOf(BackupSetting("show_adult", "boolean", "not a boolean"))),
        )
        invalid.forEach { rejected(BackupCodec.encrypt(it, password)) }
    }
    @Test fun orphanedMarksMappingsFeedsAndRecentEntriesAreRejected() {
        val otherSource = "source-b"
        val otherFeed = contentKey(otherSource, "https://other.invalid/epg")
        val invalid = listOf(
            data.copy(sources = emptyList()),
            data.copy(feeds = listOf(data.feeds.single().copy(source = "missing"))),
            data.copy(items = emptyList()),
            data.copy(marks = listOf(data.marks.single().copy(id = "missing"))),
            data.copy(mappings = listOf(Mapping("missing", feed, "channel"))),
            data.copy(mappings = listOf(Mapping(id, "missing", "channel"))),
            data.copy(sources = data.sources + BackupSource(otherSource, "Other", SourceSpec("https://other.invalid", false)),
                feeds = listOf(BackupFeed(otherFeed, otherSource, "https://other.invalid/epg")),
                mappings = listOf(Mapping(id, otherFeed, "channel"))),
            data.copy(settings = listOf(BackupSetting("recent_live", "string", "missing"))),
        )
        invalid.forEach { rejected(BackupCodec.encrypt(it, password)) }
    }

    @Test fun duplicateIdentitiesAndInvalidSettingTypesAreRejected() {
        val invalid = listOf(
            data.copy(sources = data.sources + data.sources),
            data.copy(items = data.items + data.items),
            data.copy(feeds = data.feeds + data.feeds),
            data.copy(marks = data.marks + data.marks),
            data.copy(mappings = data.mappings + data.mappings),
            data.copy(settings = data.settings + data.settings),
            data.copy(settings = listOf(BackupSetting("secret_test", "boolean", "true"))),
            data.copy(settings = listOf(BackupSetting("number", "double", "NaN"))),
            data.copy(settings = listOf(BackupSetting("list", "strings", "[1]"))),
        )
        invalid.forEach { rejected(BackupCodec.encrypt(it, password)) }
    }

    // Authenticate deliberately malformed payloads so schema checks, rather than GCM, reject them.
    private fun malformedPayload(change: (JSONObject) -> Unit): ByteArray {
        val header = JSONObject(BackupCodec.encrypt(data, password).toString(Charsets.UTF_8))
        val base64 = Base64.getDecoder()
        val keySpec = javax.crypto.spec.PBEKeySpec(password, base64.decode(header.getString("salt")), 210_000, 256)
        val key = javax.crypto.spec.SecretKeySpec(javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(keySpec).encoded, "AES")
        keySpec.clearPassword()
        val aad = "Lumenara|1|PBKDF2-HMAC-SHA256|210000|AES-256-GCM".toByteArray()
        fun cipher(mode: Int) = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, javax.crypto.spec.GCMParameterSpec(128, base64.decode(header.getString("iv"))))
            updateAAD(aad)
        }
        val plain = cipher(javax.crypto.Cipher.DECRYPT_MODE).doFinal(base64.decode(header.getString("data")))
        val json = try { JSONObject(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
        change(json)
        header.put("data", Base64.getEncoder().encodeToString(cipher(javax.crypto.Cipher.ENCRYPT_MODE)
            .doFinal(json.toString().toByteArray())))
        return header.toString().toByteArray()
    }

    @Test fun legacyUpdateKeysAreIgnoredWhenDecodingBackups() {
        val bytes = malformedPayload {
            it.getJSONArray("settings").put(JSONObject().put("name", "update_o/r")
                .put("type", "string").put("value", "foreign cache"))
        }
        assertEquals(data, BackupCodec.decrypt(bytes, password))
    }

    @Test fun olderBackupsWithoutCarryIdentityFieldsRemainReadable() {
        val bytes = malformedPayload { json ->
            json.remove("redirects")
            json.getJSONArray("items").getJSONObject(0).apply {
                remove("providerId"); remove("tvgName"); remove("tvgGroup")
            }
        }
        assertEquals(data.copy(items = listOf(data.items.single().copy(providerId = "", tvgName = "", tvgGroup = "")),
            redirects = emptyList()), BackupCodec.decrypt(bytes, password))
    }

    @Test fun authenticatedMalformedSchemaIsRejectedBeforePreview() {
        val invalid: List<(JSONObject) -> Unit> = listOf(
            { it.remove("items"); Unit },
            { it.getJSONArray("sources").getJSONObject(0).getJSONObject("spec").put("http", "false"); Unit },
            { it.getJSONArray("marks").getJSONObject(0).put("listed", "true"); Unit },
            { it.getJSONArray("marks").getJSONObject(0).put("position", 1.5); Unit },
            { it.getJSONArray("settings").getJSONObject(0).put("value", false); Unit },
        )
        invalid.forEach { rejected(malformedPayload(it)) }
    }

    @Test fun innerVersionIsCheckedWithoutIntegerTruncation() {
        for (version in listOf(2L, 4_294_967_297L)) {
            assertEquals("Bu yedek sürümü desteklenmiyor.", rejected(malformedPayload { it.put("version", version) }).message)
        }
        val header = JSONObject(BackupCodec.encrypt(data, password).toString(Charsets.UTF_8))
        header.put("version", 4_294_967_297L)
        assertEquals("Bu yedek sürümü desteklenmiyor.", rejected(header.toString().toByteArray()).message)
    }

}
