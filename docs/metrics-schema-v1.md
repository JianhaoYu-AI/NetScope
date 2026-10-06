# 指标与证据契约 v1

本文件描述当前实现的指标键名与语义。公开展示版保留既有字段、目标与统计口径；更新时保持历史档案兼容。

## 通用约定

- `Evidence.metrics` 是字符串到字符串的映射，数值也按字符串保存。
- `FAILED`、`TIMEOUT`、`UNSUPPORTED` 的 metrics 为空 `{}`，失败原因留在状态、message 与 rawSnapshot 中。
- `<null>` 表示单值未取得，`<none>` 表示列表为空；键不存在与上述占位分别处理。
- 不用默认零、伪造值或插值补充采集失败。
- 来源、观测时间、耗时和原始快照属于同一条证据，模型与规则不改写观测。

## 1. `net_overview`（NetOverviewCollector）

**EvidenceType** = `NET_OVERVIEW`　**source** = `ConnectivityManager.activeNetwork + NetworkCapabilities + LinkProperties`

| 键 | 语义 | 取值示例 | 缺失时 |
|---|---|---|---|
| `networkType` | 网络类型枚举名 | `WIFI` / `CELLULAR` / `ETHERNET` / `VPN` / `NONE` / `UNKNOWN` | 必现 |
| `primaryTransport` | 主传输层 | `WIFI` / `CELLULAR` / `VPN` / `ETHERNET` / `BLUETOOTH` / `<none>` | `<none>` |
| `isMetered` | 是否计费网络 | `true` / `false` / `<null>` | `<null>` |
| `validated` | 网络已通过系统连通性校验 | `true` / `false` / `<null>` | `<null>` |
| `captivePortal` | 系统判定为门户认证网络 | `true` / `false` / `<null>` | `<null>` |
| `gateway` | 默认网关 IPv4 | `192.168.1.1` / `<null>` | `<null>` |
| `dnsServerCount` | 系统 DNS 服务器数量 | `2` | 必现（可为 `0`） |
| `ssid` | WiFi 名称（已归一化哨兵值） | `MyHomeWiFi` / `<null>` | `<null>` |
| `bssid` | 接入点 MAC（已归一化哨兵值） | `aa:bb:cc:dd:ee:ff` / `<null>` | `<null>` |
| `rssiDbm` | 信号强度（已归一化哨兵值） | `-52` / `<null>` | `<null>` |
| `linkSpeedMbps` | 链路协商速率 | `433` / `<null>` | `<null>` |
| `degradedReason` | 字段缺失清单（条件键） | `字段缺失：ssid,bssid` | 不出现 |

**哨兵值归一化规则**（`<unknown ssid>` / `02:00:00:00:00:00` / `-127` / 前缀 `<`）→ 一律 `<null>`。

---

## 2. `wifi_detail`（WifiDetailCollector）

**EvidenceType** = `WIFI_DETAIL`　**source** = `WifiInfo + LinkProperties.dhcpServerAddresses`

| 键 | 语义 | 取值示例 | 缺失时 |
|---|---|---|---|
| `wifiConnected` | 是否已建立 WiFi 连接 | `true` / `false` | 必现 |
| `ssid` | WiFi 名称（与 net_overview 同规则） | `MyHomeWiFi` / `<null>` | `<null>` |
| `bssid` | 接入点 MAC | `aa:bb:cc:dd:ee:ff` / `<null>` | `<null>` |
| `rssiDbm` | 信号强度 | `-52` / `<null>` | `<null>` |
| `linkSpeedMbps` | 链路协商速率 | `433` / `<null>` | `<null>` |
| `frequencyMhz` | 工作频率 | `5180` / `2412` / `<null>` | `<null>` |
| `channelWidthMhz` | 信道宽度 | `20` / `40` / `80` / `160` / `<null>` | `<null>` |
| `ipAddress` | DHCP 分配的本机 IPv4 | `192.168.1.42` / `<null>` | `<null>` |
| `subnetMask` | 子网掩码 | `255.255.255.0` / `<null>` | `<null>` |
| `mtu` | 接口 MTU | `1500` / `<null>` | `<null>` |
| `note` | 状态注记（条件键） | `no-wifi-connection` | 不出现 |

