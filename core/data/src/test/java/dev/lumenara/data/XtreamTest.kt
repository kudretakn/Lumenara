package dev.lumenara.data

import dev.lumenara.catalog.Kind
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class XtreamTest {
 @get:org.junit.Rule val cache = org.junit.rules.TemporaryFolder()

 @Test fun snapshotComparesAllEndpointsAndIgnoresVolatileAccountFields() = MockWebServer().use { s ->
  val x = Xtream(SourceSpec(s.url("/").toString(), true, "u", "p"))
  fun enqueue(clock: Int, changedCategory: Boolean = false) {
   s.enqueue(MockResponse().setBody("""{"user_info":{"auth":1,"max_connections":2,"active_cons":$clock},"server_info":{"timezone":"Europe/Istanbul","timestamp_now":$clock}}"""))
   repeat(6) { index -> s.enqueue(MockResponse().setBody(if (index == 0 && changedCategory) "[{\"category_id\":\"new\"}]" else "[]")) }
  }
  enqueue(1)
  val state = x.prepare(cache.root, FetchState()).use { assertFalse(it.unchanged); it.state }
  enqueue(2)
  x.prepare(cache.root, FetchState.decode(state.encode())).use { assertTrue(it.unchanged) }
  enqueue(3, changedCategory = true)
  x.prepare(cache.root, state).use { assertFalse(it.unchanged) }
  assertEquals(21, s.requestCount)
  assertTrue(cache.root.listFiles()!!.isEmpty())
 }

 @Test fun failedSnapshotRemovesAlreadyDownloadedBodies() = MockWebServer().use { s ->
  s.enqueue(MockResponse().setBody("""{"user_info":{"auth":1}}"""))
  s.enqueue(MockResponse().setBody("[]"))
  s.enqueue(MockResponse().setResponseCode(503))
  val error = runCatching { Xtream(SourceSpec(s.url("/").toString(), true, "u", "p")).prepare(cache.root, FetchState()) }.exceptionOrNull()
  assertTrue(error is SourceFailure)
  assertTrue(cache.root.listFiles()!!.isEmpty())
 }
 @Test fun guideAddressEncodesCredentialsAsQueryParameters() = MockWebServer().use { s ->
  val x=Xtream(SourceSpec(s.url("/root").toString().trimEnd('/'),true,"user name","p&ss")); val url=x.guideAddress().toHttpUrl()
  assertEquals("xmltv.php",url.pathSegments.last()); assertEquals("user name",url.queryParameter("username")); assertEquals("p&ss",url.queryParameter("password"))
 }
 @Test fun episodeParsingSortsSeasonsAndKeepsIntroMarkers() = MockWebServer().use { s ->
  s.enqueue(MockResponse().setBody("""{"episodes":{"2":[{"id":"e2","episode_num":3,"title":"Next","info":{"intro_start_ms":1200,"intro_end_ms":3400}}],"1":[{"id":"e1","title":"Pilot"}]}}"""))
  val episodes=Xtream(SourceSpec(s.url("/").toString(),true,"u","p")).episodes(s.url("episodes").toString(),"Series")
  assertEquals(2,episodes.size); assertEquals(Kind.EPISODE,episodes[0].kind); assertEquals("Sezon 1",episodes[0].group); assertEquals(1200,episodes[1].introStartMs); assertEquals(3400,episodes[1].introEndMs)
 }
 @Test fun anonymousGetSeriesInfoOrdersEpisodesAndReadsDurationAndSeason() = MockWebServer().use { s ->
  s.enqueue(MockResponse().setBody("""{"info":{"name":"Kıyı"},"seasons":[{"season_number":2},{"season_number":1}],"episodes":{"2":[{"id":"205","episode_num":5,"season":2,"title":"Dönüş","container_extension":"mkv","info":{"duration_secs":2400,"movie_image":"https://art.invalid/205.jpg","plot":"Yeni başlangıç"}}],"1":[{"id":"110","episode_num":10,"season":1,"title":"Final","info":{"duration":"00:42:00"}},{"id":"102","episode_num":2,"season":1,"title":"Yol"}]}}"""))
  val rows=Xtream(SourceSpec(s.url("/").toString(),true,"u","p")).episodes(s.url("/player_api.php?action=get_series_info&series_id=10").toString(),"Kıyı")
  assertEquals(listOf(2,10,5), rows.map { dev.lumenara.catalog.episodeName(it.title).episode })
  assertEquals(listOf(1,1,2), rows.map { dev.lumenara.catalog.episodeName(it.title).season })
  assertTrue(rows.last().url.endsWith("/series/u/p/205.mkv"))
  assertEquals(2400000L,rows.last().durationMs); assertEquals(2520000L,rows[1].durationMs)
  assertEquals("Yeni başlangıç",rows.last().summary)
 }
 @Test fun episodeParsingSkipsMalformedEntriesAndUsesDefaults() = MockWebServer().use { s ->
  s.enqueue(MockResponse().setBody("""{"episodes":{"1":[null,{"title":"missing id"},{"id":"ok","title":"Fine"}]}}"""))
  val ep=Xtream(SourceSpec(s.url("/").toString(),true)).episodes(s.url("episodes").toString(),"Show").single()
  assertEquals(-1,ep.introStartMs); assertEquals(-1,ep.introEndMs); assertTrue(ep.url.endsWith("/ok.mp4"))
 }
}
