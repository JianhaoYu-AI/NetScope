# 验证方式

## Android 构建与单元测试

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleRelease
```

现有 JVM 测试覆盖 Evidence 序列化、DNS 结果、规则缺失处理、模型工具边界、调用预算、引用审计、档案导出、前后复验及界面操作状态。测试报告位于 `app/build/reports/tests/testDebugUnitTest/index.html`。

## 网关测试

```powershell
node --test backend/server.test.js
```

测试使用隔离临时目录与本地模拟上游，检查认证、模型白名单、输出限制、请求体限制、并发、速率、持久额度与配置缺失降级，不调用真实付费模型。

## 真机检查

1. 安装 Android 包，授予所需权限，检查能力受限提示。
2. 执行五探针，逐项展开来源、时间与原始快照。
3. 保存后结束进程、重启，核对最近记录的时间和快照保持一致。
4. 在自己的网络上改变连接条件，比较新的网络类型及探针状态。
5. 配置实际 HTTPS 网关，核对新 traceId、模型响应与工具产生的新证据。
6. 重新运行同批探针进行前后复验；结果按观测状态解释。

软件测试与真机环境验证分别记录。测试中的构造数据验证工程逻辑，不作为实际采集、故障定位准确率或设备兼容性的证明。
