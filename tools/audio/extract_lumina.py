import sys, re, json, base64, subprocess, wave
# Usage: python3 extract_lumina.py <lumina-sfx clone> <out dir> <recording names...>  (see build_bank.py for the list)
repo, out = sys.argv[1], sys.argv[2]
import os
cp = os.path.join(out, 'credits.json')
credits = json.load(open(cp)) if os.path.exists(cp) else {}   # merge with earlier runs
def field(src, k):
    m = re.search(r'["\']?%s["\']?\s*:\s*["\']([^"\']*)["\']' % k, src); return m.group(1) if m else None
for n in sys.argv[3:]:
    src = subprocess.run(['git','-C',repo,'show',f'HEAD:recipes/_recordings/{n}.ts'],capture_output=True,text=True,timeout=180).stdout
    if not src: print('MISSING', n); continue
    head = src[:src.index('ampleRate')]
    cr = {k: field(head, k) for k in ('title','author','url','license')}
    if cr['license'] != 'CC0-1.0': print('SKIP license', n, cr); continue
    sr = int(re.search(r'sampleRate["\']?\s*:\s*(\d+)', src).group(1))
    mm = re.search(r'["\']?takes["\']?\s*:\s*\[', src)
    body = src[mm.end():]
    takes=[]; cur=''; depth=0; i=0
    while i < len(body):
        c = body[i]
        if c in '"\'':
            j = body.index(c, i+1); cur += body[i+1:j]; i = j+1; continue
        if c == '[': depth += 1
        elif c == ']':
            if depth == 0: break
            depth -= 1
            if depth == 0 and cur: takes.append(cur); cur=''
        elif c == ',' and depth == 0 and cur: takes.append(cur); cur=''
        i += 1
    if cur: takes.append(cur)
    for i,t in enumerate(takes):
        b = base64.b64decode(t)
        w = wave.open(f'{out}/{n}__{i}.wav','wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(b); w.close()
    exc = re.search(r'Source excerpts[^\n]*', src)
    credits[n] = dict(cr, sampleRate=sr, takes=len(takes), excerpt=exc.group(0) if exc else '')
    print(n, sr, len(takes), [round(len(base64.b64decode(x))/2/sr,1) for x in takes])
json.dump(credits, open(f'{out}/credits.json','w'), indent=1)
