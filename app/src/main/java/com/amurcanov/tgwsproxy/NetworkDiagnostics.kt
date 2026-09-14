package com.amurcanov.tgwsproxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Network diagnostics deliberately use Android's platform TLS implementation.
 * The proxy core currently uses rustls, so a successful native probe next to a
 * failed proxy connection is a useful signal that the TLS/WS implementation or
 * its fingerprint is involved rather than basic IP reachability.
 */
object NetworkDiagnostics {
    private const val CONNECT_TIMEOUT_MS = 4_500
    private const val IO_TIMEOUT_MS = 12_000
    private const val STREAM_TIMEOUT_MS = 70_000
    private const val DNS_TIMEOUT_MS = 5_000L
    private const val MAX_HEADER_BYTES = 32 * 1024
    private const val MAX_STATUS_BODY_BYTES = 16 * 1024
    private const val CF_DOWNLOAD_TARGET_BYTES = 64 * 1024
    private const val RELAY_1_MIB = 1 * 1024 * 1024
    private const val RELAY_10_MIB = 10 * 1024 * 1024
    private const val RELAY_50_MIB = 50 * 1024 * 1024

    enum class ProbeKind {
        TLS,
        WEBSOCKET,
        WEBSOCKET_STREAM,
        WEBSOCKET_UPLOAD,
        HTTP_STATUS,
        HTTP_DOWNLOAD
    }

    data class ProbeSpec(
        val id: String,
        val group: String,
        val label: String,
        val connectHost: String,
        val tlsHost: String = connectHost,
        val port: Int = 443,
        val path: String = "/",
        val kind: ProbeKind,
        val fixedAddress: String? = null,
        val expectedBytes: Int = 0,
        val authBearer: String = ""
    )

    data class AddressAttempt(
        val family: String,
        val address: String,
        val tcpMs: Long? = null,
        val tlsMs: Long? = null,
        val protocol: String? = null,
        val cipher: String? = null,
        val httpStatus: Int? = null,
        val bytesRead: Int? = null,
        val success: Boolean,
        val stage: String,
        val error: String? = null
    )

    data class ProbeResult(
        val spec: ProbeSpec,
        val dnsMs: Long,
        val ipv4Count: Int,
        val ipv6Count: Int,
        val attempts: List<AddressAttempt>
    ) {
        val success: Boolean get() = attempts.any { it.success }
    }

    data class NetworkSnapshot(
        val transport: String,
        val operator: String,
        val simOperator: String,
        val metered: Boolean,
        val validated: Boolean,
        val vpn: Boolean,
        val privateDnsActive: Boolean,
        val privateDnsName: String,
        val localIpv4: Boolean,
        val localIpv6: Boolean,
        val dnsCount: Int
    )

