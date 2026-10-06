package com.netscope.ui.collect

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.netscope.core.capability.CapabilityAvailability
import com.netscope.core.agent.AgentOutcome
import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceType
import com.netscope.core.model.ProbeStatus
import com.netscope.core.permission.PermissionState
import com.netscope.core.util.NsLog
import com.netscope.core.verification.VerificationOutcome




@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectScreen(viewModel: CollectViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    var detailsExpanded by remember { mutableStateOf(false) }
    var pendingArchive by remember { mutableStateOf<ByteArray?>(null) }
    var showGatewaySettings by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = pendingArchive
        pendingArchive = null
        if (uri != null && snapshot != null) viewModel.exportArchive(uri, snapshot)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        NsLog.d("权限申请结果：$result")
        viewModel.onPermissionRequestFinished()
    }

    // 进入页面即实测一次能力：不做缓存、不做预热，保证看到的永远是「这一台机器此刻」的真实情况。
    LaunchedEffect(Unit) {
        viewModel.refreshCapability()
    }

    if (showGatewaySettings) {
        GatewaySettingsDialog(viewModel.gatewayEndpoint(), viewModel::configureGateway) { showGatewaySettings = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NetworkMark()
                        Column {
                            Text("NetScope", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("网诊智航", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { showGatewaySettings = true }, enabled = state.canCollect) { Text("服务设置") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            CollectActionCard(
                state = state,
                onRun = viewModel::runCollectors,
                onSave = viewModel::saveCurrentDiagnosis,
                onVerify = viewModel::verifyCurrentDiagnosis,
                onExport = {
                    pendingArchive = viewModel.prepareArchive()
                    if (pendingArchive != null) exportLauncher.launch("NetScope-${System.currentTimeMillis()}.json")
                },
            )

            if (state.isCapabilityLimited) CapabilityLimitedBanner(state)

            TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                Text(if (detailsExpanded) "收起设备与权限详情" else "查看设备与权限详情")
            }
            AnimatedVisibility(visible = detailsExpanded || state.hasRuntimePending) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PermissionCard(state, { permissions ->
                        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
                    }, viewModel::refreshPermissions)
                    CapabilityCard(state, viewModel::refreshCapability)
                }
            }

            if (state.evidences.isNotEmpty()) {
                RuleMatchSection(state)
                AgentSection(state, viewModel::runAgentDiagnosis) { showGatewaySettings = true }
                state.verification?.let { VerificationSection(it) }
                EvidenceListSection(state.evidences)
            }

            if (state.evidences.isEmpty()) {
                Text("从一次真实观测开始", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("采集后可查看五类证据、运行诊断，并用前后实测检验恢复情况。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SaveFeedbackCard(state)

            if (state.historyPreview.isNotEmpty()) {
                HistoryPreviewSection(state.historyPreview)
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun NetworkMark() {
    val ink = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(36.dp)) {
        val a = Offset(size.width * .2f, size.height * .25f)
        val b = Offset(size.width * .8f, size.height * .5f)
        val c = Offset(size.width * .2f, size.height * .78f)
        drawLine(ink, a, b, 2.4.dp.toPx(), StrokeCap.Round)
        drawLine(ink, b, c, 2.4.dp.toPx(), StrokeCap.Round)
        drawLine(ink, c, a, 2.4.dp.toPx(), StrokeCap.Round)
        listOf(a, b, c).forEach { drawCircle(ink, 4.dp.toPx(), it) }
    }
}

@Composable
private fun AgentSection(state: CollectUiState, onRun: () -> Unit, onSettings: () -> Unit) {
    var stepsExpanded by remember { mutableStateOf(false) }
    InfoCard("模型辅助诊断") {
        Text("以本轮证据为输入，必要时调用固定工具复测。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRun, enabled = state.canSave, modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium) {
            Text(if (state.agentRunning) "诊断中…" else "基于本次证据请求诊断")
        }
        TextButton(onClick = onSettings, enabled = state.canCollect) { Text("模型服务设置") }
        if (state.agentRunning) {
            Text("正在读取证据 · 最多 8 次工具调用 / 90 秒", style = MaterialTheme.typography.bodySmall)
        }
        state.agentError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.agentReport?.let { report ->
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            when (report.outcome) {
                AgentOutcome.MODEL_UNAVAILABLE -> {
                    Text("模型服务暂不可用", fontWeight = FontWeight.SemiBold)
                    Text("${report.reason}。本地规则与证据仍可使用。", style = MaterialTheme.typography.bodySmall)
                }
                AgentOutcome.INCONCLUSIVE -> {
                    Text("未形成可审计结论", fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary)
                    report.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                AgentOutcome.COMPLETED -> {
                    report.claims.forEach { claim ->
                        Text(claim.text, style = MaterialTheme.typography.bodyLarge)
                        Text("引用已核验 · ${claim.evidenceIds.joinToString(", ")}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                    }
                    if (report.semanticReviewPending) {
                        Text("引用有效性已校验；自然语言与证据的语义一致性仍待复核。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("工具调用 ${report.attemptedToolCalls}/8 · 被拒绝断言 ${report.rejectedClaims} 条",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            report.durationMs?.let { Text("本轮耗时 ${it} ms", style = MaterialTheme.typography.labelSmall) }
            if (report.toolSteps.isNotEmpty()) {
                TextButton(onClick = { stepsExpanded = !stepsExpanded }) {
                    Text(if (stepsExpanded) "收起工具调用记录" else "查看工具调用记录")
                }
                AnimatedVisibility(stepsExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        report.toolSteps.forEachIndexed { index, step ->
                            val result = if (step.evidenceId != null) "${step.evidenceId} ${step.status}"
                                else "已拒绝：${step.rejectedReason}"
                            Text("${index + 1}. ${step.name} → $result", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GatewaySettingsDialog(initialEndpoint: String, onApply: (String, String) -> Boolean, onClose: () -> Unit) {
    var endpoint by remember { mutableStateOf(initialEndpoint) }
    var code by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("模型服务设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("填写服务端提供的网关地址与服务访问码。访问码仅在本次应用运行期间保留，重启后需重新输入；不是大模型 API Key。")
                OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("服务地址") }, singleLine = true)
                OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("服务访问码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (invalid) Text("地址须为 HTTPS 且以 /v1/chat/completions 结尾；访问码为服务端提供的 32–128 位字符。本机调试可使用回环 HTTP 并留空访问码。", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { if (onApply(endpoint, code)) onClose() else invalid = true }) { Text("应用设置") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

@Composable
private fun VerificationSection(report: com.netscope.core.verification.VerificationReport) {
    InfoCard(title = "修复后复验 · 前后实测") {
        val conclusion = when (report.outcome) {
            VerificationOutcome.OBSERVED_RECOVERY -> "本轮观测到故障现象恢复；不能单凭前后对照证明修复动作的因果。"
            VerificationOutcome.STILL_AFFECTED -> "本轮仍有规则命中或探针不可用，不能判定恢复。"
            VerificationOutcome.INCONCLUSIVE -> "复验数据或规则结果不完整，无法判定。"
            VerificationOutcome.NO_PRIOR_ISSUE -> "复验前未观察到故障或规则命中。"
        }
        Text(conclusion)
        report.comparisons.forEach { item ->
            Text("${item.type.displayName}: ${item.beforeId} ${item.beforeStatus} → " +
                "${item.afterId ?: "<缺失>"} ${item.afterStatus ?: "<缺失>"}",
                style = MaterialTheme.typography.bodySmall)
            item.metrics.take(3).forEach { metric ->
                Text("  ${metric.key}: ${metric.before} → ${metric.after}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (report.remainingRuleIds.isNotEmpty()) {
            Text("仍命中规则：${report.remainingRuleIds.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RuleMatchSection(state: CollectUiState) {
    InfoCard("本地规则诊断") {
        if (state.ruleError != null) {
            Text(state.ruleError, color = MaterialTheme.colorScheme.error)
        } else if (state.ruleMatches.isEmpty()) {
            Text("未命中已配置规则", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            Text("本轮未形成规则支持的根因判断，可进一步查看证据。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            state.ruleMatches.take(3).forEach { match ->
                Text(match.summary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text("${match.ruleId} · ${match.causeCode} · 规则分值 ${(match.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("依据 ${match.evidenceIds.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun CapabilityLimitedBanner(state: CollectUiState) {
    val unavailable = state.capability?.unavailableCapabilities().orEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "部分设备能力受限",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (unavailable.isEmpty()) {
                    "部分必需权限未授予。系统会如实标注这些数据为「不可得」，而不是用默认值代替。"
                } else {
                    "不可得项：" + unavailable.joinToString("、") +
                        "。系统会如实标注为「不可得」，而不是用默认值代替。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 权限矩阵
// ---------------------------------------------------------------------------

@Composable
private fun PermissionCard(
    state: CollectUiState,
    onRequestPermissions: (List<String>) -> Unit,
    onRefresh: () -> Unit,
) {
    InfoCard(title = "权限矩阵") {
        state.permissionStatuses.forEach { status ->
            KeyValueRow(
                key = shortPermissionName(status.spec.permission),
                value = status.state.displayName,
                valueColor = permissionColor(status.state),
            )
            if (!status.state.isGranted) {
                Text(
                    text = status.spec.purpose,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onRequestPermissions(state.runtimeRequestable) },
                enabled = state.hasRuntimePending,
            ) {
                Text(if (state.hasRuntimePending) "申请缺失权限" else "运行时权限已齐备")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = onRefresh) {
                Text("刷新")
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "提示：Android 13 及以上读取 WiFi 名称需要「附近设备」权限；" +
                "Android 12 及以下需要定位权限，且系统定位总开关必须打开。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// 实测能力矩阵
// ---------------------------------------------------------------------------

@Composable
private fun CapabilityCard(
    state: CollectUiState,
    onProbe: () -> Unit,
) {
    InfoCard(title = "实测能力矩阵") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onProbe, enabled = !state.probing) {
                Text("重新探测")
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (state.probing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("探测中…", style = MaterialTheme.typography.bodySmall)
            }
        }

        state.error?.takeIf { state.evidences.isEmpty() }?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        val matrix = state.capability
        if (matrix == null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (state.probing) "正在读取设备能力…" else "尚无结果，请点击「重新探测」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@InfoCard
        }

        Spacer(modifier = Modifier.height(8.dp))
        MatrixSection(title = "设备") {
            KeyValueRow("机型", "${matrix.manufacturer} / ${matrix.model}")
            KeyValueRow("系统", "Android ${matrix.sdkRelease}（API ${matrix.apiLevel}）")
        }
        MatrixSection(title = "网络") {
            KeyValueRow("活动传输", matrix.activeTransports.joinToString(" + ").ifEmpty { "无" })
            KeyValueRow("WiFi 开关", boolText(matrix.wifiEnabled))
            KeyValueRow("VPN", boolText(matrix.vpnActive))
        }
        MatrixSection(title = "实测可读性") {
            KeyValueRow("SSID", availabilityText(matrix.ssid), availabilityColor(matrix.ssid))
            KeyValueRow("BSSID", availabilityText(matrix.bssid), availabilityColor(matrix.bssid))
            KeyValueRow("信号强度", availabilityText(matrix.rssi), availabilityColor(matrix.rssi))
            KeyValueRow("定位总开关", boolText(matrix.locationServiceEnabled))
        }

        if (matrix.notes.isNotEmpty()) {
            MatrixSection(title = "采集说明（原样透传进证据快照）") {
                matrix.notes.forEach { note ->
                    Text(
                        text = "· $note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 采集动作卡
// ---------------------------------------------------------------------------

@Composable
private fun CollectActionCard(
    state: CollectUiState,
    onRun: () -> Unit,
    onSave: () -> Unit,
    onVerify: () -> Unit,
    onExport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = Color(0xFF142B45), contentColor = Color.White,
            shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("看清网络，\n让每个结论有据可查。", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                Text("网络 · WiFi · DNS · TCP · HTTP", style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC3D5E8))
                HorizontalDivider(color = Color(0xFF37516F))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(when {
                        state.collecting -> "正在采集真实网络证据"
                        state.verifying -> "正在对照前后观测"
                        state.agentRunning -> "模型诊断进行中"
                        state.evidences.isNotEmpty() -> "本轮已采集 ${state.evidences.size} 条证据"
                        else -> "准备开始采集"
                    }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    if (state.collecting || state.verifying || state.agentRunning) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color(0xFF81D6C4), strokeWidth = 2.dp)
                    }
                }
                Button(onClick = onRun, enabled = state.canCollect,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB6EFE0),
                        contentColor = Color(0xFF003B32), disabledContainerColor = Color(0xFF38526B),
                        disabledContentColor = Color(0xFFC3D5E8))) {
                    Text(if (state.collecting) "采集中…" else "运行采集", fontWeight = FontWeight.Bold)
                }
            }
        }
        if (state.evidences.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onSave, enabled = state.canSave,
                    modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                    Text(if (state.saving) "保存中…" else "保存本次诊断")
                }
                OutlinedButton(onClick = onExport, enabled = state.canSave,
                    modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                    Text(if (state.exporting) "导出中…" else "导出 JSON")
                }
            }
            TextButton(onClick = onVerify, enabled = state.canVerify, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.verifying) "复验中…" else "修复后重新采集并复验")
            }
        }
        state.lastExportedAt?.let {
            Text("诊断档案已导出", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun EvidenceListSection(evidences: List<Evidence>) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("本轮证据", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${evidences.size} 条真实观测", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("展开可查看全部指标、来源接口与原始快照。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        evidences.forEach { evidence ->
            EvidenceCard(evidence, expanded[evidence.id] ?: false) {
                expanded[evidence.id] = !(expanded[evidence.id] ?: false)
            }
        }
    }
}

@Composable
private fun EvidenceCard(evidence: Evidence, expanded: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(evidence.type.displayName, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text("${evidence.id} · ${evidence.durationMs} ms", style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusBadge(evidence.status)
            }
            val priority = when (evidence.type) {
                EvidenceType.NET_OVERVIEW -> listOf("networkType", "validated", "gateway")
                EvidenceType.WIFI_DETAIL -> listOf("ssid", "rssiDbm", "linkSpeedMbps")
                EvidenceType.DNS_RESOLVE -> listOf("successCount", "testedDomainCount", "avgResolveMs")
                EvidenceType.TCP_PROBE -> listOf("successCount", "targetCount", "handshakeMs")
                EvidenceType.HTTP_PROBE -> listOf("statusCode", "ttfbMs", "totalMs")
                else -> emptyList()
            }
            val compact = priority.mapNotNull { key -> evidence.metrics[key]?.let { key to it } }
                .ifEmpty { evidence.metrics.entries.take(3).map { it.key to it.value } }
            compact.forEach { (key, value) -> KeyValueRow(metricLabel(key), value) }
            if (evidence.metrics.isEmpty() && evidence.status != ProbeStatus.OK) {
                Text("本次未取得可用指标", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            evidence.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                Text(if (expanded) "收起证据详情" else "展开指标与原始快照")
            }
            AnimatedVisibility(expanded) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("来源接口", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text(evidence.source, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        Text("采集时间 ${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(evidence.timestampMs))}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        evidence.metrics.forEach { (key, value) -> KeyValueRow(key, value) }
                        Text("原始快照", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text(evidence.rawSnapshot.ifBlank { "<空>" }, modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(12.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: ProbeStatus) {
    val (label, background, foreground) = when (status) {
        ProbeStatus.OK -> Triple("正常 · OK", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        ProbeStatus.DEGRADED -> Triple("受限 · DEGRADED", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        ProbeStatus.FAILED -> Triple("失败 · FAILED", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        ProbeStatus.TIMEOUT -> Triple("超时 · TIMEOUT", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        ProbeStatus.UNSUPPORTED -> Triple("不可用 · UNSUPPORTED", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(color = background, contentColor = foreground, shape = MaterialTheme.shapes.small) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SaveFeedbackCard(state: CollectUiState) {
    if (state.lastSavedAt == null && state.error == null) return
    if (state.lastSavedAt != null) {
        InfoCard(title = "保存结果") {
            Text(
                text = "本次诊断已存入本机，重新打开应用后可查看最近记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    } else if (state.error != null && state.evidences.isNotEmpty()) {
        InfoCard(title = "保存失败") {
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun HistoryPreviewSection(history: List<Evidence>) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    InfoCard(title = "最近保存 · ${history.size} 条证据") {
        Text(
            text = "以下为本机保存的历史观测，不代表当前网络状态。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        history.forEach { ev ->
            EvidenceCard(
                evidence = ev,
                expanded = expanded[ev.id] ?: false,
                onToggle = { expanded[ev.id] = !(expanded[ev.id] ?: false) },
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// 通用小组件
// ---------------------------------------------------------------------------

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun MatrixSection(title: String, content: @Composable () -> Unit) {
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.height(2.dp))
    Column { content() }
}

@Composable
private fun KeyValueRow(key: String, value: String, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1.1f),
            color = valueColor ?: if (value == "<null>" || value == "<none>")
                MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}

private fun availabilityText(availability: CapabilityAvailability): String =
    if (availability == CapabilityAvailability.BLOCKED_BY_PERMISSION) {
        "UNSUPPORTED（${availability.displayName}）"
    } else {
        availability.displayName
    }

@Composable
private fun permissionColor(state: PermissionState): Color = when (state) {
    PermissionState.GRANTED -> MaterialTheme.colorScheme.primary
    PermissionState.DENIED, PermissionState.NOT_DECLARED -> MaterialTheme.colorScheme.error
    PermissionState.NOT_APPLICABLE, PermissionState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun availabilityColor(availability: CapabilityAvailability): Color = when (availability) {
    CapabilityAvailability.AVAILABLE -> MaterialTheme.colorScheme.primary
    CapabilityAvailability.BLOCKED_BY_PERMISSION -> MaterialTheme.colorScheme.error
    CapabilityAvailability.NOT_SUPPORTED, CapabilityAvailability.UNKNOWN ->
        MaterialTheme.colorScheme.onSurfaceVariant
}

private fun boolText(value: Boolean?): String = when (value) {
    true -> "是"
    false -> "否"
    null -> "无法判定"
}

private fun shortPermissionName(permission: String): String =
    permission.substringAfterLast('.').removePrefix("ACCESS_").removePrefix("NEARBY_")




private fun metricLabel(key: String): String = when (key) {
    "networkType" -> "网络类型"
    "validated" -> "系统连通校验"
    "gateway" -> "默认网关"
    "ssid" -> "WiFi 名称"
    "rssiDbm" -> "信号强度 / dBm"
    "linkSpeedMbps" -> "链路速率 / Mbps"
    "successCount" -> "成功次数"
    "testedDomainCount" -> "测试域名数"
    "targetCount" -> "目标数"
    "avgResolveMs" -> "平均解析 / ms"
    "handshakeMs" -> "TCP 建连 / ms"
    "statusCode" -> "HTTP 状态码"
    "ttfbMs" -> "首字节时延 / ms"
    "totalMs" -> "总耗时 / ms"
    else -> key
}
