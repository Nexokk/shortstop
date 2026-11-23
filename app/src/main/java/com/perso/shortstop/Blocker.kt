package com.perso.shortstop

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Un module de blocage pour une app donnée. Chaque module est indépendant :
 * sa propre logique, son propre interrupteur et ses propres compteurs (via [key]).
 */
abstract class Blocker(
    /** Identifiant court, utilisé pour les préférences (interrupteur, compteurs). */
    val key: String,
    /** Package Android de l'app surveillée. */
    val packageName: String,
    /** Texte du bandeau affiché après un blocage. */
    val bannerText: String,
) {
    var debug = false

    /** Examine l'écran courant. Retourne true s'il faut bloquer. */
    abstract fun check(root: AccessibilityNodeInfo): Boolean

    /**
     * Identifie la vidéo courte affichée (auteur, légende...), ou null si inconnue.
     * Sert à laisser finir la vidéo en cours à la fin d'un déblocage temporaire.
     */
    open fun signature(root: AccessibilityNodeInfo): String? = null

    /** Effectue le blocage. Par défaut : bouton retour. */
    open fun block(service: AccessibilityService, root: AccessibilityNodeInfo) {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    protected fun nodes(root: AccessibilityNodeInfo, id: String): List<AccessibilityNodeInfo> =
        root.findAccessibilityNodeInfosByViewId("$packageName:id/$id").filter { it.isVisibleToUser }

    protected fun anyVisible(root: AccessibilityNodeInfo, ids: List<String>): Boolean =
        ids.any { nodes(root, it).isNotEmpty() }

    fun log(msg: String) {
        if (debug) Log.d("Shortstop", "[$key] $msg")
    }
}
