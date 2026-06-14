# QRSC — QR 离线数据同步系统

离线环境下，通过二维码实现从受限 Ubuntu 设备到 Android 移动端的单向数据同步系统。支持文本剪切板同步和任意二进制文件传输。

## 系统架构

```
[受限设备] 键盘输入 → sender/ 工具 → 显示器展示二维码动画
                                                 ↓
[移动端]   Android摄像头 → ML Kit扫码 → 系统剪切板 / 文件存储
```

## 项目结构

```
qrsc/
├── sender/                  # PC端 Python 工具（单文件、自包含）
│   ├── qrsc_clip_qt.py      # 剪切板→QR 实时监控（PyQt5）
│   ├── qrsc_qt.py           # 剪切板+文件传输综合版（PyQt5）
│   ├── qrsc_file_qt.py      # 文件分块→SCQR→逐块QR传输（PyQt5）
│   ├── qrsc_tk.py           # 剪切板+文件传输（tkinter）
│   ├── qrsc_file_term.py    # 文件传输终端版（简单ANSI渲染）
│   ├── qrsc_file_term_hd.py # 文件传输终端版（Unicode半块高清）
│   ├── qrsc_file_term_auto.py # 文件传输终端版（自适应QR版本）
│   ├── qrsc_xclip_tui.py    # X11剪切板+文件传输（ctypes调X11）
│   ├── qrsc_sum_qt.py       # 剪切板校验和（PyQt5）
│   ├── qrsc_sum_tk.py       # 剪切板校验和（tkinter）
│   ├── qrsc_sum_tui.py      # 剪切板校验和（跨平台终端）
│   ├── qrsc_log_qt.py       # 剪切板变更日志（PyQt5）
│   ├── xclip.py             # X11剪切板读取工具
│   ├── requirements/
│   │   ├── base.txt         # 纯终端依赖
│   │   ├── qt.txt           # PyQt5 依赖
│   │   └── tk.txt           # tkinter 依赖
│   └── tests/
│       └── test_scqr.py     # SCQR 协议单元测试（9个用例）
│
├── qrsc-android/            # Android APP
│   └── app/src/main/java/com/example/qrsc/
│       ├── MainActivity.kt           # 入口
│       ├── MainNavigation.kt         # 底部三页导航
│       ├── MainViewModel.kt          # ViewModel
│       ├── ScannerState.kt           # 全局单例状态
│       ├── ScannerForegroundService.kt # 扫码前台服务
│       ├── QrPayloadHandler.kt       # QR载荷分发
│       ├── AppSettings.kt            # 持久化设置
│       ├── CaptureProcessingState.kt # 处理状态机
│       ├── CaptureCacheRepository.kt # 缓存文件管理
│       ├── CachedCaptureProcessor.kt # 关键帧提取+ML Kit OCR
│       ├── DebugLog.kt               # debug-only日志
│       ├── ScannerTab.kt             # 采集页面
│       ├── FilesTab.kt               # 文件包管理页面
│       ├── SettingsTab.kt            # 设置页面
│       ├── FilePacketState.kt        # 文件块状态单例
│       ├── FileUtils.kt              # SCQR协议解析
│       ├── camera/CameraManager.kt   # CameraX双路绑定
│       └── ui/theme/                 # Material3深色主题
│
├── docs/
│   └── overview.md          # 项目概述
├── CLAUDE.md                # 项目开发说明（本文件）
├── README.md
└── LICENSE                  # MIT
```

## PC端 Python 脚本开发约束

### 环境约束
- 运行于无网络的 Ubuntu 系统，仅键盘/鼠标/显示器可用
- Ubuntu 仅支持英文 ASCII 输入，脚本中**不得出现中文或特殊符号**
- 脚本需通过键盘手动录入，因此必须**足够精炼**
- 受限设备预装 conda 环境已安装第三方包清单见下方

### 关键设计原则
- QR 编码须自实现（受限环境无 `qrcode` 库）——各脚本中的 QR 编码器为自包含实现
- GUI 使用 PyQt5（已有环境），tkinter 作为备选
- 剪切板通过轮询而非信号监听（兼容 RDP 跨设备剪切板）
- **单文件、自包含、零外部依赖**（除 conda 已有包外），每个 sender/ 下的脚本可直接拷贝到受限设备运行

