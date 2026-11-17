package dev.lumenara.data

import androidx.sqlite.db.SupportSQLiteProgram
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class GuidePageQueryTest {
    @Test fun pageStaysBelowAndroidNineBindLimit() {
        val rows = (0 until 200).map { GuideChannel("i$it", "Channel $it", "c$it", source = "s") }
        assertEquals(800, guidePageTargetsQuery(rows).argCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedPageMustBeChunkedBeforeQuerying() {
        guidePageTargetsQuery((0 until 201).map { GuideChannel("i$it", "Channel $it", "c$it") })
    }

    @Test fun exactTitleNameIsNormalizedAndBoundSeparatelyFromSql() {
        val query = guidePageTargetsQuery(listOf(GuideChannel("i", " İÇ ' Haber ", "c", source = "s")))
        val bound = mutableMapOf<Int, String>()
        val program = Proxy.newProxyInstance(SupportSQLiteProgram::class.java.classLoader,
            arrayOf(SupportSQLiteProgram::class.java)) { _, method, args ->
            if (method.name == "bindString") bound[args!![0] as Int] = args[1] as String
            null
        } as SupportSQLiteProgram
        query.bindTo(program)
        assertEquals(listOf("i", "s", "c", "ic ' haber"), (1..4).map { bound[it] })
        assertFalse(query.sql.contains("Haber"))
    }
}