    data class Report(
        val startedAt: Long,
        val finishedAt: Long,
        val network: NetworkSnapshot,
        val results: List<ProbeResult>,
        val customEndpoint: String,
        val privateRelayDomain: String = "",
        val privateRelayAuthTests: Boolean = false,
        val fatalError: String? = null
    ) {
        fun asText(): String = buildString {
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(startedAt))
            appendLine("TG WS Proxy Network Lab report")
            appendLine("Format: 2")
            appendLine("Started: $date")
            appendLine("Duration: ${finishedAt - startedAt} ms")
            appendLine("App: ${BuildConfig.VERSION_NAME}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            appendLine()
            appendLine("Network:")
            appendLine("- transport: ${network.transport}")
            appendLine("- operator: ${network.operator.ifBlank { "unknown" }}")
            appendLine("- sim operator: ${network.simOperator.ifBlank { "unknown" }}")
            appendLine("- metered: ${network.metered}")
            appendLine("- validated: ${network.validated}")
            appendLine("- VPN transport: ${network.vpn}")
            appendLine("- private DNS: ${network.privateDnsActive}${network.privateDnsName.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}")
            appendLine("- local IPv4/IPv6: ${network.localIpv4}/${network.localIpv6}")
            appendLine("- DNS servers: ${network.dnsCount}")
            appendLine("- local/public subscriber IP addresses are intentionally omitted")
            if (customEndpoint.isNotBlank()) appendLine("- custom endpoint tested: ${redactUrlForReport(customEndpoint)}")
            if (privateRelayDomain.isNotBlank()) {
                appendLine("- private relay tested: $privateRelayDomain")
                appendLine("- private relay /apiws auth tests: ${if (privateRelayAuthTests) "enabled" else "skipped (token missing/invalid)"}")
            }
            appendLine()

            fatalError?.let {
                appendLine("FATAL: $it")
                return@buildString
            }

            results.forEach { result ->
                appendLine("[${if (result.success) "PASS" else "FAIL"}] ${result.spec.group} / ${result.spec.label}")
                appendLine("  target=${result.spec.connectHost}:${result.spec.port} sni=${result.spec.tlsHost} kind=${result.spec.kind}")
                appendLine("  dns=${result.dnsMs}ms A=${result.ipv4Count} AAAA=${result.ipv6Count}")
                if (result.attempts.isEmpty()) {
                    appendLine("  no address attempts")
                }
                result.attempts.forEach { attempt ->
                    val timing = listOfNotNull(
                        attempt.tcpMs?.let { "tcp=${it}ms" },
                        attempt.tlsMs?.let { "tls=${it}ms" }
                    ).joinToString(" ")
                    val transportDetails = listOfNotNull(
                        attempt.protocol,
                        attempt.cipher,
                        attempt.httpStatus?.let { "http=$it" },
                        attempt.bytesRead?.let { "bytes=$it" }
                    ).joinToString(" ")
                    append("  - ${attempt.family} ${attempt.address} ${if (attempt.success) "OK" else "FAIL"} stage=${attempt.stage}")
                    if (timing.isNotBlank()) append(" $timing")
                    if (transportDetails.isNotBlank()) append(" $transportDetails")
                    attempt.error?.let { append(" error=$it") }
                    appendLine()
                }
                appendLine()
            }

            appendLine("Interpretation hints:")
            appendLine("- TCP FAIL: routing/IP filtering or temporary mobile-network restriction.")
            appendLine("- TCP OK + TLS FAIL: SNI/TLS/fingerprint filtering or TLS incompatibility.")
            appendLine("- TLS OK + WS not 101: WebSocket/path/domain filtering, redirect, rate limit, or endpoint failure.")
            appendLine("- Relay /apiws DC PASS means Caddy+relay reached that Telegram DC and accepted WSS.")
            appendLine("- Relay download/WSS stream PASS means large phone↔VPS transfers are not being cut off.")
            appendLine("- Cloudflare 64 KiB below ~65536 bytes: possible early connection cutoff/throttling.")
        }
    }

    suspend fun run(
        context: Context,
        customEndpoint: String = "",
        privateRelayDomain: String = "",
        privateRelayToken: String = "",
        onProgress: suspend (completed: Int, total: Int, label: String) -> Unit = { _, _, _ -> }
    ): Report {
        val startedAt = System.currentTimeMillis()
        var snapshot = emptyNetworkSnapshot()
        val relayHosts = parseRelayHosts(privateRelayDomain)
        val relayHost = relayHosts.joinToString(",")
        val relayAuthEnabled = privateRelayToken.trim().length >= 32

        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val activeNetwork = cm?.activeNetwork
            snapshot = networkSnapshot(context, cm, activeNetwork)

            if (activeNetwork == null) {
                return Report(
                    startedAt = startedAt,
                    finishedAt = System.currentTimeMillis(),
                    network = snapshot,
                    results = emptyList(),
                    customEndpoint = customEndpoint.trim(),
                    privateRelayDomain = relayHost,
                    privateRelayAuthTests = relayAuthEnabled,
                    fatalError = "No active Android network"
                )
            }

            val specs = defaultSpecs().toMutableList()
            parseCustomEndpoint(customEndpoint)?.let(specs::add)
            relayHosts.forEach { host -> specs += privateRelaySpecs(host, privateRelayToken.trim()) }

            val results = ArrayList<ProbeResult>(specs.size)
            specs.forEachIndexed { index, spec ->
                onProgress(index, specs.size, spec.label)
                results += probe(activeNetwork, spec)
            }
            onProgress(specs.size, specs.size, "done")

            Report(
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                network = snapshot,
                results = results,
                customEndpoint = customEndpoint.trim(),
                privateRelayDomain = relayHost,
                privateRelayAuthTests = relayAuthEnabled
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Report(
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                network = snapshot,
                results = emptyList(),
                customEndpoint = customEndpoint.trim(),
                privateRelayDomain = relayHost,
                privateRelayAuthTests = relayAuthEnabled,
                fatalError = compactError(error)
            )
        }
    }

