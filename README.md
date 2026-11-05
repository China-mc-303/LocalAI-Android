# 本地AI助手（Android）

完全离线运行的安卓本地大模型应用，基于 llama.cpp，无需联网、无 API 费用。

## 下载

| 文件 | 说明 |
|---|---|
| [LocalAI-v1.0.0.apk](https://github.com/China-mc-303/LocalAI-Android/releases/download/v1.0.0/LocalAI-v1.0.0.apk) | 安装包，Android 8.0+ / arm64 |
| [LocalAI-v1.0.0-full.zip](https://github.com/China-mc-303/LocalAI-Android/releases/download/v1.0.0/LocalAI-v1.0.0-full.zip) | 完整包：源码 + APK + 签名证书 |

## 功能

- 本地 GGUF 模型推理（Qwen2.5 0.5B / 1.5B / 3B），流式输出、多轮对话
- 模型导入（手机内任意 GGUF）或 App 内下载
- 文件问答：zip 压缩包、pdf、docx、pptx、xlsx、txt、md、csv、json、代码
- 文档摘要、要点提取、中英互译
- 系统语音输入、办公写作模式
- 数据全部保存在手机本地

## 使用

1. 安装 APK
2. 进入「模型」页，下载 Qwen2.5-1.5B（约 1GB）或导入已有 GGUF
3. 回到「对话」页开始使用

## 构建

需要 Android SDK 35、NDK 与 CMake；将 llama.cpp 源码放在项目同级目录，执行 `gradle :app:assembleRelease`。
