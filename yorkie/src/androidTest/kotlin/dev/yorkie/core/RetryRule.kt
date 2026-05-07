package dev.yorkie.core

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Re-runs a failed instrumented test up to [retryCount] additional times.
 *
 * Yorkie integration tests run against a docker server on a slow CI emulator
 * where occasional cold-cache delays in the unified Watch RPC cause shifting
 * presence-event flakes that do not reproduce locally. The retry compensates
 * for that environmental variance without masking real regressions: a real bug
 * fails every attempt.
 */
class RetryRule(private val retryCount: Int = 2) : TestRule {

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                var lastFailure: Throwable? = null
                val totalAttempts = retryCount + 1
                for (attempt in 1..totalAttempts) {
                    try {
                        base.evaluate()
                        if (attempt > 1) {
                            val name = description.displayName
                            System.err.println(
                                "$name: passed on retry $attempt/$totalAttempts",
                            )
                        }
                        return
                    } catch (t: Throwable) {
                        lastFailure = t
                        if (attempt < totalAttempts) {
                            val name = description.displayName
                            val cls = t::class.simpleName
                            System.err.println(
                                "$name: attempt $attempt/$totalAttempts " +
                                    "failed ($cls: ${t.message}); retrying",
                            )
                        }
                    }
                }
                throw lastFailure!!
            }
        }
}
