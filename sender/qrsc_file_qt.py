#!/usr/bin/env python3
import sys, math, os, time
from PyQt5.QtCore import Qt, QTimer
from PyQt5.QtGui import QPainter, QColor, QFont
from PyQt5.QtWidgets import QApplication, QWidget, QPushButton, QLabel, QHBoxLayout, QVBoxLayout, QFileDialog
import numpy as np

# ===================== QR Code Encoder =====================
ECC=[7,10,15,20,26,18,20,24,30,18,20,24,26,30,22,24,28,30,28,28,28,28,30,30,26,28,30,30,30,30,30,30,30,30,30,30,30,30,30,30]
BLK=[1,1,1,1,1,2,2,2,2,4,4,4,4,4,6,6,6,6,7,8,8,9,9,10,12,12,12,13,14,15,16,17,18,19,19,20,21,22,24,25]

def rawmods(v):
    r=(16*v+128)*v+64
    if v>=2:
        n=v//7+2; r-=(25*n-10)*n-55
        if v>=7: r-=36
    return r

def apos(v):
    if v==1: return []
    s=4*v+17; n=v//7+2
    step=26 if v==32 else ((v*4+n*2+1)//(n*2-2))*2
    a=[]; p=s-7
    for _ in range(n-1): a.append(p); p-=step
    return [6]+a[::-1]

def gmul(x,y):
    z=0
    while y:
        if y&1: z^=x
        x<<=1
        if x&256: x^=0x11d
        y>>=1
    return z

def rsdiv(n):
    r=[0]*(n-1)+[1]; root=1
    for _ in range(n):
        r.append(0)
        for i in range(n): r[i]=gmul(r[i],root)^r[i+1]
        r.pop(); root=gmul(root,2)
    return r

def rsrem(data,div):
    r=[0]*len(div)
    for b in data:
        f=b^r.pop(0); r.append(0)
        for i,d in enumerate(div): r[i]^=gmul(d,f)
    return r

def putf(m,f,x,y,c):
    if 0<=x<len(m) and 0<=y<len(m): m[y][x]=1 if c else 0; f[y][x]=1

def fmt(mask):
    d=(1<<3)|mask; r=d
    for _ in range(10): r=(r<<1)^(((r>>9)&1)*0x537)
    return ((d<<10)|r)^0x5412

def ver(v):
    r=v
    for _ in range(12): r=(r<<1)^(((r>>11)&1)*0x1f25)
    return (v<<12)|r

def drawfmt(m,f,mask):
    s=len(m); b=fmt(mask)
    for i in range(15):
        x=(b>>i)&1
        if i<6: putf(m,f,8,i,x)
        elif i==6: putf(m,f,8,7,x)
        elif i==7: putf(m,f,8,8,x)
        elif i==8: putf(m,f,7,8,x)
        else: putf(m,f,14-i,8,x)
        if i<8: putf(m,f,s-1-i,8,x)
        else: putf(m,f,8,s-15+i,x)
    putf(m,f,8,s-8,1)

def drawver(m,f,v):
    if v<7: return
    s=len(m); b=ver(v)
    for i in range(18):
        x=(b>>i)&1; a=s-11+i%3; c=i//3
        putf(m,f,a,c,x); putf(m,f,c,a,x)

def base(v):
    s=4*v+17; m=[[-1]*s for _ in range(s)]; f=[[0]*s for _ in range(s)]
    def finder(cx,cy):
        for y in range(cy-4,cy+5):
            for x in range(cx-4,cx+5):
                d=max(abs(x-cx),abs(y-cy)); putf(m,f,x,y,d!=2 and d!=4)
    finder(3,3); finder(s-4,3); finder(3,s-4)
    a=apos(v)
    for y in a:
        for x in a:
            if f[y][x]: continue
            for dy in range(-2,3):
                for dx in range(-2,3): putf(m,f,x+dx,y+dy,max(abs(dx),abs(dy))!=1)
    for i in range(s):
        if not f[6][i]: putf(m,f,i,6,i%2==0)
        if not f[i][6]: putf(m,f,6,i,i%2==0)
    drawfmt(m,f,0); drawver(m,f,v)
    return m,f

def ecc(data,v):
    nb=BLK[v-1]; el=ECC[v-1]; raw=rawmods(v)//8
    ns=nb-raw%nb; short=raw//nb; div=rsdiv(el); blocks=[]; k=0
    for i in range(nb):
        ln=short-el+(0 if i<ns else 1); d=data[k:k+ln]; k+=ln
        blocks.append((d,rsrem(d,div)))
    out=[]
    for i in range(max(len(b[0]) for b in blocks)):
        for d,e in blocks:
            if i<len(d): out.append(d[i])
    for i in range(el):
        for d,e in blocks: out.append(e[i])
    return out

def mb(mask,x,y):
    if mask==0: return (x+y)%2==0
    if mask==1: return y%2==0
    if mask==2: return x%3==0
    if mask==3: return (x+y)%3==0
    if mask==4: return (x//3+y//2)%2==0
    if mask==5: return (x*y)%2+(x*y)%3==0
    if mask==6: return ((x*y)%2+(x*y)%3)%2==0
    return ((x+y)%2+(x*y)%3)%2==0

def place(m,f,cw):
    s=len(m); bit=0; x=s-1; up=True
    while x>0:
        if x==6: x-=1
        yr=range(s-1,-1,-1) if up else range(s)
        for y in yr:
            for xx in (x,x-1):
                if not f[y][xx]:
                    m[y][xx]=(cw[bit>>3]>>(7-(bit&7)))&1 if bit<len(cw)*8 else 0; bit+=1
        up=not up; x-=2

def penalty(m):
    s=len(m); p=0
    for rows in (m,list(map(list,zip(*m)))):
        for r in rows:
            run=1; last=r[0]; bits=0
            for i,b in enumerate(r):
                bits=((bits<<1)|b)&0x7ff
                if i>=10 and (bits==0x05d or bits==0x5d0): p+=40
                if i and b==last: run+=1
                else:
                    if run>=5: p+=run-2
                    last=b; run=1
            if run>=5: p+=run-2
    for y in range(s-1):
        for x in range(s-1):
            c=m[y][x]
            if c==m[y][x+1]==m[y+1][x]==m[y+1][x+1]: p+=3
    dark=sum(map(sum,m)); p+=abs(dark*20-s*s*10)//(s*s)*10
    return p

def qr(text):
    """Encode a UTF-8 string into a QR code matrix. Returns (matrix, version, byte_count)."""
    b=text.encode('utf-8'); useeci=any(x>127 for x in b)
    for v in range(1,41):
        ccb=8 if v<10 else 16
        need=(12 if useeci else 0)+4+ccb+8*len(b)
        cap=(rawmods(v)//8-ECC[v-1]*BLK[v-1])*8
        if need<=cap: break
    else:
        raise ValueError('Content too long for QR v40-L (~2950 bytes).')
    bits=[]
    def put(val,n):
        for i in range(n-1,-1,-1): bits.append((val>>i)&1)
    if useeci: put(7,4); put(26,8)
    put(4,4); put(len(b),8 if v<10 else 16)
    for x in b: put(x,8)
    dl=rawmods(v)//8-ECC[v-1]*BLK[v-1]; cap=dl*8
    bits+=[0]*min(4,cap-len(bits))
    while len(bits)%8: bits.append(0)
    data=[sum(bits[i+j]<<(7-j) for j in range(8)) for i in range(0,len(bits),8)]
    k=0
    while len(data)<dl: data.append(0xec if k%2==0 else 0x11); k+=1
    cw=ecc(data,v); bm,bf=base(v); place(bm,bf,cw)
    best=None; ans=None
    for ma in range(8):
        mm=[r[:] for r in bm]; ff=[r[:] for r in bf]
        for y in range(len(mm)):
            for x in range(len(mm)):
                if not ff[y][x] and mb(ma,x,y): mm[y][x]^=1
        drawfmt(mm,ff,ma); sc=penalty(mm)
        if best is None or sc<best: best=sc; ans=mm
    return ans,v,len(b)

def qr_bytes(data):
    """Encode raw bytes directly into a QR code (byte mode).
    Unlike qr(), this does NOT UTF-8-encode the input -- it uses the bytes directly.
    Returns (matrix, version, byte_count)."""
    b=data
    for v in range(1,41):
        ccb=8 if v<10 else 16
        need=4+ccb+8*len(b)
        cap=(rawmods(v)//8-ECC[v-1]*BLK[v-1])*8
        if need<=cap: break
    else:
        raise ValueError('Data too long for QR v40-L.')
    bits=[]
    def put(val,n):
        for i in range(n-1,-1,-1): bits.append((val>>i)&1)
    put(4,4)  # byte mode indicator
    ccb=8 if v<10 else 16
    put(len(b),ccb)  # version-dependent char count
    for x in b: put(x,8)
    dl=rawmods(v)//8-ECC[v-1]*BLK[v-1]
    bits+=[0]*min(4,dl*8-len(bits))
    while len(bits)%8: bits.append(0)
    data=[sum(bits[i+j]<<(7-j) for j in range(8)) for i in range(0,len(bits),8)]
    k=0
    while len(data)<dl: data.append(0xec if k%2==0 else 0x11); k+=1
    cw=ecc(data,v); bm,bf=base(v); place(bm,bf,cw)
    best=None; ans=None
    for ma in range(8):
        mm=[r[:] for r in bm]; ff=[r[:] for r in bf]
        for y in range(len(mm)):
            for x in range(len(mm)):
                if not ff[y][x] and mb(ma,x,y): mm[y][x]^=1
        drawfmt(mm,ff,ma); sc=penalty(mm)
        if best is None or sc<best: best=sc; ans=mm
    return ans,v,len(b)


# ===================== Binary Header (SCQR format) =====================
SCQR_MAGIC = b'SCQR'  # 0x53 0x43 0x51 0x52
SCQR_VERSION = 1
# Maximum data bytes per QR chunk. Lower values = larger QR blocks (easier to scan).
# v40-L capacity is ~2956 bytes but produces very dense QR at phone-camera distances.
# 1200-1500 gives good tradeoff between density and scan reliability.
CHUNK_SIZE = 1200  # bytes per chunk payload (excluding header)
START_CHUNK = 0  # set to N to skip first N chunks on send
OVERHEAD_MAX = 11 + 255  # header fixed + max filename

def make_chunk_header(total, index, filename, is_last):
    """Build binary header: 11 fixed bytes + filename."""
    fn_bytes = filename.encode('utf-8')
    if len(fn_bytes) > 255:
        raise ValueError('Filename too long (max 255 bytes)')
    flags = 1 if is_last else 0
    header = bytearray(11)
    header[0:4] = SCQR_MAGIC
    header[4] = SCQR_VERSION
    header[5] = flags
    header[6] = (total >> 8) & 0xFF
    header[7] = total & 0xFF
    header[8] = (index >> 8) & 0xFF
    header[9] = index & 0xFF
    header[10] = len(fn_bytes)
    header.extend(fn_bytes)
    return bytes(header)

def parse_chunk_header(data):
    """Parse binary SCQR header. Returns dict or None if not a valid header."""
    if len(data) < 11:
        return None
    if data[0:4] != SCQR_MAGIC:
        return None
    version = data[4]
    flags = data[5]
    total = (data[6] << 8) | data[7]
    index = (data[8] << 8) | data[9]
    fn_len = data[10]
    if len(data) < 11 + fn_len:
        return None
    filename = data[11:11+fn_len].decode('utf-8')
    payload = data[11+fn_len:]
    return {
        'version': version,
        'is_last': bool(flags & 1),
        'total': total,
        'index': index,
        'filename': filename,
        'payload': payload,
    }

def chunk_file(filepath, filename=None):
    """Read a binary file, split into chunks with SCQR headers.
    Returns (chunks_list, filename, total_chunks).
    Each chunk is bytes(header + payload)."""
    with open(filepath, 'rb') as f:
        raw = f.read()
    if filename is None:
        filename = os.path.basename(filepath)
    fn_bytes = filename.encode('utf-8')
    overhead = 11 + len(fn_bytes)
    chunk_data_size = CHUNK_SIZE - overhead
    if chunk_data_size <= 0:
        raise ValueError('Filename too long for any chunk')
    total = (len(raw) + chunk_data_size - 1) // chunk_data_size
    chunks = []
    for i in range(total):
        start = i * chunk_data_size
        end = min(start + chunk_data_size, len(raw))
        payload = raw[start:end]
        header = make_chunk_header(total, i, filename, i == total - 1)
        chunks.append(header + payload)
    return chunks, filename, total

def safe_filename(name):
    """Replace dots with underscores for cache file naming."""
    return name.replace('.', '_')


# ===================== File Send Directory =====================
CACHE_DIR = 'qr_send_cache'

def cache_path_for(filename, timestamp, index):
    safe = safe_filename(filename)
    return os.path.join(CACHE_DIR, f'__{safe}_{timestamp}_{index:05d}.qrf')

def is_cache_file(filepath):
    name = os.path.basename(filepath)
    return name.startswith('__') and name.endswith('.qrf')

def parse_cache_filename(filepath):
    """Parse a cache filename to extract metadata. Returns dict or None."""
    name = os.path.basename(filepath)
    if not is_cache_file(filepath):
        return None
    # __<safe_name>_<HHMM>_<index>.qrf
    # Remove __ prefix and .qrf suffix
    inner = name[2:-4]  # remove __ and .qrf
    parts = inner.rsplit('_', 2)
    if len(parts) != 3:
        return None
    safe_name, timestamp, idx_str = parts
    try:
        index = int(idx_str)
    except ValueError:
        return None
    return {'safe_name': safe_name, 'timestamp': timestamp, 'index': index}


# ===================== PyQt5 GUI =====================
class FileQRWidget(QWidget):
    def __init__(self):
        super().__init__()
        self.app = None
        self.setWindowTitle('FileQR - QR File Transfer')
        self.resize(420, 500)
        self.setMinimumSize(250, 300)

        # State
        self.selected_file = None
        self.chunks = []
        self.chunk_index = 0
        self.total_chunks = 0
        self.filename = ''
        self.current_mat = None
        self.current_ver = 0
        self.current_byte_count = 0
        self.is_sending = False
        self.is_cache_mode = False  # retransmitting from cache
        self.send_timestamp = ''

        # Status message
        self.status_msg = 'Select file then click Send to start'

        # Build UI
        self._build_ui()

        # Timer for 3-second cycling
        self.display_timer = QTimer(self)
        self.display_timer.timeout.connect(self._show_next_chunk)

    def _build_ui(self):
        layout = QVBoxLayout()
        layout.setSpacing(6)
        layout.setContentsMargins(10, 10, 10, 10)

        # Top bar: file selection
        top_layout = QHBoxLayout()
        self.browse_btn = QPushButton('Select File')
        self.browse_btn.clicked.connect(self._browse_file)
        self.file_label = QLabel('No file selected')
        top_layout.addWidget(self.browse_btn)
        top_layout.addWidget(self.file_label, 1)
        layout.addLayout(top_layout)

        # Start/Stop button
        btn_layout = QHBoxLayout()
        self.send_btn = QPushButton('Send')
        self.send_btn.clicked.connect(self._toggle_send)
        self.clear_btn = QPushButton('Clear Cache')
        self.clear_btn.clicked.connect(self._clear_cache)
        btn_layout.addWidget(self.send_btn)
        btn_layout.addWidget(self.clear_btn)
        layout.addLayout(btn_layout)

        # Stretch area -- QR code drawn in paintEvent
        layout.addStretch(1)

        # Status bar
        self.status_label = QLabel(self.status_msg)
        self.status_label.setAlignment(Qt.AlignCenter)
        layout.addWidget(self.status_label)

        self.setLayout(layout)

    def _browse_file(self):
        if self.is_sending:
            return
        path, _ = QFileDialog.getOpenFileName(self, 'Select File')
        if not path:
            return
        self.selected_file = path
        basename = os.path.basename(path)

        # Check if it's a cache file for retransmission
        if is_cache_file(path):
            self.is_cache_mode = True
            # Read and parse header from the cache file
            with open(path, 'rb') as f:
                chunk_data = f.read()
            header = parse_chunk_header(chunk_data)
            if header is None:
                self.file_label.setText(f'{basename} (invalid cache)')
                self.status_msg = 'Invalid cache file'
                self.status_label.setText(self.status_msg)
                self.chunks = []
                return
            # We only send this one chunk
            self.chunks = [chunk_data]
            self.total_chunks = 1
            self.chunk_index = 0
            self.filename = header['filename']
            # Show first QR immediately
            self._render_chunk(0)
            size_display = f'({len(chunk_data)} B, retransmit)'
            self.file_label.setText(f'{basename} {size_display}')
            self.status_msg = f'Retransmit: {header["filename"]} packet #{header["index"]}'
        else:
            self.is_cache_mode = False
            try:
                size = os.path.getsize(path)
                if size == 0:
                    self.file_label.setText(f'{basename} (empty file)')
                    self.status_msg = 'File is empty'
                    self.status_label.setText(self.status_msg)
                    self.chunks = []
                    return
                chunks, fn, total = chunk_file(path)
                self.chunks = chunks
                self.total_chunks = total
                self.chunk_index = 0
                self.filename = fn
                self._render_chunk(0)
                size_str = self._format_size(size)
                self.file_label.setText(f'{basename} ({size_str}, {total} chunks)')
                self.status_msg = f'Ready: {fn}, {total} chunks'
            except Exception as e:
                self.file_label.setText(f'{basename} (error)')
                self.status_msg = f'Read error: {e}'
                self.chunks = []

        self.status_label.setText(self.status_msg)
        self.update()

    def _toggle_send(self):
        if self.is_sending:
            self._stop_sending()
        else:
            self._start_sending()

    def _start_sending(self):
        if not self.chunks:
            self.status_msg = 'Please select file first'
            self.status_label.setText(self.status_msg)
            return

        self.is_sending = True
        self.send_btn.setText('Stop')
        self.browse_btn.setEnabled(False)

        # Record start timestamp
        self.send_timestamp = time.strftime('%H%M')

        # Ensure cache dir exists
        if not os.path.exists(CACHE_DIR):
            os.makedirs(CACHE_DIR)

        # Start from START_CHUNK
        self.chunk_index = START_CHUNK
        self._write_cache(START_CHUNK)
        self._render_chunk(START_CHUNK)
        self._update_status()

        # Start 3-second timer
        self.display_timer.start(3000)

    def _stop_sending(self):
        self.is_sending = False
        self.display_timer.stop()
        self.send_btn.setText('Send')
        self.browse_btn.setEnabled(True)
        self.status_msg = 'Stopped'
        self.status_label.setText(self.status_msg)

    def _write_cache(self, idx):
        """Write a single chunk to cache (lazy, per-chunk)."""
        if self.is_cache_mode:
            return
        if idx < 0 or idx >= len(self.chunks):
            return
        cpath = cache_path_for(self.filename, self.send_timestamp, idx)
        if not os.path.exists(cpath):
            with open(cpath, 'wb') as f:
                f.write(self.chunks[idx])

    def _show_next_chunk(self):
        self.chunk_index += 1
        if self.chunk_index >= self.total_chunks:
            # Transmission complete
            self.display_timer.stop()
            self.is_sending = False
            self.send_btn.setText('Send')
            self.browse_btn.setEnabled(True)
            self.status_msg = f'Transfer complete: {self.filename} ({self.total_chunks} chunks)'
            self.status_label.setText(self.status_msg)
            return
        self._write_cache(self.chunk_index)
        self._render_chunk(self.chunk_index)
        self._update_status()

    def _render_chunk(self, idx):
        if idx < 0 or idx >= len(self.chunks):
            self.current_mat = None
            return
        try:
            self.current_mat, self.current_ver, self.current_byte_count = qr_bytes(self.chunks[idx])
        except Exception as e:
            self.current_mat = None
            self.status_msg = f'QR encode failed (chunk {idx}): {e}'
        self.update()

    def _update_status(self):
        pct = (self.chunk_index + 1) * 100 // self.total_chunks
        self.status_msg = f'chunk {self.chunk_index+1:03d}/{self.total_chunks:03d} | {pct}% | {self.filename}'
        self.status_label.setText(self.status_msg)

    def _clear_cache(self):
        if self.is_sending:
            return
        if not os.path.exists(CACHE_DIR):
            return
        import shutil
        count = len([f for f in os.listdir(CACHE_DIR) if f.endswith('.qrf')])
        shutil.rmtree(CACHE_DIR, ignore_errors=True)
        os.makedirs(CACHE_DIR, exist_ok=True)
        self.status_msg = f'Cleared {count} cache file(s)'
        self.status_label.setText(self.status_msg)

    def _format_size(self, size):
        if size < 1024:
            return f'{size} B'
        elif size < 1024 * 1024:
            return f'{size/1024:.1f} KB'
        else:
            return f'{size/(1024*1024):.1f} MB'

    def paintEvent(self, e):
        super().paintEvent(e)
        if self.current_mat is None:
            return

        p = QPainter(self)
        p.fillRect(self.rect(), QColor('white'))
        p.setRenderHint(QPainter.Antialiasing, False)

        # QR area: absolute coords between button row and status label
        btn_bottom = self.send_btn.geometry().bottom() + 10
        lbl_top = self.status_label.geometry().top() - 10
        qr_h = max(1, lbl_top - btn_bottom)
        qr_w = self.width()

        q = 4  # quiet zone
        n = len(self.current_mat) + q * 2
        cell = max(1, min(qr_w, qr_h) // n)
        size = cell * n
        left = (qr_w - size) // 2
        top = btn_bottom + (qr_h - size) // 2

        p.fillRect(left, top, size, size, QColor('white'))
        p.setPen(Qt.NoPen)
        p.setBrush(QColor('black'))
        for y, row in enumerate(self.current_mat):
            for x, b in enumerate(row):
                if b:
                    p.drawRect(left + (x + q) * cell, top + (y + q) * cell, cell, cell)
        p.end()

    def keyPressEvent(self, e):
        if e.key() == Qt.Key_Escape:
            if self.is_sending:
                self._stop_sending()
            self.close()
        elif e.key() == Qt.Key_R and (e.modifiers() & Qt.ControlModifier):
            # Ctrl+R to reload selected file
            if self.selected_file and not self.is_sending:
                self._browse_file()


if __name__ == '__main__':
    app = QApplication(sys.argv)
    w = FileQRWidget()
    w.app = app
    w.show()
    sys.exit(app.exec_())
