package dev.lumenara.tv

import android.util.Log
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class RetryRule(private val attempts: Int = 2) : TestRule {
    init {
        require(attempts > 0) { "attempts must be positive" }
    }

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                for (attempt in 1..attempts) {
                    try {
                        base.evaluate()
                        return
                    } catch (failure: Throwable) {
                        if (attempt == attempts) throw failure
                        Log.w(
                            "LumenaraRetry",
                            "RETRY ${description.className}.${description.methodName} attempt=$attempt",
                            failure,
                        )
                    }
                }
            }
        }
}