**注意**：`wifiConnected=false` 时其余键全为 `<null>`，`note=no-wifi-connection`——
**该状态仍是 `OK`**（探针正常执行，WiFi 确实没连），不是 FAILED。

---

## 3. `dns_resolve`（DnsResolveCollector）

**EvidenceType** = `DNS_RESOLVE`　**source** = `LinkProperties.dnsServers + InetAddress.getAllByName`

| 键 | 语义 | 取值示例 | 缺失时 |
|---|---|---|---|
| `systemDnsCount` | 系统 DNS 配置数量 | `2` | 必现 |
| `systemDns` | 系统 DNS 列表（逗号连接） | `192.168.1.1,8.8.8.8` / `<none>` | `<none>` |
| `testedDomainCount` | 测试域名总数 | `3` | 必现 |
| `successCount` | 解析成功数 | `3` | 必现 |
| `avgResolveMs` | 平均解析耗时（仅成功项） | `45`；**无成功项时 `-1`** | 必现 |
| `maxResolveMs` | 最长解析耗时（仅成功项） | `120`；**无成功项时 `-1`** | 必现 |
| `degradedReason` | 部分失败标记（条件键） | `部分域名解析失败：2/3` | 不出现 |

**状态分支规则**（与 metrics 直接相关）：

| 场景 | Evidence.status | metrics |
|---|---|---|
| 全部域名解析成功 | `OK` | 全部键 + 无 degradedReason |
| 部分成功（如 DNS 缓存命中） | `OK` + `degradedReason` | 全部键 + degradedReason |
| 全部失败（B07：DNS 不可达） | `FAILED` | **空 Map `{}`** + message=`DNS 服务不可达（N/N 测试域名失败）` |
| 无网络且无 DNS 配置 | `FAILED` | **空 Map `{}`** |

**rawSnapshot 中的域名级行格式**（日志检索友好，failureReason 值域固定）：
```
baidu.com = OK 50ms -> 220.181.38.148,220.181.38.149
cloudflare.com = FAIL 3000ms reason=NXDOMAIN
example.com = FAIL 5ms reason=TIMEOUT
```
failureReason 值域（封闭集合，不得扩展语义）：
`TIMEOUT` / `NXDOMAIN` / `EMPTY_RESULT` / 其他异常的简短类名

**测试域名固定三项**（作为探针固定目标，不由模型指定）：
`baidu.com` / `cloudflare.com` / `example.com`

---



## 4. TCP 与 HTTP 探针

以下字段仅在取得真实成功结果时输出，失败路径仍为空 metrics。

| 探针 | 键 | 语义 |
|---|---|---|
| `tcp_probe` | `targetCount` | 本轮实际尝试的 IP:Port 目标数 |
| `tcp_probe` | `successCount` | 本轮成功建立 TCP 连接的尝试次数（每目标三次） |
| `tcp_probe` | `handshakeMs` | 成功 TCP connect 耗时的中位数，毫秒；全部失败时不输出指标 |
| `http_probe` | `payloadBytes` | 本轮实际发送的 HTTP 请求负载字节数 |
| `http_probe` | `statusCode` | 服务器实际返回的 HTTP 状态码 |
| `http_probe` | `ttfbMs` | 从请求开始到收到首个响应字节的耗时，毫秒 |
| `http_probe` | `tlsHandshakeMs` | 实际 HTTPS TLS 握手耗时，毫秒；明文 HTTP 为 `<null>` |
| `http_probe` | `totalMs` | 本轮请求到响应读取结束的耗时，毫秒 |
| `http_probe` | `redirectCount` | 本轮实际遇到的重定向次数 |

## 5. 解释边界

`handshakeMs` 是成功 TCP connect 的中位耗时；`successCount` 是成功尝试次数。WiFi 协商速率不是实测带宽。系统 DNS 配置不是实际查询路径证明。当前采集器的 NXDOMAIN 是异常映射标签，不是验证过的 DNS 报文响应码。
