#!/usr/bin/env python3
import sys,os,time,shutil

E=[7,10,15,20,26,18,20,24,30,18,20,24,26,30,22,24,28,30,28,28,28,28,30,30,26,28,30,30,30,30,30,30,30,30,30,30,30,30,30,30]
K=[1,1,1,1,1,2,2,2,2,4,4,4,4,4,6,6,6,6,7,8,8,9,9,10,12,12,12,13,14,15,16,17,18,19,19,20,21,22,24,25]
CS=1200; CD='qr_send_cache'

def rm(v):
    r=(16*v+128)*v+64
    if v>1:
        n=v//7+2; r-=(25*n-10)*n-55
        if v>6: r-=36
    return r

def ap(v):
    if v<2: return []
    s=4*v+17; n=v//7+2; st=26 if v==32 else ((4*v+2*n+1)//(2*n-2))*2
    a=[]; p=s-7
    for _ in range(n-1): a.append(p); p-=st
    return [6]+a[::-1]

def gm(x,y):
    z=0
    while y:
        if y&1: z^=x
        x<<=1
        if x&256: x^=0x11d
        y>>=1
    return z

def rd(n):
    r=[0]*(n-1)+[1]; x=1
    for _ in range(n):
        r.append(0)
        for i in range(n): r[i]=gm(r[i],x)^r[i+1]
        r.pop(); x=gm(x,2)
    return r

def rr(data,div):
    r=[0]*len(div)
    for b in data:
        f=b^r.pop(0); r.append(0)
        for i,d in enumerate(div): r[i]^=gm(d,f)
    return r

def pf(m,f,x,y,c):
    if 0<=x<len(m) and 0<=y<len(m): m[y][x]=1 if c else 0; f[y][x]=1

def ft(mask):
    d=8|mask; r=d
    for _ in range(10): r=(r<<1)^(((r>>9)&1)*0x537)
    return ((d<<10)|r)^0x5412

def vt(v):
    r=v
    for _ in range(12): r=(r<<1)^(((r>>11)&1)*0x1f25)
    return (v<<12)|r

def df(m,f,mask):
    s=len(m); b=ft(mask)
    for i in range(15):
        x=(b>>i)&1
        if i<6: pf(m,f,8,i,x)
        elif i<7: pf(m,f,8,7,x)
        elif i<8: pf(m,f,8,8,x)
        elif i<9: pf(m,f,7,8,x)
        else: pf(m,f,14-i,8,x)
        if i<8: pf(m,f,s-1-i,8,x)
        else: pf(m,f,8,s-15+i,x)
    pf(m,f,8,s-8,1)

def dv(m,f,v):
    if v<7: return
    s=len(m); b=vt(v)
    for i in range(18):
        x=(b>>i)&1; a=s-11+i%3; c=i//3
        pf(m,f,a,c,x); pf(m,f,c,a,x)

def ba(v):
    s=4*v+17; m=[[-1]*s for _ in range(s)]; f=[[0]*s for _ in range(s)]
    def fd(cx,cy):
        for y in range(cy-4,cy+5):
            for x in range(cx-4,cx+5):
                d=max(abs(x-cx),abs(y-cy)); pf(m,f,x,y,d!=2 and d!=4)
    fd(3,3); fd(s-4,3); fd(3,s-4)
    a=ap(v)
    for y in a:
        for x in a:
            if f[y][x]: continue
            for dy in range(-2,3):
                for dx in range(-2,3): pf(m,f,x+dx,y+dy,max(abs(dx),abs(dy))!=1)
    for i in range(s):
        if not f[6][i]: pf(m,f,i,6,i%2==0)
        if not f[i][6]: pf(m,f,6,i,i%2==0)
    df(m,f,0); dv(m,f,v)
    return m,f

def ec(data,v):
    nb=K[v-1]; el=E[v-1]; raw=rm(v)//8
    ns=nb-raw%nb; short=raw//nb; div=rd(el); bs=[]; k=0
    for i in range(nb):
        n=short-el+(0 if i<ns else 1); d=data[k:k+n]; k+=n; 
        bs.append((d,rr(d,div)))
    out=[]
    for i in range(max(len(b[0]) for b in bs)):
        for d,e in bs:
            if i<len(d): out.append(d[i])
    for i in range(el):
        for d,e in bs: out.append(e[i])
    return out

