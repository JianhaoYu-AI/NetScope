# 构建与签名

## 工具链

| 配置 | 版本 |
|---|---|
| JDK / Java / Kotlin JVM target | 17 |
| Gradle Wrapper | 8.11.1 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.1.0 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| SDK 包 | platform-tools、platforms;android-35、build-tools;35.0.0 |
| AGP 默认 Build Tools | 34.0.0；当前脚本未覆盖默认版本 |

版本统一保存在 `gradle/libs.versions.toml`，构建时使用仓库提供的 Wrapper。

## 本地配置

设置 `JAVA_HOME` 为 JDK 根目录，设置 `ANDROID_HOME` 为实际 SDK 根目录。在仓库根目录建立 `local.properties`：

```properties
sdk.dir=D:/Android/Sdk
```

路径必须替换为本机实际位置。`local.properties`、密钥和签名文件由 `.gitignore` 排除。

## 构建顺序

```powershell
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug --stacktrace
.\gradlew.bat :app:testDebugUnitTest --stacktrace
.\gradlew.bat :app:assembleRelease --stacktrace
```

macOS / Linux 使用 `./gradlew`。Windows 建议放在 ASCII 路径下构建。如果测试工作进程找不到 JVM 测试类，先检查工程路径编码及工具链配置。

## 可选模型配置

```powershell
.\gradlew.bat :app:assembleDebug -PNETSCOPE_MODEL_ID=qwen-plus
```

`NETSCOPE_MODEL_ID` 必须为实际可调用的模型。可用 `NETSCOPE_BACKEND_URL` 设置默认完整网关地址；界面支持在运行时覆盖地址。API Key 不属于构建参数，不应放入 APK。

## Release 签名

签名字段通过 `local.properties`、Gradle 属性或环境变量读取，读取优先级依次为这三者：

| 字段 | 用途 |
|---|---|
| NETSCOPE_STORE_FILE | keystore 的真实路径 |
| NETSCOPE_STORE_PASSWORD | keystore 密码 |
| NETSCOPE_KEY_ALIAS | 密钥别名 |
| NETSCOPE_KEY_PASSWORD | 密钥密码 |

存在有效 keystore 时使用 Release 签名配置，否则使用本机 debug 签名。覆盖安装需要原包与新包签名一致。debug 包名带 `.debug` 后缀，可以与 release 并存。
