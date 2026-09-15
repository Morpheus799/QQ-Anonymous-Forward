# AnonForward

AnonForward 为 QQ/TIM 多选转发菜单增加一次性的“匿名转发”，同时提供 QAuxiliary 外部插件和独立 LSPosed 模块。

当前实现采用上传前改写：QQ 先生成完整的 `SsoSendLongMsg`，共享核心在发送前改写其中的 `MultiMsg` Protobuf。图片、表情和嵌套 action 保持原始结构，身份与可追踪字段被替换。QQNT 入口只处理轻量的 `MultiMsgInfo` 列表，外层 JSON、XML 及 zlib LightApp 统一在发包线程改写。

## 项目结构

- `core`：协议解析、匿名状态机、菜单注入和所有 Hook 逻辑，不依赖 QAuxiliary 或 Xposed API。
- `app`：QAuxiliary 外部插件入口和配置应用。
- `standalone`：独立 LSPosed/Xposed 入口、HookBridge 实现和模块说明应用。
- `qaux-api-stubs`：只供 QAux 插件编译，使用 `compileOnly`，不会进入独立模块。

## 当前处理内容

- `senderUin` 替换为 `1094950020`
- `senderUid` 替换为 `u_B-xbHgFtPzMTjvfvZNVuqw`
- 群号替换为 `284840486`
- 同一身份在一次转发中使用同一“匿名用户N”昵称
- 结构化 At 转换成普通文本 `@匿名用户N`
- `srcMsg` 保留正文与媒体，替换发送者、消息 ID 和序号
- 清空 `forward.avatar` 等头像字段
- 递归处理嵌套 `MultiMsg`、压缩 XML 与 LightApp JSON
- 外层 `com.tencent.multimsg` 预览同步匿名化

## 构建

项目需要 JDK 17+ 与 Android SDK。一次构建两份产物：

```powershell
.\gradlew.bat assembleProducts
```

APK 输出：

```text
outputs/AnonForward-QAux-<版本>-debug.apk
outputs/AnonForward-LSPosed-<版本>-debug.apk
```

## 更新版本

版本号统一保存在根目录 `gradle.properties`：

```properties
anonVersionCode=<递增整数>
anonVersionName=<语义版本号>
```

使用脚本设置新版本；省略 `VersionCode` 时会自动加一：

```powershell
.\tools\set-version.ps1 -VersionName 0.3.3
```

更新后立即构建两份 APK：

```powershell
.\tools\set-version.ps1 -VersionName 0.3.3 -Build
```

## QAuxiliary 外部插件

1. 安装 `AnonForward-QAux-*.apk`。
2. 打开“匿名转发”应用，复制包名和证书 SHA-256。
3. 在 QAuxiliary 的“实验性功能 → 加载外部插件”中添加包名和证书SHA256，均在应用首页有显示
4. 启用外部模块并彻底重启 QQ。
5. 在 QQ 中多选消息并点击转发。
6. 在“逐条转发”“合并转发”之后选择“匿名转发”。该入口会复用原合并转发流程，并只匿名化本次操作。

## 独立 LSPosed 模块

1. 安装 `AnonForward-LSPosed-*.apk`。
2. 在 LSPosed 中启用“匿名转发（LSPosed）”。
3. 作用域选择 QQ 或 TIM。
4. 强行停止并重新启动目标应用。
5. 在多选转发菜单中使用“匿名转发”。

独立模块可以与 QAuxiliary 本体同时启用。请勿同时启用 QAux 外部插件版本和独立 LSPosed 版本；核心包含进程级所有者保护，检测到重复运行时会跳过后加载的一份。

## 调试

日志同时写入 Android Logcat 和 LSPosed 模块日志：

```text
AnonForward
```

关键成功日志：

```text
AnonForward hooks installed
Intercepted QQNT forward entry
Observed native SSO command=trpc.group.long_msg_interface.MsgService.SsoSendLongMsg
Sanitized SsoSendLongMsg bodies=N
Sanitized outgoing multimsg Ark preview
Replaced outer multimsg preview
```

## 当前限制

媒体元素目前按原始数据保留，因此原始文件哈希和 QQ 媒体资源标识仍可能关联到原消息。强匿名模式需要增加“下载后重新上传媒体”的流程。

当前由于未探明的缓存逻辑，手机端转发后自己会看见正常的未匿名聊天记录，但是实际上其他人看到的都是匿名状态。如果将这个缓存的聊天记录直接在自己的号上转发，则会变成实名，建议重新打包记录再转发。
