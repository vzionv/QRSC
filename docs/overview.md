# QRSC 项目概述

离线环境下，通过二维码实现从受限 Ubuntu 设备到 Android 移动端的单向数据同步系统。支持文本剪切板同步和任意二进制文件传输。

## 系统架构

```
[受限设备] 键盘输入 → sender/ 工具 → 显示器展示二维码动画
                                                 ↓
[移动端]   Android摄像头 → ML Kit扫码 → 系统剪切板 / 文件存储
```

## 核心原理

- 文本同步：将剪贴板文本编码为 QR 码，手机扫码后写入系统剪贴板
- 文件同步：二进制文件分块 → SCQR 协议封装 → 逐块 QR 码动画 → 手机逐块扫码 → 文件重组装

## 组件说明

### sender/（发送端工具）

运行于无网络的 Ubuntu 设备，所有脚本均为单文件自包含：

| 脚本 | 功能 | 依赖 |
|---|---|---|
| qrsc_clip_qt.py | 剪切板→QR 实时监控 | PyQt5 |
| qrsc_qt.py | 剪切板+文件传输综合版 | PyQt5 |
| qrsc_file_qt.py | 文件分块→QR 传输 | PyQt5 |
| qrsc_tk.py | 剪切板+文件传输 | tkinter |
| qrsc_file_term.py | 文件传输（终端基础版） | 无 |
| qrsc_file_term_hd.py | 文件传输（高清渲染） | 无 |
| qrsc_file_term_auto.py | 文件传输（自适应 QR 版本） | 无 |
| qrsc_xclip_tui.py | X11 剪切板+文件传输 | ctypes(X11) |
| qrsc_sum_qt.py | 剪切板校验和 | PyQt5 |
| qrsc_sum_tk.py | 剪切板校验和 | tkinter |
| qrsc_sum_tui.py | 剪切板校验和（跨平台终端） | 无 |
| qrsc_log_qt.py | 剪切板变更日志 | PyQt5 |
| xclip.py | X11 剪切板读取工具 | ctypes(X11) |

### qrsc-android（Android APP）

CameraX + ML Kit Barcode Scanning 驱动的扫码应用。支持：

- 实时二维码扫码并写入系统剪贴板
- SCQR 文件传输协议接收与文件重组装
- 视频缓存录制 + 离线关键帧提取 + QR 识别
- 前后摄像头切换 / 黑屏模式 / 预览模式
- 采集参数配置（帧阈值、帧率、自动处理开关）
