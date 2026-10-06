package com.netscope.ui.collect

import android.content.Context
import android.net.Uri
import com.netscope.BuildConfig
import com.netscope.core.export.DiagnosisArchive
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netscope.core.capability.CapabilityMatrix
import com.netscope.core.capability.CapabilityProbe
import com.netscope.core.agent.AgentReport
import com.netscope.core.agent.AgentSession
import com.netscope.core.agent.GatewayAccess
import com.netscope.core.collector.Collector
import com.netscope.core.collector.DnsResolveCollector
import com.netscope.core.collector.HttpProbeCollector
import com.netscope.core.collector.NetOverviewCollector
import com.netscope.core.collector.ProbeTask
import com.netscope.core.collector.TcpProbeCollector
import com.netscope.core.collector.WifiDetailCollector
import com.netscope.core.model.DeviceContext
import com.netscope.core.model.Evidence
import com.netscope.core.model.NetworkType
import com.netscope.core.model.ProbeStatus
import com.netscope.core.permission.PermissionGateway
import com.netscope.core.permission.PermissionStatus
import com.netscope.core.rules.RuleEngine
import com.netscope.core.rules.RuleMatch
import com.netscope.core.util.Clock
import com.netscope.core.util.NsLog
import com.netscope.data.store.DiagnosisRepository
import com.netscope.core.verification.VerificationReport
import com.netscope.core.verification.Verifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject


data class CollectUiState(
    val permissionStatuses: List<PermissionStatus> = emptyList(),
    val runtimeRequestable: List<String> = emptyList(),
    val capability: CapabilityMatrix? = null,
    val probing: Boolean = false,
    val collecting: Boolean = false,
    val verifying: Boolean = false,
    val saving: Boolean = false,
    val agentRunning: Boolean = false,
    val exporting: Boolean = false,
    val lastExportedAt: Long? = null,
    val agentReport: AgentReport? = null,
    val agentError: String? = null,
    val error: String? = null,
    /** 最近一次采集产出的证据列表。UI 渲染卡片用。 */
    val evidences: List<Evidence> = emptyList(),
    /** 仅来自本轮证据的本地规则命中；没有命中就保持空列表。 */
    val ruleMatches: List<RuleMatch> = emptyList(),
    val ruleError: String? = null,
    val verification: VerificationReport? = null,
    /** 最近一次保存是否成功，用于在按钮附近显示提示。 */
    val lastSavedAt: Long? = null,
    /** 启动时自动加载的最近一次历史记录（杀进程后回放）。 */
    val historyPreview: List<Evidence> = emptyList(),
) {
    val hasBlockingGap: Boolean
        get() = permissionStatuses.any { it.spec.required && !it.state.isGranted }

    val hasRuntimePending: Boolean
        get() = runtimeRequestable.isNotEmpty()

    val isCapabilityLimited: Boolean
        get() = capability?.isLimited ?: hasBlockingGap

    val canSave: Boolean
        get() = evidences.isNotEmpty() && canCollect

    val canCollect: Boolean
        get() = !saving && !collecting && !verifying && !agentRunning && !exporting

    val canVerify: Boolean
        get() = evidences.isNotEmpty() && canCollect

    fun permissionStateOf(permission: String): com.netscope.core.permission.PermissionState? =
        permissionStatuses.firstOrNull { it.spec.permission == permission }?.state
}


@HiltViewModel
class CollectViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionGateway: PermissionGateway,
    private val capabilityProbe: CapabilityProbe,
    private val netOverview: NetOverviewCollector,
    private val wifiDetail: WifiDetailCollector,
    private val dnsResolve: DnsResolveCollector,
    private val tcpProbe: TcpProbeCollector,
    private val httpProbe: HttpProbeCollector,
    private val probeTask: ProbeTask,
    private val repository: DiagnosisRepository,
    private val ruleEngine: RuleEngine,
    private val verifier: Verifier,
    private val agentSession: AgentSession,
    private val gatewayAccess: GatewayAccess,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollectUiState())
    val uiState: StateFlow<CollectUiState> = _uiState.asStateFlow()

    fun gatewayEndpoint(): String = gatewayAccess.snapshot().endpoint

    fun configureGateway(endpoint: String, code: String): Boolean =
        _uiState.value.canCollect && gatewayAccess.configure(endpoint, code)

    init {
        refreshPermissions()
        loadHistory()
    }

    fun refreshPermissions() {
        val statuses = permissionGateway.statuses()
        val requestable = permissionGateway.runtimePermissionsToRequest()
        NsLog.d(
            "权限矩阵刷新：${statuses.joinToString(" | ") { "${it.spec.permission}=${it.state.name}" }}",
        )
        _uiState.update {
            it.copy(
                permissionStatuses = statuses,
                runtimeRequestable = requestable,
                error = null,
            )
        }
    }

    


    fun refreshCapability() {
        viewModelScope.launch {
            _uiState.update { it.copy(probing = true, error = null) }

            val outcome = runCatching {
                withContext(Dispatchers.IO) { capabilityProbe.probe() }
            }

            outcome.fold(
                onSuccess = { matrix ->
                    _uiState.update { it.copy(capability = matrix, probing = false) }
                },
                onFailure = { throwable ->
                    NsLog.e("能力探测失败", throwable)
                    _uiState.update {
                        it.copy(
                            probing = false,
                            error = "能力探测失败：${throwable::class.java.simpleName} ${throwable.message ?: ""}",
                        )
                    }
                },
            )
        }
    }

    fun onPermissionRequestFinished() {
        refreshPermissions()
        refreshCapability()
    }

