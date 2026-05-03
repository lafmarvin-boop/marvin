package com.marvin.assistant.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * AccessibilityService used to *read* and *click* in apps that don't expose
 * any public API (FamilyWall, Ecovacs Home) and — read-only — bank apps.
 *
 * Garde-fous:
 *  - On limite [packageNames] dans res/xml/accessibility_service_config.xml,
 *    donc Marvin ne reçoit JAMAIS d'événements depuis d'autres apps.
 *  - Pour les apps bancaires, on ne fait que de la lecture (jamais de
 *    performAction(ACTION_CLICK) sur des boutons de virement, etc.).
 *
 * Comment l'utiliser depuis [com.marvin.assistant.actions]:
 *  1. Lance l'app cible via un Intent.
 *  2. Pose un "pending action" (cf. [pendingAction]) que ce service exécute
 *     dès que la fenêtre attendue apparaît.
 */
class MarvinAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Accessibility service connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val action = pendingAction ?: return
        if (action.targetPackage != pkg) return
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return
        try {
            if (action.run(root)) {
                pendingAction = null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Pending action failed", t)
            pendingAction = null
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * A pending UI automation. [run] is invoked on every accessibility event
     * from [targetPackage]; return true once the action is done so we stop
     * trying. The function MUST be idempotent and quick (returns immediately
     * if the target node isn't ready yet).
     */
    class PendingAction(
        val targetPackage: String,
        val run: (AccessibilityNodeInfo) -> Boolean
    )

    companion object {
        private const val TAG = "MarvinA11y"

        @Volatile
        private var instance: MarvinAccessibilityService? = null

        @Volatile
        var pendingAction: PendingAction? = null

        fun isEnabled(): Boolean = instance != null

        /** Helpers used by action classes. */
        fun findByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
            val matches = root.findAccessibilityNodeInfosByText(text)
            return matches?.firstOrNull()
        }

        fun findByViewId(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
            val matches = root.findAccessibilityNodeInfosByViewId(viewId)
            return matches?.firstOrNull()
        }

        fun click(node: AccessibilityNodeInfo): Boolean =
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
