package dev.streamora.data

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class BoundedInputTest {
 @Test fun acceptsInputExactlyAtLimit() { assertEquals("1234", bounded(ByteArrayInputStream("1234".toByteArray()),4).reader().readText()) }
 @Test fun rejectsSingleReadPastLimit() { assertTrue(runCatching { bounded(ByteArrayInputStream(byteArrayOf(1,2,3)),2).readBytes() }.exceptionOrNull() is SourceFailure) }
 @Test fun rejectsOverflowAcrossMultipleReads() { val input=bounded(ByteArrayInputStream(byteArrayOf(1,2,3)),2); assertEquals(1,input.read()); assertEquals(2,input.read()); assertTrue(runCatching { input.read() }.exceptionOrNull() is SourceFailure) }
 @Test fun permitsEmptyInputWithZeroLimit() { assertEquals(-1,bounded(ByteArrayInputStream(byteArrayOf()),0).read()) }
 @Test fun rejectsOneByteWithZeroLimit() { assertTrue(runCatching { bounded(ByteArrayInputStream(byteArrayOf(1)),0).read() }.exceptionOrNull() is SourceFailure) }
}