### 文件传输编码（SCQR）
- 二进制 Header `SCQR` 格式：`MAGIC(4) + VERSION(1) + FLAGS(1) + TOTAL(2) + INDEX(2) + FNAME_LEN(1) + FNAME(N) + PAYLOAD`
- 分块传输：默认每块 1200 字节 payload，3 秒切换（慢速块）
- 自适应间隔：普通块 1 秒，特定索引块（0/3/12/18/36/38）3 秒
- 缓存文件：`qr_send_cache/__<文件名>_<时间>_<索引>.qrf`
- 支持重传：选中 `.qrf` 文件自动进入重传模式

### 受限设备已有 conda 包
```
PyQt5/PySide6, qtconsole, QtPy, qtawesome, qstylizer, superqt,
pillow, numpy, scipy, scikit-image, matplotlib/holoviews, imageio,
pywavelets, tifffile,
cryptography, lz4, zstandard,
regex, chardet/charset_normalizer, base64,
pyyaml/toml, msgpack,
aiohttp, aiodns, aiofiles, tornado, uvloop, threadpoolctl,
distributed/dask, concurrent.futures,
keyring, pyxdg, watchdog, pynacl,
click/typer, python-dotenv, pyyaml, toml,
pytest, black, isort, mypy, pylint/ruff,
ipython/jupyter, rich, streamlit, flask, pandas
```

## Android端 开发指引

### 技术栈
| 组件 | 版本 |
|------|------|
| Kotlin | 1.9.0 |
| Jetpack Compose + Material 3 | BOM 2024.02.00 |
| CameraX (core/camera2/lifecycle/video) | 1.3.1 |
| ML Kit Barcode Scanning | 17.2.0 |
| Navigation Compose | 2.7.7 |
| AGP | 8.2.2 |
| compileSdk / targetSdk 34, minSdk 24 |

### 构建
```bash
cd qrsc-android
build-apk.bat              # debug APK
gradlew assembleRelease    # release APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 包名与标识
- namespace / applicationId: `com.example.qrsc`
- APP显示名: `QRSC`
- `strings.xml` app_name: `QRSC`

### 核心流程

**实时扫码：**
```
CameraX → QRCodeAnalyzer → QrPayloadHandler.handle()
  → parseChunkHeader() 匹配 SCQR? → FilePacketState.addChunk()
  → 不匹配? → 剪贴板写入（文本）
```

**缓存采集 + 关键帧提取：**
```
用户点击"开始缓存" → CameraX VideoCapture 写入 MP4
用户点击"停止缓存" → 保存到 capture_cache/
  → [自动解析] → MediaMetadataRetriever 抽帧 → 帧差分析
    → 帧差 > 阈值? → ML Kit BarcodeScanning 识别
      → QrPayloadHandler.handle() → FilePacketState.addChunk()
```

### 状态机
```
Idle → Capturing(保存中) → Processing(关键帧提取+识别) → Idle
Idle → Processing(选择缓存解析) → Idle
Any → cancelled → Idle
```

### 核心依赖
- CameraX 1.3.1 + ML Kit Barcode Scanning 17.2.0
- Jetpack Compose + Navigation Compose + ViewModel + LifecycleService
- Kotlin 1.9, compileSdk/targetSdk 34, minSdk 24

### 权限
- CAMERA — 运行时
- POST_NOTIFICATIONS — Android 13+
- FOREGROUND_SERVICE + FOREGROUND_SERVICE_CAMERA

### 调试日志标签
| 标签 | 范围 |
|------|------|
| QRSC-MainViewModel | 初始化、缓存刷新 |
| QRSC-Service | 服务生命周期、各action |
| QRSC-State | sendCommand、processingState |
| QRSC-CamMgr | 相机绑定、录制 |
| QRSC-Processor | 帧处理/取消/完成 |
| QRSC-CacheRepo | 输出文件管理 |
| QRSC-FilePacket | addChunk/removeGroup |

## Kotlin 编译守则

每次修改后必须运行 `gradlew assembleDebug` 验证编译通过。

| 问题 | 正确做法 |
|------|----------|
| `companion object` 重复 | 一个类至多一个 companion object |
| Setter 返回 `Unit?` | setter 用 `set(v) { ... }` 块体 |
| 字符串多参数格式 | 用 `%1$s` `%2$d` |
| 未使用的 import/变量 | 编译无 warning 为最低标准 |
| 可空类型安全调用 | 判空后直接调用，不加 `?.` |

## 通用规则

- PC端脚本：自包含、零网络依赖、单文件
- Android端：通过 ScannerState 单例进行 UI-Service 通信，避免内存泄漏
- 不得硬编码密钥或凭证
- 每次修改后必须运行编译验证，不得假设修改正确
