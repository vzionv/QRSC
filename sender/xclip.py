import ctypes,ctypes.util,time,os
from ctypes import *

class XS(Structure):
    _fields_=[('type',c_int),('serial',c_ulong),('send_event',c_int),
    ('display',c_void_p),('requestor',c_ulong),('selection',c_ulong),
    ('target',c_ulong),('property',c_ulong),('time',c_ulong)]
class XE(Union):
    _fields_=[('type',c_int),('xselection',XS),('pad',c_long*24)]

def xclip():
    lib=ctypes.util.find_library('X11') or 'libX11.so.6'
    x=CDLL(lib)
    x.XOpenDisplay.argtypes=[c_char_p]; x.XOpenDisplay.restype=c_void_p
    x.XDefaultRootWindow.argtypes=[c_void_p]; x.XDefaultRootWindow.restype=c_ulong
    x.XInternAtom.argtypes=[c_void_p,c_char_p,c_int]; x.XInternAtom.restype=c_ulong
    x.XCreateSimpleWindow.restype=c_ulong
    x.XPending.argtypes=[c_void_p]; x.XPending.restype=c_int
    x.XGetWindowProperty.argtypes=[c_void_p,c_ulong,c_ulong,c_long,c_long,c_int,c_ulong,POINTER(c_ulong),POINTER(c_int),POINTER(c_ulong),POINTER(c_ulong),POINTER(c_void_p)]
    x.XFree.argtypes=[c_void_p]
    d=x.XOpenDisplay(None)
    if not d: return ''
    r=x.XDefaultRootWindow(d)
    w=x.XCreateSimpleWindow(d,r,0,0,1,1,0,0,0)
    sel=x.XInternAtom(d,b'CLIPBOARD',0)
    prop=x.XInternAtom(d,b'PY_CLIP',0)
    for name in (b'UTF8_STRING',b'STRING'):
        target=x.XInternAtom(d,name,0)
        x.XConvertSelection(d,sel,target,prop,w,0); x.XFlush(d)
        end=time.time()+1
        while time.time()<end:
            if x.XPending(d):
                e=XE(); x.XNextEvent(d,byref(e))
                if e.type==31 and e.xselection.property:
                    at=c_ulong(); fmt=c_int(); n=c_ulong(); left=c_ulong(); p=c_void_p()
                    x.XGetWindowProperty(d,w,prop,0,1000000,1,0,byref(at),byref(fmt),byref(n),byref(left),byref(p))
                    if p:
                        b=string_at(p,n.value if fmt.value==8 else n.value*4)
                        x.XFree(p)
                        return b.decode('utf-8','replace')
            time.sleep(.01)
    return ''

print(xclip())