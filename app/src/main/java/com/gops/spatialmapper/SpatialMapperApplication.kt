package com.gops.spatialmapper

import android.app.Application
import android.util.Log
import com.gops.spatialmapper.data.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "SpatialMapperApp"

/**
 * Process-scoped startup work. Currently one job: backfilling
 * [com.gops.spatialmapper.data.SessionEntity.areaSquareMeters] for rows saved before area
 * calculation existed.
 *
 * WHY HERE, and not the two obvious alternatives:
 *
 *  - Not a Room [androidx.room.RoomDatabase.Callback.onOpen] hook. onOpen runs inside Room's open
 *    path, on whichever thread first touched the database, and blocks every query behind it until it
 *    returns — including the history screen's Flow. Reading every arealess row and writing each one
 *    back from there would stall the first frame that needs data, and re-entrant DAO calls during
 *    open are a well-known way to deadlock.
 *  - Not WorkManager. It isn't a dependency, and it would be the wrong shape anyway: this is a
 *    seconds-long local computation that should be finished by the time the operator reaches the
 *    History tab, not a deferrable constrained background job surviving reboots.
 *  - Not [MainActivity.onCreate] with lifecycleScope. It would re-run on every activity recreation
 *    (rotation is locked out, but not process-warm relaunch or a theme change), and the work has
 *    nothing to do with any one screen's lifetime.
 *
 * So: once per process, on [Dispatchers.IO], in a scope that intentionally outlives every Activity.
 * [SupervisorJob] so a future second startup task can't be cancelled by this one failing, and the
 * whole launch is wrapped — a broken backfill must never stop the app from starting.
 *
 * The pass is idempotent and self-terminating (see [SessionRepository.backfillMissingAreas]), so
 * running it on every cold start costs one indexless SELECT that matches nothing once it has caught
 * up. That is deliberately cheaper than tracking a "migration done" flag that could disagree with
 * what is actually in the table.
 */
class SpatialMapperApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            runCatching { SessionRepository.get(this@SpatialMapperApplication).backfillMissingAreas() }
                .onSuccess { count ->
                    if (count > 0) Log.i(TAG, "Startup area backfill wrote $count session row(s)")
                    else Log.d(TAG, "Startup area backfill: nothing to do")
                }
                .onFailure { Log.e(TAG, "Startup area backfill failed; areas stay null", it) }
        }
    }

    companion object {
        /**
         * Process-lifetime scope for work that must outlive whatever started it.
         *
         * Two callers, and both need exactly that guarantee. The startup area backfill has no
         * Activity to belong to. Saving a finished survey is launched from a composable, and
         * `rememberCoroutineScope()` would be the obvious choice there — except it is cancelled the
         * moment that composable leaves composition, so an operator who taps End survey and
         * immediately switches tabs would silently lose the walk. A save that must not be cancelled
         * by navigation cannot live in a scope tied to navigation.
         *
         * A companion val rather than an instance field so callers reach it without casting
         * `applicationContext`; it is a process singleton either way. [SupervisorJob] so one failing
         * job cannot take the other down.
         */
        val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
