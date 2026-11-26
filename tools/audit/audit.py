import vmap, numpy as np, math, collections, json
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
    n=t['mn']; i=int(min(max(x-t['ox']+0.5,0),n-1)); j=int(min(max(y-t['oy']+0.5,0),n-1))
    return max(t['hard'][j*n+i], t['marsh'][j*n+i])/255
for k in m['dir']: T(k)
rep=collections.defaultdict(list)
# --- bridges
types={}
for l in open(A+'bridges/bridge_types.txt'):
    if l.startswith('#') or not l.strip(): continue
    p=l.split(); types[p[0]]=float(p[1])
br=[]
for ln,l in enumerate(open(A+'bridges/bridges.txt'),1):
    if l.startswith('#') or not l.strip(): continue
    p=l.split(); name=p[0].replace('bridge_',''); x,y,r=float(p[1]),float(p[2]),math.radians(float(p[3])); sx=float(p[4])
    base=name
    for suf in ('_a','_m','_z'):
        if name.endswith(suf): base=name[:-2]
    L=types.get(base, types.get(name, 10))*sx
    br.append((ln,name,x,y,r,sx,L))
for (ln,name,x,y,r,sx,L) in br:
    if name.endswith(('_a','_m','_z')) or name.startswith('liberta') or name.startswith('ribbon') or name.startswith('girder'): continue
    c=solid(x,y)
    e1=solid(x+math.cos(r)*L/2,y+math.sin(r)*L/2); e2=solid(x-math.cos(r)*L/2,y-math.sin(r)*L/2)
    if c>0.6: rep['bridge_centre_on_land'].append((ln,name,round(x),round(y),round(c,2)))
    if e1<0.4 or e2<0.4: rep['bridge_end_in_water'].append((ln,name,round(x),round(y),round(L,1),round(e1,2),round(e2,2)))
for i in range(len(br)):
    for j in range(i+1,len(br)):
        a,b=br[i],br[j]
        if math.hypot(a[2]-b[2],a[3]-b[3])<2.0: rep['bridge_duplicate'].append((a[0],a[1],b[0],b[1],round(a[2]),round(a[3])))
# --- actv stops
stops=[l.rstrip('\n').split('\t') for l in open(A+'landmarks/actv.txt') if l.strip()]
for i,s in enumerate(stops):
    x,y=float(s[1]),float(s[2])
    if solid(x,y)>0.4: rep['stop_on_land'].append((s[0],round(x),round(y),round(solid(x,y),2)))
    land=min([d for d in range(0,60) for a in range(16) if solid(x+math.cos(a*math.pi/8)*d,y+math.sin(a*math.pi/8)*d)>0.5] or [99])
    if land>30: rep['stop_far_from_land'].append((s[0],round(x),round(y),land))
    for t in stops[i+1:]:
        if math.hypot(x-float(t[1]),y-float(t[2]))<8: rep['stop_overlap'].append((s[0],t[0],round(x),round(y)))
# --- buildings and props
names={1:'BENCH',2:'WELL',3:'BRICOLA',4:'TREE',5:'LAMP',6:'STOP',7:'FLAG',8:'BIN',9:'FOUNTAIN'}
for k,t in tiles.items():
    if not t: continue
    for (kind,h,seed,sizes,xy,tris) in t['blds']:
        if not sizes: continue
        pts=np.array(xy[:2*sizes[0]]).reshape(-1,2); cx,cy=pts.mean(0)+[t['ox'],t['oy']]
        if solid(cx,cy)<0.3: rep['building_on_water'].append((round(cx),round(cy),h,kind))
        if h>60: rep['building_very_tall'].append((round(cx),round(cy),h))
    for (tp,x,y,d,nm) in t['pts']:
        wx,wy=x+t['ox'],y+t['oy']
        if tp in (1,2,5,8,9,4) and solid(wx,wy)<0.3: rep['prop_in_water_'+names[tp]].append((round(wx),round(wy)))
for k,v in rep.items(): print(k,len(v)); [print('   ',x) for x in v[:12]]
json.dump({k:v for k,v in rep.items()},open('./audit.json','w'))
