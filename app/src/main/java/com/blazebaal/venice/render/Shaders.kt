// GLSL ES 3.00 shaders. All procedural: no textures besides the land masks.
package com.blazebaal.venice.render

object Shaders {
    private const val COMMON = """
uniform vec3 uSunDir;      // towards the sun
uniform vec3 uSunCol;
uniform vec3 uSkyAmb;
uniform vec3 uGroundAmb;
uniform vec3 uFogCol;
uniform float uFogDens;
uniform vec3 uCam;
uniform float uTime;
uniform highp sampler2DShadow uShadow;
uniform mat4 uLightVP;
uniform float uShadowOn;

float hash11(float p) { p = fract(p * .1031); p *= p + 33.33; p *= p + p; return fract(p); }
float hash21(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * .1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
float noise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1, 0)), f.x), mix(hash21(i + vec2(0, 1)), hash21(i + vec2(1, 1)), f.x), f.y);
}
float fbm(vec2 p) { float a = 0.5, s = 0.0; for (int i = 0; i < 4; i++) { s += a * noise(p); p *= 2.03; a *= 0.5; } return s; }

float sunShadow(vec3 wpos, vec3 n) {
    if (uShadowOn < 0.5) return 1.0;
    vec4 lp = uLightVP * vec4(wpos + n * 0.12, 1.0);
    vec3 c = lp.xyz / lp.w * 0.5 + 0.5;
    if (c.x < 0.0 || c.y < 0.0 || c.x > 1.0 || c.y > 1.0 || c.z > 1.0) return 1.0;
    float d = c.z - 0.0008;
    vec2 t = vec2(1.0 / 2048.0);
    float s = texture(uShadow, vec3(c.xy + vec2(-0.5, -0.5) * t, d)) + texture(uShadow, vec3(c.xy + vec2(0.5, -0.5) * t, d))
            + texture(uShadow, vec3(c.xy + vec2(-0.5, 0.5) * t, d)) + texture(uShadow, vec3(c.xy + vec2(0.5, 0.5) * t, d));
    // fade out at the edge of the shadowed area
    vec2 e = smoothstep(vec2(0.0), vec2(0.08), c.xy) * smoothstep(vec2(1.0), vec2(0.92), c.xy);
    return mix(1.0, s * 0.25, e.x * e.y);
}

vec3 shade(vec3 albedo, vec3 n, vec3 wpos, float gloss, float ao) {
    vec3 v = normalize(uCam - wpos);
    float ndl = max(dot(n, uSunDir), 0.0) * sunShadow(wpos, n);
    vec3 hemi = mix(uGroundAmb, uSkyAmb, n.z * 0.5 + 0.5);
    vec3 h = normalize(uSunDir + v);
    float spec = pow(max(dot(n, h), 0.0), 8.0 + gloss * 140.0) * gloss * 1.2;
    return albedo * (uSunCol * ndl + hemi * ao) + uSunCol * spec * ndl;
}
vec3 fog(vec3 c, vec3 wpos) {
    float d = length(wpos - uCam);
    float f = 1.0 - exp(-pow(d * uFogDens, 1.35));
    return mix(c, uFogCol, clamp(f, 0.0, 1.0));
}
"""

