# NetScope 模型网关

Node.js 24 内置模块实现，无 npm 依赖。网关接收 OpenAI 兼容请求，生成 `x-netscope-trace-id`，转发到配置的上游并记录执行轨迹。手机不持有服务商 API Key。

## 本机运行

```powershell
node backend/server.js
```

默认监听 `127.0.0.1:8787`。没有上游配置时，health 仅返回网关状态，诊断返回 `503 model_unavailable`。

在进程环境中设置 `NETSCOPE_API_KEY` 和 `NETSCOPE_UPSTREAM_CHAT_URL`，后者必须为实际 HTTPS `/chat/completions` URL。使用自己的私密配置管理方式注入凭据，不写进源码或手机。Node 的代理环境支持取决于所用版本；需要代理时可使用 Node.js 24 的 `--use-env-proxy` 选项。

```powershell
adb reverse tcp:8787 tcp:8787
```

该映射只用于模型链路调试，网络诊断观测仍由手机探针获取。

## 接口

| 接口 | 行为 |
|---|---|
| GET /health | 网关状态与是否配置上游，不执行模型请求 |
| POST /v1/chat/completions | 转发 OpenAI 兼容请求与响应，返回 traceId |

## 保护模式

设置 `NETSCOPE_DEPLOYMENT_MODE=protected` 后，必须同时提供服务端 API Key、上游 HTTPS URL、独立客户端 token、模型白名单、调用总限额、绝对 quota 文件路径与 HTTPS 入口配置确认。非回环监听在配置不完整时拒绝启动。

| 边界 | 当前约束 |
|---|---|
| 客户端凭据 | `Authorization: Bearer <服务访问码>`；32–128 位 `[A-Za-z0-9_-]` |
| 请求体 | 最大 64 KiB |
| 输出 | 最大 1024 tokens，n 只能为 1 |
| 并发 / 速率 | 最多 2 个并发、每分钟 20 次上游尝试 |
| 总调用数 | 每次上游尝试前持久预留，失败不退回 |
| 运行方式 | 单进程、单副本、私密持久卷 |

配置字段见 [.env.example](.env.example)。Node 不会自动加载这个文件，需由运行环境加载。

## HTTPS 部署

提供真实 HTTPS 入口或反向代理，阻止公网直接绕过 HTTPS 访问网关端口。`NETSCOPE_TLS_TERMINATED=1` 是部署确认字段，不会自动创建证书或证明 TLS 配置正确。quota 和 trace 文件须位于非公开的持久目录；多副本需要额外共享额度机制，不能依靠当前文件计数器实现全局限制。

Dockerfile 基于官方 `node:24-bookworm-slim`，只复制网关源码，使用非 root 的 node 用户。挂载目录必须对该用户可写，凭据由运行环境提供。容器模板需要在目标部署环境验证。

## 测试

```powershell
node --test backend/server.test.js
```

测试使用本地模拟上游，不消耗真实模型额度。日志包含诊断证据，应私密存储并明确保留期限。
