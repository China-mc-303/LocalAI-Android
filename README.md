# 本地AI助手（Android）

安卓本地大模型应用，基于 llama.cpp，可完全离线运行；也可以接入 DeepSeek、Kimi、豆包、元宝、GPT 等在线大模型。

## 下载

| 文件 | 说明 |
|---|---|
| [LocalAI-v3.0.0.apk](https://github.com/China-mc-303/LocalAI-Android/releases/download/v3.0.0/LocalAI-v3.0.0.apk) | 安装包，Android 8.0+ / arm64-v8a |
| [LocalAI-v3.0.0-source.zip](https://github.com/China-mc-303/LocalAI-Android/releases/download/v3.0.0/LocalAI-v3.0.0-source.zip) | 完整源码包（含 llama.cpp） |

## 仓库结构

```
app/                  Android 应用源码（Kotlin + Jetpack Compose + JNI）
  src/main/cpp/       llama.cpp JNI 封装
  src/main/java/      聊天、文件解析、在线模型、网页抓取、手机操控
llama.cpp/            推理引擎源码（构建时由 CMake 引用）
build.gradle.kts      工程配置
```

## 功能

- 液态玻璃风格的半透明 UI（渐变光斑背景 + 磨砂玻璃卡片）
- 本地 GGUF 模型推理（Qwen2.5 0.5B / 1.5B / 3B），流式输出、多轮对话
- 在线大模型：DeepSeek、Kimi、豆包（火山方舟）、元宝（腾讯混元）、GPT（可选）、自定义 OpenAI 兼容接口
  - 在「设置」中填写 API Key、模型名、Base URL，每个平台独立保存，Key 只存本机
- 下载列表：查看下载进度、安装状态与历史记录，可直接加载或删除
- 每个 AI 都有独立终端：本地模型与每个在线平台各自保存命令历史，可执行 ls/cat/unzip 等命令
  - AI 也能通过 shell 工具在工作目录中执行命令
- 工作目录可在设置中修改；终端内可浏览目录、解析文件、查看 zip 内文件清单
- 模型导入（手机内任意 GGUF）或 App 内下载（ModelScope 源）
- 文件问答：zip 压缩包、pdf、docx、pptx、xlsx、txt、md、csv、json、代码
- 文档摘要、要点提取、中英互译
- 系统语音输入、办公写作模式
- 所有模型都能查看网页：内置 `fetch_url` 工具，对话框里直接发链接也会自动读取正文
- 所有模型都能操控手机：读取屏幕、点击、滑动、输入、返回、打开应用（基于无障碍服务）
  - AI 操控手机期间，屏幕顶部会显示红色「紧急关闭」悬浮按钮，通知栏也有停止入口
- 数据全部保存在手机本地（使用在线模型时对话内容会发送到对应平台）

## 使用

1. 安装 APK
2. 本地模型：进入「模型」页，下载 Qwen2.5-1.5B（约 1GB）或导入已有 GGUF，回到「对话」页使用
3. 在线模型：进入「设置 → 模型来源」，选择 DeepSeek / Kimi / 豆包 / 元宝 / GPT，填写 API Key
4. 终端与工作目录：在「设置」里设置工作目录；对话页右上角终端图标可打开当前 AI 的终端并浏览目录
5. 手机操控：在「设置」开启「AI 操控手机」，并开启系统「无障碍服务」、授予「悬浮窗」权限

## 构建

- Android SDK 35、NDK（CMake 3.22+）、JDK 17
- 创建 `local.properties`，写入 `sdk.dir=<你的 Android SDK 路径>`
- 在 `app/build.gradle.kts` 中配置自己的 `signingConfigs`（本仓库不包含签名证书）
- 执行 `gradle :app:assembleRelease`

## 说明

模型文件不包含在 APK 中，需在 App 内下载或自行准备 GGUF 文件导入。
