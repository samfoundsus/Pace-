package com.example.engine.server

import com.example.data.server.ServerKind
import com.example.data.server.ServerProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Lenient parser for LibreSpeed community server feeds (`servers.php`).
 * - Ignores unknown fields and malformed entries.
 * - Drops entries missing any required field (`name`, `server`, `dlURL`, `ulURL`, `pingURL`).
 * - Converts scheme-relative URLs ("//host/path") to HTTPS and validates every endpoint via
 *   [ServerUrlValidator] (HTTPS only, no credentials, no localhost/private/link-local/loopback).
 */
object LibreSpeedServerParser {

    fun parseCommunityServers(jsonText: String): List<ServerProfile> {
        val trimmed = jsonText.trim()
        if (trimmed.isEmpty()) return emptyList()

        val rootArray = runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
        val parsed = ArrayList<ServerProfile>(rootArray.length())
        val seenIds = HashSet<String>()

        for (index in 0 until rootArray.length()) {
            val item = rootArray.optJSONObject(index) ?: continue
            val profile = parseSingleEntry(item) ?: continue
            if (seenIds.add(profile.id)) {
                parsed.add(profile)
            }
        }
        return parsed
    }

    private fun parseSingleEntry(obj: JSONObject): ServerProfile? {
        val rawName = obj.optNonBlankString("name") ?: return null
        val rawServer = obj.optNonBlankString("server") ?: return null
        val rawDl = obj.optNonBlankString("dlURL") ?: return null
        val rawUl = obj.optNonBlankString("ulURL") ?: return null
        val rawPing = obj.optNonBlankString("pingURL") ?: return null
        val rawGetIp = obj.optNonBlankString("getIpURL")
        val rawSponsor = obj.optNonBlankString("sponsorName")

        val baseUrl = ServerUrlValidator.validateHttpsUrl(rawServer) ?: return null
        val dlUrl = ServerUrlValidator.resolveEndpointUrl(baseUrl, rawDl) ?: return null
        val ulUrl = ServerUrlValidator.resolveEndpointUrl(baseUrl, rawUl) ?: return null
        val pingUrl = ServerUrlValidator.resolveEndpointUrl(baseUrl, rawPing) ?: return null
        val getIpUrl = rawGetIp?.let { ServerUrlValidator.resolveEndpointUrl(baseUrl, it) }

        val (displayName, location) = splitNameAndLocation(rawName, rawSponsor)
        val stableId = buildStableId(obj, baseUrl, displayName)

        return ServerProfile(
            id = stableId,
            name = displayName,
            location = location,
            kind = ServerKind.COMMUNITY,
            baseUrl = baseUrl,
            downloadBaseUrl = dlUrl,
            uploadBaseUrl = ulUrl,
            pingBaseUrl = pingUrl,
            getIpBaseUrl = getIpUrl,
        )
    }

    /**
     * LibreSpeed names typically follow `"City, Country (Sponsor)"`, e.g.
     * `"Amsterdam, Netherlands (Clouvider)"` or `"Los Angeles, United States (1) (Clouvider)"`.
     * Extracts clean display name and location without losing city disambiguation.
     */
    internal fun splitNameAndLocation(
        rawName: String,
        sponsorName: String?,
    ): Pair<String, String> {
        val clean = rawName.trim()
        val lastOpen = clean.lastIndexOf('(')
        val lastClose = clean.lastIndexOf(')')
        if (lastOpen > 0 && lastClose == clean.lastIndex && lastClose > lastOpen + 1) {
            val beforeParen = clean.substring(0, lastOpen).trim().trimEnd(',', '-', '·').trim()
            val insideParen = clean.substring(lastOpen + 1, lastClose).trim()
            if (beforeParen.isNotEmpty() && insideParen.isNotEmpty()) {
                return insideParen to beforeParen
            }
        }
        val cleanSponsor = sponsorName?.trim()?.takeIf { it.isNotEmpty() && !it.equals(clean, ignoreCase = true) }
        return if (cleanSponsor != null) {
            cleanSponsor to clean
        } else {
            clean to ""
        }
    }

    private fun buildStableId(obj: JSONObject, baseUrl: String, displayName: String): String {
        val idRaw: Any? = obj.opt("id")
        val numericOrStringId = when (idRaw) {
            null -> null
            is Number -> idRaw.toLong().toString()
            is String -> idRaw.trim().takeIf { it.isNotEmpty() }
            else -> null
        }
        if (numericOrStringId != null) {
            return "community_$numericOrStringId"
        }
        val hash = "${baseUrl.lowercase(Locale.US)}|${displayName.lowercase(Locale.US)}".hashCode()
        return "community_${hash.toUInt().toString(16)}"
    }

    private fun JSONObject.optNonBlankString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val raw = opt(key) as? String ?: return null
        return raw.trim().takeIf { it.isNotEmpty() }
    }
}
