package com.neurovox.ira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceVoiceCommandParserTest {
    @Test
    fun parsesWakeWordSettingsCommand() {
        val commandText = DeviceVoiceCommandParser.commandAfterWakeWord(
            "Ira, open Bluetooth settings",
            wakeWordWasHeard = false,
        )

        assertEquals(
            DeviceVoiceCommand.OpenSettings("bluetooth"),
            DeviceVoiceCommandParser.parse(commandText.orEmpty()),
        )
    }

    @Test
    fun preservesTextEnteredIntoFocusedField() {
        assertEquals(
            DeviceVoiceCommand.EnterText("Send this to Alex."),
            DeviceVoiceCommandParser.parse("type Send this to Alex."),
        )
    }

    @Test
    fun rejectsUnrecognizedCommands() {
        assertNull(DeviceVoiceCommandParser.parse("do something private"))
        assertNull(DeviceVoiceCommandParser.commandAfterWakeWord("open settings", false))
    }
}
