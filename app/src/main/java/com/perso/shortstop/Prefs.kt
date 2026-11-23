package com.perso.shortstop

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/** Préférences, séparées par module de blocage (clé "yt", "ig", ...). */
object Prefs {
    private const val FILE = "shortstop_prefs"
    private const val KEY_DEBUG = "debug"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // YouTube garde les noms de clés d'origine pour conserver ses compteurs.
    private fun name(base: String, key: String) = if (key == "yt") base else "${base}_$key"

    fun isDebug(ctx: Context) = prefs(ctx).getBoolean(KEY_DEBUG, false)
    fun setDebug(ctx: Context, value: Boolean) = prefs(ctx).edit().putBoolean(KEY_DEBUG, value).apply()

    fun isEnabled(ctx: Context, key: String) = prefs(ctx).getBoolean(name("enabled", key), true)
    fun setEnabled(ctx: Context, key: String, value: Boolean) =
        prefs(ctx).edit().putBoolean(name("enabled", key), value).apply()

    /** Déblocage temporaire : heure de fin (System.currentTimeMillis), 0 si aucun. */
    fun pauseUntil(ctx: Context, key: String) = prefs(ctx).getLong("pause_until_$key", 0L)
    fun setPauseUntil(ctx: Context, key: String, until: Long) =
        prefs(ctx).edit().putLong("pause_until_$key", until).apply()

    /** Démarre un déblocage temporaire et l'enregistre dans les stats (série, minutes). */
    fun startUnlock(ctx: Context, key: String, minutes: Int) {
        val durationMs = minutes * 60_000L
        setPauseUntil(ctx, key, System.currentTimeMillis() + durationMs)
        addUnlockedMs(ctx, key, durationMs)
        prefs(ctx).edit().putString("last_unlock_date_$key", LocalDate.now().toString()).apply()
    }

    /** Rebloque avant la fin : on retire des stats le temps non utilisé. */
    fun relockNow(ctx: Context, key: String) {
        val remaining = pauseUntil(ctx, key) - System.currentTimeMillis()
        if (remaining > 0) addUnlockedMs(ctx, key, -remaining)
        setPauseUntil(ctx, key, 0L)
    }

    private fun unlockedKey(key: String, date: LocalDate) = "unlocked_ms_${key}_$date"

    private fun addUnlockedMs(ctx: Context, key: String, deltaMs: Long) {
        val k = unlockedKey(key, LocalDate.now())
        val p = prefs(ctx)
        p.edit().putLong(k, maxOf(0L, p.getLong(k, 0L) + deltaMs)).apply()
    }

    /** Minutes débloquées sur les 7 derniers jours (aujourd'hui compris). */
    fun unlockedMinutesLast7Days(ctx: Context, key: String): Long {
        val today = LocalDate.now()
        val totalMs = (0L..6L).sumOf { prefs(ctx).getLong(unlockedKey(key, today.minusDays(it)), 0L) }
        return (totalMs + 30_000L) / 60_000L
    }

    /**
     * Série : nombre de jours d'affilée sans déblocage, aujourd'hui compris.
     * Débloqué aujourd'hui = 0. Débloqué hier = 1. Jamais débloqué = jours depuis l'installation.
     */
    fun streakDays(ctx: Context, key: String): Long {
        val p = prefs(ctx)
        val today = LocalDate.now()
        val lastUnlock = p.getString("last_unlock_date_$key", null)?.let(LocalDate::parse)
        if (lastUnlock != null) return java.time.temporal.ChronoUnit.DAYS.between(lastUnlock, today)
        val start = p.getString("tracking_start", null)?.let(LocalDate::parse)
            ?: today.also { p.edit().putString("tracking_start", it.toString()).apply() }
        return java.time.temporal.ChronoUnit.DAYS.between(start, today) + 1
    }

    /** Dernière durée de déblocage choisie (minutes), proposée par défaut. */
    fun lastMinutes(ctx: Context, key: String) = prefs(ctx).getInt("last_minutes_$key", 15)
    fun setLastMinutes(ctx: Context, key: String, minutes: Int) =
        prefs(ctx).edit().putInt("last_minutes_$key", minutes).apply()

    fun total(ctx: Context, key: String) = prefs(ctx).getInt(name("blocked_total", key), 0)

    fun today(ctx: Context, key: String): Int {
        val p = prefs(ctx)
        val isToday = p.getString(name("blocked_today_date", key), null) == LocalDate.now().toString()
        return if (isToday) p.getInt(name("blocked_today", key), 0) else 0
    }

    fun recordBlock(ctx: Context, key: String) {
        val p = prefs(ctx)
        val todayStr = LocalDate.now().toString()
        p.edit()
            .putInt(name("blocked_total", key), total(ctx, key) + 1)
            .putInt(name("blocked_today", key), today(ctx, key) + 1)
            .putString(name("blocked_today_date", key), todayStr)
            .apply()
    }
}
