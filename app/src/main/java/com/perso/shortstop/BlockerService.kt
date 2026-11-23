package com.perso.shortstop

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

/**
 * Service d'accessibilité unique qui délègue à un module par app ([Blocker]).
 * Pour ajouter une app : créer un Blocker, l'ajouter à [BLOCKERS]
 * et ajouter son package dans res/xml/accessibility_config.xml.
 */
class BlockerService : AccessibilityService() {

    companion object {
        private const val TAG = "Shortstop"

        val BLOCKERS: List<Blocker> = listOf(YouTubeBlocker(), InstagramBlocker())
        private val BY_PACKAGE = BLOCKERS.associateBy { it.packageName }

        private const val BACK_COOLDOWN_MS = 800L
        private const val CHECK_THROTTLE_MS = 250L
        private const val DEBUG_DUMP_THROTTLE_MS = 2000L
        private const val DEBUG_DUMP_MAX_LINES = 300
        private const val BANNER_DURATION_MS = 2500L
        private const val MILESTONE_BANNER_DURATION_MS = 5000L
        /**
         * Paliers (minutes restantes) signalés pendant un déblocage temporaire.
         * Seuls ceux strictement inférieurs à la durée choisie s'affichent :
         * 30 min de déblocage → 10, 5 et 1 min.
         */
        private val MILESTONES_MIN = listOf(30, 10, 5, 1)
        /** Durée max pour finir la vidéo en cours après un déblocage (Shorts/Reels : 3 min max). */
        private const val GRACE_MAX_MS = 4 * 60_000L
    }

    private var lastBackAt = 0L
    private var lastCheckAt = 0L
    private var lastDumpAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var banner: View? = null
    private val hideBannerRunnable = Runnable { hideBanner() }

    // Vérification différée : un événement ignoré (délai anti-rebond, limitation de fréquence)
    // n'est jamais perdu, il déclenche une vérification dès que possible.
    private var recheckPending = false
    private val recheckRunnable = Runnable {
        recheckPending = false
        checkScreen()
    }

    /** Vidéo qu'on a le droit de finir après la fin d'un déblocage temporaire (par module). */
    private val grace = mutableMapOf<String, String>()
    private val graceStartedAt = mutableMapOf<String, Long>()
    private val expiryRunnable = Runnable { checkScreen() }

