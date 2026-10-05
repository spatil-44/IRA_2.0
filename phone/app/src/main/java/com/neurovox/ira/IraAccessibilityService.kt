package com.neurovox.ira

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class IraVoiceControlState(
    val isListening: Boolean = false,
    val message: String = "Wake-word listening is off.",
)

object IraVoiceControlStatus {
    private val mutableState = MutableStateFlow(IraVoiceControlState())
    val state = mutableState.asStateFlow()

    internal fun update(isListening: Boolean, message: String) {
        mutableState.value = IraVoiceControlState(isListening, message)
    }

    internal fun updateMessage(message: String) {
        mutableState.value = mutableState.value.copy(message = message)
    }
}

class IraAccessibilityService : AccessibilityService() {
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val removeOverlay = Runnable { removeAssistantOverlay() }
    private var assistantOverlay: IraAssistantOverlayView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        overlayHandler.removeCallbacksAndMessages(null)
        removeAssistantOverlay()
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    private fun execute(command: DeviceVoiceCommand) {
        val result = when (command) {
            is DeviceVoiceCommand.OpenApp -> openApp(command.name)
            is DeviceVoiceCommand.OpenSettings -> openSettings(command.category)
            is DeviceVoiceCommand.ClickText -> clickVisibleText(command.text)
            is DeviceVoiceCommand.EnterText -> enterText(command.text)
            is DeviceVoiceCommand.Scroll -> scroll(command.forward)
            DeviceVoiceCommand.Back ->
                globalAction(GLOBAL_ACTION_BACK, "Went back.")
            DeviceVoiceCommand.Home ->
                globalAction(GLOBAL_ACTION_HOME, "Opened the home screen.")
            DeviceVoiceCommand.ShowNotifications ->
                globalAction(GLOBAL_ACTION_NOTIFICATIONS, "Opened notifications.")
            DeviceVoiceCommand.ShowQuickSettings ->
                globalAction(GLOBAL_ACTION_QUICK_SETTINGS, "Opened quick settings.")
            DeviceVoiceCommand.StopListening -> {
                stopService(Intent(this, IraWakeWordService::class.java))
                "Wake-word listening stopped."
            }
        }
        IraVoiceControlStatus.updateMessage(result)
        showAssistantOverlay("Done", result, listening = false, durationMillis = 2600L)
    }

    private fun openApp(name: String): String {
        val launchers = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        )
        val matches = launchers.filter {
            it.loadLabel(packageManager).toString().equals(name, ignoreCase = true)
        }
        if (matches.size != 1) {
            return if (matches.isEmpty()) {
                "Could not find an app named $name."
            } else {
                "More than one app is named $name. Use a more specific app name."
            }
        }

        val activity = matches.single().activityInfo
        val launchIntent = packageManager.getLaunchIntentForPackage(activity.packageName)
            ?: return "Android did not provide a launch action for $name."
        return try {
            startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened $name."
        } catch (error: ActivityNotFoundException) {
            "Could not open $name: ${error.message ?: "the app is unavailable"}."
        }
    }

    private fun openSettings(category: String?): String {
        val action = when (category) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "privacy" -> if (android.os.Build.VERSION.SDK_INT >= 29) {
                Settings.ACTION_PRIVACY_SETTINGS
            } else {
                Settings.ACTION_SETTINGS
            }
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "notifications" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened ${category ?: "system"} settings. Review and apply changes there."
        } catch (error: ActivityNotFoundException) {
            "Could not open ${category ?: "system"} settings: " +
                (error.message ?: "the settings screen is unavailable")
        }
    }

    private fun clickVisibleText(label: String): String {
        val root = rootInActiveWindow ?: return "No active screen is available."
        val matches = root.findAccessibilityNodeInfosByText(label)
            .orEmpty()
            .filter { node ->
                node.text?.toString()?.equals(label, ignoreCase = true) == true ||
                    node.contentDescription?.toString()?.equals(label, ignoreCase = true) == true
            }
        val clickableTargets = matches.mapNotNull(::clickableAncestor).distinct()
        return when (clickableTargets.size) {
            0 -> "Could not find an exact visible control named $label."
            1 -> if (clickableTargets.single().performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                "Activated $label."
            } else {
                "Android could not activate $label."
            }
            else -> "More than one control is named $label. Use a more specific label."
        }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable) {
            current = current.parent
        }
        return current
    }

    private fun enterText(text: String): String {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "There is no focused text field."
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            "Entered text in the focused field. Nothing was sent to Ira's server."
        } else {
            "The focused field does not allow voice text entry."
        }
    }

    private fun scroll(forward: Boolean): String {
        val root = rootInActiveWindow ?: return "No active screen is available."
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.isScrollable) nodes.add(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { pending.addLast(it) }
            }
        }
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return when (nodes.size) {
            0 -> "No scrollable area is visible."
            1 -> if (nodes.single().performAction(action)) {
                if (forward) "Scrolled down." else "Scrolled up."
            } else {
                "The visible area could not be scrolled."
            }
            else -> "More than one area can scroll. Use the screen controls to choose one."
        }
    }

    private fun globalAction(action: Int, success: String): String =
        if (performGlobalAction(action)) success else "Android could not perform that action."

    private fun showAssistantOverlay(
        title: String,
        subtitle: String,
        listening: Boolean,
        durationMillis: Long? = null,
    ) {
        overlayHandler.removeCallbacks(removeOverlay)
        var overlay = assistantOverlay
        if (overlay == null) {
            val newOverlay = IraAssistantOverlayView(this)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                (112 * resources.displayMetrics.density).toInt(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (96 * resources.displayMetrics.density).toInt()
            }
            getSystemService(WindowManager::class.java).addView(newOverlay, params)
            assistantOverlay = newOverlay
            overlay = newOverlay
        }
        overlay.show(title, subtitle, listening)
        if (durationMillis != null) {
            overlayHandler.postDelayed(removeOverlay, durationMillis)
        }
    }

    private fun removeAssistantOverlay() {
        assistantOverlay?.let { overlay ->
            getSystemService(WindowManager::class.java).removeView(overlay)
            overlay.stop()
            assistantOverlay = null
        }
    }

    private fun setAssistantAudioLevel(level: Float) {
        assistantOverlay?.setAudioLevel(level)
    }

    companion object {
        @Volatile
        private var activeService: IraAccessibilityService? = null

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val component = ComponentName(context, IraAccessibilityService::class.java)
                .flattenToString()
            return Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0,
            ) == 1 && enabled.split(':').any { it.equals(component, ignoreCase = true) }
        }

        internal fun performVoiceCommand(command: DeviceVoiceCommand): Boolean {
            val service = activeService ?: return false
            service.execute(command)
            return true
        }

        internal fun showAssistantOverlay(
            title: String,
            subtitle: String,
            listening: Boolean,
            durationMillis: Long? = null,
        ) {
            activeService?.let { service ->
                service.overlayHandler.post {
                    service.showAssistantOverlay(title, subtitle, listening, durationMillis)
                }
            }
        }

        internal fun setAssistantAudioLevel(level: Float) {
            activeService?.let { service ->
                service.overlayHandler.post { service.setAssistantAudioLevel(level) }
            }
        }

        internal fun hideAssistantOverlay() {
            activeService?.let { service ->
                service.overlayHandler.post {
                    service.overlayHandler.removeCallbacks(service.removeOverlay)
                    service.removeAssistantOverlay()
                }
            }
        }
    }
}
