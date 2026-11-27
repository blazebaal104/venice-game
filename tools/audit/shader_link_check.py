import re,sys
src=open('render/Shaders.kt').read()
common=re.search(r'val COMMON = """(.*?)"""',src,re.S).group(1)
shaders={}
for m in re.finditer(r'val (\w+) = """(.*?)"""',src,re.S):
    shaders[m.group(1)]=m.group(2).replace('$COMMON',common)
def uniforms(code):
    out={}
    for m in re.finditer(r'uniform\s+((?:lowp|mediump|highp)\s+)?(\w+)\s+([^;]+);',code):
        for name in m.group(3).split(','):
            name=name.strip().split('[')[0].strip()
            if name: out[name]=((m.group(1) or '').strip(), m.group(2))
    return out
def defprec(code, stage):
    m=re.search(r'precision\s+(lowp|mediump|highp)\s+float',code)
    return m.group(1) if m else ('highp' if stage=='v' else None)
pairs=set()
import glob
for f in glob.glob('render/*.kt'):
    t=open(f).read()
    for m in re.finditer(r'Program\(Shaders\.(\w+),\s*Shaders\.(\w+)',t): pairs.add(m.groups())
bad=0
for vs,fs in sorted(pairs):
    v,fr=shaders.get(vs),shaders.get(fs)
    if v is None or fr is None: print('missing',vs,fs); continue
    uv,uf=uniforms(v),uniforms(fr); dv,df=defprec(v,'v'),defprec(fr,'f')
    for n in set(uv)&set(uf):
        fl=('float','vec2','vec3','vec4','mat4','mat3','mat2')
        pv=uv[n][0] or (dv if uv[n][1] in fl else 'int/sampler')
        pf=uf[n][0] or (df if uf[n][1] in fl else 'int/sampler')
        if pv!=pf: print('MISMATCH',vs,fs,n,pv,pf); bad+=1
    # samplers redeclared twice in the same stage
    for code,nm in ((v,vs),(fr,fs)):
        names=re.findall(r'uniform\s+(?:\w+\s+)?\w+\s+(\w+)\s*[;\[]',code)
        d=[x for x in set(names) if names.count(x)>1]
        if d: print('DUPLICATE uniform', nm, d); bad+=1
print('checked',len(pairs),'programs, problems:',bad)
