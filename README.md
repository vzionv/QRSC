# QRSC — QR 离线数据同步系统

在无网络环境中，通过二维码实现从受限设备到 Android 移动端的单向数据同步。支持文本剪切板同步和任意二进制文件传输。

## 系统架构

```
[受限设备]
  sender/ 脚本 → 显示器展示二维码动画
                       ↓
[Android 端] 摄像头 → ML Kit 扫码 → 剪贴板 / 文件存储
```

PC 端运行于无网络 Ubuntu，仅键盘/鼠标/显示器可用。QR 编码自实现（无第三方库）。

## 项目结构

```
qrsc/
├── sender/              # PC 端 Python 工具（单文件、自包含）
├── qrsc-android/        # Android 扫码 APP
├── docs/                # 文档
├── README.md
└── LICENSE
```

## 快速开始

### PC 端

选择对应环境的脚本直接运行：

```bash
# PyQt5 环境：剪切板→QR 实时监控
python qrsc_clip_qt.py

# PyQt5 环境：文件传输
python qrsc_file_qt.py

# 终端环境：文件传输（无 GUI）
python qrsc_file_term.py <文件名>
```

### Android 端

```bash
cd qrsc-android
build-apk.bat                    # debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## SCQR 文件传输协议

| 偏移 | 大小 | 字段 | 说明 |
|---|---|---|---|
| 0 | 4 | MAGIC | "SCQR" (0x53 0x43 0x51 0x52) |
| 4 | 1 | VERSION | 0x01 |
| 5 | 1 | FLAGS | bit0=LAST_CHUNK |
| 6 | 2 | TOTAL_CHUNKS | uint16 BE |
| 8 | 2 | CHUNK_INDEX | uint16 BE |
| 10 | 1 | FILENAME_LEN | uint8 |
| 11 | N | FILENAME | UTF-8 |
| 11+N | -- | PAYLOAD | 文件内容 |