    // ------------------------------------------------------------------ generic vertex-coloured
    const val GENERIC_VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNrm; layout(location=2) in vec2 aUv; layout(location=3) in vec4 aAttr;
uniform mat4 uVP; uniform mat4 uModel;
out vec3 vW; out vec3 vN; out vec4 vC;
void main() { vec4 w = uModel * vec4(aPos, 1.0); vW = w.xyz; vN = mat3(uModel) * aNrm; vC = aAttr; gl_Position = uVP * w; }
"""
    val GENERIC_FS = """#version 300 es
precision highp float;
$COMMON
in vec3 vW; in vec3 vN; in vec4 vC;
out vec4 o;
void main() {
    vec3 n = normalize(vN);
    if (!gl_FrontFacing) n = -n;
    vec3 alb = vC.rgb;
    // gentle grain so flat colours don't look plastic
    alb *= 0.92 + 0.16 * noise(vW.xy * 3.1 + vW.z * 2.7);
    // waterline wetness on anything standing in the lagoon
    float wet = smoothstep(0.55, -0.05, vW.z);
    alb = mix(alb, alb * vec3(0.45, 0.55, 0.45), wet);
    float ao = mix(0.75, 1.0, smoothstep(0.0, 1.5, vW.z));
    o = vec4(fog(shade(alb, n, vW, vC.a, ao), vW), 1.0);
}
"""

    // ------------------------------------------------------------------ facades, roofs, cornices, quays
    const val FACADE_VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNrm; layout(location=2) in vec2 aUv; layout(location=3) in vec4 aAttr;
uniform mat4 uVP; uniform mat4 uModel;
out vec3 vW; out vec3 vN; out vec2 vUv; out vec4 vA;
void main() { vec4 w = uModel * vec4(aPos, 1.0); vW = w.xyz; vN = aNrm; vUv = aUv; vA = aAttr; gl_Position = uVP * w; }
"""
    val FACADE_FS = """#version 300 es
precision highp float;
$COMMON
in vec3 vW; in vec3 vN; in vec2 vUv; in vec4 vA;
out vec4 o;

// Venetian intonaco palette: ochre, terracotta, salmon, pink, cream, faded red, burnt orange, grey-white
vec3 plaster(float s) {
    vec3 p[8];
    p[0] = vec3(0.80, 0.62, 0.40); p[1] = vec3(0.71, 0.38, 0.27); p[2] = vec3(0.86, 0.60, 0.50); p[3] = vec3(0.86, 0.70, 0.64);
    p[4] = vec3(0.88, 0.82, 0.68); p[5] = vec3(0.62, 0.30, 0.24); p[6] = vec3(0.82, 0.50, 0.30); p[7] = vec3(0.80, 0.78, 0.72);
    int i = int(floor(s * 8.0)) % 8;
    return mix(p[i], vec3(0.74, 0.70, 0.64), 0.15);   // a little sun-bleached
}
vec3 brick(vec2 q) {
    vec2 b = vec2(0.26, 0.075);
    float row = floor(q.y / b.y);
    float x = q.x / b.x + mod(row, 2.0) * 0.5;
    vec2 f = vec2(fract(x), fract(q.y / b.y));
    float mortar = step(f.x, 0.06) + step(f.y, 0.13);
    float h = hash21(vec2(floor(x), row));
    vec3 c = mix(vec3(0.55, 0.25, 0.17), vec3(0.68, 0.36, 0.24), h);
    return mix(c, vec3(0.62, 0.58, 0.52), clamp(mortar, 0.0, 1.0));
}

void main() {
    vec3 n = normalize(vN);
    float kind = vA.z;
    float seed = vA.y;
    vec3 alb; float gloss = 0.03; float ao = 1.0;
    vec3 wp = vW;

    if (kind > 0.5 && kind < 1.5) {
        // ---- roof: terracotta coppi (half-round tiles) in courses
        vec2 q = vUv; // local xy metres
        float dir = step(0.5, hash11(seed * 91.0));
        vec2 r = dir > 0.5 ? q.yx : q;
        float course = floor(r.y / 0.42);
        float ridge = abs(fract(r.x / 0.24 + hash11(course) * 0.5) - 0.5) * 2.0;
        float h = hash21(vec2(floor(r.x / 0.24), course));
        alb = mix(vec3(0.52, 0.24, 0.16), vec3(0.66, 0.36, 0.24), h) * (0.75 + 0.25 * (1.0 - ridge));
        alb *= 0.85 + 0.3 * fbm(q * 0.15 + seed * 10.0);         // lichen & age patches
        alb = mix(alb, vec3(0.35, 0.33, 0.28), smoothstep(0.62, 0.8, fbm(q * 0.4 + 7.0)) * 0.5);
        gloss = 0.05;
    } else if (kind > 1.5 && kind < 2.5) {
        // ---- quay wall: brick with an Istrian stone base course, algae and salt at the tide line
        vec2 q = vec2(vUv.x, vW.z);
        alb = brick(q);
        float stone = smoothstep(0.55, 0.6, vW.z);
        vec3 ist = vec3(0.80, 0.79, 0.74) * (0.85 + 0.15 * noise(q * vec2(2.0, 8.0)));
        alb = mix(alb, ist, stone);
        float tide = 0.15 + 0.12 * noise(vec2(vUv.x * 0.7, 3.0));
        alb = mix(alb, vec3(0.10, 0.14, 0.09), smoothstep(tide + 0.25, tide - 0.05, vW.z));       // green-black algae
        alb = mix(alb, alb * vec3(0.55, 0.62, 0.5), smoothstep(tide + 0.6, tide + 0.2, vW.z) * 0.6);
        alb = mix(alb, vec3(0.04, 0.06, 0.05), smoothstep(0.0, -0.4, vW.z));                     // under water
        gloss = mix(0.02, 0.35, smoothstep(tide + 0.3, tide, vW.z));
    } else if (kind > 2.5) {
        // ---- cornice: Istrian stone with dentils
        float dent = step(0.5, fract(vUv.x / 0.22));
        alb = vec3(0.82, 0.80, 0.75) * (0.88 + 0.12 * noise(vUv * vec2(3.0, 9.0))) * (n.z < -0.2 ? 0.7 + 0.15 * dent : 1.0);
        alb *= 0.8 + 0.2 * smoothstep(0.3, 0.9, fbm(vW.xy * 0.3));
        gloss = 0.08;
    } else {
        // ---- wall
        float segLen = vA.x;
        float u = vUv.x; float z = vUv.y;
        float rel = z - 1.1;
        vec3 base = plaster(seed);
        base *= 0.94 + 0.12 * hash11(seed * 37.0);
        // plaster mottling + rain streaks below the cornice
        float mott = fbm(vec2(u, z) * 0.35 + seed * 31.0);
        alb = base * (0.82 + 0.3 * mott);
        float streak = noise(vec2(u * 2.2 + seed * 40.0, z * 0.08)) * smoothstep(0.0, 0.8, fbm(vec2(u * 0.5, z * 0.2) + 3.0));
        alb *= 1.0 - 0.18 * streak;
        // peeling plaster -> exposed brick, mostly low on the wall
        float peel = fbm(vec2(u, z) * 0.45 + seed * 17.0) + smoothstep(3.5, 0.3, rel) * 0.12;
        alb = mix(alb, brick(vec2(u, z)) * 0.9, smoothstep(0.76, 0.79, peel) * step(0.35, hash11(seed * 5.0)));

        // ---- openings
        float groundH = 4.0; float floorH = 3.25;
        float fl = rel < groundH ? 0.0 : 1.0 + floor((rel - groundH) / floorH);
        float fz = rel < groundH ? rel : mod(rel - groundH, floorH);
        float topZ = 1.1 + 999.0;
        float nwin = max(1.0, floor(segLen / 2.9));
        float spacing = segLen / nwin;
        float idx = floor(u / spacing);
        float lu = mod(u, spacing) - spacing * 0.5;
        float wHalf = mix(0.42, 0.62, hash11(seed * 13.0));
        float archType = floor(hash11(seed * 7.0) * 3.0); // 0 rect, 1 round, 2 gothic pointed
        bool hasWin = segLen > 2.2;
        float inWin = 0.0; float frame = 0.0; float shutter = 0.0;
        if (hasWin) {
            float y0, y1;
            if (fl < 0.5) {
                float door = step(0.62, hash21(vec2(idx, seed * 99.0)));
                y0 = door > 0.5 ? 0.0 : 1.5; y1 = door > 0.5 ? 2.9 : 2.6;
            } else { y0 = 0.95; y1 = 2.55; }
            float ax = abs(lu);
            // arch head
            float head = y1;
            if (archType > 1.5) head = y1 + wHalf * 1.1 * (1.0 - pow(ax / wHalf, 0.8));     // pointed (gothic)
            else if (archType > 0.5) head = y1 + sqrt(max(wHalf * wHalf - ax * ax, 0.0));   // round
            float inside = step(ax, wHalf) * step(y0, fz) * step(fz, head);
            float outer = step(ax, wHalf + 0.12) * step(y0 - 0.14, fz) * step(fz, head + 0.12);
            inWin = inside; frame = outer - inside;
            float closed = step(0.55, hash21(vec2(idx * 7.0 + fl, seed * 51.0)));
            shutter = inside * closed * step(0.5, fl);
        }
        // Istrian stone frames and sills
        vec3 ist = vec3(0.86, 0.84, 0.78) * (0.85 + 0.15 * noise(vec2(u, z) * 4.0));
        alb = mix(alb, ist, frame);
        // string course between floors
        float sc = step(abs(fz - (fl < 0.5 ? groundH : floorH) + 0.05), 0.07) * step(0.5, hash11(seed * 3.0));
        alb = mix(alb, ist, sc);
        if (inWin > 0.5) {
            if (shutter > 0.5) {
                float slat = step(0.5, fract(fz / 0.09));
                alb = vec3(0.12, 0.22, 0.16) * (0.8 + 0.2 * slat);        // green wooden "scuri"
                gloss = 0.1;
            } else {
                vec3 sky = mix(uFogCol, uSkyAmb * 1.4, 0.5);
                float refl = 0.35 + 0.35 * noise(vec2(u * 1.3, z * 0.7)) + 0.2 * smoothstep(0.0, 1.0, fz - 1.0);
                alb = mix(vec3(0.05, 0.06, 0.07), sky * 0.75, refl);
                alb = mix(alb, vec3(0.75, 0.73, 0.68), step(abs(lu), 0.025) * step(0.5, fl));  // central mullion
                if (fl < 0.5 && fz < 2.95 && abs(lu) < wHalf) alb = vec3(0.16, 0.10, 0.06);   // wooden door
                gloss = 0.6; ao = 0.6;
            }
        }
        // salt & algae band at the base (walls standing in the canals)
        float tide = 0.2 + 0.15 * noise(vec2(u * 0.6, 1.0));
        alb = mix(alb, vec3(0.11, 0.14, 0.09), smoothstep(tide + 0.25, tide - 0.05, z));
        alb = mix(alb, alb * vec3(0.7, 0.72, 0.62) + 0.08, smoothstep(tide + 1.4, tide + 0.3, z) * 0.55);
        alb = mix(alb, vec3(0.04, 0.06, 0.05), smoothstep(0.0, -0.4, z));
        ao = min(ao, mix(0.7, 1.0, smoothstep(0.5, 6.0, rel)));
    }
    o = vec4(fog(shade(alb, n, wp, gloss, ao), wp), 1.0);
}
"""

