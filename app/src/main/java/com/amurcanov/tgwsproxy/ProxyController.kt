package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first

object ProxyController {

    suspend fun startFromSavedSettings(
        context: Context,
        showInvalidPortToast: Boolean = false
    ): Boolean {
        val settingsStore = SettingsStore(context)
        settingsStore.migrateLegacyDefaults()
        val portText = settingsStore.port.first()
        val port = portText.toIntOrNull()
        if (port == null) {
            if (showInvalidPortToast) {
                Toast.makeText(context, context.getString(R.string.invalid_port), Toast.LENGTH_SHORT).show()
            }
            ProxyTileService.requestSync(context)
            return false
        }

        val bindIp = settingsStore.bindIp.first()
        val isExperimental = settingsStore.isExperimentalMode.first()
        val isDcAuto = settingsStore.isDcAuto.first()
        val poolSize = settingsStore.poolSize.first()
        val cfEnabled = settingsStore.cfproxyEnabled.first()
        val customCfDomainEnabled = settingsStore.customCfDomainEnabled.first()
        val customCfDomain = settingsStore.customCfDomain.first().trim()
        val privateRelayEnabled = settingsStore.privateRelayEnabled.first()
        val privateRelayDomain = normalizeRelayDomain(settingsStore.privateRelayDomain.first())
        val privateRelayToken = settingsStore.privateRelayToken.first().trim()
        if (privateRelayEnabled &&
            (!isValidRelayDomain(privateRelayDomain) || !isValidRelayToken(privateRelayToken))
        ) {
            if (showInvalidPortToast) {
                Toast.makeText(context, context.getString(R.string.invalid_private_relay), Toast.LENGTH_LONG).show()
            }
            ProxyTileService.requestSync(context)
            return false
        }
        val secretKey = ensureSecretKey(settingsStore)

        val parsedIps = buildList {
            if (!isDcAuto && !privateRelayEnabled) {
                appendDc(1, settingsStore.dc1.first())
                appendDc(2, settingsStore.dc2.first())
                appendDc(3, settingsStore.dc3.first())
                appendDc(4, settingsStore.dc4.first())

                if (isExperimental) {
                    appendDc(5, settingsStore.dc5.first())
                    appendDc(203, settingsStore.dc203.first())
                    appendDc(-1, settingsStore.dc1m.first())
                    appendDc(-2, settingsStore.dc2m.first())
                    appendDc(-3, settingsStore.dc3m.first())
                    appendDc(-4, settingsStore.dc4m.first())
                    appendDc(-5, settingsStore.dc5m.first())
                    appendDc(-203, settingsStore.dc203m.first())
                }
            }
        }.joinToString(",")

        ContextCompat.startForegroundService(
            context,
            Intent(context, ProxyService::class.java).apply {
                action = ProxyService.ACTION_START
                putExtra(ProxyService.EXTRA_BIND_IP, bindIp)
                putExtra(ProxyService.EXTRA_PORT, port)
                putExtra(ProxyService.EXTRA_IPS, parsedIps)
                putExtra(ProxyService.EXTRA_POOL_SIZE, poolSize)
                putExtra(ProxyService.EXTRA_CFPROXY_ENABLED, cfEnabled && !privateRelayEnabled)
                putExtra(ProxyService.EXTRA_CFPROXY_PRIORITY, true)
                putExtra(ProxyService.EXTRA_PRIVATE_RELAY_ENABLED, privateRelayEnabled)
                putExtra(ProxyService.EXTRA_PRIVATE_RELAY_DOMAIN, privateRelayDomain)
                putExtra(ProxyService.EXTRA_PRIVATE_RELAY_TOKEN, privateRelayToken)
                putExtra(
                    ProxyService.EXTRA_CFPROXY_DOMAIN,
                    if (customCfDomainEnabled && cfEnabled) customCfDomain else ""
                )
                putExtra(ProxyService.EXTRA_SECRET_KEY, secretKey)
            }
        )
        ProxyTileService.requestSync(context)
        return true
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, ProxyService::class.java).apply {
                action = ProxyService.ACTION_STOP
            }
        )
        ProxyTileService.requestSync(context)
    }

    private suspend fun ensureSecretKey(settingsStore: SettingsStore): String {
        val current = settingsStore.secretKey.first().trim()
        if (isValidSecret(current)) {
            return current
        }

        val generated = generateRandomSecret()
        settingsStore.saveSecretKey(generated)
        return generated
    }

    private fun MutableList<String>.appendDc(dc: Int, value: String) {
        val ip = value.trim()
        if (ip.isNotBlank()) {
            add("$dc:$ip")
        }
    }

    private fun normalizeRelayDomain(value: String): String {
        return parseRelayDomains(value).joinToString(",")
    }

    private fun parseRelayDomains(value: String): List<String> = value
        .split(',', ';', '\n', '\t', ' ')
        .map { raw ->
            raw.trim()
                .removePrefix("wss://")
                .removePrefix("https://")
                .removePrefix("ws://")
                .removePrefix("http://")
                .substringBefore('/')
                .lowercase()
        }
        .map(::normalizeRelayHostPort)
        .filter { it.isNotBlank() }
        .distinct()

    // "relay.example.ru:8443" остаётся с портом, мусорные ':' отрезаем.
    private val relayHostPortRegex = Regex("^([a-z0-9.-]+)(:(\\d{1,5}))?$")

    private fun normalizeRelayHostPort(value: String): String {
        val m = relayHostPortRegex.matchEntire(value) ?: return value.substringBefore(':')
        val port = m.groupValues[3]
        if (port.isNotEmpty() && (port.toIntOrNull() ?: 0) !in 1..65535) {
            return m.groupValues[1]
        }
        return value
    }

    private fun splitRelayHostPort(value: String): Pair<String, Int?> {
        val m = relayHostPortRegex.matchEntire(value) ?: return value.substringBefore(':').to(null)
        val host = m.groupValues[1]
        val port = m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()
        return host.to(port)
    }

    private fun isValidRelayDomain(value: String): Boolean {
        val domains = parseRelayDomains(value)
        return domains.isNotEmpty() && domains.size <= 8 && domains.all { domain ->
            val (host, port) = splitRelayHostPort(domain)
            host.length in 3..253 &&
                '.' in host &&
                host.all { it.isLetterOrDigit() || it == '.' || it == '-' } &&
                (port == null || port in 1..65535)
        }
    }

    private fun isValidRelayToken(value: String): Boolean {
        return value.length in 32..256 && value.all {
            it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' || it == '~'
        }
    }

    private fun generateRandomSecret(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun isValidSecret(value: String): Boolean {
        return value.length == 32 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }
}
