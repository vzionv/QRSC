import sys
import re
from PyQt5.QtWidgets import QApplication, QLabel
from PyQt5.QtCore import Qt, QTimer

def fs(s):
    total=0
    for ch in s:
        if ch not in "; \t\n\r":
            total+=ord(ch)
    return total

class CLipboardSum(QLabel):
    def __init__(self):
        super().__init__()
        self.setWindowFlags(Qt.WindowStaysOnTopHint | Qt.FramelessWindowHint)
        self.setAlignment(Qt.AlignCenter)
        self.setFixedSize(120,50)
        self.setStyleSheet("background-color: black; color: white; font-size: 20px;")
        self.last_text = ""
        self.drag = None
        self.timer = QTimer()
        self.timer.timeout.connect(self.check_clipboard)
        self.timer.start(300)

    def check_clipboard(self):
        text = QApplication.clipboard().text()
        if text != self.last_text:
            self.last_text = text
            total = fs(text)
            self.setText(str(total))

    def mousePressEvent(self, e):
        if e.button() == Qt.RightButton:
            QApplication.quit()
        elif e.button() == Qt.LeftButton:
            self.drag = e.globalPos() - self.pos()

    def mouseMoveEvent(self, e):
        if self.drag:
            self.move(e.globalPos() - self.drag)

    def mouseReleaseEvent(self, e):
        self.drag = None

if __name__ == "__main__":
    app = QApplication(sys.argv)
    w = CLipboardSum()
    w.show()
    sys.exit(app.exec_())
