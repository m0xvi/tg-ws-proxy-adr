package com.amurcanov.tgwsproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurcanov.tgwsproxy.NetworkDiagnostics
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsTab(settingsStore: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val privateRelayDomain by settingsStore.privateRelayDomain.collectAsStateWithLifecycle(initialValue = "")
    val privateRelayToken by settingsStore.privateRelayToken.collectAsStateWithLifecycle(initialValue = "")
    var customEndpoint by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<NetworkDiagnostics.Report?>(null) }
    var running by remember { mutableStateOf(false) }
    var completed by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var currentLabel by remember { mutableStateOf("") }
    var uiError by remember { mutableStateOf<String?>(null) }
    var runJob by remember { mutableStateOf<Job?>(null) }

    fun startDiagnostics() {
        if (running) return
        running = true
        report = null
        uiError = null
        completed = 0
        total = 0
        currentLabel = context.getString(R.string.diagnostics_preparing)
        runJob = scope.launch {
            try {
                report = NetworkDiagnostics.run(
                    context = context.applicationContext,
                    customEndpoint = customEndpoint,
                    privateRelayDomain = privateRelayDomain,
                    privateRelayToken = privateRelayToken
                ) { done, count, label ->
                    completed = done
                    total = count
                    currentLabel = label
                }
            } catch (_: CancellationException) {
                currentLabel = context.getString(R.string.diagnostics_cancelled)
            } catch (error: Throwable) {
                Log.e("TgWsProxy", "Diagnostics UI failure", error)
                uiError = buildString {
                    append(error.javaClass.simpleName)
                    error.message?.takeIf { it.isNotBlank() }?.let { append(": $it") }
                }
            } finally {
                running = false
            }
        }
    }

    fun copyReport() {
        val text = report?.asText() ?: return
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("TG Network Lab report", text))
        Toast.makeText(context, context.getString(R.string.diagnostics_copied), Toast.LENGTH_SHORT).show()
    }

    fun shareReport() {
        val text = report?.asText() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "TG Network Lab report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.diagnostics_share)))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = stringResource(R.string.diagnostics_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.diagnostics_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
                Text(
                    text = stringResource(R.string.diagnostics_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (privateRelayDomain.isBlank()) {
                        "Private Relay не настроен: будут запущены только базовые сетевые тесты."
                    } else {
                        "Private Relay будет протестирован автоматически: /healthz, /readyz, /probe, /apiws DC1/2/3/4/5/203 и большие HTTPS/WSS потоки. Токен в отчёт не попадает."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
                OutlinedTextField(
                    value = customEndpoint,
                    onValueChange = { customEndpoint = it.trim() },
                    enabled = !running,
                    label = { Text(stringResource(R.string.diagnostics_custom_endpoint)) },
                    supportingText = { Text(stringResource(R.string.diagnostics_custom_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                )

                if (running) {
                    val progress = if (total > 0) completed.toFloat() / total else 0f
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "${completed.coerceAtMost(total)}/$total · $currentLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (running) {
                                runJob?.cancel()
                            } else {
                                startDiagnostics()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        colors = if (running) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        } else {
                            ButtonDefaults.buttonColors()
                        }
                    ) {
                        Icon(
                            imageVector = if (running) Icons.Default.Close else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(Modifier.size(7.dp))
                        Text(
                            text = if (running) stringResource(R.string.diagnostics_stop) else stringResource(R.string.diagnostics_start),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = { copyReport() },
                        enabled = report != null && !running,
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = stringResource(R.string.copy), modifier = Modifier.size(19.dp))
                    }
                    OutlinedButton(
                        onClick = { shareReport() },
                        enabled = report != null && !running,
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.diagnostics_share), modifier = Modifier.size(19.dp))
                    }
                }
            }
        }

        when {
            running && report == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(34.dp))
                }
            }
            uiError != null -> {
                ResultCard(
                    title = stringResource(R.string.diagnostics_fatal),
                    subtitle = uiError.orEmpty(),
                    success = false,
                    details = stringResource(R.string.diagnostics_error_hint)
                )
            }
            report != null -> {
                val current = report!!
                val passed = current.results.count { it.success }
                val failed = current.results.size - passed
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryChip(
                        text = stringResource(R.string.diagnostics_passed, passed),
                        color = Color(0xFF2E7D32),
                        modifier = Modifier.weight(1f)
                    )
                    SummaryChip(
                        text = stringResource(R.string.diagnostics_failed, failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryChip(
                        text = current.network.transport,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    current.fatalError?.let { fatal ->
                        item {
                            ResultCard(
                                title = stringResource(R.string.diagnostics_fatal),
                                subtitle = fatal,
                                success = false,
                                details = ""
                            )
                        }
                    }
                    items(current.results, key = { it.spec.id }) { result ->
                        val details = result.attempts.joinToString("\n") { attempt ->
                            buildString {
                                append("${attempt.family} ${attempt.address}: ")
                                append(if (attempt.success) "OK" else "FAIL")
                                append(" · ${attempt.stage}")
                                attempt.tcpMs?.let { append(" · TCP ${it}ms") }
                                attempt.tlsMs?.let { append(" · TLS ${it}ms") }
                                attempt.protocol?.let { append(" · $it") }
                                attempt.httpStatus?.let { append(" · HTTP $it") }
                                attempt.bytesRead?.let { append(" · $it B") }
                                attempt.error?.let { append(" · $it") }
                            }
                        }
                        ResultCard(
                            title = result.spec.label,
                            subtitle = "${result.spec.group} · DNS ${result.dnsMs}ms · A ${result.ipv4Count} / AAAA ${result.ipv6Count}",
                            success = result.success,
                            details = details
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
            else -> {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.60f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.diagnostics_not_started),
                        modifier = Modifier.padding(18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.30f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun ResultCard(
    title: String,
    subtitle: String,
    success: Boolean,
    details: String
) {
    val color = if (success) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (success) "PASS" else "FAIL",
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    fontWeight = FontWeight.Black
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (details.isNotBlank()) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 17.sp
                )
            }
        }
    }
}
