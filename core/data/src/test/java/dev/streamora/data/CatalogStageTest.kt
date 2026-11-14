package dev.streamora.data

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class CatalogStageTest {
 private fun item(id:String="id")=Item(id,"source","Title","Group","MOVIE","","https://example.test/v.mp4","title",7,"logo",summary="summary",introStartMs=100,introEndMs=200,backdrop="https://example.test/bg.jpg")
 @Test fun writesAndReadsItemsInBatches() {
  val dir=Files.createTempDirectory("catalog-stage").toFile(); val stage=CatalogStage(dir,"source",7)
  try { stage.write { emit -> emit(item()) }; val rows=mutableListOf<Item>(); stage.read { rows.addAll(it) }; assertEquals(1,rows.size); assertEquals("Title",rows[0].title); assertEquals(100,rows[0].introStartMs); assertEquals("https://example.test/bg.jpg",rows[0].backdrop) } finally { stage.close(); dir.deleteRecursively() }
 }
 @Test fun emptyCatalogFailsClearly() {
  val dir=Files.createTempDirectory("catalog-stage").toFile(); val stage=CatalogStage(dir,"source",1)
  try { assertTrue(runCatching { stage.write { } }.exceptionOrNull() is SourceFailure) } finally { stage.close(); dir.deleteRecursively() }
 }
 @Test fun closeDeletesPendingFile() {
  val dir=Files.createTempDirectory("catalog-stage").toFile(); val stage=CatalogStage(dir,"source",1); stage.write { it(item()) }; stage.close()
  assertFalse(java.io.File(dir,"catalog-import/pending.ndjson").exists()); dir.deleteRecursively()
 }
 @Test fun readRestoresSourceAndGenerationFromStageContext() {
  val dir=Files.createTempDirectory("catalog-stage").toFile(); val stage=CatalogStage(dir,"source-x",99); stage.write { it(item()) }; var row:Item?=null; stage.read { row=it.single() }
  assertEquals("source-x",row!!.source); assertEquals(99,row!!.generation); stage.close(); dir.deleteRecursively()
 }
}