    private fun defaultSpecs(): List<ProbeSpec> = listOf(
        ProbeSpec(
            id = "control-ya",
            group = "Control",
            label = "Android TLS to ya.ru",
            connectHost = "ya.ru",
            kind = ProbeKind.TLS
        ),
        ProbeSpec(
            id = "tg-kws2-dns",
            group = "Telegram direct",
            label = "DC2 WebSocket by DNS",
            connectHost = "kws2.web.telegram.org",
            path = "/apiws",
            kind = ProbeKind.WEBSOCKET
        ),
        ProbeSpec(
            id = "tg-kws2-ip",
            group = "Telegram direct",
            label = "DC2 WebSocket fixed IP",
            connectHost = "149.154.167.51",
            tlsHost = "kws2.web.telegram.org",
            path = "/apiws",
            kind = ProbeKind.WEBSOCKET,
            fixedAddress = "149.154.167.51"
        ),
        ProbeSpec(
            id = "tg-kws4-ip",
            group = "Telegram direct",
            label = "DC4 WebSocket fixed IP",
            connectHost = "149.154.167.91",
            tlsHost = "kws4.web.telegram.org",
            path = "/apiws",
            kind = ProbeKind.WEBSOCKET,
            fixedAddress = "149.154.167.91"
        ),
        ProbeSpec(
            id = "cf-offshor",
            group = "Cloudflare shared",
            label = "CF WS offshor",
            connectHost = "kws2.offshor.co.uk",
            path = "/apiws",
            kind = ProbeKind.WEBSOCKET
        ),
        ProbeSpec(
            id = "cf-noskomnadzor",
            group = "Cloudflare shared",
            label = "CF WS noskomnadzor",
            connectHost = "kws2.noskomnadzor.co.uk",
            path = "/apiws",
            kind = ProbeKind.WEBSOCKET
        ),
        ProbeSpec(
            id = "cf-sorokdva",
            group = "Cloudflare shared",
            label = "CF WS sorokdva",
            connectHost = "kws2.sorokdva.co.uk",
            path = "/apiws",
            kind = ProbeKind.WEBSOCKET
        ),
        ProbeSpec(
            id = "cf-64k",
            group = "Cloudflare control",
            label = "Cloudflare 64 KiB transfer",
            connectHost = "speed.cloudflare.com",
            path = "/__down?bytes=$CF_DOWNLOAD_TARGET_BYTES",
            kind = ProbeKind.HTTP_DOWNLOAD,
            expectedBytes = CF_DOWNLOAD_TARGET_BYTES
        )
    )

    private fun privateRelaySpecs(domain: String, token: String): List<ProbeSpec> {
        if (domain.isBlank()) return emptyList()
        val slug = domain.replace(Regex("[^A-Za-z0-9]+"), "-")
        val (host, port) = splitRelayHostPort(domain)
        val specs = mutableListOf(
            ProbeSpec(
                id = "relay-healthz-$slug",
                group = "Private relay",
                label = "Relay /healthz",
                connectHost = host,
                port = port,
                path = "/healthz",
                kind = ProbeKind.HTTP_STATUS
            ),
            ProbeSpec(
                id = "relay-readyz-$slug",
                group = "Private relay",
                label = "Relay /readyz DC reachability",
                connectHost = host,
                port = port,
                path = "/readyz",
                kind = ProbeKind.HTTP_STATUS
            ),
            ProbeSpec(
                id = "relay-probe-$slug",
                group = "Private relay",
                label = "Relay /probe WSS handshake",
                connectHost = host,
                port = port,
                path = "/probe",
                kind = ProbeKind.WEBSOCKET
            ),
            ProbeSpec(
                id = "relay-download-1m-$slug",
                group = "Private relay large transfer",
                label = "Relay HTTPS download 1 MiB",
                connectHost = host,
                port = port,
                path = "/download?bytes=$RELAY_1_MIB",
                kind = ProbeKind.HTTP_DOWNLOAD,
                expectedBytes = RELAY_1_MIB
            ),
            ProbeSpec(
                id = "relay-download-10m-$slug",
                group = "Private relay large transfer",
                label = "Relay HTTPS download 10 MiB",
                connectHost = host,
                port = port,
                path = "/download?bytes=$RELAY_10_MIB",
                kind = ProbeKind.HTTP_DOWNLOAD,
                expectedBytes = RELAY_10_MIB
            ),
            ProbeSpec(
                id = "relay-wss-1m-$slug",
                group = "Private relay large transfer",
                label = "Relay WSS stream 1 MiB",
                connectHost = host,
                port = port,
                path = "/probe-stream?bytes=$RELAY_1_MIB",
                kind = ProbeKind.WEBSOCKET_STREAM,
                expectedBytes = RELAY_1_MIB
            ),
            ProbeSpec(
                id = "relay-wss-10m-$slug",
                group = "Private relay large transfer",
                label = "Relay WSS stream 10 MiB",
                connectHost = host,
                port = port,
                path = "/probe-stream?bytes=$RELAY_10_MIB",
                kind = ProbeKind.WEBSOCKET_STREAM,
                expectedBytes = RELAY_10_MIB
            ),
            ProbeSpec(
                id = "relay-wss-50m-$slug",
                group = "Private relay large transfer",
                label = "Relay WSS stream 50 MiB",
                connectHost = host,
                port = port,
                path = "/probe-stream?bytes=$RELAY_50_MIB",
                kind = ProbeKind.WEBSOCKET_STREAM,
                expectedBytes = RELAY_50_MIB
            ),
            ProbeSpec(
                id = "relay-wss-upload-50m-$slug",
                group = "Private relay large upload",
                label = "Relay WSS upload 50 MiB",
                connectHost = host,
                port = port,
                path = "/probe-upload?bytes=$RELAY_50_MIB",
                kind = ProbeKind.WEBSOCKET_UPLOAD,
                expectedBytes = RELAY_50_MIB
            )
        )

        if (token.length >= 32) {
            for (dc in listOf(1, 2, 3, 4, 5, 203)) {
                specs += ProbeSpec(
                    id = "relay-apiws-dc$dc-$slug",
                    group = "Private relay DC",
                    label = "Relay /apiws DC$dc",
                    connectHost = host,
                    port = port,
                    path = "/apiws?dc=$dc",
                    kind = ProbeKind.WEBSOCKET,
                    authBearer = token
                )
            }
        }
        return specs
    }

