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

def chunks(path,cs=CS):
    raw=open(path,'rb').read(); name=os.path.basename(path); n=cs-11-len(name.encode('utf-8'))
    if n<1: raise ValueError('filename too long')
    total=(len(raw)+n-1)//n
    if total>65535: raise ValueError('file too large')
    return [head(total,i,name)+raw[i*n:(i+1)*n] for i in range(total)],name

def cpath(name,ts,i): return os.path.join(CD,'__'+name.replace('.','_')+'_'+ts+'_%05d.qrf'%i)
def isc(path): return os.path.basename(path).startswith('__') and path.endswith('.qrf')
def sz(n): return '%d B'%n if n<1024 else ('%.1f KB'%(n/1024) if n<1048576 else '%.1f MB'%(n/1048576))
def cap(v):
    n=8 if v<10 else 16
    return ((rm(v)//8-E[v-1]*K[v-1])*8-4-n)//8
def fitv():
    try: t=os.get_terminal_size(); cols,rows=t.columns,t.lines
    except OSError: cols,rows=80,25
    side=min(cols-8,2*(rows-2)-8)
    v=(side-17)//4
    return max(1,min(40,v)),cols,rows

def cls(): sys.stdout.write('\033[H\033[2J')
def tui(on=1): sys.stdout.write('\033[?1049h\033[?25l' if on else '\033[?25h\033[?1049l'); sys.stdout.flush()

def show(m,msg=''):
    q=4; n=len(m)+q*2; cls()
    try: t=os.get_terminal_size(); cols,lines=t.columns,t.lines
    except OSError: cols,lines=999,999
    need=(n+1)//2+1
    if n>cols or need>lines: print('terminal too small: need %dx%d, have %dx%d'%(n,need,cols,lines))
    z=[0]*len(m); rows=[z]*q+[r[:] for r in m]+[z]*q
    for i in range(0,len(rows),2):
        a=rows[i]; c=rows[i+1] if i+1<len(rows) else z
        s=[]
        for x in range(-q,len(m)+q):
            u=a[x] if 0<=x<len(m) else 0; d=c[x] if 0<=x<len(m) else 0
            s.append(' ' if not u and not d else (chr(0x2580) if u and not d else (chr(0x2584) if d and not u else chr(0x2588))))
        print(''.join(s))
    if msg: print(msg)
    sys.stdout.flush()

def textqr(s):
    m,v,n=qt(s); tui(1)
    try:
        show(m,'QR v%d | %d bytes | %d chars | Enter exit'%(v,n,len(s))); input()
    finally: tui(0)

def fileqr(path,once=0):
    fv,cols,rows=fitv()
    if isc(path):
        d=open(path,'rb').read(); x=parse(d)
        if not x: raise ValueError('bad cache file')
        ch=[d]; fn=x[4]; cache=1; cs=len(d)
    else:
        if os.path.getsize(path)==0: raise ValueError('empty file')
        name=os.path.basename(path); cs=min(CS,cap(fv))
        if cs<=11+len(name.encode('utf-8')): raise ValueError('terminal too small')
        ch,fn=chunks(path,cs); cache=0
    ts=time.strftime('%H%M'); os.makedirs(CD,exist_ok=True); i=0; tui(1)
    try:
        while i<len(ch):
            if not cache:
                p=cpath(fn,ts,i)
                if not os.path.exists(p): open(p,'wb').write(ch[i])
            m,v,n=qr(ch[i],0)
            show(m,'chunk %03d/%03d | %d%% | %s | %d bytes | QR v%d | fit v%d | Ctrl+C stop'%(i+1,len(ch),(i+1)*100//len(ch),fn,n,v,fv))
            if once: break
            i+=1
            if i<len(ch): time.sleep(1)
        if not once: show(m,'done %s (%d chunks)'%(fn,len(ch)))
    except KeyboardInterrupt:
        show(m,'stopped')
    finally:
        time.sleep(.8); tui(0)

def clear():
    n=len([x for x in os.listdir(CD) if x.endswith('.qrf')]) if os.path.isdir(CD) else 0
    shutil.rmtree(CD,ignore_errors=True); os.makedirs(CD,exist_ok=True); print('cleared %d cache files'%n)

def usage():
    print('usage:')
    print('  python3 scqr_term_tui_fit.py -t TEXT')
    print('  echo TEXT | python3 scqr_term_tui_fit.py -')
    print('  python3 scqr_term_tui_fit.py FILE')
    print('  python3 scqr_term_tui_fit.py --once FILE')
    print('  python3 scqr_term_tui_fit.py --clear')

def run():
    a=sys.argv[1:]
    if not a: usage(); return
    once=0
    if '--clear' in a: clear(); return
    if '--once' in a: once=1; a.remove('--once')
    if a and a[0]=='-t':
        if len(a)<2: usage(); return
        textqr(' '.join(a[1:])); return
    if a and a[0]=='-': textqr(sys.stdin.read()); return
    fileqr(a[0],once)

if __name__=='__main__': run()
