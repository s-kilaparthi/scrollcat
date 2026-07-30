package com.example.scrollcat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Injects real swipe gestures into the app underneath the cat.
 * The user must enable this manually in Settings > Accessibility.
 * Also supports finding a focused editable field for voice dictation insertion.
 */
class CatAccessibilityService : AccessibilityService() {

    companion object {
        var instance: CatAccessibilityService? = null
            private set
        private const val TAG = "ScrollCat"
        private const val SWIPE_DURATION_MS = 350L
        private const val SWIPE_DURATION_LONG_MS = 80L

        /** Suppress incoming screen auto-translate after outgoing voice/dictation text is produced. */
        fun suppressIncomingScreenTranslate(cooldownMs: Long = 5000L) {
            instance?.suppressIncomingTranslateUntilMs =
                System.currentTimeMillis() + cooldownMs
        }
    }

    /**
     * Identity of an editable field captured before voice recognition starts.
     * Used to re-resolve the node after recognition if input focus was briefly stolen.
     */
    data class EditableTargetSnapshot(
        val packageName: String?,
        val viewIdResourceName: String?,
        val className: String?,
        val boundsInScreen: Rect,
        val textBefore: String,
        val selectionStart: Int,
        val selectionEnd: Int
    )

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isGestureInProgress = false
    private var lastTranslatedText = ""
    private val translationCooldownMs = 5000L
    private var lastTranslationTime = 0L
    private var lastReactedPackage = ""
    private var lastReactedTime = 0L
    private val REACTION_DEBOUNCE_MS = 2000L // 2 seconds

    /**
     * After Smart Voice / dictation writes outgoing text, block incoming screen auto-translate
     * so compose-field [TYPE_VIEW_TEXT_CHANGED] events are not mistaken for incoming messages.
     */
    private var suppressIncomingTranslateUntilMs = 0L

    /** Last focus state reported to [OverlayService] (editable field as source of truth). */
    private var lastReportedEditableFocused = false
    private val evaluateEditableFocusRunnable = Runnable { evaluateAndReportEditableFocus() }

