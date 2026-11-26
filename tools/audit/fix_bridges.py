import vmap, numpy as np, math, json
m=vmap.load('../../app/src/main/assets/venice.map')
A='../../app/src/main/assets/'
tiles={}
def T(k):
    if k not in tiles: tiles[k]=vmap.tile(m,*k) if k in m['dir'] else None
    return tiles[k]
def solid(x,y):
    tx=math.floor((x-m['minX'])/256); ty=math.floor((y-m['minY'])/256)
    t=T((tx,ty))
    if not t or not t['hard']: return 0.0
    n=t['mn']; fx=x-t['ox']+0.5; fy=y-t['oy']+0.5
    i=int(min(max(fx,0),n-2)); j=int(min(max(fy,0),n-2)); ax=min(max(fx-i,0),1); ay=min(max(fy-j,0),1)
    def s(ii,jj): return max(t['hard'][jj*n+ii],t['marsh'][jj*n+ii])/255
    return (s(i,j)*(1-ax)+s(i+1,j)*ax)*(1-ay)+(s(i,j+1)*(1-ax)+s(i+1,j+1)*ax)*ay
types={l.split()[0]:float(l.split()[1]) for l in open(A+'bridges/bridge_types.txt') if l.strip() and not l.startswith('#')}
SKIP=('liberta','ribbon','girder','timber','costituzione','scalzi','accademia','guglie','bowstring')
def gap(x,y,r):
    """water span along the axis through (x,y): (back, fwd) distances to land, None if centre on land"""
    if solid(x,y)>0.4: return None
    c,s=math.cos(r),math.sin(r); out=[]
    for sg in (1,-1):
        d=0.0
        while d<60 and solid(x+sg*c*d,y+sg*s*d)<0.5: d+=0.25
        out.append(d)
    return out
def ok(x,y,r,L):
    g=gap(x,y,r)
    if g is None or max(g)>=60: return None
    span=g[0]+g[1]
    # needs land under both feet; recentre the bridge on the water gap
    return span, (g[0]-g[1])/2
lines=open(A+'bridges/bridges.txt').read().splitlines()
out=[]; changes=[]; review=[]
def water_near(x,y,R):
    for d in np.arange(0,R+0.01,0.5):
        for a in range(24):
            if solid(x+math.cos(a*math.pi/12)*d,y+math.sin(a*math.pi/12)*d)<0.3: return True
    return False
for ln,l in enumerate(lines,1):
    if l.startswith('#') or not l.strip(): out.append(l); continue
    p=l.split(); name=p[0].replace('bridge_','')
    if name.startswith(SKIP) or name.endswith(('_a','_m','_z')): out.append(l); continue
    x,y,rd=float(p[1]),float(p[2]),float(p[3]); sx=float(p[4])
    L0=types.get(name,10); L=L0*sx; r=math.radians(rd); c,s_=math.cos(r),math.sin(r)
    if solid(x,y)>0.6 and not water_near(x,y,6):
        out.append('# removed by the map audit (no water under it): '+l); changes.append(('removed',ln,name,round(x),round(y))); continue
    g=gap(x,y,r)
    if g is None:
        review.append(('centre_on_land_near_water',ln,name,round(x),round(y))); out.append(l); continue
    # both feet must stand 1 m inland: lengthen symmetrically to cover the water span
    half=max(g)+0.8
    if max(g)>L/2-0.3 and max(g)<40:
        ns=min(2.0*half/L0, 1.9)
        if ns>sx*1.6: review.append(('much_too_short',ln,name,round(x),round(y),round(sx,2),round(ns,2))); out.append(l); continue
        if ns>sx*1.02:
            p[4]=f"{ns:.4f}"; out.append(' '.join(p)); changes.append(('lengthened',ln,name,round(x),round(y),round(sx,2),round(ns,2))); continue
    if max(g)>=40: review.append(('axis_along_water',ln,name,round(x),round(y)))
    out.append(l)
open('./bridges_fixed.txt','w').write('\n'.join(out)+'\n')
json.dump({'changes':changes,'review':review},open('./bridge_changes.json','w'))
import collections; print(collections.Counter(c[0] for c in changes), collections.Counter(r[0] for r in review))
for c in changes: print(c)
for r in review: print(r)
