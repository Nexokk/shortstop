package com.perso.shortstop

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private companion object {
        val BG = Color.parseColor("#0E0F13")
        val CARD = Color.parseColor("#1A1C23")
        val MUTED = Color.parseColor("#8A8F9C")
        val ACCENT = Color.parseColor("#FF5F6D")
        val TRACK_OFF = Color.parseColor("#3A3E49")
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** Une carte par module de blocage. */
    private class Card(
        val key: String,
        val root: LinearLayout,
        val status: TextView,
        val switch: Switch,
        val today: TextView,
        val total: TextView,
        val streak: TextView,
        val unlockedWeek: TextView,
        val unlockChoices: LinearLayout,
        val relock: TextView,
    )

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = Runnable { refresh() }
    private val timeFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    private lateinit var warning: LinearLayout
    private val cards = mutableListOf<Card>()
    private var refreshing = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Notifications (Android 13+) : nécessaires pour être prévenu si le blocage s'arrête.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Watchdog.schedule(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }

        // Titre. Appui long : mode debug (arbre des vues dans Logcat).
        content.addView(text("Shortstop", 28f, Color.WHITE, MEDIUM).apply {
            setOnLongClickListener {
                val on = !Prefs.isDebug(this@MainActivity)
                Prefs.setDebug(this@MainActivity, on)
                Toast.makeText(this@MainActivity, if (on) "Debug activé" else "Debug désactivé", Toast.LENGTH_SHORT).show()
                true
            }
            gravity = Gravity.CENTER_HORIZONTAL
        }, matchWidth(bottom = 32))

        // Alerte si le service d'accessibilité est désactivé
        warning = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(14), dp(14))
            background = rounded(Color.parseColor("#2A1A1E"))
            addView(text("Accessibilité désactivée", 15f, Color.WHITE),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("Activer", 15f, ACCENT, MEDIUM).apply {
                setPadding(dp(12), dp(6), dp(4), dp(6))
            })
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        content.addView(warning, matchWidth(bottom = 24))

        content.addView(buildCard("yt", "YouTube", "Shorts", "com.google.android.youtube").root, matchWidth(bottom = 14))
        content.addView(buildCard("ig", "Instagram", "Reels", "com.instagram.android").root, matchWidth())

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(content)
            // Android 15+ affiche l'app sous les barres système : on ajoute leur hauteur en marge.
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(scroll)
    }

    private fun buildCard(key: String, appName: String, what: String, appPackage: String): Card {
        val status = text("", 13f, MUTED)
        val switch = Switch(this).apply {
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, Color.parseColor("#C9CCD3"))
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ACCENT, TRACK_OFF)
            )
            setOnCheckedChangeListener { _, checked ->
                if (!refreshing) Prefs.setEnabled(this@MainActivity, key, checked)
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Icône de l'app installée sur le téléphone (rien si l'app est absente).
            runCatching { packageManager.getApplicationIcon(appPackage) }.getOrNull()?.let { icon ->
                addView(android.widget.ImageView(this@MainActivity).apply { setImageDrawable(icon) },
                    LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(14) })
            }
            val names = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("$appName $what", 19f, Color.WHITE, MEDIUM))
                addView(status)
            }
            addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(switch)
        }

        val today = text("0", 26f, Color.WHITE, MEDIUM)
        val total = text("0", 26f, Color.WHITE, MEDIUM)
        val streak = text("0", 26f, Color.WHITE, MEDIUM)
        val unlockedWeek = text("0", 26f, Color.WHITE, MEDIUM)
        fun statRow(left: LinearLayout, right: LinearLayout) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, 0)
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statRow(stat(today, "bloqués aujourd'hui"), stat(total, "bloqués au total")))
            addView(statRow(stat(streak, "sans déblocage"), stat(unlockedWeek, "débloquées sur 7 jours")))
        }

        // Déblocage temporaire
        val unlockChoices = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, 0)
            addView(text("Débloquer", 13f, MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val minutesInput = android.widget.EditText(this@MainActivity).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(android.text.InputFilter.LengthFilter(3))
                setText(Prefs.lastMinutes(this@MainActivity, key).toString())
                setSelectAllOnFocus(true)
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = MEDIUM
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rounded(Color.parseColor("#262932"))
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            }
            addView(minutesInput, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(text("min", 14f, MUTED).apply { setPadding(dp(8), 0, dp(4), 0) })
            val start = {
                val minutes = minutesInput.text.toString().toIntOrNull()
                if (minutes == null || minutes !in 1..600) {
                    Toast.makeText(this@MainActivity, "Entre une durée entre 1 et 600 minutes", Toast.LENGTH_SHORT).show()
                } else {
                    minutesInput.clearFocus()
                    getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .hideSoftInputFromWindow(minutesInput.windowToken, 0)
                    Prefs.setLastMinutes(this@MainActivity, key, minutes)
                    Prefs.startUnlock(this@MainActivity, key, minutes)
                }
            }
            minutesInput.setOnEditorActionListener { _, _, _ -> start(); true }
            addView(chip("OK") { start() }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) })
        }
        val relock = chip("Rebloquer maintenant") { Prefs.relockNow(this, key) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(16), dp(18))
            addView(header)
            addView(stats)
            addView(unlockChoices)
            addView(relock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(18) })
        }

        return Card(key, root, status, switch, today, total, streak, unlockedWeek, unlockChoices, relock)
            .also { cards += it }
    }

    override fun onResume() {
        super.onResume()
        Prefs.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        refresh()
    }

    override fun onPause() {
        Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun refresh() {
        warning.visibility = if (isServiceEnabled()) View.GONE else View.VISIBLE

        handler.removeCallbacks(refreshRunnable)
        val now = System.currentTimeMillis()
        var nextExpiry = Long.MAX_VALUE

        refreshing = true
        for (card in cards) {
            val enabled = Prefs.isEnabled(this, card.key)
            val until = Prefs.pauseUntil(this, card.key)
            val unlocked = enabled && until > now
            if (unlocked) nextExpiry = minOf(nextExpiry, until)

            card.switch.isChecked = enabled
            card.status.text = when {
                !enabled -> "En pause"
                unlocked -> "Débloqué jusqu'à " +
                    java.time.Instant.ofEpochMilli(until).atZone(java.time.ZoneId.systemDefault()).format(timeFormat)
                else -> "Bloqués"
            }
            card.status.setTextColor(if (enabled && !unlocked) ACCENT else MUTED)
            card.root.background = rounded(CARD).apply {
                if (enabled && !unlocked) setStroke(dp(1), Color.parseColor("#55FF5F6D"))
            }
            card.unlockChoices.visibility = if (enabled && !unlocked) View.VISIBLE else View.GONE
            card.relock.visibility = if (unlocked) View.VISIBLE else View.GONE
            card.today.text = Prefs.today(this, card.key).toString()
            card.total.text = Prefs.total(this, card.key).toString()
            val days = Prefs.streakDays(this, card.key)
            card.streak.text = if (days <= 1) "$days jour" else "$days jours"
            card.unlockedWeek.text = "${Prefs.unlockedMinutesLast7Days(this, card.key)} min"
        }
        refreshing = false
        // Met l'écran à jour tout seul à la fin d'un déblocage.
        if (nextExpiry != Long.MAX_VALUE) handler.postDelayed(refreshRunnable, nextExpiry - now + 200)
    }

    private fun chip(label: String, onClick: () -> Unit) = text(label, 14f, Color.WHITE, MEDIUM).apply {
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.parseColor("#262932"))
        }
        setOnClickListener { onClick() }
    }

    private fun isServiceEnabled() = Watchdog.isServiceEnabled(this)

    // Helpers d'UI

    private fun text(s: String, size: Float, color: Int, face: Typeface = Typeface.DEFAULT) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            typeface = face
        }

    private fun stat(value: TextView, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(value)
        addView(text(label, 13f, MUTED))
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(18).toFloat()
    }

    private fun wrap(bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(bottom) }

    private fun matchWidth(bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(bottom) }
}