    /**
     * Field identity that last received a successful voice insertion.
     * Second+ dictations into the same field always SPLICE (never REPLACE).
     */
    private var lastInsertedFieldKey: String? = null
    /** Baseline text used on the first REPLACE for [lastInsertedFieldKey] (e.g. "Message"). */
    private var lastReplaceBaselineForField: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
        // Sync initial focus state after connect (e.g. user already in a text field).
        scheduleEditableFocusEvaluate(delayMs = 200L)
    }

    /**
     * Searches the active window (and interactive windows when available) for a
     * focused editable text field. Caller must [AccessibilityNodeInfo.recycle] the result.
     */
    fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { root ->
            val fromFocus = editableFromInputFocus(root)
            if (fromFocus != null) {
                root.recycle()
                return rejectIfPassword(fromFocus)
            }
            val fromTree = searchEditableFocused(root)
            root.recycle()
            if (fromTree != null) return rejectIfPassword(fromTree)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val wins = windows ?: return null
            for (window in wins) {
                val wr = window.root ?: continue
                val fromFocus = editableFromInputFocus(wr)
                if (fromFocus != null) {
                    wr.recycle()
                    return rejectIfPassword(fromFocus)
                }
                val fromTree = searchEditableFocused(wr)
                wr.recycle()
                if (fromTree != null) return rejectIfPassword(fromTree)
            }
        }
        return null
    }

    /**
     * Password fields are never dictation targets. Returning null makes callers behave
     * exactly as if no editable field were focused at all.
     */
    private fun rejectIfPassword(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (!isPasswordField(node)) return node
        Log.d(TAG, "findFocusedEditableNode - skipping password field, isPassword=true")
        node.recycle()
        return null
    }

    /** True for nodes flagged as passwords, including IME-reported password input types. */
    private fun isPasswordField(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val inputType = try {
            node.inputType
        } catch (_: Exception) {
            0
        }
        if (inputType == 0) return false
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        val isTextClass =
            (inputType and android.text.InputType.TYPE_MASK_CLASS) ==
                android.text.InputType.TYPE_CLASS_TEXT
        val isNumberClass =
            (inputType and android.text.InputType.TYPE_MASK_CLASS) ==
                android.text.InputType.TYPE_CLASS_NUMBER
        return when {
            isTextClass && variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD -> true
            isTextClass &&
                variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> true
            isTextClass &&
                variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> true
            isNumberClass &&
                variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD -> true
            else -> false
        }
    }

    fun hasFocusedEditableField(): Boolean {
        val node = findFocusedEditableNode() ?: return false
        node.recycle()
        return true
    }

    /** Snapshot the currently focused editable field (for post-recognition re-resolve). */
    fun captureEditableTarget(): EditableTargetSnapshot? {
        val node = findFocusedEditableNode() ?: return null
        return try {
            val snap = snapshotFromNode(node)
            prepareDictationSession(snap)
            snap
        } finally {
            node.recycle()
        }
    }

    /**
     * Called when a dictation panel opens on [snapshot]'s field.
     * Clears "already inserted" if focus moved away and back to an empty/placeholder field.
     */
    fun prepareDictationSession(snapshot: EditableTargetSnapshot?) {
        if (snapshot == null) return
        val key = fieldIdentityKey(snapshot)
        if (lastInsertedFieldKey == null) return
        if (lastInsertedFieldKey != key) {
            Log.d(
                TAG,
                "Dictation session on different field (was=$lastInsertedFieldKey now=$key) " +
                    "— prior-insert flag does not apply"
            )
            return
        }
        val text = snapshot.textBefore
        if (text.isEmpty() || text == lastReplaceBaselineForField) {
            Log.d(
                TAG,
                "Reset insert tracking — same field back to empty/placeholder ('$text')"
            )
            lastInsertedFieldKey = null
            lastReplaceBaselineForField = null
        }
    }

    fun fieldIdentityKey(
        snapshot: EditableTargetSnapshot?,
        node: AccessibilityNodeInfo? = null
    ): String {
        val pkg = node?.packageName?.toString()
            ?: snapshot?.packageName
            ?: "?"
        val viewId = node?.viewIdResourceName ?: snapshot?.viewIdResourceName
        if (!viewId.isNullOrBlank()) return "$pkg|$viewId"
        val bounds = when {
            node != null -> Rect().also { node.getBoundsInScreen(it) }
            snapshot != null -> snapshot.boundsInScreen
            else -> null
        }
        return if (bounds != null) {
            "$pkg|${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
        } else {
            pkg
        }
    }

    /**
     * Fresh resolve at insertion time: prefer currently focused editable, else match
     * [snapshot] by view id / bounds / package (field may still be on screen but
     * briefly unfocused after a transparent recognition Activity).
     */
    fun resolveEditableForInsertion(
        snapshot: EditableTargetSnapshot?
    ): AccessibilityNodeInfo? {
        findFocusedEditableNode()?.let { return it }
        if (snapshot == null) return null
        Log.d(TAG, "No focused editable — trying snapshot match viewId=${snapshot.viewIdResourceName}")
        return findEditableMatchingSnapshot(snapshot)
    }

    /**
     * Inserts [text] at the cursor. Always re-resolves a fresh node at call time
     * (never reuse a pre-recognition AccessibilityNodeInfo).
     *
     * [originalNodeForLog] is only for diagnostics — may be stale after recognition.
     */
    fun insertTextAtCursor(
        text: String,
        snapshot: EditableTargetSnapshot? = null,
        originalNodeForLog: AccessibilityNodeInfo? = null
    ): Boolean {
        if (text.isEmpty()) return false

        val freshNode = resolveEditableForInsertion(snapshot)
        val originalStillValid = try {
            if (originalNodeForLog == null) {
                false
            } else {
                val refreshed = originalNodeForLog.refresh()
                if (refreshed) {
                    originalNodeForLog.text
                    true
                } else {
                    false
                }
            }
        } catch (_: Exception) {
            false
        }
        Log.d(
            TAG,
            "Insertion check - fresh node found: ${freshNode != null}, " +
                "was original node still valid: $originalStillValid"
        )

        if (freshNode == null) {
            Log.w(TAG, "insertTextAtCursor: no fresh editable node at insertion time")
            return false
        }

        return try {
            performInsertAtCursor(freshNode, text, snapshot)
        } finally {
            freshNode.recycle()
        }
    }

    private fun performInsertAtCursor(
        node: AccessibilityNodeInfo,
        text: String,
        snapshot: EditableTargetSnapshot?
    ): Boolean {
        if (isPasswordField(node)) {
            Log.d(TAG, "findFocusedEditableNode - skipping password field, isPassword=true")
            return false
        }
        try {
            // Prefer focusing the field again if focus was stolen.
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            val currentText = node.text?.toString() ?: ""
            val baselineText = snapshot?.textBefore ?: ""
            val unchanged = currentText == baselineText
            val fieldKey = fieldIdentityKey(snapshot, node)
            val hasInsertedBefore = lastInsertedFieldKey == fieldKey

            val replace = !hasInsertedBefore && unchanged
            val actionLabel = when {
                hasInsertedBefore -> "SPLICE (forced, prior insertion exists)"
                unchanged -> "REPLACE"
                else -> "SPLICE"
            }
            Log.d(
                TAG,
                "Insertion decision - hasInsertedBeforeForThisField=$hasInsertedBefore, " +
                    "action: $actionLabel"
            )
            Log.d(
                TAG,
                "Insertion decision detail - baseline='$baselineText' current='$currentText' " +
                    "unchanged=$unchanged fieldKey=$fieldKey"
            )

            val combined: String
            val newCursor: Int
            if (replace) {
                // First dictation into this field; content unchanged since panel open
                // (empty or fake placeholder like WhatsApp "Message") — replace entirely.
                combined = text
                newCursor = text.length
            } else {
                // Prior dictation into this field, or user typed since panel open — splice.
                var start = node.textSelectionStart
                var end = node.textSelectionEnd
                if (start < 0 && snapshot != null) start = snapshot.selectionStart
                if (end < 0 && snapshot != null) end = snapshot.selectionEnd
                if (start < 0) start = currentText.length
                if (end < 0) end = start
                if (start > end) {
                    val tmp = start
                    start = end
                    end = tmp
                }
                start = start.coerceIn(0, currentText.length)
                end = end.coerceIn(0, currentText.length)
                // If cursor sits at 0 with full-looking selection of prior text, append instead.
                if (hasInsertedBefore && start == 0 && end == 0 && currentText.isNotEmpty()) {
                    start = currentText.length
                    end = currentText.length
                }
                if (hasInsertedBefore && start == 0 && end == currentText.length &&
                    currentText.isNotEmpty()
                ) {
                    // Full selection of prior dictation — append rather than replace selection.
                    start = currentText.length
                    end = currentText.length
                }
                val before = currentText.substring(0, start)
                val after = currentText.substring(end)
                val needsSpace = before.isNotEmpty() &&
                    !before.last().isWhitespace() &&
                    text.isNotEmpty() &&
                    !text.first().isWhitespace()
                val toInsert = if (needsSpace) " $text" else text
                combined = before + toInsert + after
                newCursor = (start + toInsert.length).coerceIn(0, combined.length)
            }

            val setArgs = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    combined
                )
            }
            val setOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
            if (!setOk) {
                Log.w(TAG, "ACTION_SET_TEXT failed for editable node")
                return false
            }

            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)

            lastInsertedFieldKey = fieldKey
            if (replace) {
                lastReplaceBaselineForField = baselineText
            }
            Log.d(
                TAG,
                "insertTextAtCursor ok action=$actionLabel len=${text.length} " +
                    "cursor=$newCursor fieldLen=${combined.length}"
            )
            suppressIncomingScreenTranslate()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "insertTextAtCursor failed: ${e.message}", e)
            return false
        }
    }

    private fun snapshotFromNode(node: AccessibilityNodeInfo): EditableTargetSnapshot {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        // Raw getText() as baseline — including fake placeholders (WhatsApp "Message").
        // Insertion compares current vs this baseline to decide REPLACE vs SPLICE.
        val text = node.text?.toString() ?: ""
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0) start = text.length
        if (end < 0) end = start
        Log.d(
            TAG,
            "Editable baseline snapshot package=${node.packageName} text='$text' " +
                "sel=$start..$end"
        )
        return EditableTargetSnapshot(
            packageName = node.packageName?.toString(),
            viewIdResourceName = node.viewIdResourceName,
            className = node.className?.toString(),
            boundsInScreen = Rect(bounds),
            textBefore = text,
            selectionStart = start,
            selectionEnd = end
        )
    }

    private fun findEditableMatchingSnapshot(
        snapshot: EditableTargetSnapshot
    ): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable && !isPasswordField(node)) {
                val pkg = node.packageName?.toString()
                val viewId = node.viewIdResourceName
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val idMatch = !snapshot.viewIdResourceName.isNullOrBlank() &&
                    viewId == snapshot.viewIdResourceName
                val boundsMatch = overlapRatio(bounds, snapshot.boundsInScreen) >= 0.5f
                val pkgOk = snapshot.packageName == null || snapshot.packageName == pkg
                if (pkgOk && (idMatch || boundsMatch)) {
                    return AccessibilityNodeInfo.obtain(node)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = search(child)
                child.recycle()
                if (found != null) return found
            }
            return null
        }

        rootInActiveWindow?.let { root ->
            val found = search(root)
            root.recycle()
            if (found != null) return found
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val wins = windows ?: return null
            for (window in wins) {
                val wr = window.root ?: continue
                val found = search(wr)
                wr.recycle()
                if (found != null) return found
            }
        }
        return null
    }

    private fun overlapRatio(a: Rect, b: Rect): Float {
        val inter = Rect(a)
        if (!inter.intersect(b)) return 0f
        val interArea = inter.width().toFloat() * inter.height()
        val base = (b.width().toFloat() * b.height()).coerceAtLeast(1f)
        return interArea / base
    }

    private fun editableFromInputFocus(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (focused.isEditable) return focused
        // Walk parents — some IMEs focus a child of the EditText.
        var parent = focused.parent
        focused.recycle()
        while (parent != null) {
            if (parent.isEditable) return parent
            val next = parent.parent
            parent.recycle()
            parent = next
        }
        return null
    }

    private fun searchEditableFocused(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchEditableFocused(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    fun performSwipe(up: Boolean, long: Boolean = false) {
        if (isGestureInProgress) return

        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.5f
        val startY: Float
        val endY: Float
        val duration: Long

        if (long) {
            startY = if (up) dm.heightPixels * 0.70f else dm.heightPixels * 0.30f
            endY = if (up) dm.heightPixels * 0.30f else dm.heightPixels * 0.70f
            duration = SWIPE_DURATION_LONG_MS
        } else {
            startY = if (up) dm.heightPixels * 0.70f else dm.heightPixels * 0.30f
            endY = if (up) dm.heightPixels * 0.30f else dm.heightPixels * 0.70f
            duration = SWIPE_DURATION_MS
        }

        val path = android.graphics.Path().apply {
            moveTo(x, startY)
            val mid1Y = startY + (endY - startY) * 0.15f
            val mid2Y = startY + (endY - startY) * 0.85f
            cubicTo(x, mid1Y, x, mid2Y, x, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        isGestureInProgress = true
        OverlayService.instance?.setTouchable(false)

        handler.postDelayed({
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    handler.postDelayed({
                        isGestureInProgress = false
                        OverlayService.instance?.setTouchable(true)
                    }, 50)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Gesture cancelled")
                    handler.postDelayed({
                        isGestureInProgress = false
                        OverlayService.instance?.setTouchable(true)
                    }, 50)
                }
            }, null)

            if (!dispatched) {
                Log.e(TAG, "dispatchGesture returned false")
                isGestureInProgress = false
                OverlayService.instance?.setTouchable(true)
            }
        }, 32)
    }

    fun performBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun performRecentApps() {
        val behavior = SettingsManager.getLeftSwipeBehavior(this)
        Logger.d("Left swipe: $behavior")
        when (behavior) {
            "screenshot" -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            else -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                // Immediate check — primary signal that an editable gained focus.
                scheduleEditableFocusEvaluate(delayMs = 0L)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Keyboard open/close, app switch — re-check focused editable after a short settle.
                scheduleEditableFocusEvaluate(delayMs = 80L)

                val packageName = event.packageName?.toString() ?: return
                val ignoredPackages = setOf(
                    "com.samsung.android.honeyboard",
                    "com.google.android.inputmethod.latin",
                    "com.sec.android.inputmethod",
                    "com.android.systemui",
                    "com.samsung.android.app.cocktailbarservice",
                    "com.sec.android.app.launcher",
                    "android",
                    "com.example.scrollcat",
                    "com.example.scrollcat.debug"
                )
                if (packageName in ignoredPackages) return

                val now = System.currentTimeMillis()
                if (packageName == lastReactedPackage &&
                    now - lastReactedTime < REACTION_DEBOUNCE_MS
                ) {
                    return
                }
                lastReactedPackage = packageName
                lastReactedTime = now

                Logger.d("App opened: $packageName")
                OverlayService.instance?.reactToApp(packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Catch focus loss when the field/IME goes away without a clean VIEW_FOCUSED event.
                scheduleEditableFocusEvaluate(delayMs = 120L)
                maybeTranslateFromContentEvent(event)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                maybeTranslateFromContentEvent(event)
            }
        }
    }

    private fun scheduleEditableFocusEvaluate(delayMs: Long) {
        handler.removeCallbacks(evaluateEditableFocusRunnable)
        if (delayMs <= 0L) {
            handler.post(evaluateEditableFocusRunnable)
        } else {
            handler.postDelayed(evaluateEditableFocusRunnable, delayMs)
        }
    }

    /**
     * Source of truth: [findFocusedEditableNode]. Only notifies Overlay when the
     * boolean focus state changes, so continuous content events stay cheap.
     */
    private fun evaluateAndReportEditableFocus() {
        val focused = hasFocusedEditableField()
        if (focused == lastReportedEditableFocused) return
        lastReportedEditableFocused = focused
        OverlayService.instance?.onEditableFocusChanged(focused)
    }

    private fun maybeTranslateFromContentEvent(event: AccessibilityEvent) {
        if (isOutgoingOrEditBoxContent(event)) {
            Log.d(
                TAG,
                "Incoming-translate check skipped — this is outgoing/edit-box content, not an incoming message"
            )
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastTranslationTime < translationCooldownMs) return

        val rawText = event.text?.joinToString(" ")?.trim() ?: return

        val sentences = rawText.split(".", "!", "?", "\n")
            .map { it.trim() }
            .filter { it.length > 15 }
            .sortedByDescending { it.length }

        val text = sentences.firstOrNull() ?: return
        if (text.length < 10) return
        if (text == lastTranslatedText) return

        lastTranslatedText = text
        lastTranslationTime = now

        val overlay = OverlayService.instance ?: return
        overlay.screenTranslator?.detectAndTranslate(text) { original, translated, language ->
            handler.post {
                overlay.showTranslationBubble(original, translated, language)
            }
        }
    }

    /**
     * Incoming screen auto-translate must not run on the user's own outgoing reply /
     * dictation output (reply panel edit box, compose fields, post-voice-insert text).
     */
    private fun isOutgoingOrEditBoxContent(event: AccessibilityEvent): Boolean {
        val now = System.currentTimeMillis()
        if (now < suppressIncomingTranslateUntilMs) return true

        val pkg = event.packageName?.toString().orEmpty()
        if (pkg == "com.example.scrollcat" || pkg == "com.example.scrollcat.debug") {
            return true
        }

        val source = event.source ?: return false
        return try {
            val className = source.className?.toString().orEmpty()
            source.isEditable || className.contains("EditText", ignoreCase = true)
        } finally {
            source.recycle()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (lastReportedEditableFocused) {
            lastReportedEditableFocused = false
            OverlayService.instance?.onEditableFocusChanged(false)
        }
        instance = null
        super.onDestroy()
    }
}
