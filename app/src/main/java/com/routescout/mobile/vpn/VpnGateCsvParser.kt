package com.routescout.mobile.vpn

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Strict parser for the official VPN Gate CSV feed.
 *
 * The feed contains a comment line, a commented header, and rows whose final
 * column is a Base64-encoded OpenVPN profile. Quoted CSV fields are supported.
 */
internal object VpnGateCsvParser {

    fun parse(csv: String): List<FreeVpnServer> {
        if (csv.isBlank()) return emptyList()

        val parsed = parseRecords(csv).mapNotNull { row ->
            if (row.size < 15) return@mapNotNull null

            val hostName = row[0].trim().removePrefix("\uFEFF")
            val ipAddress = row[1].trim()
            val firstField = hostName.trim()

            if (firstField.startsWith("*") || firstField.startsWith("#")) return@mapNotNull null
            if (hostName.isBlank() || !isValidIpv4(ipAddress)) return@mapNotNull null

            val configBase64 = row.last().trim()
            if (!isUsableOpenVpnConfig(configBase64)) return@mapNotNull null

            val countryName = row[5].trim().ifBlank { "Unknown country" }
            val countryCode = row[6].trim().uppercase().takeIf { it.length == 2 } ?: "??"
            val message = row.subList(13, row.lastIndex).joinToString(",").trim()

            FreeVpnServer(
                hostName = hostName,
                ipAddress = ipAddress,
                score = row[2].trim().toLongOrNull() ?: 0L,
                pingMs = row[3].trim().toIntOrNull()?.takeIf { it in 1..10_000 },
                speedBitsPerSecond = row[4].trim().toLongOrNull()?.takeIf { it > 0L },
                countryName = countryName,
                countryCode = countryCode,
                activeSessions = row[7].trim().toLongOrNull() ?: 0L,
                uptimeSeconds = row[8].trim().toLongOrNull() ?: 0L,
                totalUsers = row[9].trim().toLongOrNull() ?: 0L,
                totalTrafficBytes = row[10].trim().toLongOrNull() ?: 0L,
                logType = row[11].trim(),
                operator = row[12].trim(),
                message = message,
                openVpnConfigBase64 = configBase64
            )
        }

        return parsed
            .groupBy { it.key }
            .values
            .mapNotNull { entries -> entries.maxByOrNull { it.score } }
            .sortedWith(
                compareByDescending<FreeVpnServer> { it.score }
                    .thenBy { it.pingMs ?: Int.MAX_VALUE }
                    .thenByDescending { it.speedBitsPerSecond ?: 0L }
            )
    }

    private fun isValidIpv4(value: String): Boolean {
        val octets = value.split(".")
        return octets.size == 4 && octets.all { octet ->
            val number = octet.toIntOrNull()
            number != null && number in 0..255 && octet.isNotBlank()
        }
    }

    private fun isUsableOpenVpnConfig(encoded: String): Boolean {
        if (encoded.length < 80) return false
        return try {
            val config = String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8)
            val hasClient = config.lineSequence().any { it.trim().equals("client", ignoreCase = true) }
            val hasRemote = config.lineSequence().any { line ->
                val parts = line.trim().split(Regex("\\s+"))
                parts.size >= 3 &&
                    parts[0].equals("remote", ignoreCase = true) &&
                    parts[2].toIntOrNull() != null
            }
            hasClient && hasRemote
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun parseRecords(input: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        fun finishField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun finishRow() {
            finishField()
            if (row.any { it.isNotBlank() }) records.add(row)
            row = mutableListOf()
        }

        while (index < input.length) {
            val character = input[index]
            when {
                character == '"' -> {
                    if (quoted && index + 1 < input.length && input[index + 1] == '"') {
                        field.append('"')
                        index++
                    } else {
                        quoted = !quoted
                    }
                }
                character == ',' && !quoted -> finishField()
                (character == '\n' || character == '\r') && !quoted -> {
                    finishRow()
                    if (character == '\r' && index + 1 < input.length && input[index + 1] == '\n') {
                        index++
                    }
                }
                else -> field.append(character)
            }
            index++
        }

        if (field.isNotEmpty() || row.isNotEmpty()) finishRow()
        return records
    }
}
