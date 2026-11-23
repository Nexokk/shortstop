package com.perso.shortstop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

/**
 * Prévient par notification quand le service d'accessibilité ne tourne plus
 * (désactivé dans les réglages, coupé par le système...).
 *  - quand le service est délié, il programme une vérification 5 s plus tard ;
 *  - une tâche périodique (~15 min, survit au redémarrage) vérifie qu'il est toujours activé.
 */
object Watchdog {
    private const val CHANNEL_ID = "service_status"
    private const val NOTIFICATION_ID = 1
    private const val JOB_ID = 1
    private const val SOON_JOB_ID = 2

    fun isServiceEnabled(ctx: Context): Boolean {
        val expected = ComponentName(ctx, BlockerService::class.java)
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        return splitter.any { ComponentName.unflattenFromString(it) == expected }
    }

    fun schedule(ctx: Context) {
        val scheduler = ctx.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, WatchdogJob::class.java))
            .setPeriodic(15 * 60_000L)
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }

    /**
     * Vérification ponctuelle quelques secondes plus tard. Utilisé quand le service s'arrête :
     * Android tue le processus presque aussitôt, mais la tâche planifiée est conservée par le
     * système et relancera l'app pour afficher la notification.
     */
    fun scheduleSoonCheck(ctx: Context) {
        val job = JobInfo.Builder(SOON_JOB_ID, ComponentName(ctx, WatchdogJob::class.java))
            .setMinimumLatency(5_000L)
            .setOverrideDeadline(15_000L)
            .build()
        runCatching { ctx.getSystemService(JobScheduler::class.java).schedule(job) }
    }

    fun notifyServiceStopped(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "État du blocage", NotificationManager.IMPORTANCE_HIGH)
        )
        val openSettings = PendingIntent.getActivity(
            ctx, 0,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Blocage arrêté")
            .setContentText("Le service Shortstop ne tourne plus. Touche pour le réactiver.")
            .setContentIntent(openSettings)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    fun clear(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

class WatchdogJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        if (Watchdog.isServiceEnabled(this)) Watchdog.clear(this) else Watchdog.notifyServiceStopped(this)
        return false
    }

    override fun onStopJob(params: JobParameters?) = false
}
