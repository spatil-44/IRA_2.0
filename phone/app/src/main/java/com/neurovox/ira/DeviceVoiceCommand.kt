package com.neurovox.ira

import java.util.Locale

internal sealed interface DeviceVoiceCommand {
    data class OpenApp(val name: String) : DeviceVoiceCommand
    data class OpenSettings(val category: String?) : DeviceVoiceCommand
    data class ClickText(val text: String) : DeviceVoiceCommand
    data class EnterText(val text: String) : DeviceVoiceCommand
    data class Scroll(val forward: Boolean) : DeviceVoiceCommand
    object Back : DeviceVoiceCommand
    object Home : DeviceVoiceCommand
    object ShowNotifications : DeviceVoiceCommand
    object ShowQuickSettings : DeviceVoiceCommand
    object StopListening : DeviceVoiceCommand
}

internal object DeviceVoiceCommandParser {
    private val wakeWordPattern = Regex("""\bira\b""", RegexOption.IGNORE_CASE)

    fun hasWakeWord(text: String): Boolean = wakeWordPattern.containsMatchIn(text)

    fun commandAfterWakeWord(text: String, wakeWordWasHeard: Boolean): String? {
        val match = wakeWordPattern.find(text)
        if (match != null) return text.substring(match.range.last + 1).trim()
        return text.trim().takeIf { wakeWordWasHeard }
    }

    fun parse(text: String): DeviceVoiceCommand? {
        val normalized = text.trim().lowercase(Locale.ROOT)
            .replace(Regex("[.!?,]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) return null

        when (normalized) {
            "stop listening", "turn off listening", "stop ira" ->
                return DeviceVoiceCommand.StopListening
            "back", "go back", "go back one page" -> return DeviceVoiceCommand.Back
            "home", "go home", "show home" -> return DeviceVoiceCommand.Home
            "show notifications", "open notifications" ->
                return DeviceVoiceCommand.ShowNotifications
            "open quick settings", "show quick settings" ->
                return DeviceVoiceCommand.ShowQuickSettings
            "scroll down" -> return DeviceVoiceCommand.Scroll(forward = true)
            "scroll up" -> return DeviceVoiceCommand.Scroll(forward = false)
            "open settings", "settings" ->
                return DeviceVoiceCommand.OpenSettings(category = null)
        }

        val settingsCategory = listOf(
            "wifi", "wi-fi", "bluetooth", "display", "sound", "accessibility",
            "privacy", "apps", "location", "notifications", "battery", "security",
            "date", "language", "storage",
        ).firstOrNull { category ->
            normalized == "open $category" ||
                normalized == "open $category settings" ||
                normalized == "$category settings"
        }
        if (settingsCategory != null) {
            return DeviceVoiceCommand.OpenSettings(settingsCategory)
        }

        Regex("""^(?:open|launch|start) (?:app )?(.+)$""")
            .matchEntire(normalized)
            ?.groupValues
            ?.get(1)
            ?.takeIf(String::isNotBlank)
            ?.let { return DeviceVoiceCommand.OpenApp(it) }

        Regex("""^(?:tap|click|press) (.+)$""")
            .matchEntire(normalized)
            ?.groupValues
            ?.get(1)
            ?.takeIf(String::isNotBlank)
            ?.let { return DeviceVoiceCommand.ClickText(it) }

        Regex("""^type (.+)$""", RegexOption.IGNORE_CASE)
            .matchEntire(text.trim())
            ?.groupValues
            ?.get(1)
            ?.takeIf(String::isNotBlank)
            ?.let { return DeviceVoiceCommand.EnterText(it) }

        return null
    }
}