    // ------------------------------------------------------------------ ground (masked quad per tile)
    const val GROUND_VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uVP; uniform mat4 uModel;
out vec3 vW; out vec2 vL;
void main() { vec4 w = uModel * vec4(aPos, 1.0); vW = w.xyz; vL = aPos.xy; gl_Position = uVP * w; }
"""
    val GROUND_FS = """#version 300 es
precision highp float;
$COMMON
in vec3 vW; in vec2 vL;
uniform sampler2D uMask;   // r = hard land coverage, g = marsh coverage (linear)
uniform sampler2D uClass;  // r = class (nearest)
uniform float uMarshPass;
out vec4 o;
void main() {
    vec2 uv = (vL + 1.0) / 258.0;
    vec2 m = texture(uMask, uv).rg;
    float cov = uMarshPass > 0.5 ? m.g : m.r;
    if (cov < 0.5) discard;
    vec3 alb;
    float gloss = 0.04;
    if (uMarshPass > 0.5) {
        float v = fbm(vW.xy * 0.25);
        alb = mix(vec3(0.30, 0.34, 0.20), vec3(0.42, 0.40, 0.26), v);
        alb = mix(alb, vec3(0.16, 0.20, 0.14), smoothstep(0.6, 0.5, cov) );
    } else {
        float c = texture(uClass, uv).r * 255.0;
        if (c > 1.5 && c < 2.5) {
            alb = mix(vec3(0.24, 0.32, 0.14), vec3(0.36, 0.42, 0.20), fbm(vW.xy * 0.7));
        } else if (c > 2.5) {
            alb = mix(vec3(0.78, 0.71, 0.55), vec3(0.86, 0.80, 0.64), noise(vW.xy * 2.0));
        } else {
            // masegni: trachyte paving slabs in staggered courses
            vec2 q = vW.xy;
            float row = floor(q.y / 0.55);
            float x = q.x / 1.05 + hash11(row) * 0.7;
            vec2 f = vec2(fract(x), fract(q.y / 0.55));
            float joint = step(f.x, 0.012) + step(f.y, 0.02);
            float h = hash21(vec2(floor(x), row));
            alb = mix(vec3(0.43, 0.42, 0.40), vec3(0.50, 0.49, 0.46), h) * (0.92 + 0.08 * noise(q * 5.0));
            alb *= 0.9 + 0.1 * fbm(q * 0.08);   // worn, darker where people walk
            alb = mix(alb, vec3(0.32, 0.31, 0.30), clamp(joint, 0.0, 1.0) * 0.8);
            gloss = 0.08;
        }
    }
    o = vec4(fog(shade(alb, vec3(0, 0, 1), vW, gloss, 0.95), vW), 1.0);
}
"""

    // ------------------------------------------------------------------ water
    const val WATER_VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uVP; uniform vec3 uCenter; uniform float uTime;
out vec3 vW;
void main() {
    vec3 w = aPos + vec3(uCenter.xy, 0.0);
    w.z = 0.04 * sin(w.x * 0.35 + uTime * 1.3) + 0.03 * sin(w.y * 0.47 - uTime * 1.1);
    vW = w; gl_Position = uVP * vec4(w, 1.0);
}
"""
    val WATER_FS = """#version 300 es
precision highp float;
$COMMON
in vec3 vW;
uniform vec3 uSkyZenith;
out vec4 o;
vec2 wave(vec2 p, vec2 d, float f, float sp, float a) { float ph = dot(p, d) * f + uTime * sp; return d * cos(ph) * a * f; }
void main() {
    vec2 p = vW.xy;
    vec2 g = vec2(0.0);
    g += wave(p, normalize(vec2(1.0, 0.3)), 0.9, 1.6, 0.022);
    g += wave(p, normalize(vec2(-0.4, 1.0)), 1.7, 2.1, 0.012);
    g += wave(p, normalize(vec2(0.7, -0.8)), 3.3, 2.9, 0.006);
    g += wave(p, normalize(vec2(-0.9, -0.2)), 6.1, 3.7, 0.003);
    vec2 q = p * 0.9 + vec2(uTime * 0.21, uTime * 0.13);
    float e = 0.15;
    float n0 = fbm(q), nx = fbm(q + vec2(e, 0.0)), ny = fbm(q + vec2(0.0, e));
    g += vec2(nx - n0, ny - n0) / e * 0.045;
    vec3 n = normalize(vec3(-g, 1.0));
    vec3 v = normalize(uCam - vW);
    float fres = 0.02 + 0.98 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
    vec3 r = reflect(-v, n);
    vec3 sky = mix(uFogCol, uSkyZenith, clamp(r.z * 1.6, 0.0, 1.0));
    vec3 deep = vec3(0.035, 0.11, 0.10);   // Venetian lagoon green
    vec3 col = mix(deep, sky, fres);
    float sp = pow(max(dot(reflect(-uSunDir, n), v), 0.0), 220.0) * 3.0;
    float sh = sunShadow(vW, vec3(0.0, 0.0, 1.0));
    col += uSunCol * sp * sh;
    col *= mix(0.78, 1.0, sh);   // shaded canals read darker
    o = vec4(fog(col, vW), 1.0);
}
"""

