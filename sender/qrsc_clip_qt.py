#!/usr/bin/env python3
import sys, math
from PyQt5.QtCore import Qt, QTimer
from PyQt5.QtGui import QPainter, QColor, QFont
from PyQt5.QtWidgets import QApplication, QWidget
import numpy as np

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
    b=text.encode('utf-8'); useeci=any(x>127 for x in b)
    for v in range(1,41):
        ccb=8 if v<10 else 16
        need=(12 if useeci else 0)+4+ccb+8*len(b)
        cap=(rawmods(v)//8-ECC[v-1]*BLK[v-1])*8
        if need<=cap: break
    else:
        raise ValueError('内容太长：标准 QR-L 的上限约 2950 字节；中文约 900 多字。')
    bits=[]
    def put(val,n):
        for i in range(n-1,-1,-1): bits.append((val>>i)&1)
    if useeci: put(7,4); put(26,8)
    put(4,4); put(len(b),8 if v<10 else 16)
    for x in b: put(x,8)
    dl=rawmods(v)//8-ECC[v-1]*BLK[v-1]; cap=dl*8
    bits += [0]*min(4,cap-len(bits))
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

class W(QWidget):
    def __init__(self,app):
        super().__init__(); self.app=app; self.last=None; self.mat=None; self.msg=''; self.v=0; self.n=0
        self.setWindowTitle('AirQR - clipboard to QR'); self.resize(420,450); self.setMinimumSize(170,200); self.move2bl()
        self.reload(); self.t=QTimer(self); self.t.timeout.connect(self.reload); self.t.start(800)
    def reload(self):
        txt=self.app.clipboard().text()
        if txt==self.last: return
        self.last=txt
        if not txt:
            self.mat=None; self.msg='剪贴板没有文本。复制文本后本窗口会自动刷新。'
        else:
            try:
                self.mat,self.v,self.n=qr(txt); self.msg=f'QR v{self.v} | {self.n} bytes | {len(txt)} chars | Esc 退出 | Ctrl+R 刷新'
            except Exception as e:
                self.mat=None; self.msg=str(e)
        self.update()
    def move2bl(self):
        scn_rect=QApplication.primaryScreen().availableGeometry()
        x=scn_rect.left()
        y=scn_rect.bottom()-self.height()
        self.move(x,y)
    def keyPressEvent(self,e):
        if e.key()==Qt.Key_Escape: self.close()
        elif e.key()==Qt.Key_R and e.modifiers()&Qt.ControlModifier: self.last=None; self.reload()
    def paintEvent(self,e):
        p=QPainter(self); p.fillRect(self.rect(),QColor('white')); p.setRenderHint(QPainter.Antialiasing,False)
        p.setPen(QColor('black')); p.setFont(QFont('Sans',9))
        htxt=28; w=self.width(); h=self.height()-htxt
        if self.mat:
            q=4; n=len(self.mat)+q*2; cell=max(1,min(w,h)//n); size=cell*n
            left=(w-size)//2; top=(h-size)//2
            p.fillRect(left,top,size,size,QColor('white')); p.setPen(Qt.NoPen); p.setBrush(QColor('black'))
            for y,r in enumerate(self.mat):
                for x,b in enumerate(r):
                    if b: p.drawRect(left+(x+q)*cell,top+(y+q)*cell,cell,cell)
            p.setPen(QColor('black'))
        p.drawText(8,self.height()-20,w-16,20,Qt.AlignCenter,self.msg)

if __name__=='__main__':
    app=QApplication(sys.argv); w=W(app); w.show(); sys.exit(app.exec_())
