package com.netscope.core.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.netscope.core.util.NsLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton


@Serializable
enum class PermissionState(val displayName: String) {
    GRANTED("已授予"),
    DENIED("未授予"),
    /** 清单未申报。出现即视为开发缺陷，必须立即修复。 */
    NOT_DECLARED("未申报"),
    /** 当前 API 级别不需要该权限。 */
    NOT_APPLICABLE("本版本不需要"),
    /** 系统查询失败，状态未知。 */
    UNKNOWN("查询失败"),
    ;

    val isGranted: Boolean get() = this == GRANTED
}

/**
 * 权限申报条目。
 *
 * @property permission 系统权限常量。
 * @property minApi 生效的最小 API 级别。低于该级别返回 [PermissionState.NOT_APPLICABLE]。
 * @property runtime 是否属于「运行时权限」（需动态申请）。普通权限安装即授予。
 * @property required 缺少它是否会导致核心能力不可用。决定 UI 是否显示「能力受限」横幅。
 * @property purpose 用途说明。会在 UI 上直接展示给用户——
 *   权限被拒的根因往往是用户不理解为什么需要它，把用途写在申请按钮旁边是成本最低的解法。
 */
@Serializable
data class PermissionSpec(
    val permission: String,
    val minApi: Int,
    val runtime: Boolean,
    val required: Boolean,
    val purpose: String,
)


@Serializable
data class PermissionStatus(
    val spec: PermissionSpec,
    val state: PermissionState,
)


@Singleton
class PermissionGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val apiLevel: Int get() = Build.VERSION.SDK_INT

    /** 权限申报表。顺序即 UI 展示顺序：先运行时权限（需要用户操作），后普通权限。 */
    fun specs(): List<PermissionSpec> = listOf(
        PermissionSpec(
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
            minApi = Build.VERSION_CODES.M,
            runtime = true,
            // API 33+ 起首选 NEARBY_WIFI_DEVICES，但部分 ROM 仍只认定位权限，
            // 因此这里保留为「非必需」——缺了它只是少一条回退路径，不代表能力缺失。
            required = apiLevel < Build.VERSION_CODES.TIRAMISU,
            purpose = if (apiLevel < Build.VERSION_CODES.TIRAMISU) {
                "本机为 Android ${Build.VERSION.RELEASE}，读取 WiFi 名称与信号强度必须授予定位权限（系统限制）"
            } else {
                "WiFi 信息读取的回退路径；部分机型在缺少定位权限时仍拒绝返回 SSID"
            },
        ),
        PermissionSpec(
            permission = Manifest.permission.NEARBY_WIFI_DEVICES,
            minApi = Build.VERSION_CODES.TIRAMISU,
            runtime = true,
            required = true,
            purpose = "Android 13 起读取 WiFi 名称（SSID）与路由器标识（BSSID）的必需权限；本应用仅用于网络诊断，不采集位置",
        ),
        PermissionSpec(
            permission = Manifest.permission.ACCESS_NETWORK_STATE,
            minApi = 1,
            runtime = false,
            required = true,
            purpose = "读取当前网络类型、网关、DNS 服务器与计费状态",
        ),
        PermissionSpec(
            permission = Manifest.permission.ACCESS_WIFI_STATE,
            minApi = 1,
            runtime = false,
            required = true,
            purpose = "读取 WiFi 链路信息（信号强度、频率、协商速率）",
        ),
        PermissionSpec(
            permission = Manifest.permission.INTERNET,
            minApi = 1,
            runtime = false,
            required = true,
            purpose = "执行 DNS 解析、TCP 握手与 HTTP 请求探测",
        ),
    )

    /** 全部权限的当前状态，供 UI 渲染「权限矩阵」。 */
    fun statuses(): List<PermissionStatus> = specs().map { PermissionStatus(it, stateOf(it.permission)) }

    /**
     * 查询单个权限状态。
     *
     * 判定顺序：API 适用性 → 是否申报 → 系统授予情况。
     * 先查「是否申报」很重要：未申报的权限调 [ContextCompat.checkSelfPermission]
     * 会返回 DENIED，从而与「用户拒绝」混淆，掩盖 Manifest 缺陷。
     */
    fun stateOf(permission: String): PermissionState {
        val spec = specs().firstOrNull { it.permission == permission }
        if (spec != null && apiLevel < spec.minApi) return PermissionState.NOT_APPLICABLE

        if (!declaredPermissions().contains(permission)) {
            NsLog.w("权限 $permission 未在 AndroidManifest 中申报 —— 这是开发缺陷，不是用户拒绝")
            return PermissionState.NOT_DECLARED
        }

        return runCatching {
            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                PermissionState.GRANTED
            } else {
                PermissionState.DENIED
            }
        }.getOrElse {
            NsLog.w("查询权限 $permission 状态失败：${it::class.java.simpleName} ${it.message}")
            PermissionState.UNKNOWN
        }
    }

    /**
     * 需要在运行时向用户申请的权限（仅本 API 级别适用的运行时权限，且尚未授予）。
     *
     * 返回值直接交给 `ActivityResultLauncher.launch(...)`。
     * **已授予的不重复申请**——重复申请在部分 ROM 上会直接返回拒绝，反而制造假故障。
     */
    fun runtimePermissionsToRequest(): List<String> = specs()
        .filter { it.runtime && apiLevel >= it.minApi }
        .filter { stateOf(it.permission) == PermissionState.DENIED }
        .map { it.permission }

    /**
     * 是否存在「必需权限未授予」的缺口。
     * UI 据此显示「能力受限」横幅；同时它也是附加自检 3 的判定入口。
     */
    fun hasBlockingGap(): Boolean = statuses().any { it.spec.required && !it.state.isGranted }

    /** 未授予的必需权限，用于在界面上逐项说明「哪些数据这次拿不到」。 */
    fun blockingGaps(): List<PermissionStatus> = statuses().filter { it.spec.required && !it.state.isGranted }

    /** 应用是否在清单中申报了该权限。 */
    @Suppress("DEPRECATION")
    private fun declaredPermissions(): Set<String> = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            .orEmpty()
    }.getOrElse {
        NsLog.w("读取已申报权限列表失败：${it::class.java.simpleName} ${it.message}")
        emptySet()
    }
}
