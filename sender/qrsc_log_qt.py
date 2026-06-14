import os
import sys
from pathlib import Path
from datetime import datetime

from PyQt5.QtCore import Qt, QTimer
from PyQt5.QtGui import QColor, QPainter
from PyQt5.QtWidgets import (
    QApplication, QWidget, QPushButton, QLabel,
    QHBoxLayout
)


class StatusLight(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.color = QColor("#888888")
        self.setFixedSize(16, 16)

    def set_color(self, color):
        self.color = QColor(color)
        self.update()

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)
        painter.setBrush(self.color)
        painter.setPen(Qt.NoPen)
        painter.drawEllipse(2, 2, 12, 12)


class ClipboardLogger(QWidget):
    def __init__(self):
        super().__init__()

        self.setWindowTitle("剪切板监听")
        self.setWindowFlags(
            Qt.FramelessWindowHint |
            Qt.WindowStaysOnTopHint |
            Qt.Tool
        )
        self.setFixedSize(210, 42)

        self.clipboard = QApplication.clipboard()
        self.listening = False
        self.last_text = None
        self.file = None
        self.drag_pos = None

        timestamp = datetime.now().strftime(r"%Y%m%d%H%M%S")
        self.log_path = Path.cwd() / f"clipboard_log_{timestamp}.txt"

        self.button = QPushButton("开始")
        self.button.setFixedSize(58, 26)
        self.button.clicked.connect(self.toggle_listening)

        self.light = StatusLight()

        self.min_btn = QPushButton("—")
        self.min_btn.setFixedSize(24, 24)
        self.min_btn.clicked.connect(self.showMinimized)

        self.close_btn = QPushButton("×")
        self.close_btn.setFixedSize(24, 24)
        self.close_btn.clicked.connect(self.exit_app)

        layout = QHBoxLayout()
        layout.setContentsMargins(8, 6, 8, 6)
        layout.setSpacing(6)
        layout.addWidget(self.button)
        layout.addWidget(QLabel("状态"))
        layout.addWidget(self.light)
        layout.addStretch(1)
        layout.addWidget(self.min_btn)
        layout.addWidget(self.close_btn)
        self.setLayout(layout)

        # RDP 跨设备剪贴板同步时，Qt 的 dataChanged 信号可能不触发。
        # 所以这里改为定时轮询，稳定性更好。
        self.poll_timer = QTimer(self)
        self.poll_timer.setInterval(300)
        self.poll_timer.timeout.connect(self.check_clipboard)

        self.reset_light_timer = QTimer(self)
        self.reset_light_timer.setSingleShot(True)
        self.reset_light_timer.timeout.connect(self.turn_light_gray)

    def toggle_listening(self):
        if not self.listening:
            self.start_listening()
        else:
            self.stop_listening()

    def start_listening(self):
        self.file = open(self.log_path, "a", encoding="utf-8")
        self.last_text = self.clipboard.text()

        self.listening = True
        self.button.setText("停止")
        self.turn_light_gray()
        self.poll_timer.start()

    def stop_listening(self):
        self.poll_timer.stop()

        if self.file:
            self.file.flush()
            os.fsync(self.file.fileno())
            self.file.close()
            self.file = None

        self.listening = False
        self.button.setText("开始")
        self.turn_light_gray()

    def check_clipboard(self):
        if not self.listening or not self.file:
            return

        current_text = self.clipboard.text()

        if not current_text:
            return

        if current_text != self.last_text:
            self.last_text = current_text
            self.append_to_file(current_text)
            self.flash_green()

    def append_to_file(self, text):
        self.file.write(text)
        self.file.flush()
        os.fsync(self.file.fileno())

    def flash_green(self):
        self.light.set_color("#00cc44")
        self.reset_light_timer.start(2000)

    def turn_light_gray(self):
        self.light.set_color("#888888")

    def exit_app(self):
        if self.listening:
            self.stop_listening()
        QApplication.instance().quit()

    def closeEvent(self, event):
        if self.listening:
            self.stop_listening()
        event.accept()
        QApplication.instance().quit()

    def mousePressEvent(self, event):
        if event.button() == Qt.LeftButton:
            self.drag_pos = event.globalPos() - self.frameGeometry().topLeft()
            event.accept()

    def mouseMoveEvent(self, event):
        if event.buttons() == Qt.LeftButton and self.drag_pos is not None:
            self.move(event.globalPos() - self.drag_pos)
            event.accept()

    def mouseReleaseEvent(self, event):
        self.drag_pos = None
        event.accept()


if __name__ == "__main__":
    app = QApplication(sys.argv)
    window = ClipboardLogger()
    window.show()
    sys.exit(app.exec_())
