package dev.lumenara.data

import dev.lumenara.catalog.Kind
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class XtreamTest {
 @Test fun guideAddressEncodesCredentialsAsQueryParameters() = MockWebServer().use { s ->
  val x=Xtream(SourceSpec(s.url("/root").toString().trimEnd('/'),true,"user name","p&ss")); val url=x.guideAddress().toHttpUrl()
  assertEquals("xmltv.php",url.pathSegments.last()); assertEquals("user name",url.queryParameter("username")); assertEquals("p&ss",url.queryParameter("password"))
 }
 @Test fun episodeParsingSortsSeasonsAndKeepsIntroMarkers() = MockWebServer().use { s ->
  s.enqueue(MockResponse().setBody("""{"episodes":{"2":[{"id":"e2","episode_num":3,"title":"Next","info":{"intro_start_ms":1200,"intro_end_ms":3400}}],"1":[{"id":"e1","title":"Pilot"}]}}"""))
  val episodes=Xtream(SourceSpec(s.url("/").toString(),true,"u","p")).episodes(s.url("episodes").toString(),"Series")
  assertEquals(2,episodes.size); assertEquals(Kind.EPISODE,episodes[0].kind); assertEquals("Sezon 1",episodes[0].group); assertEquals(1200,episodes[1].introStartMs); assertEquals(3400,episodes[1].introEndMs)
 }
 @Test fun episodeParsingSkipsMalformedEntriesAndUsesDefaults() = MockWebServer().use { s ->
  s.enqueue(MockResponse().setBody("""{"episodes":{"1":[null,{"title":"missing id"},{"id":"ok","title":"Fine"}]}}"""))
  val ep=Xtream(SourceSpec(s.url("/").toString(),true)).episodes(s.url("episodes").toString(),"Show").single()
  assertEquals(-1,ep.introStartMs); assertEquals(-1,ep.introEndMs); assertTrue(ep.url.endsWith("/ok.mp4"))
 }
}
