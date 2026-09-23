package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerControlsState
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The controls payload is serialized by hand, field by field, with the separating commas written
 * out by the caller -- so forgetting one `append(',')` when adding a field yields a string that
 * `JSON.parse` rejects in the WebView, and every controls update is silently dropped from then on
 * (the player overlay stops updating entirely -- no subtitle list, no labels). That is exactly how
 * the Watch Together fields shipped once. Parse the whole payload here so the next missing comma
 * fails the build instead of the player.
 */
class ControlsJsonSerializerTest {
    @Test
    fun `controls payload is valid json in both fullscreen states`() {
        listOf(false, true).forEach { isFullscreen ->
            val json = PlayerControlsState().toControlsJson(isFullscreen)
            val parsed = Json.parseToJsonElement(json).jsonObject
            assertTrue(parsed.isNotEmpty(), "controls payload parsed to an empty object")
            listOf(
                "subtitlesLabel",
                "episodesLabel",
                "watchTogetherEnabled",
                "watchTogetherActive",
                "watchTogetherLabel",
                "watchTogetherStatus",
                "externalPlayerLabel",
            ).forEach { key ->
                assertTrue(key in parsed, "controls payload is missing \"$key\"")
            }
        }
    }
}