    private fun parseCustomEndpoint(raw: String): ProbeSpec? {
        val value = raw.trim()
        if (value.isBlank()) return null
        return try {
            val normalized = if ("://" in value) value else "wss://$value/apiws"
            val uri = URI(normalized)
            if (uri.scheme.lowercase(Locale.US) != "wss" || uri.host.isNullOrBlank()) return null
            ProbeSpec(
                id = "custom-wss",
                group = "Custom relay",
                label = "Custom WSS endpoint",
                connectHost = uri.host,
                tlsHost = uri.host,
                port = if (uri.port > 0) uri.port else 443,
                path = uri.rawPath.takeUnless { it.isNullOrBlank() }?.let {
                    if (uri.rawQuery.isNullOrBlank()) it else "$it?${uri.rawQuery}"
                } ?: "/apiws",
                kind = ProbeKind.WEBSOCKET
            )
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun probe(network: Network, spec: ProbeSpec): ProbeResult = withContext(Dispatchers.IO) {
        val dnsStarted = SystemClock.elapsedRealtime()
        val addresses = try {
            if (spec.fixedAddress != null) {
                listOf(InetAddress.getByName(spec.fixedAddress))
            } else {
                withTimeout(DNS_TIMEOUT_MS) { network.getAllByName(spec.connectHost).toList() }
            }
        } catch (e: Exception) {
            val dnsMs = SystemClock.elapsedRealtime() - dnsStarted
            return@withContext ProbeResult(
                spec = spec,
                dnsMs = dnsMs,
                ipv4Count = 0,
                ipv6Count = 0,
                attempts = listOf(
                    AddressAttempt(
                        family = "DNS",
                        address = spec.connectHost,
                        success = false,
                        stage = "dns",
                        error = compactError(e)
                    )
                )
            )
        }
        val dnsMs = SystemClock.elapsedRealtime() - dnsStarted
        val unique = addresses.distinctBy { it.hostAddress }
        val selected = listOfNotNull(
            unique.firstOrNull { it is Inet4Address },
            unique.firstOrNull { it is Inet6Address }
        )

        val attempts = coroutineScope {
            selected.map { address ->
                async(Dispatchers.IO) { attemptAddress(network, spec, address) }
            }.map { it.await() }
        }

        ProbeResult(
            spec = spec,
            dnsMs = dnsMs,
            ipv4Count = unique.count { it is Inet4Address },
            ipv6Count = unique.count { it is Inet6Address },
            attempts = attempts
        )
    }

    private fun attemptAddress(network: Network, spec: ProbeSpec, address: InetAddress): AddressAttempt {
        val family = if (address is Inet6Address) "IPv6" else "IPv4"
        val printableAddress = address.hostAddress.orEmpty().substringBefore('%')
        var tcpMs: Long? = null
        var tlsMs: Long? = null
        var stage = "tcp"
        var raw: Socket? = null
        var tls: SSLSocket? = null

        return try {
            raw = Socket()
            network.bindSocket(raw)
            raw.soTimeout = IO_TIMEOUT_MS
            val tcpStarted = SystemClock.elapsedRealtime()
            raw.connect(java.net.InetSocketAddress(address, spec.port), CONNECT_TIMEOUT_MS)
            tcpMs = SystemClock.elapsedRealtime() - tcpStarted

            stage = "tls"
            val tlsStarted = SystemClock.elapsedRealtime()
            tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, spec.tlsHost, spec.port, true) as SSLSocket
            tls.soTimeout = if (spec.kind == ProbeKind.WEBSOCKET_STREAM || spec.kind == ProbeKind.WEBSOCKET_UPLOAD || spec.expectedBytes >= RELAY_10_MIB) STREAM_TIMEOUT_MS else IO_TIMEOUT_MS
            val params = tls.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            params.serverNames = listOf(SNIHostName(spec.tlsHost))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                params.applicationProtocols = arrayOf("http/1.1")
            }
            tls.sslParameters = params
            tls.startHandshake()
            tlsMs = SystemClock.elapsedRealtime() - tlsStarted
            val session = tls.session
            val protocol = session.protocol
            val cipher = session.cipherSuite

            when (spec.kind) {
                ProbeKind.TLS -> AddressAttempt(
                    family = family,
                    address = printableAddress,
                    tcpMs = tcpMs,
                    tlsMs = tlsMs,
                    protocol = protocol,
                    cipher = cipher,
                    success = true,
                    stage = "tls"
                )

                ProbeKind.WEBSOCKET -> {
                    stage = "websocket"
                    val ws = websocketExchange(tls, spec.tlsHost, spec.path, spec.authBearer, 0)
                    AddressAttempt(
                        family = family,
                        address = printableAddress,
                        tcpMs = tcpMs,
                        tlsMs = tlsMs,
                        protocol = protocol,
                        cipher = cipher,
                        httpStatus = ws.status,
                        bytesRead = ws.bytesRead.takeIf { it > 0 },
                        success = ws.status == 101,
                        stage = "websocket",
                        error = if (ws.status == 101) null else ws.error ?: "expected HTTP 101"
                    )
                }

                ProbeKind.WEBSOCKET_STREAM -> {
                    stage = "ws-stream"
                    val ws = websocketExchange(tls, spec.tlsHost, spec.path, spec.authBearer, spec.expectedBytes)
                    val enough = ws.bytesRead >= spec.expectedBytes
                    AddressAttempt(
                        family = family,
                        address = printableAddress,
                        tcpMs = tcpMs,
                        tlsMs = tlsMs,
                        protocol = protocol,
                        cipher = cipher,
                        httpStatus = ws.status,
                        bytesRead = ws.bytesRead,
                        success = ws.status == 101 && enough,
                        stage = "ws-stream",
                        error = when {
                            ws.status != 101 -> ws.error ?: "expected HTTP 101"
                            !enough -> "short WSS stream"
                            else -> null
                        }
                    )
                }

                ProbeKind.WEBSOCKET_UPLOAD -> {
                    stage = "ws-upload"
                    val ws = websocketUpload(tls, spec.tlsHost, spec.path, spec.authBearer, spec.expectedBytes)
                    val enough = ws.bytesRead >= spec.expectedBytes
                    AddressAttempt(
                        family = family,
                        address = printableAddress,
                        tcpMs = tcpMs,
                        tlsMs = tlsMs,
                        protocol = protocol,
                        cipher = cipher,
                        httpStatus = ws.status,
                        bytesRead = ws.bytesRead,
                        success = ws.status == 101 && enough,
                        stage = "ws-upload",
                        error = when {
                            ws.status != 101 -> ws.error ?: "expected HTTP 101"
                            !enough -> "short WSS upload"
                            else -> null
                        }
                    )
                }

                ProbeKind.HTTP_STATUS -> {
                    stage = "http"
                    val status = httpStatus(tls, spec.tlsHost, spec.path, spec.authBearer)
                    AddressAttempt(
                        family = family,
                        address = printableAddress,
                        tcpMs = tcpMs,
                        tlsMs = tlsMs,
                        protocol = protocol,
                        cipher = cipher,
                        httpStatus = status.status,
                        bytesRead = status.bytesRead,
                        success = status.status in 200..299,
                        stage = "http",
                        error = if (status.status in 200..299) null else status.error ?: "HTTP ${status.status}"
                    )
                }

                ProbeKind.HTTP_DOWNLOAD -> {
                    stage = "download"
                    val download = httpDownload(tls, spec.tlsHost, spec.path, spec.expectedBytes, spec.authBearer)
                    val enough = download.bytesRead >= spec.expectedBytes
                    AddressAttempt(
                        family = family,
                        address = printableAddress,
                        tcpMs = tcpMs,
                        tlsMs = tlsMs,
                        protocol = protocol,
                        cipher = cipher,
                        httpStatus = download.status,
                        bytesRead = download.bytesRead,
                        success = download.status in 200..299 && enough,
                        stage = "download",
                        error = when {
                            download.status !in 200..299 -> download.error ?: "HTTP ${download.status}"
                            !enough -> "short transfer"
                            else -> null
                        }
                    )
                }
            }
        } catch (e: Exception) {
            AddressAttempt(
                family = family,
                address = printableAddress,
                tcpMs = tcpMs,
                tlsMs = tlsMs,
                success = false,
                stage = stage,
                error = compactError(e)
            )
        } finally {
            try { tls?.close() } catch (_: Exception) {}
            try { raw?.close() } catch (_: Exception) {}
        }
    }