    // ------------------------------------------------------------------ shadow depth
    const val DEPTH_VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uLightVP; uniform mat4 uModel;
void main() { gl_Position = uLightVP * uModel * vec4(aPos, 1.0); }
"""
    const val DEPTH_FS = """#version 300 es
precision mediump float;
out vec4 o;
void main() { o = vec4(1.0); }
"""

    // ------------------------------------------------------------------ sky
    const val SKY_VS = """#version 300 es
layout(location=0) in vec2 aPos;
out vec2 vP;
void main() { vP = aPos; gl_Position = vec4(aPos, 0.9999, 1.0); }
"""
    const val SKY_FS = """#version 300 es
precision highp float;
in vec2 vP;
uniform mat4 uInvVP; uniform vec3 uCam; uniform vec3 uSunDir; uniform vec3 uFogCol; uniform vec3 uSkyZenith; uniform vec3 uSunCol;
out vec4 o;
void main() {
    vec4 a = uInvVP * vec4(vP, -1.0, 1.0); vec4 b = uInvVP * vec4(vP, 1.0, 1.0);
    vec3 d = normalize(b.xyz / b.w - a.xyz / a.w);
    float h = clamp(d.z, -0.2, 1.0);
    vec3 col = mix(uFogCol, uSkyZenith, pow(max(h, 0.0), 0.55));
    float s = max(dot(d, uSunDir), 0.0);
    col += uSunCol * (pow(s, 900.0) * 6.0 + pow(s, 12.0) * 0.12);
    o = vec4(col, 1.0);
}
"""
}