/**
 * 执行一次完整采集。
 *
 * 当前实现：并行调用五个网络探针，
 * 用 [ProbeTask] 注入超时与异常吸收。三路并发可显著降低用户等待时间——
 * 总耗时 = max(五探针)，而不是 sum。
 */
fun runCollectors() {
        if (!_uiState.value.canCollect) return
        viewModelScope.launch {
            _uiState.update { it.copy(collecting = true, error = null, verification = null, agentReport = null, agentError = null, lastSavedAt = null, lastExportedAt = null) }

            val evidencePairs: List<Evidence> = try {
                collectAll()
            } catch (e: CancellationException) {
                NsLog.w("采集被取消")
                _uiState.update { it.copy(collecting = false) }
                return@launch
            } catch (t: Throwable) {
                NsLog.e("采集异常", t)
                _uiState.update {
                    it.copy(
                        collecting = false,
                        error = "采集异常：${t::class.java.simpleName} ${t.message ?: ""}",
                    )
                }
                return@launch
            }

            val ruleOutcome = runCatching { ruleEngine.evaluate(evidencePairs) }
            ruleOutcome.onFailure { NsLog.e("规则评估失败", it) }
            NsLog.i("本次采集产出 ${evidencePairs.size} 条证据")
            _uiState.update {
                it.copy(
                    collecting = false,
                    evidences = evidencePairs,
                    ruleMatches = ruleOutcome.getOrDefault(emptyList()),
                    ruleError = ruleOutcome.exceptionOrNull()?.let { e -> "规则评估失败：${e.message ?: e::class.java.simpleName}" },
                )
            }
        }
    }

    /** 在用户处理网络问题后重跑同一批探针，并保留前后证据引用。 */
    fun verifyCurrentDiagnosis() {
        if (!_uiState.value.canVerify) return
        val before = _uiState.value.evidences
        if (before.isEmpty() || _uiState.value.collecting || _uiState.value.verifying || _uiState.value.agentRunning) return
        val priorRules = _uiState.value.ruleMatches.map { it.ruleId }.toSet()
        viewModelScope.launch {
            _uiState.update { it.copy(verifying = true, error = null, verification = null, agentReport = null, agentError = null, lastSavedAt = null) }
            val after = try {
                collectAll()
            } catch (e: CancellationException) {
                _uiState.update { it.copy(verifying = false) }
                return@launch
            } catch (t: Throwable) {
                NsLog.e("复验采集异常", t)
                _uiState.update { it.copy(verifying = false, error = "复验失败：${t.message ?: t::class.java.simpleName}") }
                return@launch
            }
            val rules = runCatching { ruleEngine.evaluate(after) }
            val report = verifier.compare(
                before = before,
                after = after,
                priorRuleIds = priorRules,
                currentRuleIds = rules.getOrNull()?.map { it.ruleId }?.toSet(),
            )
            NsLog.i("复验结果：${report.outcome}，前后证据 ${before.size}/${after.size} 条")
            _uiState.update {
                it.copy(
                    verifying = false,
                    evidences = after,
                    ruleMatches = rules.getOrDefault(emptyList()),
                    ruleError = rules.exceptionOrNull()?.let { e -> "规则评估失败：${e.message ?: e::class.java.simpleName}" },
                    verification = report,
                )
            }
        }
    }

    /** Optional model diagnosis. A missing gateway is reported explicitly; local rules stay usable. */
    fun runAgentDiagnosis() {
        if (!_uiState.value.canSave) return
        val initial = _uiState.value.evidences
        if (initial.isEmpty() || _uiState.value.collecting || _uiState.value.verifying ||
            _uiState.value.saving || _uiState.value.agentRunning) return
        viewModelScope.launch {
            _uiState.update { it.copy(agentRunning = true, agentReport = null, agentError = null) }
            val report = try {
                withContext(Dispatchers.IO) { agentSession.run(initial) }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(agentRunning = false) }
                return@launch
            } catch (t: Throwable) {
                NsLog.e("模型诊断异常", t)
                _uiState.update { it.copy(agentRunning = false, agentError = "模型诊断失败：${t::class.java.simpleName}") }
                return@launch
            }
            val rules = runCatching { ruleEngine.evaluate(report.evidences) }
            _uiState.update {
                it.copy(
                    agentRunning = false,
                    agentReport = report,
                    evidences = report.evidences,
                    ruleMatches = rules.getOrDefault(emptyList()),
                    ruleError = rules.exceptionOrNull()?.let { e -> "规则评估失败：${e.message ?: e::class.java.simpleName}" },
                    verification = null,
                    lastSavedAt = null,
                )
            }
        }
    }

    private suspend fun collectAll(): List<Evidence> = withContext(Dispatchers.IO) {
        val deferredNet = async { probeTask.run(netOverview, source = SOURCE_NET_OVERVIEW) }
        val deferredWifi = async { probeTask.run(wifiDetail, source = SOURCE_WIFI_DETAIL) }
        val deferredDns = async { probeTask.run(dnsResolve, source = SOURCE_DNS_RESOLVE) }
        val deferredTcp = async { probeTask.run(tcpProbe, source = SOURCE_TCP_PROBE) }
        val deferredHttp = async { probeTask.run(httpProbe, source = SOURCE_HTTP_PROBE) }
        awaitAll(deferredNet, deferredWifi, deferredDns, deferredTcp, deferredHttp)
    }

    /** Freeze the selected session before opening Android's file picker. */
    fun prepareArchive(): ByteArray? {
        val state = _uiState.value
        if (!state.canSave) return null
        return try {
            DiagnosisArchive.encode(
                UUID.randomUUID().toString(), clock.nowMillis(), BuildConfig.VERSION_NAME,
                state.evidences, state.ruleMatches, state.agentReport, state.verification,
            )
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "无法导出当前档案：${e::class.java.simpleName}") }
            null
        }
    }

    fun exportArchive(uri: Uri, bytes: ByteArray) {
        if (_uiState.value.exporting) return
        viewModelScope.launch {
            _uiState.update { it.copy(exporting = true, error = null) }
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: error("无法打开导出文件")
                }
                _uiState.update { it.copy(exporting = false, lastExportedAt = clock.nowMillis()) }
                NsLog.i("诊断档案导出成功：bytes=${bytes.size}")
            } catch (e: CancellationException) {
                _uiState.update { it.copy(exporting = false) }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(exporting = false, error = "导出失败：${e::class.java.simpleName}") }
            }
        }
    }

    /**
     * 保存本次采集。
     *
     * 关键约束：写入磁盘成功后才返回成功；
     * 任何异常都不允许被吞，必须通过 `lastSavedAt = null` 之外的方式让 UI 知晓。
     */
    fun saveCurrentDiagnosis() {
        if (!_uiState.value.canSave) return
        val evidences = _uiState.value.evidences
        if (evidences.isEmpty()) {
            _uiState.update { it.copy(error = "暂无可保存的证据，请先采集。") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, error = null) }
            val createdAt = clock.nowMillis()
            val networkType = pickPrimaryNetworkType(evidences)

            val outcome = withContext(Dispatchers.IO) {
                repository.save(
                    evidences = evidences,
                    conclusionSummary = _uiState.value.ruleMatches.firstOrNull()?.summary ?: "",
                    networkType = networkType,
                    createdAtMs = createdAt,
                )
            }

            outcome.fold(
                onSuccess = { id ->
                    _uiState.update {
                        it.copy(
                            saving = false,
                            lastSavedAt = createdAt,
                            error = null,
                        )
                    }
                    NsLog.i("保存成功：id=$id")
                },
                onFailure = { t ->
                    NsLog.e("保存失败", t)
                    _uiState.update {
                        it.copy(
                            saving = false,
                            error = "保存失败：${t.message ?: t::class.java.simpleName}",
                        )
                    }
                },
            )
        }
    }

    


    private fun loadHistory() {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { repository.latest() }
            outcome.onSuccess { list ->
                _uiState.update { it.copy(historyPreview = list) }
                if (list.isNotEmpty()) NsLog.i("回放最近诊断：${list.size} 条证据")
            }
            outcome.onFailure { t -> NsLog.w("回放历史失败：${t.message}") }
        }
    }

    /**
     * 从证据列表推导主网络类型。优先用 OK 的 net_overview，
     * 退化为已收集到的任意一条，最后兜底为 UNKNOWN。
     */
    private fun pickPrimaryNetworkType(evidences: List<Evidence>): NetworkType {
        val okOverview = evidences.firstOrNull {
            it.type == com.netscope.core.model.EvidenceType.NET_OVERVIEW && it.status == ProbeStatus.OK
        }
        if (okOverview != null) {
            val ctx = okOverview.metrics["networkType"] ?: return NetworkType.UNKNOWN
            return runCatching { NetworkType.valueOf(ctx) }.getOrDefault(NetworkType.UNKNOWN)
        }
        return NetworkType.UNKNOWN
    }

    private companion object {
        const val SOURCE_NET_OVERVIEW = "ConnectivityManager.activeNetwork + NetworkCapabilities + LinkProperties"
        const val SOURCE_WIFI_DETAIL = "WifiInfo + LinkProperties.dhcpServerAddresses"
        const val SOURCE_DNS_RESOLVE = "LinkProperties.dnsServers + InetAddress.getAllByName"
        const val SOURCE_TCP_PROBE = "java.net.Socket.connect(InetSocketAddress)"
        const val SOURCE_HTTP_PROBE = "java.net.Socket + javax.net.ssl.SSLSocket"
    }
}
