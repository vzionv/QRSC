#!/usr/bin/env python3
import os,sys,time,shutil,subprocess

SKIP='; \t\n\r'


def fs(s):
    n=0
    for c in s:
        if c not in SKIP: n+=ord(c)
    return n


def vt():
    if os.name=='nt':
        try:
            import ctypes
            k=ctypes.windll.kernel32; h=k.GetStdHandle(-11); m=ctypes.c_uint()
            if k.GetConsoleMode(h,ctypes.byref(m)): k.SetConsoleMode(h,m.value|4)
        except Exception: pass


class Clip:
    def __init__(self):
        self.r=None; self.err=''
        try:
            import tkinter as tk
            self.tk=tk; self.r=tk.Tk(); self.r.withdraw()
        except Exception as e:
            self.tk=None; self.err=str(e)
    def get(self):
        if self.r:
            try:
                self.r.update(); return self.r.clipboard_get()
            except Exception: return ''
        return cmdclip()
    def close(self):
        if self.r:
            try: self.r.destroy()
            except Exception: pass


def cmdclip():
    try:
        if sys.platform.startswith('win'):
            p=['powershell','-NoProfile','-Command','Get-Clipboard -Raw']
        elif sys.platform=='darwin':
            p=['pbpaste']
        else:
            for p in (['wl-paste','-n'],['xclip','-selection','clipboard','-o'],['xsel','-b','-o']):
                if shutil.which(p[0]): break
            else: return ''
        return subprocess.check_output(p,stderr=subprocess.DEVNULL,timeout=1).decode('utf-8','replace')
    except Exception:
        return ''


def draw(total,ln,chg,err=''):
    w,h=shutil.get_terminal_size((80,24)); lines=[]
    lines.append('ClipboardSum TUI')
    lines.append('')
    lines.append(str(total))
    lines.append('')
    lines.append(('changed' if chg else 'waiting')+' | chars %d | Ctrl+C or q to quit'%ln)
    if err: lines.append(err[:w])
    y=max(0,(h-len(lines))//2); out=['\033[H\033[2J']+['\n']*y
    for s in lines: out.append(s.center(w)[:w]+'\n')
    sys.stdout.write(''.join(out)); sys.stdout.flush()


def keyhit():
    try:
        if os.name=='nt':
            import msvcrt
            if msvcrt.kbhit(): return msvcrt.getch() in (b'q',b'Q',b'\x1b')
        else:
            import select
            if select.select([sys.stdin],[],[],0)[0]: return sys.stdin.read(1) in ('q','Q','\x1b')
    except Exception: pass
    return False


def main():
    old=None; total=0; ln=0; clip=Clip(); vt()
    raw=0; oldterm=None
    if os.name!='nt' and sys.stdin.isatty():
        try:
            import termios,tty
            oldterm=termios.tcgetattr(sys.stdin); tty.setcbreak(sys.stdin.fileno()); raw=1
        except Exception: pass
    sys.stdout.write('\033[?1049h\033[?25l'); sys.stdout.flush()
    try:
        draw(0,0,0,'')
        while 1:
            s=clip.get()
            chg=s!=old
            if chg:
                old=s; total=fs(s); ln=len(s)
            draw(total,ln,chg,clip.err if not clip.r else '')
            for _ in range(30):
                if keyhit(): return
                time.sleep(0.01)
    except KeyboardInterrupt:
        pass
    finally:
        clip.close()
        if raw:
            try: termios.tcsetattr(sys.stdin,termios.TCSADRAIN,oldterm)
            except Exception: pass
        sys.stdout.write('\033[?25h\033[?1049l'); sys.stdout.flush()


if __name__=='__main__': main()
