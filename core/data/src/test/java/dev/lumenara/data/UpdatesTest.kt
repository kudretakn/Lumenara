package dev.lumenara.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdatesTest {
 @Test fun acceptsValidRepositoryNames() { assertTrue(Updates.validRepo("owner/repo")) }
 @Test fun rejectsDotAndTrailingDotRepositoryNames() {
  for (repo in listOf("o/.", "o/..", "o/repo.")) assertFalse(Updates.validRepo(repo))
 }
 @Test fun rejectsMissingRepositoryPart() { assertFalse(Updates.validRepo("owner")) }
 @Test fun rejectsTraversalRepository() { assertFalse(Updates.validRepo("owner/../repo")) }
 @Test fun rejectsSpacesInRepository() { assertFalse(Updates.validRepo("bad owner/repo")) }
 @Test fun parsesOptionalVPrefixAndMissingComponents() { assertEquals(listOf(1,2,0,0,0,0), Updates.version(" v1.2 ")) }
 @Test fun rejectsMalformedVersion() { assertNull(Updates.version("1.x")) }
 @Test fun rejectsTooManyVersionComponents() { assertNull(Updates.version("1.2.3.4.5")) }
 @Test fun parsesReleaseForNewerVersion() {
  val digest="a".repeat(64); val j=JSONObject().put("tag_name","v2.0").put("body","notes").put("assets",org.json.JSONArray().put(JSONObject().put("name","app.apk").put("browser_download_url","https://github.com/o/r/releases/download/v2.0/app.apk").put("digest","sha256:$digest")))
  val update=Updates.parseRelease(j,"o/r","1.9"); assertNotNull(update); assertEquals(digest,update!!.sha256); assertEquals("notes",update.notes)
 }
 @Test fun ignoresOlderRelease() { assertNull(Updates.parseRelease(JSONObject().put("tag_name","1.0"),"o/r","2.0")) }
 @Test fun ignoresInvalidCurrentVersion() { assertNull(Updates.parseRelease(JSONObject().put("tag_name","2.0"),"o/r","dev")) }
 @Test fun rejectsReleaseWithoutValidApkDigest() {
  val j=JSONObject().put("tag_name","2.0").put("assets",org.json.JSONArray().put(JSONObject().put("name","app.apk").put("browser_download_url","https://github.com/o/r/releases/download/2.0/app.apk").put("digest","bad")))
  assertTrue(runCatching { Updates.parseRelease(j,"o/r","1.0") }.exceptionOrNull() is SourceFailure)
 }
 @Test fun rejectsApkAssetFromAnotherRepository() {
  val j=JSONObject().put("tag_name","2.0").put("assets",org.json.JSONArray().put(JSONObject().put("name","app.apk").put("browser_download_url","https://example.com/app.apk")))
  assertTrue(runCatching { Updates.parseRelease(j,"o/r","1.0") }.exceptionOrNull() is SourceFailure)
 }
}
