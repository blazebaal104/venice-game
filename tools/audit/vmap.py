import struct, zlib, io
def load(path):
    b=open(path,'rb').read(); o=0
    def r(fmt):
        nonlocal o; v=struct.unpack_from('>'+fmt,b,o); o+=struct.calcsize('>'+fmt); return v
    assert r('i')[0]==0x564D4150; r('i')
    lat0,lon0=r('dd'); minX,minY=r('ff'); tx_,ty_,_=r('iii'); n=r('i')[0]
    d={}
    for i in range(n):
        tx,ty,off,ln=r('iiii'); d[(tx,ty)]=(off,ln)
    blob=b[o:]
    return dict(lat0=lat0,lon0=lon0,minX=minX,minY=minY,tilesX=tx_,tilesY=ty_,dir=d,blob=blob)
def utf(s):
    n=s.r('H')[0]; v=s.buf[s.o:s.o+n].decode('utf8','replace'); s.o+=n; return v
class S:
    def __init__(s,buf): s.buf=buf; s.o=0
    def r(s,fmt):
        v=struct.unpack_from('>'+fmt,s.buf,s.o); s.o+=struct.calcsize('>'+fmt); return v
def tile(m,tx,ty):
    off,ln=m['dir'][(tx,ty)]
    s=S(zlib.decompress(m['blob'][off:off+ln]))
    ox=m['minX']+tx*256; oy=m['minY']+ty*256
    mn=s.r('i')[0]; hard=marsh=cls=None
    if mn>0:
        hard=s.buf[s.o:s.o+mn*mn]; s.o+=mn*mn; marsh=s.buf[s.o:s.o+mn*mn]; s.o+=mn*mn; cls=s.buf[s.o:s.o+mn*mn]; s.o+=mn*mn
    walls=[]
    for _ in range(s.r('i')[0]):
        k=s.r('i')[0]; walls.append(s.r('%df'%(2*k)))
    blds=[]
    for _ in range(s.r('i')[0]):
        kind=s.r('b')[0]; h=s.r('f')[0]; seed=s.r('i')[0]; nr=s.r('h')[0]
        sizes=s.r('%dh'%nr) if nr else (); xy=s.r('%df'%(2*sum(sizes)))
        nt=s.r('i')[0]; tris=s.r('%dh'%nt) if nt else ()
        blds.append((kind,h,seed,sizes,xy,tris))
    pts=[]
    for _ in range(s.r('i')[0]):
        t=s.r('b')[0]; x,y,dr=s.r('fff'); nm=utf(s); pts.append((t,x,y,dr,nm))
    brs=[]
    for _ in range(s.r('i')[0]):
        w=s.r('f')[0]; st=s.r('b')[0]; k=s.r('h')[0]; brs.append((w,st,s.r('%df'%(2*k))))
    piers=[]
    for _ in range(s.r('i')[0]):
        w=s.r('f')[0]; k=s.r('h')[0]; piers.append((w,s.r('%df'%(2*k))))
    pp=[]
    for _ in range(s.r('i')[0]):
        k=s.r('h')[0]; xy=s.r('%df'%(2*k)); nt=s.r('i')[0]; tr=s.r('%dh'%nt) if nt else (); pp.append((xy,tr))
    return dict(ox=ox,oy=oy,mn=mn,hard=hard,marsh=marsh,cls=cls,walls=walls,blds=blds,pts=pts,brs=brs,piers=piers,pp=pp)