    /** Prochain palier programmé : module, heure de fin du déblocage, minutes restantes. */
    private var pendingMilestone: Triple<String, Long, Int>? = null
    /** Dernier palier traité, pour ne jamais l'afficher deux fois. */
    private var lastMilestone: Triple<String, Long, Int>? = null
    private val milestoneRunnable = Runnable { onMilestone() }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val blocker = BY_PACKAGE[event.packageName?.toString()] ?: return
        if (!Prefs.isEnabled(this, blocker.key)) return
        val now = SystemClock.elapsedRealtime()
        val sinceBack = now - lastBackAt
        if (sinceBack < BACK_COOLDOWN_MS) {
            scheduleRecheck(BACK_COOLDOWN_MS - sinceBack + 50)
            return
        }
        // Les événements arrivent en rafale : on limite la fréquence des vérifications.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            now - lastCheckAt < CHECK_THROTTLE_MS
        ) {
            scheduleRecheck(CHECK_THROTTLE_MS)
            return
        }
        checkScreen()
    }

    private fun scheduleRecheck(delayMs: Long) {
        if (recheckPending) return
        recheckPending = true
        handler.postDelayed(recheckRunnable, delayMs)
    }

    private fun checkScreen() {
        val now = SystemClock.elapsedRealtime()
        val sinceBack = now - lastBackAt
        if (sinceBack < BACK_COOLDOWN_MS) {
            scheduleRecheck(BACK_COOLDOWN_MS - sinceBack + 50)
            return
        }
        lastCheckAt = now

        val root = rootInActiveWindow ?: return
        // On regarde l'app réellement à l'écran (évite de faire "retour" sur l'écran d'accueil).
        val blocker = BY_PACKAGE[root.packageName?.toString()] ?: return
        if (!Prefs.isEnabled(this, blocker.key)) return
        blocker.debug = Prefs.isDebug(this)

        if (blocker.debug && now - lastDumpAt > DEBUG_DUMP_THROTTLE_MS) {
            lastDumpAt = now
            dumpTree(blocker.key, root)
        }

        // Déblocage temporaire en cours : on laisse tout passer, et on prévoit
        // une vérification pile à l'heure de fin pour noter la vidéo en cours.
        val until = Prefs.pauseUntil(this, blocker.key)
        val wallNow = System.currentTimeMillis()
        if (until > wallNow) {
            handler.removeCallbacks(expiryRunnable)
            handler.postDelayed(expiryRunnable, until - wallNow + 50)
            scheduleMilestone(blocker.key, until, wallNow)
            if (blocker.debug && blocker.check(root)) blocker.log("débloqué, vidéo : ${blocker.signature(root)}")
            return
        }
        if (until != 0L) {
            // Le déblocage vient de se terminer : la vidéo affichée à cet instant peut être finie.
            // (Seulement si on est dans l'app au moment de la fin, pas en la rouvrant plus tard.)
            Prefs.setPauseUntil(this, blocker.key, 0L)
            val justEnded = wallNow - until < 5_000
            val current = if (justEnded && blocker.check(root)) blocker.signature(root) else null
            if (current != null) {
                grace[blocker.key] = current
                graceStartedAt[blocker.key] = now
            }
            blocker.log("fin du déblocage, vidéo en cours : $current")
        }

        val wantsBlock = blocker.check(root)
        val graceSignature = grace[blocker.key]
        if (graceSignature != null) {
            val signature = blocker.signature(root)
            blocker.log("tolérance : vidéo affichée = $signature, autorisée = $graceSignature")
            val withinLimit = now - (graceStartedAt[blocker.key] ?: 0L) < GRACE_MAX_MS
            if (wantsBlock && withinLimit && (signature == null || signature == graceSignature)) {
                return // toujours la même vidéo (ou en chargement) : on la laisse finir
            }
            grace.remove(blocker.key)
            blocker.log("vidéo suivante ou lecteur quitté : fin de la tolérance")
        }

        if (wantsBlock) {
            lastBackAt = now
            blocker.block(this, root)
            Prefs.recordBlock(this, blocker.key)
            showBanner(blocker.bannerText)
            // Contre-vérification : si une vidéo a été rouverte entre-temps, elle sera rebloquée.
            scheduleRecheck(BACK_COOLDOWN_MS + 50)
        }
    }

    /** Programme le prochain palier (le plus grand encore à venir) pour le déblocage en cours. */
    private fun scheduleMilestone(key: String, until: Long, wallNow: Long) {
        handler.removeCallbacks(milestoneRunnable)
        val remaining = until - wallNow
        val minutes = MILESTONES_MIN.firstOrNull {
            it * 60_000L < remaining && Triple(key, until, it) != lastMilestone
        }
        pendingMilestone = minutes?.let { Triple(key, until, it) }
        if (minutes != null) handler.postDelayed(milestoneRunnable, remaining - minutes * 60_000L)
    }

    /** Heure d'un palier : popup si l'app débloquée est toujours à l'écran et le déblocage inchangé. */
    private fun onMilestone() {
        val milestone = pendingMilestone ?: return
        val (key, until, minutes) = milestone
        pendingMilestone = null
        lastMilestone = milestone
        if (Prefs.pauseUntil(this, key) != until) return // rebloqué ou nouveau déblocage entre-temps
        val blocker = BY_PACKAGE[rootInActiveWindow?.packageName?.toString()]
        if (blocker?.key == key) {
            blocker.log("palier : $minutes min restantes")
            showBanner("Plus que $minutes min de déblocage", MILESTONE_BANNER_DURATION_MS)
        }
        scheduleMilestone(key, until, System.currentTimeMillis())
    }

    /** Bandeau en haut de l'écran (overlay d'accessibilité : aucune permission supplémentaire). */
    private fun showBanner(message: String, durationMs: Long = BANNER_DURATION_MS) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        hideBanner()

        val d = resources.displayMetrics.density
        val view = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((22 * d).toInt(), (12 * d).toInt(), (22 * d).toInt(), (12 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 40 * d
                setColor(Color.parseColor("#E6202329"))
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (64 * d).toInt()
        }

        try {
            wm.addView(view, params)
            banner = view
            handler.postDelayed(hideBannerRunnable, durationMs)
        } catch (e: Exception) {
            Log.w(TAG, "Impossible d'afficher le bandeau", e)
        }
    }

    private fun hideBanner() {
        handler.removeCallbacks(hideBannerRunnable)
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        banner?.let { runCatching { wm.removeView(it) } }
        banner = null
    }

    /**
     * Mode debug : écrit dans Logcat l'arbre des vues visibles
     * (ID, type, texte, description), une ligne par vue.
     */
    private fun dumpTree(key: String, root: AccessibilityNodeInfo) {
        val lines = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40 || lines.size >= DEBUG_DUMP_MAX_LINES) return
            if (node.isVisibleToUser) {
                val id = node.viewIdResourceName?.substringAfter(":id/")
                val cls = node.className?.toString()?.substringAfterLast('.')
                val txt = node.text?.toString()?.replace('\n', ' ')?.take(40)
                val desc = node.contentDescription?.toString()?.replace('\n', ' ')?.take(40)
                if (id != null || txt != null || desc != null) {
                    lines += "  ".repeat(minOf(depth, 15)) +
                        listOfNotNull(id?.let { "#$it" }, cls, txt?.let { "\"$it\"" }, desc?.let { "desc=\"$it\"" })
                            .joinToString(" ")
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        Log.d(TAG, "[$key] ===== écran (${lines.size} vues) =====")
        lines.forEach { Log.d(TAG, "[$key] $it") }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Watchdog.clear(this)
        Watchdog.schedule(this)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Watchdog.scheduleSoonCheck(this)
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideBanner()
        super.onDestroy()
    }
}