def ms(mask,x,y):
    if mask==0: return (x+y)%2==0
    if mask==1: return y%2==0
    if mask==2: return x%3==0
    if mask==3: return (x+y)%3==0
    if mask==4: return (x//3+y//2)%2==0
    if mask==5: return (x*y)%2+(x*y)%3==0
    if mask==6: return ((x*y)%2+(x*y)%3)%2==0
    return ((x+y)%2+(x*y)%3)%2==0

def pl(m,f,cw):
    s=len(m); bit=0; x=s-1; up=True
    while x>0:
        if x==6: x-=1
        for y in (range(s-1,-1,-1) if up else range(s)):
            for xx in (x,x-1):
                if not f[y][xx]:
                    m[y][xx]=(cw[bit>>3]>>(7-(bit&7)))&1 if bit<len(cw)*8 else 0; bit+=1
        up=not up; x-=2

def pe(m):
    s=len(m); p=0
    for rows in (m,list(map(list,zip(*m)))):
        for r in rows:
            run=1; last=r[0]; bits=0
            for i,b in enumerate(r):
                bits=((bits<<1)|b)&0x7ff
                if i>9 and (bits==0x05d or bits==0x5d0): p+=40
                if i and b==last: run+=1
                else:
                    if run>4: p+=run-2
                    last=b; run=1
            if run>4: p+=run-2
    for y in range(s-1):
        for x in range(s-1):
            c=m[y][x]
            if c==m[y][x+1]==m[y+1][x]==m[y+1][x+1]: p+=3
    return p+abs(sum(map(sum,m))*20-s*s*10)//(s*s)*10

def qr(b,eci=0):
    for v in range(1,41):
        n=8 if v<10 else 16
        if (12 if eci else 0)+4+n+8*len(b)<=(rm(v)//8-E[v-1]*K[v-1])*8: break
    else: raise ValueError('too long for QR v40-L')
    bits=[]
    def pu(x,n):
        for i in range(n-1,-1,-1): bits.append((x>>i)&1)
    if eci: pu(7,4); pu(26,8)
    pu(4,4); pu(len(b),8 if v<10 else 16)
    for x in b: pu(x,8)
    dl=rm(v)//8-E[v-1]*K[v-1]; bits += [0]*min(4,dl*8-len(bits))
    while len(bits)%8: bits.append(0)
    data=[sum(bits[i+j]<<(7-j) for j in range(8)) for i in range(0,len(bits),8)]
    k=0
    while len(data)<dl: data.append(0xec if k%2==0 else 0x11); k+=1
    cw=ec(data,v); bm,bf=ba(v); pl(bm,bf,cw); best=None; ans=None
    for ma in range(8):
        mm=[r[:] for r in bm]; ff=[r[:] for r in bf]
        for y in range(len(mm)):
            for x in range(len(mm)):
                if not ff[y][x] and ms(ma,x,y): mm[y][x]^=1
        df(mm,ff,ma); sc=pe(mm)
        if best is None or sc<best: best=sc; ans=mm
    return ans,v,len(b)

def qt(s):
    b=s.encode('utf-8'); return qr(b,any(x>127 for x in b))

def head(total,idx,name):
    f=name.encode('utf-8')
    if len(f)>255: raise ValueError('filename too long')
    return b'SCQR'+bytes([1,idx==total-1,total>>8,total&255,idx>>8,idx&255,len(f)])+f

def parse(d):
    if len(d)<11 or d[:4]!=b'SCQR': return None
    n=d[10]
    if len(d)<11+n: return None
    return (d[4],d[5],d[6]<<8|d[7],d[8]<<8|d[9],d[11:11+n].decode('utf-8'),d[11+n:])

def chunks(path):
    raw=open(path,'rb').read(); name=os.path.basename(path); n=CS-11-len(name.encode('utf-8'))
    if n<1: raise ValueError('filename too long')
    total=(len(raw)+n-1)//n
    if total>65535: raise ValueError('file too large')
    return [head(total,i,name)+raw[i*n:(i+1)*n] for i in range(total)],name

def cpath(name,ts,i): return os.path.join(CD,'__'+name.replace('.','_')+'_'+ts+'_%05d.qrf'%i)
def isc(path): return os.path.basename(path).startswith('__') and path.endswith('.qrf')
def sz(n): return '%d B'%n if n<1024 else ('%.1f KB'%(n/1024) if n<1048576 else '%.1f MB'%(n/1048576))

def run():
    from PyQt5.QtCore import Qt,QTimer
    from PyQt5.QtGui import QPainter,QColor
    from PyQt5.QtWidgets import QApplication,QWidget,QPushButton,QLabel,QHBoxLayout,QVBoxLayout,QFileDialog
    class W(QWidget):
        def __init__(self,app):
            super().__init__(); self.app=app; self.last=None; self.mat=None; self.ch=[]; self.fn=''; self.i=0; self.sending=0; self.cache=0; self.msg='copy text or select file'
            self.setWindowTitle('SCQR'); self.resize(420,500); self.setMinimumSize(230,280)
            self._build_ui()
            self.ct=QTimer(self); self.ct.timeout.connect(self.clip); self.ct.start(800)
            self.ft=QTimer(self); self.ft.timeout.connect(self.next)
            r=QApplication.primaryScreen().availableGeometry(); self.move(r.left(),r.bottom()-self.height()); self.clip()
        def _build_ui(self):
            v=QVBoxLayout(self); v.setSpacing(6); v.setContentsMargins(10,10,10,10)
            h=QHBoxLayout(); self.sel=QPushButton('Select File'); self.lab=QLabel('No file'); h.addWidget(self.sel); h.addWidget(self.lab,1); v.addLayout(h)
            h=QHBoxLayout(); self.send=QPushButton('Send'); self.clr=QPushButton('Clear Cache'); h.addWidget(self.send); h.addWidget(self.clr); v.addLayout(h); v.addStretch(1)
            self.stat=QLabel(self.msg); self.stat.setAlignment(Qt.AlignCenter); v.addWidget(self.stat)
            self.sel.clicked.connect(self.pick); self.send.clicked.connect(self.go); self.clr.clicked.connect(self.clear)
        def showmsg(self,s): self.msg=s; self.stat.setText(s); self.update()
        def clip(self):
            if self.sending: return
            t=self.app.clipboard().text()
            if t==self.last: return
            self.last=t
            if not t: self.mat=None; self.showmsg('clipboard is empty')
            else:
                try: self.mat,self.v,self.n=qt(t); self.showmsg('QR v%d | %d bytes | %d chars'%(self.v,self.n,len(t)))
                except Exception as e: self.mat=None; self.showmsg(str(e))
        def pick(self):
            if self.sending: return
            p,_=QFileDialog.getOpenFileName(self,'Select File')
            if not p: return
            b=os.path.basename(p)
            try:
                if isc(p):
                    d=open(p,'rb').read(); x=parse(d)
                    if not x: raise ValueError('bad cache file')
                    self.ch=[d]; self.fn=x[4]; self.cache=1; self.lab.setText(b+' (resend)'); self.render(0); self.showmsg('ready resend %s #%d'%(self.fn,x[3]))
                else:
                    if os.path.getsize(p)==0: raise ValueError('empty file')
                    self.ch,self.fn=chunks(p); self.cache=0; self.lab.setText('%s (%s, %d chunks)'%(b,sz(os.path.getsize(p)),len(self.ch))); self.render(0); self.showmsg('ready %s, %d chunks'%(self.fn,len(self.ch)))
                self.last=self.app.clipboard().text()
            except Exception as e:
                self.ch=[]; self.mat=None; self.lab.setText(b+' (error)'); self.showmsg(str(e))
        def go(self): self.stop() if self.sending else self.start()
        def start(self):
            if not self.ch: self.showmsg('select file first'); return
            self.sending=True; self.ct.stop(); self.send.setText('Stop'); self.sel.setEnabled(False); os.makedirs(CD,exist_ok=True); self.ts=time.strftime('%H%M'); self.i=0; self.save(0); self.render(0); self.upd(); self.ft.start(3000)
        def stop(self):
            self.sending=False; self.ft.stop(); self.ct.start(800); self.send.setText('Send'); self.sel.setEnabled(True); self.showmsg('stopped')
        def save(self,i):
            if not self.cache:
                p=cpath(self.fn,self.ts,i)
                if not os.path.exists(p):
                    with open(p,'wb') as f:
                        f.write(self.ch[i])
        def next(self):
            self.i+=1
            if self.i>=len(self.ch):
                self.sending=False; self.ft.stop(); self.ct.start(800); self.send.setText('Send'); self.sel.setEnabled(True); self.showmsg('done %s (%d chunks)'%(self.fn,len(self.ch))); return
            self.save(self.i); self.render(self.i); self.upd()
        def render(self,i):
            try: self.mat,self.v,self.n=qr(self.ch[i],0)
            except Exception as e: self.mat=None; self.showmsg('QR error '+str(e))
            self.update()
        def upd(self): self.showmsg('chunk %03d/%03d | %d%% | %s'%(self.i+1,len(self.ch),(self.i+1)*100//len(self.ch),self.fn))
        def clear(self):
            if self.sending: return
            n=len([x for x in os.listdir(CD) if x.endswith('.qrf')]) if os.path.isdir(CD) else 0
            shutil.rmtree(CD,ignore_errors=True); os.makedirs(CD,exist_ok=True); self.showmsg('cleared %d cache files'%n)
        def keyPressEvent(self,e):
            if e.key()==Qt.Key_Escape:
                if self.sending: self.stop()
                self.close()
            elif e.key()==Qt.Key_R and (e.modifiers()&Qt.ControlModifier): self.last=None; self.clip()
        def paintEvent(self,e):
            super().paintEvent(e)
            p=QPainter(self); p.fillRect(self.rect(),QColor('white'))
            if self.mat:
                q=4; top=self.send.geometry().bottom()+10; bot=self.stat.geometry().top()-10; h=max(1,bot-top); w=self.width(); n=len(self.mat)+q*2; cell=max(1,min(w,h)//n); size=cell*n; left=(w-size)//2; top=top+(h-size)//2
                p.fillRect(left,top,size,size,QColor('white')); p.setPen(Qt.NoPen); p.setBrush(QColor('black'))
                for y,r in enumerate(self.mat):
                    for x,b in enumerate(r):
                        if b: p.drawRect(left+(x+q)*cell,top+(y+q)*cell,cell,cell)
            p.end()
    app=QApplication(sys.argv); w=W(app); w.show(); sys.exit(app.exec_())

if __name__=='__main__': run()
