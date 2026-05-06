# GameHaptic

GameHaptic 是一个 Android App，用于将游戏背景声音实时转换成震动反馈。它使用 Android `AudioPlaybackCapture` 捕获播放声音，在本地分析音频信号，并把冲击、低频节奏和瞬态声音映射为不同强度和形态的震动。

[English](README.md)

## 功能特性

- 使用 Android `AudioPlaybackCapture` 捕获游戏声音
- 通过前台服务持续进行后台音频捕获
- 使用隐形悬浮窗辅助后台保活
- 过滤低音量声音、稳定背景音乐和底噪
- 根据音频强度输出不同强度和波形的震动
- 提供静音门限、震动灵敏度、音乐过滤等震感调校选项
- 支持英文、简体中文、繁体中文、日文和韩文

## 环境要求

- Android 10 或更高版本，API 29+
- 设备需要支持震动
- 如果设备支持震动幅度控制，效果会更好
- Android Studio 或项目自带 Gradle Wrapper

部分游戏可能会禁止系统播放捕获。如果游戏主动禁止捕获，Android 不会允许本 App 获取其声音。

## 权限说明

GameHaptic 会请求以下权限：

- 录音 / 播放捕获：通过系统捕获 API 获取游戏播放声音
- 悬浮窗权限：创建透明保活窗口，帮助 App 在游戏后台持续运行
- 通知权限：较新 Android 版本中，前台服务通知需要此权限
- 震动权限：用于输出震动反馈

## 构建

克隆仓库：

```bash
git clone git@github.com:ikanam/GameHaptic.git
cd GameHaptic
```

构建 Debug APK：

```bash
./gradlew assembleDebug
```

运行单元测试：

```bash
./gradlew testDebugUnitTest
```

## 项目结构

- `app/src/main/java/top/jarman/gamehaptic/MainActivity.kt`：Compose UI、权限申请、捕获控制和震感调校
- `app/src/main/java/top/jarman/gamehaptic/audio/HapticAudioAnalyzer.kt`：音频转震动的分析逻辑
- `app/src/main/java/top/jarman/gamehaptic/service/AudioCaptureService.kt`：前台音频捕获服务和震动输出
- `app/src/main/res/values*/strings.xml`：多语言文案资源

## 开源协议

本项目基于 MIT 协议开源。详情见 [LICENSE](LICENSE)。
