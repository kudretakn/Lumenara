package dev.lumenara.tv

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.lumenara.data.Preferences
import dev.lumenara.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** History only, like LibraryJourneyTest's seed. Catalog import still uses the real UI. */
class BaselineProfileFixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "dev.lumenara.tv.SEED_BASELINE_HISTORY") return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Stay below the foreground broadcast's ANR budget.
                withTimeout(8_000) {
                    val repo = Repository.get(context)
                    val source = repo.library.snapshot().single { it.name == "Baseline Profile fixture" }
                    val spec = repo.sourceSpec(source.id)
                    check(android.net.Uri.parse(spec.address).host in setOf("localhost", "127.0.0.1", "::1"))
                    val recent = repo.browse(source.id, "LIVE", "BP Spor", "", "", 12).first()
                    val movies = repo.browse(source.id, "MOVIE", "BP Filmler A", "", "", 12).first()
                    check(recent.size == 12 && movies.size == 12)
                    val preferences = Preferences(context)
                    preferences.set("recent_live", recent.joinToString("\n") { it.id })
                    val now = System.currentTimeMillis()
                    movies.forEachIndexed { index, item ->
                        repo.updateMark(item.id) {
                            it.copy(position = 900_000, duration = 5_400_000, updated = now - index)
                        }
                    }
                }
                result.setResultCode(Activity.RESULT_OK)
                result.setResultData("fixture-history-ready")
            } catch (error: Exception) {
                result.setResultCode(Activity.RESULT_CANCELED)
                result.setResultData("fixture-history-failed: ${error.javaClass.simpleName}")
            } finally {
                result.finish()
            }
        }
    }
}
