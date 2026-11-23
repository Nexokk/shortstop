package com.perso.shortstop

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Instagram :
 *  - le fil d'accueil (onglet "Feed") est autorisé, vidéos lues dans le fil comprises ;
 *  - l'onglet "For you" de l'accueil est un lecteur Reels : on rebascule sur "Feed" ;
 *  - le lecteur Reels plein écran est bloqué (retour), d'où qu'il soit ouvert ;
 *  - exception : le Reel qu'on t'a envoyé en message est autorisé, autant de fois qu'on veut.
 *    Instagram l'affiche avec la barre de l'expéditeur et un champ de réponse ; le Reel suivant
 *    ne les a plus, il est donc bloqué et le retour ramène dans la conversation.
 *
 * Tous les IDs ci-dessous ont été relevés sur l'appareil (uiautomator, novembre 2026).
 */
class InstagramBlocker : Blocker(
    key = "ig",
    packageName = "com.instagram.android",
    bannerText = "Reel bloqué",
) {
    /** Lecteur Reels. */
    private val viewerIds = listOf("clips_viewer_view_pager")

    /** Présents uniquement sur le Reel ouvert depuis une conversation. */
    private val sentInDmIds = listOf(
        "sender_username_or_fullname",
        "reel_viewer_message_composer",
        "reply_bar_edittext",
    )

    /** Accueil à onglets "Feed" / "For you". */
    private val homePagerId = "merged_feed_view_pager"
    private val homeTabTitleId = "tab_title"
    private val feedTabLabels = setOf("feed", "fil", "suivis", "abonnements")

    override fun check(root: AccessibilityNodeInfo): Boolean {
        if (!anyVisible(root, viewerIds)) return false
        if (isHomeForYou(root)) {
            log("onglet For you")
            return true
        }
        if (anyVisible(root, sentInDmIds)) {
            log("Reel reçu en message : autorisé")
            return false
        }
        log("lecteur Reels : bloqué")
        return true
    }

    override fun block(service: AccessibilityService, root: AccessibilityNodeInfo) {
        if (isHomeForYou(root)) {
            // Sur l'accueil, "retour" fermerait Instagram : on revient sur l'onglet "Feed".
            // Les titres d'onglets sont masqués pendant la lecture, d'où le défilement en secours.
            when {
                clickFeedTab(root) -> log("onglet Feed : clic")
                scrollHomeToFeed(root) -> log("onglet Feed : défilement")
                else -> log("impossible de revenir sur Feed")
            }
            return
        }
        super.block(service, root)
    }

    /** Fait défiler l'accueil d'une page vers la gauche ("For you" -> "Feed"). */
    private fun scrollHomeToFeed(root: AccessibilityNodeInfo): Boolean {
        val pager = nodes(root, homePagerId).firstOrNull() ?: return false
        return pager.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    override fun signature(root: AccessibilityNodeInfo): String? {
        val author = nodes(root, "clips_author_username").firstOrNull()?.text?.toString() ?: return null
        val video = nodes(root, "clips_video_container").firstOrNull()?.contentDescription?.toString().orEmpty()
        val caption = nodes(root, "clips_caption_component").firstOrNull()?.let { firstText(it, 0) }.orEmpty()
        return "$author | ${video.take(80)} | ${caption.take(60)}"
    }

    private fun firstText(node: AccessibilityNodeInfo?, depth: Int): String? {
        if (node == null || depth > 4) return null
        (node.text ?: node.contentDescription)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        for (i in 0 until node.childCount) firstText(node.getChild(i), depth + 1)?.let { return it }
        return null
    }

    private fun isHomeForYou(root: AccessibilityNodeInfo) = nodes(root, homePagerId).isNotEmpty()

    private fun clickFeedTab(root: AccessibilityNodeInfo): Boolean {
        val tabs = nodes(root, homeTabTitleId)
        val feed = tabs.firstOrNull { it.text?.toString()?.trim()?.lowercase() in feedTabLabels }
            ?: tabs.firstOrNull()
            ?: return false
        var node: AccessibilityNodeInfo? = feed
        while (node != null && !node.isClickable) node = node.parent
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }
}