    private data class WsExchangeResult(val status: Int, val bytesRead: Int = 0, val error: String? = null)
    private data class HttpReadResult(val status: Int, val bytesRead: Int = 0, val error: String? = null)

    private fun websocketExchange(socket: SSLSocket, host: String, path: String, authBearer: String, expectedBytes: Int): WsExchangeResult {
        val keyBytes = ByteArray(16).also(SecureRandom()::nextBytes)
        val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        val request = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: $host\r\n")
            append("Connection: Upgrade\r\n")
            append("Upgrade: websocket\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Sec-WebSocket-Protocol: binary\r\n")
            if (authBearer.isNotBlank()) append("Authorization: Bearer $authBearer\r\n")
            append("Origin: https://web.telegram.org\r\n")
            append("User-Agent: Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36\r\n")
            append("\r\n")
        }
        val output = BufferedOutputStream(socket.outputStream)
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()

        val input = BufferedInputStream(socket.inputStream)
        val header = readHeaders(input)
        val status = parseHttpStatus(header)
        if (status != 101) {
            val body = readSmallBody(input)
            return WsExchangeResult(status = status, error = body.takeIf { it.isNotBlank() })
        }

        val expected = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),
            Base64.NO_WRAP
        )
        val accept = header.lineSequence()
            .firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        if (accept != expected) return WsExchangeResult(status = -101, error = "bad Sec-WebSocket-Accept")

        if (expectedBytes <= 0) return WsExchangeResult(status = status)
        return WsExchangeResult(status = status, bytesRead = readWebSocketPayloadBytes(input, expectedBytes))
    }

    private fun httpStatus(socket: SSLSocket, host: String, path: String, authBearer: String): HttpReadResult {
        val request = buildHttpRequest(host, path, authBearer, accept = "application/json")
        val output = BufferedOutputStream(socket.outputStream)
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()
        val input = BufferedInputStream(socket.inputStream)
        val header = readHeaders(input)
        val status = parseHttpStatus(header)
        val body = readSmallBody(input)
        return HttpReadResult(status = status, bytesRead = body.toByteArray(Charsets.UTF_8).size, error = body.takeIf { it.isNotBlank() })
    }

    private fun websocketUpload(socket: SSLSocket, host: String, path: String, authBearer: String, bytesToSend: Int): WsExchangeResult {
        val keyBytes = ByteArray(16).also(SecureRandom()::nextBytes)
        val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        val request = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: $host\r\n")
            append("Connection: Upgrade\r\n")
            append("Upgrade: websocket\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Sec-WebSocket-Protocol: binary\r\n")
            if (authBearer.isNotBlank()) append("Authorization: Bearer $authBearer\r\n")
            append("Origin: https://web.telegram.org\r\n")
            append("User-Agent: TG-WS-Proxy-Network-Lab/${BuildConfig.VERSION_NAME}\r\n")
            append("\r\n")
        }
        val output = BufferedOutputStream(socket.outputStream)
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()

        val input = BufferedInputStream(socket.inputStream)
        val header = readHeaders(input)
        val status = parseHttpStatus(header)
        if (status != 101) {
            val body = readSmallBody(input)
            return WsExchangeResult(status = status, error = body.takeIf { it.isNotBlank() })
        }
        val expected = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),
            Base64.NO_WRAP
        )
        val accept = header.lineSequence()
            .firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        if (accept != expected) return WsExchangeResult(status = -101, error = "bad Sec-WebSocket-Accept")

        val chunk = ByteArray(64 * 1024) { i -> ((i * 31 + 17) and 0xff).toByte() }
        var sent = 0
        while (sent < bytesToSend) {
            val n = minOf(chunk.size, bytesToSend - sent)
            writeMaskedWebSocketFrame(output, chunk, n)
            sent += n
        }
        output.flush()
        val ack = readOneWebSocketText(input)
        return WsExchangeResult(status = status, bytesRead = sent, error = if (ack.contains("upload-ok")) null else "upload ack missing: ${ack.take(80)}")
    }

    private fun writeMaskedWebSocketFrame(output: BufferedOutputStream, payload: ByteArray, length: Int) {
        val header = ArrayList<Byte>(14)
        header += 0x82.toByte()
        when {
            length < 126 -> header += (0x80 or length).toByte()
            length <= 0xffff -> {
                header += (0x80 or 126).toByte()
                header += ((length ushr 8) and 0xff).toByte()
                header += (length and 0xff).toByte()
            }
            else -> throw IllegalArgumentException("frame too large")
        }
        val mask = ByteArray(4).also(SecureRandom()::nextBytes)
        header.addAll(mask.toList())
        output.write(header.toByteArray())
        val masked = ByteArray(length)
        for (i in 0 until length) masked[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        output.write(masked)
    }

    private fun readOneWebSocketText(input: BufferedInputStream): String {
        val b1 = input.read()
        val b2 = input.read()
        if (b1 < 0 || b2 < 0) return ""
        var length = (b2 and 0x7f).toLong()
        if (length == 126L) length = readUInt16(input).toLong()
        else if (length == 127L) length = readUInt64Capped(input)
        val masked = (b2 and 0x80) != 0
        val mask = if (masked) ByteArray(4).also { readFully(input, it, it.size) } else null
        val len = length.coerceAtMost(4096).toInt()
        val buf = ByteArray(len)
        readFully(input, buf, len)
        if (mask != null) for (i in buf.indices) buf[i] = (buf[i].toInt() xor mask[i and 3].toInt()).toByte()
        if (length > len) skipBytes(input, length - len, ByteArray(1024), mask)
        return buf.toString(Charsets.UTF_8)
    }

    private fun httpDownload(socket: SSLSocket, host: String, path: String, targetBytes: Int, authBearer: String): HttpReadResult {
        val request = buildHttpRequest(host, path, authBearer, accept = "application/octet-stream")
        val output = BufferedOutputStream(socket.outputStream)
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()

        val input = BufferedInputStream(socket.inputStream)
        val header = readHeaders(input)
        val status = parseHttpStatus(header)
        var count = 0
        val buffer = ByteArray(32 * 1024)
        val limit = targetBytes + 8 * 1024
        while (count < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - count))
            if (read < 0) break
            count += read
            if (count >= targetBytes) break
        }
        return HttpReadResult(status = status, bytesRead = count)
    }

    private fun buildHttpRequest(host: String, path: String, authBearer: String, accept: String): String = buildString {
        append("GET $path HTTP/1.1\r\n")
        append("Host: $host\r\n")
        append("Connection: close\r\n")
        append("Accept: $accept\r\n")
        if (authBearer.isNotBlank()) append("Authorization: Bearer $authBearer\r\n")
        append("User-Agent: TG-WS-Proxy-Network-Lab/${BuildConfig.VERSION_NAME}\r\n")
        append("\r\n")
    }

    private fun readWebSocketPayloadBytes(input: BufferedInputStream, targetBytes: Int): Int {
        var total = 0
        val scratch = ByteArray(32 * 1024)
        while (total < targetBytes) {
            val b1 = input.read()
            val b2 = input.read()
            if (b1 < 0 || b2 < 0) break
            val opcode = b1 and 0x0f
            val masked = (b2 and 0x80) != 0
            var length = (b2 and 0x7f).toLong()
            if (length == 126L) {
                length = readUInt16(input).toLong()
            } else if (length == 127L) {
                length = readUInt64Capped(input)
            }
            val mask = if (masked) ByteArray(4).also { readFully(input, it, it.size) } else null
            if (opcode == 0x8) {
                skipBytes(input, length, scratch, mask)
                break
            }
            var remaining = length
            var maskIndex = 0
            while (remaining > 0) {
                val want = minOf(scratch.size.toLong(), remaining).toInt()
                val read = input.read(scratch, 0, want)
                if (read < 0) return total
                if (mask != null) {
                    for (i in 0 until read) scratch[i] = (scratch[i].toInt() xor mask[(maskIndex + i) and 3].toInt()).toByte()
                    maskIndex += read
                }
                if (opcode == 0x0 || opcode == 0x1 || opcode == 0x2) {
                    total += read
                    if (total >= targetBytes) {
                        skipBytes(input, remaining - read, scratch, mask?.let { ByteArray(4) })
                        return total
                    }
                }
                remaining -= read.toLong()
            }
        }
        return total
    }

    private fun readUInt16(input: BufferedInputStream): Int {
        val a = input.read()
        val b = input.read()
        if (a < 0 || b < 0) return 0
        return (a shl 8) or b
    }

    private fun readUInt64Capped(input: BufferedInputStream): Long {
        var value = 0L
        repeat(8) {
            val b = input.read()
            if (b >= 0 && value < Int.MAX_VALUE) value = (value shl 8) or b.toLong()
        }
        return value.coerceAtMost(Int.MAX_VALUE.toLong())
    }

    private fun readFully(input: BufferedInputStream, buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) break
            offset += read
        }
    }

    private fun skipBytes(input: BufferedInputStream, bytes: Long, buffer: ByteArray, mask: ByteArray?) {
        var remaining = bytes
        var maskIndex = 0
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) return
            if (mask != null) maskIndex += read
            remaining -= read.toLong()
        }
        val _unused = maskIndex
    }

    private fun readSmallBody(input: BufferedInputStream): String {
        val buffer = ByteArray(1024)
        val out = ArrayList<Byte>(1024)
        while (out.size < MAX_STATUS_BODY_BYTES) {
            val read = input.read(buffer, 0, minOf(buffer.size, MAX_STATUS_BODY_BYTES - out.size))
            if (read < 0) break
            for (i in 0 until read) out += buffer[i]
        }
        return out.toByteArray().toString(Charsets.UTF_8)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(220)
    }

    private fun readHeaders(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>(1024)
        var state = 0
        while (bytes.size < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) break
            val b = value.toByte()
            bytes += b
            state = when {
                state == 0 && b == '\r'.code.toByte() -> 1
                state == 1 && b == '\n'.code.toByte() -> 2
                state == 2 && b == '\r'.code.toByte() -> 3
                state == 3 && b == '\n'.code.toByte() -> 4
                b == '\r'.code.toByte() -> 1
                else -> 0
            }
            if (state == 4) break
        }
        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
    }

    private fun parseHttpStatus(header: String): Int =
        header.lineSequence().firstOrNull()
            ?.split(' ')
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: -1

    private fun compactError(error: Throwable): String {
        val message = error.message.orEmpty()
            .replace(Regex("from /\\S+(?: \\(port \\d+\\))?"), "from /[local-address-redacted]")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(180)
        return if (message.isBlank()) error.javaClass.simpleName else "${error.javaClass.simpleName}: $message"
    }

    private fun parseRelayHosts(raw: String): List<String> = raw
        .split(',', ';', '\n', '\t', ' ')
        .map { value ->
            value.trim()
                .removePrefix("wss://")
                .removePrefix("https://")
                .removePrefix("ws://")
                .removePrefix("http://")
                .substringBefore('/')
                .trim()
                .lowercase(Locale.US)
        }
        .filter { it.isNotBlank() }
        .distinct()
        .take(8)

    // "relay.example.ru:8443" -> ("relay.example.ru", 8443); без порта -> (host, 443)
    private val relayHostPortRegex = Regex("^([a-z0-9.-]+)(:(\\d{1,5}))?$")

    private fun splitRelayHostPort(value: String): Pair<String, Int> {
        val m = relayHostPortRegex.matchEntire(value) ?: return value.substringBefore(':').to(443)
        val host = m.groupValues[1]
        val port = m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 443
        if (port !in 1..65535) return host.to(443)
        return host.to(port)
    }

    private fun redactUrlForReport(raw: String): String = try {
        raw.replace(Regex("(?i)(token=)[^&\\s]+"), "$1[redacted]")
            .replace(Regex("(?i)(Authorization: Bearer )[^&\\s]+"), "$1[redacted]")
    } catch (_: Exception) {
        "[unprintable-url]"
    }

    private fun emptyNetworkSnapshot() = NetworkSnapshot(
        transport = "unknown",
        operator = "",
        simOperator = "",
        metered = false,
        validated = false,
        vpn = false,
        privateDnsActive = false,
        privateDnsName = "",
        localIpv4 = false,
        localIpv6 = false,
        dnsCount = 0
    )

    private fun networkSnapshot(
        context: Context,
        cm: ConnectivityManager?,
        network: Network?
    ): NetworkSnapshot {
        val caps = if (cm != null && network != null) {
            cm.getNetworkCapabilities(network)
        } else {
            null
        }
        val links = if (cm != null && network != null) {
            cm.getLinkProperties(network)
        } else {
            null
        }
        val transports = buildList {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
        }.joinToString("+").ifBlank { "unknown" }

        val telephony = context.getSystemService(TelephonyManager::class.java)
        val addresses = links?.linkAddresses.orEmpty().map { it.address }
        return NetworkSnapshot(
            transport = transports,
            operator = try { telephony?.networkOperatorName.orEmpty() } catch (_: Exception) { "" },
            simOperator = try { telephony?.simOperatorName.orEmpty() } catch (_: Exception) { "" },
            metered = cm?.isActiveNetworkMetered ?: false,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            privateDnsActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) links?.isPrivateDnsActive == true else false,
            privateDnsName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) links?.privateDnsServerName.orEmpty() else "",
            localIpv4 = addresses.any { it is Inet4Address },
            localIpv6 = addresses.any { it is Inet6Address },
            dnsCount = links?.dnsServers?.size ?: 0
        )
    }
}
