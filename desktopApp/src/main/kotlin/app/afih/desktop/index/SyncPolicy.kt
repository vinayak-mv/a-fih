package app.afih.desktop.index

import app.afih.model.TelegramSource

internal fun syncDue(source: TelegramSource, run: SyncRun, now: Long, firstSeen: Long, launched: Boolean,
    latestId: Long, checkpoint: Long, pending: Boolean, cooldown: Long): Boolean {
    if(now < maxOf(run.retryAt,cooldown)) return false
    val launch = !launched && source.scanOnLaunch
    val interval = source.scanIntervalMinutes * 60_000L
    val due = interval > 0 && now - (run.lastSuccess.takeIf { it > 0 } ?: firstSeen) >= interval
    val retry = (source.automaticUpdates || interval > 0 || source.scanOnLaunch) && run.failures > 0
    return launch || due || retry || (source.automaticUpdates && (pending || latestId > checkpoint))
}
