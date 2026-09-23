package com.nuvio.app.features.watchtogether

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter

/**
 * What the host hands the guest: where the room is, and the token that opens it.
 *
 * [baseUrl] rather than a host/port pair, so the Tailscale / tunnel override in settings is
 * a straight substitution and needs no special case anywhere else.
 */
data class WatchTogetherInvite(
    val baseUrl: String,
    val joinToken: String,
    val protocolVersion: Int = WATCH_TOGETHER_PROTOCOL_VERSION,
) {
    /**
     * A short, unambiguous rendering of the token for reading aloud.
     *
     * It confirms that both sides hold the *same* invite; it is **not** a way to find the
     * room. There is no rendezvous service in v1, so the address always has to travel with
     * it — the UI must say so rather than implying a six-character code is enough.
     */
    val displayCode: String get() = watchTogetherDisplayCode(joinToken)
}

/**
 * A host of its own: `nuvio://watch` is already claimed by the details deep link
 * ("detail", "details", "open", "watch" in AppUrlBridge), and two parsers competing for one
 * host is a bug waiting to be written.
 */
private const val INVITE_HOST = "watch-together"

/** No I, O, 0 or 1: those are the pairs people mishear and mistype. */
private const val DISPLAY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private const val DISPLAY_CODE_LENGTH = 6

fun buildWatchTogetherInviteUrl(invite: WatchTogetherInvite): String = buildString {
    append("nuvio://")
    append(INVITE_HOST)
    append("?u=")
    append(invite.baseUrl.trim().trimEnd('/').encodeURLParameter())
    append("&k=")
    append(invite.joinToken.trim().encodeURLParameter())
    append("&v=")
    append(invite.protocolVersion)
}

/**
 * Parses an invite the user pasted or opened.
 *
 * Returns null for anything malformed. The base URL is required to be http(s) with a host:
 * this value ends up as the player's source URL, so accepting an arbitrary scheme here would
 * let a pasted link point the player wherever the sender liked.
 */
fun parseWatchTogetherInvite(raw: String): WatchTogetherInvite? {
    val text = raw.trim()
    if (text.isEmpty()) return null

    val parsed = runCatching { Url(text) }.getOrNull() ?: return null
    if (!parsed.protocol.name.equals("nuvio", ignoreCase = true)) return null
    if (!parsed.host.equals(INVITE_HOST, ignoreCase = true)) return null

    val baseUrl = parsed.parameters["u"]?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
    val joinToken = parsed.parameters["k"]?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val version = parsed.parameters["v"]?.trim()?.toIntOrNull() ?: WATCH_TOGETHER_PROTOCOL_VERSION

    if (!isPlausibleRoomBaseUrl(baseUrl)) return null

    return WatchTogetherInvite(baseUrl = baseUrl, joinToken = joinToken, protocolVersion = version)
}

internal fun isPlausibleRoomBaseUrl(baseUrl: String): Boolean {
    val lower = baseUrl.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    val parsed = runCatching { Url(baseUrl) }.getOrNull() ?: return false
    return parsed.host.isNotBlank()
}

/**
 * A deterministic six-character checksum of the token.
 *
 * FNV-1a, not a cryptographic digest: its job is to catch a truncated or mistyped invite
 * when two people compare it out loud, and nothing rests on it being unforgeable — the
 * token itself is the secret.
 */
fun watchTogetherDisplayCode(joinToken: String): String {
    var hash = 0x811C9DC5u
    for (char in joinToken) {
        hash = hash xor char.code.toUInt()
        hash *= 0x01000193u
    }
    return buildString {
        var remaining = hash
        repeat(DISPLAY_CODE_LENGTH) {
            append(DISPLAY_ALPHABET[(remaining % DISPLAY_ALPHABET.length.toUInt()).toInt()])
            remaining /= DISPLAY_ALPHABET.length.toUInt()
            // The 32-bit hash runs out of entropy before six base-32 digits do; refill from
            // the top so every position still varies.
            if (remaining == 0u) remaining = hash / 7u + 1u
        }
    }
}
