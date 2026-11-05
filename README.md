# 本地AI助手（Android）

完全离线运行的安卓本地大模型应用，基于 llama.cpp，无需联网、无 API 费用。

## 下载

| 文件 | 说明 |
|---|---|
| [LocalAI-v1.0.0.apk](https://github.com/China-mc-303/LocalAI-Android/releases/download/v1.0.0/LocalAI-v1.0.0.apk) | 安装包，Android 8.0+ / arm64-v8a |
| [LocalAI-v1.0.0-source.zip](https://github.com/China-mc-303/LocalAI-Android/releases/download/v1.0.0/LocalAI-v1.0.0-source.zip) | 完整源码包（含 llama.cpp 与 APK） |

## 仓库结构

```
app/                  Android 应用源码（Kotlin + Jetpack Compose + JNI）
  src/main/cpp/       llama.cpp JNI 封装
llama.cpp/            推理引擎源码（构建时由 CMake 引用）
build.gradle.kts      工程配置
```

## 功能

- 本地 GGUF 模型推理（Qwen2.5 0.5B / 1.5B / 3B），流式输出、多轮对话
- 模型导入（手机内任意 GGUF）或 App 内下载（ModelScope 源）
- 文件问答：zip 压缩包、pdf、docx、pptx、xlsx、txt、md、csv、json、代码
- 文档摘要、要点提取、中英互译
- 系统语音输入、办公写作模式
- 数据全部保存在手机本地

## 使用

1. 安装 APK
2. 进入「模型」页，下载 Qwen2.5-1.5B（约 1GB）或导入已有 GGUF
3. 回到「对话」页开始使用

## 构建

- Android SDK 35、NDK（CMake 3.22+）、JDK 17
- 创建 `local.properties`，写入 `sdk.dir=<你的 Android SDK 路径>`
- 在 `app/build.gradle.kts` 中配置自己的 `signingConfigs`（本仓库不包含签名证书）
- 执行 `gradle :app:assembleRelease`

## 说明

模型文件不包含在 APK 中，需在 App 内下载或自行准备 GGUF 文件导入。
