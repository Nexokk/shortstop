package com.perso.shortstop

import android.view.accessibility.AccessibilityNodeInfo

/** YouTube : tout écran Shorts est bloqué. */
class YouTubeBlocker : Blocker(
    key = "yt",
    packageName = "com.google.android.youtube",
    bannerText = "Short bloqué",
) {
    /** IDs de vues du lecteur Shorts. À compléter si YouTube les change (voir le mode debug). */
    private val shortsIds = listOf(
        "reel_recycler",
        "reel_player_page_container",
        "reel_watch_player",
    )

    override fun check(root: AccessibilityNodeInfo): Boolean = anyVisible(root, shortsIds)

    /**
     * YouTube n'expose ni titre ni chaîne. On utilise la position du Short dans la liste,
     * et à défaut sa durée totale (lue sur la barre de progression).
     */
    override fun signature(root: AccessibilityNodeInfo): String? {
        nodes(root, "reel_player_page_container").firstOrNull()?.collectionItemInfo?.let {
            return "position ${it.rowIndex}"
        }
        val timeBar = nodes(root, "reel_time_bar").firstOrNull() ?: return null
        val progress = findDescription(timeBar, " of ", 0) ?: return null
        return "durée ${progress.substringAfter(" of ")}"
    }

    private fun findDescription(node: AccessibilityNodeInfo?, contains: String, depth: Int): String? {
        if (node == null || depth > 4) return null
        node.contentDescription?.toString()?.takeIf { contains in it }?.let { return it }
        for (i in 0 until node.childCount) findDescription(node.getChild(i), contains, depth + 1)?.let { return it }
        return null
    }
}
