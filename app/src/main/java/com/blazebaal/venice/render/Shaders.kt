// GLSL ES 3.00 shaders: PBR-lite lighting in linear HDR, photo-textured materials (texture arrays),
// planar-reflected water on the shared wave field, wake foam, spray particles, filmic post-processing.
package com.blazebaal.venice.render

import com.blazebaal.venice.core.Waves

object Shaders {
    private val COMMON = """
uniform vec3 uSunDir; uniform vec3 uSunCol; uniform vec3 uSkyAmb; uniform vec3 uGroundAmb; uniform vec3 uFogCol; uniform vec3 uSkyZenith;
uniform float uFogDens; uniform vec3 uCam; uniform float uTime;
uniform float uFogStart; uniform float uFogEnd;     // render distance: fog is total at uFogEnd, where chunks unload
uniform float uWet;                                  // 0 dry .. 1 soaked: rain darkens stone, plaster and wood
uniform float uWinLit;                               // share of windows lit from inside (night, render/DayCycle)
uniform highp sampler2DShadow uShadow; uniform mat4 uLightVP; uniform float uShadowOn;
uniform highp sampler2DShadow uShadowB; uniform mat4 uLightVPB;      // boat-only shadow, re-rendered every frame
uniform float uClipZ;
uniform mediump sampler2DArray uAlb;   // rgb albedo (sRGB), a = paintable mask
uniform mediump sampler2DArray uNrm;   // rg normal (GL), b = roughness
const float PI = 3.14159265;

float hash11(float p) { p = fract(p * .1031); p *= p + 33.33; p *= p + p; return fract(p); }
float hash21(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * .1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
float noise(vec2 p) { vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1, 0)), f.x), mix(hash21(i + vec2(0, 1)), hash21(i + vec2(1, 1)), f.x), f.y); }
float fbm(vec2 p) { float a = 0.5, s = 0.0; for (int i = 0; i < 4; i++) { s += a * noise(p); p *= 2.03; a *= 0.5; } return s; }
vec3 lin(vec3 c) { return pow(c, vec3(2.2)); }

float sunShadow(vec3 wpos, vec3 n) {
    if (uShadowOn < 0.5) return 1.0;
    vec4 lp = uLightVP * vec4(wpos + n * 0.12, 1.0);
    vec3 c = lp.xyz / lp.w * 0.5 + 0.5;
    float s = 1.0;
    if (c.x > 0.0 && c.y > 0.0 && c.x < 1.0 && c.y < 1.0 && c.z < 1.0) {
        float d = c.z - 0.0008; vec2 t = vec2(1.0 / 2048.0);
        float k = texture(uShadow, vec3(c.xy + vec2(-0.5, -0.5) * t, d)) + texture(uShadow, vec3(c.xy + vec2(0.5, 0.5) * t, d));
        vec2 e = smoothstep(vec2(0.0), vec2(0.08), c.xy) * smoothstep(vec2(1.0), vec2(0.92), c.xy);
        s = mix(1.0, k * 0.5, e.x * e.y);
    }
    vec4 lb = uLightVPB * vec4(wpos + n * 0.03, 1.0);
    vec3 cb = lb.xyz / lb.w * 0.5 + 0.5;
    if (cb.x > 0.0 && cb.y > 0.0 && cb.x < 1.0 && cb.y < 1.0 && cb.z < 1.0) s = min(s, texture(uShadowB, vec3(cb.xy, cb.z - 0.001)));
    return s;
}

// texture size in metres per layer group
float layerScale(float l) {
    if (l < 5.5) return 2.6;  if (l < 10.5) return 2.4;  if (l < 14.5) return 1.5;  if (l < 17.5) return 1.8;
    if (l < 21.5) return 2.2; if (l < 25.5) return 2.6;  if (l < 28.5) return 2.0;  if (l < 30.5) return 3.0;  return 1.5;
}
// sample a layer: linear albedo (paintable part tinted) in rgb, roughness in a; tangent-space normal in nOut
vec4 matSample(vec2 uv, float layer, vec3 tint, out vec3 nOut) {
    vec4 a = texture(uAlb, vec3(uv, layer));
    vec3 nr = texture(uNrm, vec3(uv, layer)).rgb;
    vec3 raw = lin(a.rgb);
    vec3 painted = tint * a.rgb * 2.0;               // stored as neutral detail averaging 0.5
    vec3 c = mix(raw * tint, painted, a.a);
    vec2 nxy = nr.rg * 2.0 - 1.0;
    nOut = vec3(nxy, sqrt(max(1.0 - dot(nxy, nxy), 0.0)));
    return vec4(c, nr.b);
}
vec3 perturb(vec3 n, vec3 t, vec3 b, vec3 tn, float strength) {
    return normalize(t * tn.x * strength + b * tn.y * strength + n * tn.z);
}

vec3 light(vec3 alb, vec3 n, vec3 wpos, float rough, float ao, float f0) {
    vec3 v = normalize(uCam - wpos);
    vec3 l = uSunDir; vec3 h = normalize(l + v);
    float nl = max(dot(n, l), 0.0), nv = max(dot(n, v), 1e-3), nh = max(dot(n, h), 0.0), vh = max(dot(v, h), 0.0);
    float sh = nl > 0.0 ? sunShadow(wpos, n) : 0.0;
    float a = max(rough * rough, 0.02); float a2 = a * a;
    float d = max(nh * nh * (a2 - 1.0) + 1.0, 1e-3); float D = a2 / (PI * d * d);
    float k = (rough + 1.0) * (rough + 1.0) / 8.0;
    float G = (nl / (nl * (1.0 - k) + k)) * (nv / (nv * (1.0 - k) + k));
    float fx = clamp(1.0 - vh, 0.0, 1.0); float F = f0 + (1.0 - f0) * fx * fx * fx * fx * fx;
    vec3 spec = nl > 0.0 ? uSunCol * min(D * G * F / max(4.0 * nl * nv, 1e-3), 200.0) * nl * sh : vec3(0.0);
    vec3 hemi = mix(uGroundAmb, uSkyAmb, n.z * 0.5 + 0.5);
    vec3 diff = alb * (uSunCol * nl * sh + hemi * ao) * (1.0 - F * 0.5);
    vec3 r = reflect(-v, n);
    // reflected environment: sky above the horizon, the dark canal/paving below it (soffits mirror water, not sky)
    vec3 env = r.z >= 0.0 ? mix(uFogCol, uSkyZenith, clamp(r.z * 1.5, 0.0, 1.0)) : mix(uFogCol * 0.5, uFogCol * 0.18, clamp(-r.z * 3.0, 0.0, 1.0));
    float ax = clamp(1.0 - nv, 0.0, 1.0); float Fa = f0 + (1.0 - f0) * ax * ax * ax * ax * ax;
    return clamp(diff + spec + env * Fa * clamp(1.0 - rough, 0.0, 1.0) * ao * 0.9, vec3(0.0), vec3(500.0));
}
vec3 fog(vec3 c, vec3 wpos) {
    c *= 1.0 - 0.22 * uWet * step(0.05, wpos.z);
    float d = length(wpos - uCam);
    float f = 1.0 - exp(-pow(max(d * uFogDens, 1e-6), 1.4));
    f = max(f, (1.0 - exp(-d * 0.00035)) * 0.35);   // light lagoon haze
    // caligo: the low mist that sits on the lagoon - thicker near the water, thinning out over the rooftops
    float low = exp(-max(wpos.z, 0.0) / 16.0);
    f = max(f, (1.0 - exp(-d * uFogDens * 0.9)) * (0.55 + 0.45 * low));
    // render distance: everything is fully inside the fog before the edge where chunks are unloaded
    if (uFogEnd > 1.0) {
        float e = smoothstep(uFogStart, uFogEnd, d);
        f = max(f, e * e * (3.0 - 2.0 * e) * (0.6 + 0.4 * low) + e * 0.4 * (1.0 - low));
    }
    vec3 r = mix(c, uFogCol * 1.15, clamp(f, 0.0, 1.0));
    // a broken value anywhere upstream (NaN / inf in the lighting) used to come out black: show the fog instead
    if (any(isnan(r)) || any(isinf(r))) r = uFogCol * 1.15;
    return r;
}
"""

    const val STD_VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNrm; layout(location=2) in vec2 aUv; layout(location=3) in vec4 aAttr;
uniform mat4 uVP; uniform mat4 uModel; uniform mat4 uMirror;
out vec3 vW; out vec3 vN; out vec2 vUv; out vec4 vA;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0); vW = w.xyz; vN = mat3(uModel) * aNrm; vUv = aUv; vA = aAttr;
    gl_Position = uVP * uMirror * w;
}
"""

    // ------------------------------------------------------------------ generic (props, bridges, piers, landmarks, boat)
    val GENERIC_FS = """#version 300 es
precision highp float;
$COMMON
in vec3 vW; in vec3 vN; in vec2 vUv; in vec4 vA;
uniform sampler2D uAtlas; uniform sampler2D uSign; uniform vec4 uSignRect;
uniform float uFadeDist;     // > 0: dissolve out (dithered) towards this distance - vegetation and close-up detail
out vec4 o;
void main() {
    if (vW.z < uClipZ) discard;
    if (uFadeDist > 0.0) {
        float a = 1.0 - smoothstep(uFadeDist * 0.78, uFadeDist, length(vW.xy - uCam.xy));
        if (hash21(floor(gl_FragCoord.xy)) > a) discard;
    }
    vec3 n = normalize(vN);
    if (!gl_FrontFacing) n = -n;
    float m = vA.x;
    vec3 alb; float rough; float f0 = 0.04;
    if (m >= 0.0) {
        // m >= 200: hand-built landmark - albedo (with baked weathering/AO) from its own atlas,
        // micro normal + roughness from texture layer (m - 200)
        bool sign = m > 299.5;
        bool atlas = m > 199.5 && !sign;
        float layer = sign ? m - 300.0 : (atlas ? m - 200.0 : m);
        bool glass = layer > 33.5;                 // landmark glazing: smooth, glossy
        if (glass) layer = 33.0;
        float s = 1.0 / layerScale(layer);
        vec3 w = pow(abs(n), vec3(4.0)); w /= (w.x + w.y + w.z);
        vec3 nx, ny, nz;
        vec4 ax = matSample(vW.yz * s, layer, vA.yzw, nx);
        vec4 ay = matSample(vW.xz * s, layer, vA.yzw, ny);
        vec4 az = matSample(vW.xy * s, layer, vA.yzw, nz);
        vec4 a = ax * w.x + ay * w.y + az * w.z;
        alb = a.rgb; rough = a.a;

        vec3 tn = normalize(vec3(nx.xy * w.x + ny.xy * w.y + nz.xy * w.z, 1.0));
        if (sign) { alb = lin(texture(uSign, uSignRect.xy + vec2(clamp(fract(vUv.x * 0.9999), 0.004, 0.996), clamp(vUv.y, 0.04, 0.96)) * uSignRect.zw).rgb); rough = 0.6; tn = vec3(0.0, 0.0, 1.0); }
        if (atlas) {
            // baked albedo, modulated by the detail layer's own light/dark pattern (bricks, veining, grain) at close range
            vec3 avg = lin(textureLod(uAlb, vec3(0.5, 0.5, layer), 9.0).rgb);
            float dl = dot(a.rgb, vec3(0.3, 0.55, 0.15)) / max(dot(avg, vec3(0.3, 0.55, 0.15)), 0.02);
            bool tintLayer = layer < 5.5 || (layer > 31.5 && layer < 32.5);
            float detail = tintLayer ? 1.0 : clamp(mix(1.0, dl, 0.55), 0.6, 1.4);
            alb = lin(texture(uAtlas, vUv).rgb) * detail;
            tn = normalize(mix(vec3(0.0, 0.0, 1.0), tn, 0.4));
            rough = max(rough, 0.72);              // weathered stone, brick, lead: matte
            if (glass) { rough = 0.07; f0 = 0.05; tn = vec3(0.0, 0.0, 1.0); }
        }
        vec3 t = abs(n.z) > 0.9 ? vec3(1, 0, 0) : normalize(vec3(-n.y, n.x, 0.0));
        n = perturb(n, t, cross(n, t), tn, 0.8);
    } else {
        alb = vA.yzw; float gloss = -m - 1.0; rough = mix(0.85, 0.12, clamp(gloss, 0.0, 1.0)); f0 = gloss > 0.6 ? 0.08 : 0.04;
        alb *= 0.93 + 0.14 * noise(vW.xy * 3.1 + vW.z * 2.7);
    }
    float wet = smoothstep(0.5, -0.05, vW.z);
    alb = mix(alb, alb * vec3(0.35, 0.45, 0.36), wet); rough = mix(rough, 0.25, wet);
    float ao = mix(0.7, 1.0, smoothstep(0.0, 1.6, vW.z));
    o = vec4(fog(light(alb, n, vW, rough, ao, f0), vW), 1.0);
}
"""

    // ------------------------------------------------------------------ facades, roofs, cornices, quay walls
    const val FACADE_VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNrm; layout(location=2) in vec2 aUv; layout(location=3) in vec4 aAttr; layout(location=4) in vec4 aStyle;
uniform mat4 uVP; uniform mat4 uModel; uniform mat4 uMirror;
out vec3 vW; out vec3 vN; out vec2 vUv; out vec4 vA; flat out vec4 vS;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0); vW = w.xyz; vN = mat3(uModel) * aNrm; vUv = aUv; vA = aAttr; vS = aStyle;
    gl_Position = uVP * uMirror * w;
}
"""
    val FACADE_FS = """#version 300 es
precision highp float;
precision highp int;
$COMMON
in vec3 vW; in vec3 vN; in vec2 vUv; in vec4 vA; flat in vec4 vS;
out vec4 o;

// opening outline: 0 rectangular, 1 round arch, 2 pointed (gothic), 3 ogee (Venetian gothic), 4/5 rectangular with a
// triangular / segmental pediment, 6 cusped gothic. Returns the height of the opening's top edge at |x| = ax.
float archTop(int shape, float ax, float hw, float y1) {
    if (shape == 1) { float ys = y1 - hw; return ys + sqrt(max(hw * hw - ax * ax, 0.0)); }
    if (shape == 2 || shape == 6) { float rise = hw * 1.2; float R = (rise * rise + hw * hw) / (2.0 * hw); float ys = y1 - rise;
        return ys + sqrt(max(R * R - (ax + R - hw) * (ax + R - hw), 0.0)); }
    if (shape == 3) { float rise = hw * 1.35; float ys = y1 - rise; float t = clamp(ax / hw, 0.0, 1.0);
        return ys + rise * (1.0 - smoothstep(0.0, 1.0, t)) + 0.1 * hw * sin(t * PI); }
    return y1;
}

// Venetian intonaco palette (sRGB): rosso veneziano, ochre, pink, salmon, cream, white, sienna, orange, grey-green, faded red
vec3 paint(float s) {
    vec3 p[10];
    p[0] = vec3(0.70, 0.33, 0.24); p[1] = vec3(0.82, 0.62, 0.38); p[2] = vec3(0.86, 0.62, 0.55); p[3] = vec3(0.88, 0.58, 0.44);
    p[4] = vec3(0.90, 0.84, 0.70); p[5] = vec3(0.90, 0.89, 0.85); p[6] = vec3(0.66, 0.40, 0.28); p[7] = vec3(0.86, 0.52, 0.30);
    p[8] = vec3(0.72, 0.71, 0.62); p[9] = vec3(0.80, 0.45, 0.40);
    int i = int(floor(s * 10.0)) % 10;
    return lin(p[i] * (0.92 + 0.16 * hash11(s * 91.7)));
}

void main() {
    if (vW.z < uClipZ) discard;
    vec3 n = normalize(vN);
    float kind = vA.z; float seed = vA.y;
    vec3 alb; float rough = 0.8; float ao = 1.0; float f0 = 0.04;
    vec3 tn = vec3(0.0, 0.0, 1.0);
    vec3 t = abs(n.z) > 0.9 ? vec3(1, 0, 0) : normalize(vec3(-n.y, n.x, 0.0));
    vec3 b = cross(n, t);

    if (kind > 0.5 && kind < 1.5) {
        // ---- roof: coppi in courses, 4 tile families, aged
        float layer = 18.0 + floor(hash11(seed * 3.3) * 4.0);
        vec2 q = vUv.yx;
        vec4 m = matSample(q / layerScale(layer), layer, vec3(1.0), tn);
        alb = m.rgb * (0.8 + 0.35 * fbm(vUv * 0.12 + seed * 9.0));
        alb = mix(alb, lin(vec3(0.42, 0.40, 0.33)), smoothstep(0.62, 0.82, fbm(vUv * 0.35 + 7.0)) * 0.45);
        rough = m.a;
    } else if (kind > 1.5 && kind < 2.5) {
        // ---- quay wall: brick with an Istrian stone course, algae and the tide line
        vec2 q = vec2(vUv.x, vW.z);
        float layer = 11.0 + floor(hash11(floor(vUv.x / 40.0) + 3.0) * 2.0);
        vec4 m = matSample(q / layerScale(layer), layer, vec3(1.0), tn);
        alb = m.rgb; rough = m.a;
        float stone = smoothstep(0.52, 0.56, vW.z);
        vec3 tn2; vec4 st = matSample(q / layerScale(15.0), 15.0, vec3(1.0), tn2);
        alb = mix(alb, st.rgb, stone); tn = mix(tn, tn2, stone);
        float tide = 0.15 + 0.12 * noise(vec2(vUv.x * 0.7, 3.0));
        float algae = smoothstep(tide + 0.25, tide - 0.05, vW.z);
        alb = mix(alb, lin(vec3(0.10, 0.15, 0.08)) * (0.7 + 0.6 * noise(q * 3.0)), algae);
        alb = mix(alb, alb * vec3(0.6, 0.66, 0.55), smoothstep(tide + 0.65, tide + 0.2, vW.z) * 0.6);
        rough = mix(rough, 0.2, algae);
    } else if (kind > 3.5) {
        // ---- campanile: brick shaft, stone corner lesene, string courses, belfry arcade under the cornice
        float segLen = vA.x; float u = vUv.x; float z = vUv.y; float top = vA.w;
        vec2 uv = vec2(u, z);
        float layer = 11.0 + floor(hash11(seed * 5.0) * 4.0);
        vec4 m = matSample(uv / layerScale(layer), layer, vec3(1.0), tn);
        alb = m.rgb * (0.9 + 0.2 * fbm(uv * 0.1 + seed)); rough = m.a;
        vec3 tnI; vec4 ist = matSample(uv / layerScale(15.0), 15.0, vec3(1.0), tnI);
        float edge = min(u, segLen - u);
        float bz0 = top - 2.6 - segLen * 0.9;
        float stone = max(step(abs(z - bz0), 0.28), step(top - 1.3, z));
        float belfry = step(bz0, z);
        float les = step(edge, 0.6) * (1.0 - belfry);
        alb = mix(alb, alb * 0.82, les); // lesene catch less light: slightly darker relief
        float nA = max(1.0, floor((segLen - 1.0) / 2.2)); float sp = (segLen - 1.0) / nA;
        float lu = mod(u - 0.5, sp) - sp * 0.5; float hw = sp * 0.3;
        float y0 = bz0 + 0.9, y1 = top - 1.3 - hw - 0.4;
        float head = y1 + sqrt(max(hw * hw - lu * lu, 0.0));
        float open = step(abs(lu), hw) * step(y0, z) * step(z, head) * step(0.5, u) * step(u, segLen - 0.5);
        float jamb = step(abs(lu), hw + 0.18) * step(y0 - 0.15, z) * step(z, head + 0.18) * step(0.5, u) * step(u, segLen - 0.5) - open;
        alb = mix(alb, ist.rgb, max(stone, jamb) * 0.92 + belfry * (1.0 - open) * 0.25);
        tn = mix(tn, tnI, max(stone, jamb)); rough = mix(rough, ist.a, max(stone, jamb));
        float slit = step(abs(mod(u, segLen) - segLen * 0.5), 0.12) * step(0.6, fract(z / 9.0)) * step(fract(z / 9.0), 0.78) * (1.0 - belfry) * step(4.0, z);
        if (open > 0.5) { alb = lin(vec3(0.03, 0.028, 0.025)); rough = 1.0; ao = 0.35; }
        if (slit > 0.5) { alb = lin(vec3(0.04)); ao = 0.5; }
        float tide = 0.2 + 0.15 * noise(vec2(u * 0.6, 1.0));
        alb = mix(alb, lin(vec3(0.10, 0.14, 0.08)), smoothstep(tide + 0.25, tide - 0.05, z));
    } else if (kind > 2.5) {
        // ---- cornice: Istrian stone with dentils on the underside
        vec4 m = matSample(vec2(vUv.x, vW.z) / layerScale(16.0), 16.0, vec3(0.82), tn);
        float dent = step(0.5, fract(vUv.x / 0.22));
        alb = m.rgb * (n.z < -0.2 ? 0.65 + 0.2 * dent : 1.0);
        rough = m.a;
        if (n.z > 0.5) {
            // top of the eave is covered by the last course of roof tiles, not bare stone
            float layer = 18.0 + floor(hash11(seed * 3.3) * 4.0);
            vec4 rm = matSample(vW.xy / layerScale(layer), layer, vec3(1.0), tn);
            alb = rm.rgb * 0.85; rough = rm.a;
        }
    } else {
        // ---- wall: every quad is one bay of a building; its codes say what opens on each floor
        int c1 = int(vS.z + 0.5), c2 = int(vS.w + 0.5);
        int winType = c1 & 7, shut = (c1 >> 3) & 3, frameT = (c1 >> 5) & 3, bayKind = (c1 >> 7) & 3, gKind = (c1 >> 9) & 7;
        bool pn = ((c1 >> 12) & 1) == 1; int matC = (c1 >> 13) & 3; int strC = (c1 >> 15) & 3; bool rust = ((c1 >> 17) & 1) == 1;
        float winW = float(c2 & 255) / 100.0, winH = float((c2 >> 8) & 255) * 0.02;
        int jseed = (c2 >> 16) & 15; int balMask = (c2 >> 20) & 15;
        float u = vUv.x; float z = vUv.y; float rel = z - 1.1;
        float bayC = vA.x, bayW = vA.w; float lu = u - bayC;
        float groundH = max(vS.x, 2.5), floorH = max(vS.y, 2.6);
        vec2 uv = vec2(u, z);
        vec3 tint = paint(seed);
        float lsel = hash11(seed * 5.77);
        float layer = matC == 0 ? floor(lsel * 6.0) : (matC == 1 ? 6.0 + floor(lsel * 5.0) : (matC == 2 ? 11.0 + floor(lsel * 4.0) : 15.0));
        vec4 m = matSample(uv / layerScale(layer), layer, tint, tn);
        if (matC == 1) {
            vec3 tnP; vec4 pl = matSample(uv / layerScale(0.0), floor(hash11(seed * 29.1) * 3.0), tint, tnP);
            float zone = smoothstep(0.5, 0.62, fbm(uv * 0.12 + seed * 13.0) + smoothstep(5.0, 0.0, rel) * 0.2);
            m = mix(pl, m, zone); tn = mix(tnP, tn, zone);
        }
        alb = m.rgb; rough = m.a;
        float streak = noise(vec2(u * 1.9 + seed * 40.0, z * 0.07)) * smoothstep(0.2, 0.9, fbm(vec2(u * 0.4, z * 0.15) + 3.0));
        alb *= 1.0 - 0.22 * streak;
        alb *= 0.88 + 0.24 * fbm(uv * 0.2 + seed * 3.0);
        vec3 tnI; vec4 ist = matSample(uv / layerScale(15.0), 15.0, vec3(1.0), tnI);
        if (rust && rel < groundH - 0.12) {
            // rusticated Istrian base (bugnato)
            vec3 tnA; vec4 as2 = matSample(uv / layerScale(17.0), 17.0, vec3(0.95), tnA);
            alb = as2.rgb; rough = as2.a; tn = tnA;
        }
        // ---- floors
        float pnF = pn ? 1.22 : 1.0;
        int fl; float fz, flH;
        if (rel < groundH) { fl = 0; fz = rel; flH = groundH; }
        else {
            float r2 = rel - groundH; float h1 = floorH * pnF;
            if (r2 < h1) { fl = 1; fz = r2; flH = h1; }
            else { float r3 = r2 - h1; fl = 2 + int(floor(r3 / floorH)); fz = mod(r3, floorH); flH = floorH; }
        }
        // string courses: a stone band at each floor line
        bool course = (strC == 1 && fl >= 1 && fz < 0.16) || (strC == 2 && fl == 1 && fz < 0.2);
        if (course) { alb = mix(alb, ist.rgb, 0.95); tn = tnI; rough = ist.a; ao = 0.92; }
        // ---- the opening of this bay on this floor
        bool has = bayKind != 2;
        bool door = false, shop = false, waterDoor = false, grille = false;
        bool balHere = fl >= 1 && fl <= 4 && ((balMask >> (fl - 1)) & 1) == 1;
        bool poly = bayKind == 1 && balHere;
        float ow = 1.0, y0 = 0.0, y1 = 0.0; int shape = winType; float xoff = 0.0;
        if (fl == 0) {
            if (gKind == 4) has = false;
            else if (gKind == 3) { waterDoor = true; ow = min(bayW - 0.5, 2.4); y0 = -2.0; y1 = min(groundH - 0.55, 3.1); shape = (winType == 2 || winType == 3 || winType == 6) ? winType : 1; }
            else if (gKind == 1) { door = true; ow = min(bayW - 0.5, 1.25); y0 = 0.0; y1 = min(groundH - 0.45, 2.6); shape = (winType == 0 || winType >= 4) ? 0 : (winType == 1 ? 1 : 2); }
            else if (gKind == 2) { shop = true; ow = bayW - 0.55; y0 = 0.05; y1 = min(groundH - 0.6, 2.75); shape = 0; }
            else { grille = true; ow = min(winW, bayW - 0.5); y0 = 1.25; y1 = min(groundH - 0.4, 2.55); shape = winType >= 4 ? 0 : winType; }
        } else if (bayKind == 1) {
            if (poly) { ow = bayW - 0.24; y0 = 0.5; y1 = min(flH - 0.5, 0.5 + winH * 1.1); shape = winType == 0 ? 1 : winType; }
            else { ow = min(winW, bayW - 0.4); y0 = 0.85; y1 = min(flH - 0.45, 0.85 + winH * (flH / floorH)); }
        } else {
            ow = min(winW, bayW - 0.4);
            float sill = (fl == 1 && pn) ? 0.75 : 0.9;
            y0 = balHere ? 0.05 : sill; y1 = min(flH - 0.45, sill + winH * (flH / floorH));
            float jit = float((jseed * 7 + fl * 13) % 17) / 16.0 - 0.5;
            xoff = (pn || matC == 3) ? 0.0 : jit * max(0.0, bayW - ow - 0.6) * 0.35;
        }
        if (y1 - y0 < 0.6) has = false;
        float hw = ow * 0.5;
        float lx = lu - xoff; float ax = abs(lx);
        float fw = frameT == 3 ? 0.0 : (frameT == 1 ? 0.15 : 0.11);
        float topE = archTop(shape, ax, hw, y1);
        float inside = (has && ax < hw && fz > y0 && fz < topE) ? 1.0 : 0.0;
        float topF = archTop(shape, ax * hw / (hw + fw), hw, y1) + fw;
        float outer = (has && ax < hw + fw && fz > y0 - (door || shop || waterDoor ? 0.0 : 0.09) && fz < topF) ? 1.0 : 0.0;
        float frame = outer - inside;
        // stone sill under windows, pediments over the baroque ones
        float sill2 = (has && !door && !shop && !waterDoor && !balHere && ax < hw + 0.14 && fz > y0 - 0.11 && fz < y0) ? 1.0 : 0.0;
        float ped = 0.0;
        if (has && fl >= 1 && (winType == 4 || winType == 5) && !poly) {
            float pw = hw + 0.22; float pz = y1 + fw + 0.05;
            float ph = winType == 4 ? 0.42 * (1.0 - ax / pw) : 0.2 + 0.1 * sqrt(max(1.0 - ax * ax / (pw * pw), 0.0));
            ped = (ax < pw && fz > pz && fz < pz + ph) ? 1.0 : 0.0;
            if (ax < pw && fz > pz - 0.04 && fz < pz) ao = 0.55;            // shadow under the pediment
        }
        // polifora columns between the lights
        if (poly && ax > hw && fz > y0 && fz < y1) frame = 1.0;
        float stone = max(max(frame, sill2), ped);
        vec3 frameCol = frameT == 2 ? alb * 0.8 : (frameT == 0 ? mix(alb, vec3(1.0), 0.35) : ist.rgb * 0.95);
        alb = mix(alb, frameCol, stone); tn = mix(tn, tnI, stone); rough = mix(rough, ist.a, stone);
        if (stone > 0.5 && fz < y0 + 0.02 && sill2 > 0.5) ao = 0.8;
        if (inside > 0.5) {
            vec3 vdir = normalize(uCam - vW);
            // the opening has depth: look into it through the jambs (parallax reveal)
            vec3 vt = vec3(dot(vdir, t), dot(vdir, b), max(dot(vdir, n), 0.12));
            float depthO = waterDoor ? 0.6 : (door || shop ? 0.18 : 0.22);
            vec2 q = vec2(lx, fz) - vt.xy / vt.z * depthO;
            float back = (abs(q.x) < hw && q.y > y0 && q.y < archTop(shape, abs(q.x), hw, y1)) ? 1.0 : 0.0;
            bool closed = !poly && fl >= 1 && shut != 0 && (jseed * 5 + fl * 11) % 7 < 2;
            if (back < 0.5) {
                // jamb / soffit of the reveal, in shade
                alb = alb * 0.55; ao = 0.6; rough = 0.9;
            } else if (closed) {
                vec3 sc2 = shut == 1 ? lin(vec3(0.15, 0.30, 0.20)) : (shut == 2 ? lin(vec3(0.36, 0.25, 0.18)) : lin(vec3(0.62, 0.62, 0.58)));
                float slat = step(0.5, fract(fz / 0.07));
                vec3 tnS; vec4 sw = matSample(vec2(lu * 2.0, z) / layerScale(32.0), 32.0, sc2 * 2.2, tnS);
                alb = sw.rgb * (0.72 + 0.28 * slat); rough = 0.55; tn = tnS;
                if (abs(lx) < 0.015) alb *= 0.4;                          // the two leaves meet
            } else if (door) {
                // painted wooden door with panels and a stone step
                vec3 dc = hash11(seed * 7.7 + float(jseed)) > 0.5 ? lin(vec3(0.17, 0.24, 0.18)) : lin(vec3(0.30, 0.18, 0.10));
                float panel = step(0.08, abs(fract(q.y / 0.7) - 0.5)) * step(0.06, abs(abs(q.x) - hw * 0.5));
                alb = dc * (0.75 + 0.25 * panel); rough = 0.55;
                if (abs(q.x) < 0.012) alb *= 0.5;
            } else if (waterDoor) {
                // portone da acqua: dark wet boathouse behind an old wooden gate
                float bars = step(0.85, fract(q.x / 0.22)) * step(fz, y1 - 0.4);
                alb = mix(lin(vec3(0.02, 0.025, 0.02)), lin(vec3(0.20, 0.13, 0.08)), bars * step(0.4, fz)); rough = 0.4;
            } else {
                vec3 r = reflect(-vdir, n);
                vec3 env = mix(uFogCol, uSkyZenith, clamp(r.z * 1.4 + 0.2, 0.0, 1.0));
                float gx = clamp(1.0 - dot(n, vdir), 0.0, 1.0); float fres = 0.08 + 0.92 * gx * gx * gx * gx;
                float wid = float(jseed) + float(fl) * 3.1 + seed * 17.0;
                vec3 interior = lin(vec3(0.05, 0.045, 0.04)) * (0.6 + 0.8 * noise(vec2(wid, 1.0)));
                if (hash11(wid) > 0.72) interior = lin(vec3(0.55, 0.50, 0.42)) * 0.35;
                if (shop) {
                    // shop window: shelves and goods behind the glass, lit from inside
                    float shelf = step(0.82, fract(q.y / 0.55)) + step(0.9, fract(q.x / 0.7)) * 0.5;
                    vec3 goods = lin(vec3(0.5 + 0.4 * noise(q * 5.0 + wid), 0.35 + 0.3 * noise(q * 4.0 + 3.0), 0.25 + 0.3 * noise(q * 6.0 + 7.0)));
                    interior = mix(goods * 0.45, lin(vec3(0.12, 0.09, 0.07)), clamp(shelf, 0.0, 1.0)) * (0.8 + 0.4 * smoothstep(0.3, 2.0, q.y));
                }
                // curtains drawn to the sides of some windows
                if (!shop && hash11(wid * 1.7) > 0.55 && abs(q.x) > hw * (0.5 + 0.2 * hash11(wid * 3.1))) interior = lin(vec3(0.78, 0.74, 0.66)) * (0.45 + 0.1 * sin(q.x * 60.0));
                // glazing bars: two casements and a transom; leaded roundels in gothic lights
                float mull = 0.0;
                if (shape == 0 || shape >= 4) mull = max(step(abs(lx), 0.02), step(abs(fz - (y0 + (y1 - y0) * 0.72)), 0.02));
                else mull = step(abs(lx), 0.015);
                if (shop) mull = max(step(abs(fz - min(2.2, y1 - 0.35)), 0.035), step(hw - 0.05, abs(lx)));
                vec3 glass = interior * (uSkyAmb + uSunCol * 0.2) + env * fres * 0.9
                    + uSunCol * pow(max(dot(reflect(-uSunDir, n), vdir), 0.0), 400.0) * sunShadow(vW, n) * 4.0;
                if (shape == 2 || shape == 3 || shape == 6) { vec2 rd = fract(vec2(lx, fz) / 0.11) - 0.5; glass *= 0.85 + 0.3 * smoothstep(0.35, 0.5, length(rd)); }
                glass = mix(glass, lin(vec3(0.28, 0.24, 0.20)) * (uSkyAmb + uSunCol * 0.3), mull);
                // after dark: rooms lit from inside - warm lamps, a few cooler ones, curtains glowing, shops brightest
                if (uWinLit > 0.001) {
                    float on = step(hash11(wid * 5.31 + 0.7), uWinLit * (shop ? 1.7 : 1.0));
                    if (on > 0.5) {
                        float tint = hash11(wid * 2.9);
                        vec3 lamp = tint > 0.8 ? vec3(0.95, 0.88, 0.75) : (tint > 0.35 ? vec3(1.0, 0.68, 0.36) : vec3(1.0, 0.58, 0.28));
                        float pool = 0.55 + 0.45 * smoothstep(-0.2, 0.9, q.y - (y1 - y0) * 0.35) * (1.0 - 0.5 * abs(lx) / max(hw, 0.1));
                        vec3 emit = lamp * (shop ? 3.2 : 1.9) * pool * (0.7 + 0.6 * hash11(wid * 8.1));
                        if (!shop && hash11(wid * 1.7) > 0.55) emit *= 0.75;           // through the curtains
                        glass += emit * (1.0 - mull) * (grille ? 0.55 : 1.0);
                    }
                }
                if (grille) { float gb = max(step(abs(fract(lx / 0.13) - 0.5), 0.07), step(abs(fract(fz / 0.45) - 0.5), 0.04)); glass = mix(glass, lin(vec3(0.03)) * uSkyAmb, gb); }
                o = vec4(fog(glass, vW), 1.0);
                return;
            }
        }
        // shop sign board above the shop window
        if (shop && fl == 0 && abs(lu) < hw && fz > y1 + 0.1 && fz < y1 + 0.5) {
            float sh2 = hash11(seed * 3.9 + float(jseed));
            alb = sh2 > 0.66 ? lin(vec3(0.14, 0.22, 0.16)) : (sh2 > 0.33 ? lin(vec3(0.35, 0.10, 0.08)) : lin(vec3(0.85, 0.80, 0.70)));
            float letters = step(0.5, noise(vec2(lu * 9.0, 3.0))) * step(abs(fz - y1 - 0.3), 0.09) * step(abs(lu), hw * 0.75);
            alb = mix(alb, sh2 > 0.33 ? lin(vec3(0.9, 0.85, 0.7)) : lin(vec3(0.1)), letters); rough = 0.5;
        }
        // rising damp and salt (efflorescence) at the foot of the walls, algae and the tide line on canal walls
        float tide = 0.2 + 0.15 * noise(vec2(u * 0.6, 1.0));
        float algae = smoothstep(tide + 0.25, tide - 0.05, z);
        alb = mix(alb, lin(vec3(0.10, 0.14, 0.08)), algae); rough = mix(rough, 0.25, algae);
        alb = mix(alb, alb * vec3(0.72, 0.74, 0.65) + 0.04, smoothstep(tide + 1.5, tide + 0.3, z) * 0.6);
        float salt = smoothstep(1.1 + 1.6, 1.1 + 0.4, z) * smoothstep(0.45, 0.7, fbm(uv * vec2(1.3, 2.2) + seed * 7.0)) * step(0.5, z);
        alb = mix(alb, lin(vec3(0.82, 0.80, 0.74)), salt * 0.45);
        ao = min(ao, mix(0.65, 1.0, smoothstep(0.5, 7.0, rel)));
    }
    n = perturb(n, t, b, tn, 0.9);
    o = vec4(fog(light(alb, n, vW, rough, ao, f0), vW), 1.0);
}
"""

    // ------------------------------------------------------------------ ground (masked quad per tile)
    const val GROUND_VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uVP; uniform mat4 uModel; uniform mat4 uMirror;
out vec3 vW; out vec2 vL;
void main() { vec4 w = uModel * vec4(aPos, 1.0); vW = w.xyz; vL = aPos.xy; gl_Position = uVP * uMirror * w; }
"""
    val GROUND_FS = """#version 300 es
precision highp float;
$COMMON
in vec3 vW; in vec2 vL;
uniform sampler2D uMask; uniform sampler2D uClass; uniform float uMarshPass; uniform float uGrassR;
out vec4 o;
void main() {
    if (vW.z < uClipZ) discard;
    vec2 uv = (vL + 1.0) / 258.0;
    vec2 mk = texture(uMask, uv).rg;
    float cov = uMarshPass > 0.5 ? mk.g : mk.r;
    if (cov < 0.5) discard;
    if (uMarshPass > 0.5) {
        // up close the barena is real grass standing in the water (MarshGrass): dithered hand-over
        float th = fract(sin(dot(floor(gl_FragCoord.xy), vec2(12.9898, 78.233))) * 43758.5453);
        if (distance(vW.xy, uCam.xy) < uGrassR * (0.8 + 0.2 * th)) discard;
    }
    vec3 alb; float rough; vec3 tn;
    vec2 q = vW.xy;
    if (uMarshPass > 0.5) {
        vec4 g = matSample(q / 3.0, 29.0, lin(vec3(0.75, 0.70, 0.45)), tn);
        vec3 tn2; vec4 mud = matSample(q / 3.0, 30.0, vec3(0.7), tn2);
        float f = smoothstep(0.35, 0.65, fbm(q * 0.15));
        alb = mix(mud.rgb, g.rgb, f); rough = mix(0.4, g.a, f);
    } else {
        float c = texture(uClass, uv).r * 255.0;
        if (c > 1.5 && c < 2.5) {
            vec4 g = matSample(q / 3.0, 29.0, vec3(1.0), tn);
            vec3 tn2; vec4 gr = matSample(q / 3.0, 30.0, vec3(1.0), tn2);
            float f = smoothstep(0.4, 0.6, fbm(q * 0.2));
            alb = mix(gr.rgb, g.rgb, f); rough = g.a;
        } else if (c > 2.5) {
            vec4 g = matSample(q / 3.0, 30.0, lin(vec3(1.25, 1.12, 0.85)), tn); alb = g.rgb; rough = 0.9;
        } else {
            // paving: trachyte masegni with zones of herring-bone and old brick (campi), worn by footfall
            float z1 = fbm(q * 0.006 + 3.1), z2 = fbm(q * 0.011 + 8.7);
            float layer = z1 > 0.62 ? 23.0 : (z2 > 0.66 ? 24.0 : (z2 < 0.3 ? 25.0 : 22.0));
            vec4 g = matSample(q / layerScale(layer), layer, vec3(1.0), tn);
            alb = g.rgb * (0.72 + 0.22 * fbm(q * 0.05)); rough = g.a;
            float wet = smoothstep(0.8, 0.55, cov);
            alb = mix(alb, alb * 0.55, wet); rough = mix(rough, 0.15, wet);
        }
    }
    vec3 n = perturb(vec3(0, 0, 1), vec3(1, 0, 0), vec3(0, 1, 0), tn, 0.9);
    o = vec4(fog(light(alb, n, vW, rough, 0.95, 0.04), vW), 1.0);
}
"""

    // ------------------------------------------------------------------ water (shared wave field + planar reflection)
    val WATER_VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uVP; uniform vec3 uCenter; uniform float uTime; uniform float uWaveAmp; uniform float uGridHalf;
uniform sampler2D uWake; uniform vec3 uWakeO;   // xy = world corner of the wake grid, z = its size in metres
out vec3 vW; out float vFade;
${Waves.glsl()}
void main() {
    vec3 w = aPos + vec3(uCenter.xy, 0.0);
    float edge = max(abs(aPos.x), abs(aPos.y)) / uGridHalf;
    vFade = 1.0 - smoothstep(0.7, 1.0, edge);
    vec2 wuv = (w.xy - uWakeO.xy) / uWakeO.z;
    float wk = 0.0;
    if (wuv.x > 0.0 && wuv.y > 0.0 && wuv.x < 1.0 && wuv.y < 1.0) {
        wk = textureLod(uWake, wuv, 0.0).r;
        float e = 1.0 / float(textureSize(uWake, 0).x); float cellM = uWakeO.z * e;
        vec2 gr = vec2(textureLod(uWake, wuv + vec2(e, 0.0), 0.0).r - textureLod(uWake, wuv - vec2(e, 0.0), 0.0).r,
                       textureLod(uWake, wuv + vec2(0.0, e), 0.0).r - textureLod(uWake, wuv - vec2(0.0, e), 0.0).r) / (2.0 * cellM);
        // a bad value from the simulation must never move the mesh (NaN/inf would throw it up into walls)
        if (isnan(wk) || isinf(wk)) wk = 0.0;
        if (any(isnan(gr)) || any(isinf(gr))) gr = vec2(0.0);
        wk = clamp(wk, -0.8, 0.8);
        w.xy -= clamp(gr, -0.4, 0.4) * 0.5;        // choppy: crests pinch, troughs widen
    }
    w.z = waveH(w.xy, uTime, uWaveAmp) * vFade + wk;
    vW = w; gl_Position = uVP * vec4(w, 1.0);
}
"""
    val WATER_FS = """#version 300 es
precision highp float;
$COMMON
${Waves.glsl()}
in vec3 vW; in float vFade;
uniform sampler2D uRefl; uniform vec2 uScreen; uniform float uWaveAmp;
uniform sampler2D uWake; uniform vec3 uWakeO; uniform vec2 uWakeDir; uniform vec2 uRipOff;   // wake state: r height, g previous height, b foam, a aeration
uniform sampler2D uWaterN;                       // tileable ripple slopes (rg), baked once at start-up
uniform sampler2D uFoamT;                        // baked foam: r lace, g froth, b blotches (assets/water/foam.png)
uniform vec4 uBoat; uniform vec2 uHull;          // x, y, heading, speed ; half length, half beam
out vec4 o;
vec2 ripple(vec2 uv) { return texture(uWaterN, uv).rg * 2.0 - 1.0; }
void main() {
    vec2 p = vW.xy;
    vec2 g = waveG(p, uTime, uWaveAmp) * max(vFade, 0.35);
    // simulated wake: slope from the heightfield, foam / aeration from its other channels
    vec2 wuv = (p - uWakeO.xy) / uWakeO.z;
    float foam = 0.0; float wakeE = 0.0; float milkA = 0.0; vec2 wakeSlope = vec2(0.0); float hcF = 0.0;
    if (wuv.x > 0.004 && wuv.y > 0.004 && wuv.x < 0.996 && wuv.y < 0.996) {
        float wn = float(textureSize(uWake, 0).x); float e = 1.0 / wn; float cellM = uWakeO.z / wn;
        vec4 wt = texture(uWake, wuv);
        float hE = texture(uWake, wuv + vec2(e, 0.0)).r, hW = texture(uWake, wuv - vec2(e, 0.0)).r;
        float hN = texture(uWake, wuv + vec2(0.0, e)).r, hS = texture(uWake, wuv - vec2(0.0, e)).r;
        float border = smoothstep(0.0, 0.06, min(min(wuv.x, wuv.y), min(1.0 - wuv.x, 1.0 - wuv.y)));
        vec2 wg = vec2(hE - hW, hN - hS) / (2.0 * cellM) * border;
        if (any(isnan(wt)) || any(isinf(wt))) wt = vec4(0.0);
        if (isnan(hE + hW + hN + hS) || isinf(hE + hW + hN + hS)) { hE = 0.0; hW = 0.0; hN = 0.0; hS = 0.0; }
        hE = clamp(hE, -0.8, 0.8); hW = clamp(hW, -0.8, 0.8); hN = clamp(hN, -0.8, 0.8); hS = clamp(hS, -0.8, 0.8); wt.r = clamp(wt.r, -0.8, 0.8);
        float hc = wt.r; hcF = hc;
        float lapW = (hE + hW + hN + hS - 4.0 * hc) / (cellM * cellM);
        wakeE = clamp((abs(hc) * 2.5 + length(wg) * 3.0 + clamp(-lapW * 8.0, 0.0, 1.0)) * border, 0.0, 1.5);   // capillary chop on crests
        foam = wt.b * border; milkA = wt.a * border;
        g += wg * mix(5.0, 1.5, smoothstep(0.3, 0.8, foam)); wakeSlope = wg;    // strong slope on the clean arms, not on the wash
        wakeE += max(hc, 0.0) * 4.0;                                           // churn on the stern mound
    }
    // churned water right next to a moving hull (no mirror beside a boat at speed)
    {
        vec2 d = p - uBoat.xy; float ch = cos(uBoat.z), sh = sin(uBoat.z);
        vec2 l = vec2(d.x * ch + d.y * sh, -d.x * sh + d.y * ch);
        float outD = max(abs(l.x) - uHull.x, abs(l.y) - uHull.y);
        wakeE = max(wakeE, (1.0 - clamp(outD / 3.0, 0.0, 1.0)) * clamp(uBoat.w / 8.0, 0.0, 1.0) * 1.3);
    }
    // frame aligned with the wash: along / across the boat's track
    vec2 al = uWakeDir, ac = vec2(-uWakeDir.y, uWakeDir.x);
    // measured from the boat (not from the world origin, hundreds of metres away: turning the boat used to swing the
    // whole pattern round that far point, so it slid across the water like a cloud of particles)
    vec2 pr = p - uBoat.xy;
    vec2 pw = vec2(dot(pr, al) * 0.2, dot(pr, ac));      // stretched along the track -> long turbulent streaks
    // the boil: churned, bulging water where the wash is fresh (just behind the outboard)
    if (foam > 0.35) {
        float bs = smoothstep(0.35, 0.9, foam);
        g += ripple(p * 0.42 + uRipOff) * 0.55 * bs;      // scroll accumulated frame by frame (was direction * total time)
        wakeE = max(wakeE, bs * 1.5);
    }
    // wake waves carry short, steep wavelets: much rougher surface where the wake energy is
    if (wakeE > 0.01) g += (ripple(p * 0.55 + vec2(uTime * 0.13, -uTime * 0.09)) * 0.16 + ripple(p * 1.1 - vec2(uTime * 0.11, uTime * 0.16)) * 0.09) * wakeE;
    // ambient ripples (two scrolling layers of the baked slope texture); the turbulent track is a slick that kills them
    float slick = 1.0 - 0.75 * clamp(milkA * 2.0, 0.0, 1.0) * (1.0 - clamp(foam * 2.0, 0.0, 1.0));
    g += (ripple(p * 0.085 + vec2(uTime * 0.021, uTime * 0.012)) * 0.075 + ripple(p * 0.23 - vec2(uTime * 0.017, -uTime * 0.03)) * 0.045) * slick;
    vec3 n = normalize(vec3(-g, 1.0));
    vec3 v = normalize(uCam - vW);
    float nv = max(dot(n, v), 0.0);
    float wx5 = clamp(1.0 - nv, 0.0, 1.0); float fres = 0.02 + 0.98 * wx5 * wx5 * wx5 * wx5 * wx5;
    vec2 roff = clamp(n.xy * 0.05 * wakeE * 2.5 + wakeSlope * 0.12, -0.03, 0.03)
              * (1.0 - clamp(foam, 0.0, 1.0)) * (1.0 - smoothstep(60.0, 200.0, length(uCam - vW)));
    vec2 suv = gl_FragCoord.xy / uScreen + n.xy * 0.035 + roff;
    vec3 refl = texture(uRefl, suv).rgb;
    float sh = sunShadow(vW, vec3(0, 0, 1));
    vec3 deep = vec3(0.008, 0.026, 0.021);
    float crest = clamp(dot(n.xy, -uSunDir.xy) * 4.0 + 0.3, 0.0, 1.0);
    vec3 scatter = vec3(0.020, 0.058, 0.040) * (0.4 + 0.6 * crest) * (0.45 + 0.55 * sh);
    vec3 body = (deep + scatter) * (uSkyAmb * 1.2 + uSunCol * 0.25 * sh);
    vec3 col = mix(body, refl, fres);
    vec3 h = normalize(uSunDir + v);
    float nh = max(dot(n, h), 0.0);
    // specular anti-aliasing: where the normal changes within a pixel (distance, ripples, the camera turning) the
    // sun's highlight widens and dims instead of sparkling pixel to pixel
    vec3 dn = fwidth(n); float nvar = dot(dn, dn);
    float sk = 1.0 / (1.0 + 900.0 * nvar);
    float p1 = max(600.0 * sk, 30.0);
    float spec = pow(nh, p1) * 40.0 * (p1 / 600.0) + pow(nh, max(80.0 * sk, 12.0)) * 0.6 * max(sk, 0.35);
    col += uSunCol * spec * sh * fres * 4.0 * (1.0 - 0.6 * clamp(milkA, 0.0, 1.0));
    float aerT = clamp(milkA * 0.9 + foam * 0.4, 0.0, 1.0);
    // the hollow behind the mound: water drops away and you see deeper into it - darker, glassier
    if (milkA > 0.15) col *= 1.0 - clamp(-hcF * 3.0, 0.0, 0.35) * (1.0 - clamp(foam * 1.5, 0.0, 1.0));
    if (aerT > 0.005) {
        vec3 milk = mix(vec3(0.045, 0.075, 0.06), vec3(0.06, 0.15, 0.13), smoothstep(0.08, 0.6, milkA)) * (uSkyAmb * 1.2 + uSunCol * 0.4 * sh);
        col = mix(col, milk, aerT * 0.45);
    }
    if (foam > 0.01) {
        // lace streaked along the track: torn patches x bubble rafts x fine froth, slowly boiling
        vec2 q = pw + n.xy * 0.4 + vec2(0.0, milkA * pw.x * 0.25);
        // two scrolling samples of the baked lace at unrelated scales (no visible tiling) + fine froth
        float lace = texture(uFoamT, q * 0.1 + uTime * 0.006).r * 0.58 + texture(uFoamT, q * 0.37 - vec2(uTime * 0.019, uTime * 0.011)).r * 0.42
                   + (texture(uFoamT, q * 1.1 + uTime * 0.03).g - 0.5) * 0.18;
        float thr = 1.0 - clamp(foam, 0.0, 1.0) * mix(0.64, 0.86, smoothstep(0.6, 0.95, foam));
        float fw = smoothstep(thr - 0.06, thr + 0.06, lace);
        vec2 sb = ripple(q * 0.9 + uTime * 0.02);
        vec3 nf = normalize(vec3(-g * 0.6 - sb * 0.5, 1.0));
        float alb = 0.62 + 0.28 * clamp(sb.x * 0.5 + 0.5, 0.0, 1.0) * (0.7 + 0.3 * texture(uFoamT, p * 0.21).g);
        float dif = max(dot(nf, uSunDir), 0.0);
        vec3 white = vec3(alb, alb * 1.03, alb * 1.02) * (uSunCol * 0.6 * dif * sh + uSkyAmb * (1.1 + 0.4 * nf.z));
        float sub = smoothstep(thr - 0.3, thr - 0.1, lace) * (1.0 - fw);
        col = mix(col, vec3(0.25, 0.50, 0.45) * (uSkyAmb * 1.1 + uSunCol * 0.35 * sh), sub * 0.45);
        float fringe = smoothstep(thr - 0.22, thr - 0.04, lace) * (1.0 - fw);
        col = mix(col, vec3(0.20, 0.42, 0.38) * (uSkyAmb * 1.2 + uSunCol * 0.45 * sh), fringe * 0.5);
        col = mix(col, white, fw * 0.95);
    }
    o = vec4(fog(col, vW), 1.0);
}
"""

    // ------------------------------------------------------------------ GPU wake simulation (one step per draw, ping-pong)
    const val WAKESIM_VS = """#version 300 es
layout(location=0) in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""
    const val WAKESIM_FS = """#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D uState;      // r height, g previous height, b foam, a aeration (nearest)
uniform lowp sampler2D uSolid;       // r > 0.5: land / quay wall
uniform int uN; uniform float uCell; uniform vec2 uOrigin; uniform ivec2 uShift;
uniform float uDt; uniform float uK; uniform float uBeta; uniform float uNu; uniform float uDamp; uniform float uSpongeMin;
uniform float uFoamDiff; uniform float uAerDiff;
uniform vec4 uB0; uniform vec4 uB0b; uniform vec4 uB1; uniform vec4 uB1b; uniform vec2 uHull;
uniform vec4 uSplat[16]; uniform int uNSplat;
// ACTV boats inside the window: pose now / one step ago (x, y, heading, depth), (throttle, 0, speed, 0), half size
uniform vec4 uV0[4]; uniform vec4 uV0b[4]; uniform vec4 uV1[4]; uniform vec2 uVH[4]; uniform int uNV;
out vec4 o;
const float PI = 3.14159265;
vec4 S(ivec2 c) { c += uShift; if (c.x < 0 || c.y < 0 || c.x >= uN || c.y >= uN) return vec4(0.0); return texelFetch(uState, c, 0); }
// hull "occupancy" (displacement depth) at world point p for a boat pose: B = x, y, heading, depth; Bb = throttle, planing
float occH(vec4 B, vec4 Bb, vec2 p, vec2 hull) {
    vec2 d = p - B.xy; float ch = cos(B.z), sh = sin(B.z);
    float lx = d.x * ch + d.y * sh, ly = -d.x * sh + d.y * ch;
    float hl = hull.x, hb = hull.y;
    if (B.w <= 0.0 || abs(lx) > hl + 7.0 || abs(ly) > hl + 7.0) return 0.0;
    if (lx < -hl) {
        // behind the transom: the outboard throws water up into a mound, then it falls into a hollow
        float bk = -hl - lx;
        float qm = ly / (hb * 0.45), qh = ly / (hb * 0.95);
        float latM = max(0.0, 1.0 - qm * qm), latH = max(0.0, 1.0 - qh * qh);
        if (latH <= 0.0 || bk > 6.5) return 0.0;
        float thr = abs(Bb.x), pl = Bb.y;
        float mound = (0.05 + 0.10 * pl) * thr * (bk < 2.0 ? sin(PI * bk / 2.0) : 0.0) * latM;
        float hollow = (0.07 + 0.10 * pl) * thr * (bk >= 2.0 ? sin(PI * (bk - 2.0) / 4.5) : 0.0) * latH;
        return hollow - mound;
    }
    float t = clamp(lx / hl, -1.0, 1.0);
    float beam = hb * (t > 0.0 ? sqrt(max(0.0, 1.0 - t * t * t)) : 1.0);
    float outD = max(abs(lx) - hl, abs(ly) - beam);
    if (outD > 0.4) return 0.0;
    float aft = 1.0 - Bb.y * 0.6 * max(0.0, t);
    float qi = ly / (beam + 0.8);
    float inside = max(0.0, 1.0 - qi * qi) * (1.0 - t * t * 0.3);
    float soft = outD > 0.0 ? pow(1.0 - outD / 0.4, 2.0) : 1.0;
    return B.w * inside * aft * soft;
}
float occ(vec4 B, vec4 Bb, vec2 p) { return occH(B, Bb, p, uHull); }
void main() {
    ivec2 c = ivec2(gl_FragCoord.xy);
    vec2 p = uOrigin + (vec2(c) + 0.5) * uCell;
    vec4 s0 = S(c);
    vec4 sE = S(c + ivec2(1, 0)), sW = S(c - ivec2(1, 0)), sN = S(c + ivec2(0, 1)), sS = S(c - ivec2(0, 1));
    float dg = S(c + ivec2(1, 1)).r + S(c + ivec2(-1, 1)).r + S(c + ivec2(1, -1)).r + S(c + ivec2(-1, -1)).r;
    float d2 = S(c + ivec2(2, 0)).r + S(c - ivec2(2, 0)).r + S(c + ivec2(0, 2)).r + S(c - ivec2(0, 2)).r;
    float u0 = s0.r, up0 = s0.g;
    float n4 = sE.r + sW.r + sN.r + sS.r;
    float lap = n4 - 4.0 * u0;
    float lap2 = 20.0 * u0 - 8.0 * n4 + 2.0 * dg + d2;           // biharmonic: weak dispersion (short waves lag)
    float lapP = sE.g + sW.g + sN.g + sS.g - 4.0 * up0;
    float v = 2.0 * u0 - up0 + uK * lap + uBeta * lap2 + uNu * (lap - lapP);
    // moving hull: its change of footprint is a volume-conserving height displacement (no velocity kick)
    float dOcc = occ(uB0, uB0b, p) - occ(uB1, uB1b, p);
    float gateV = 0.0;
    for (int k = 0; k < 4; k++) {
        if (k >= uNV) break;
        if (distance(p, uV0[k].xy) > uVH[k].x + 9.0) continue;
        dOcc += occH(uV0[k], uV0b[k], p, uVH[k]) - occH(uV1[k], uV0b[k], p, uVH[k]);
        gateV = max(gateV, 1.0 - clamp((distance(p, uV0[k].xy) - uVH[k].x - 4.0) / 10.0, 0.0, 1.0) * step(0.8, uV0b[k].z));
    }
    v -= dOcc; float uc = u0 - dOcc;
    int edge = min(min(c.x, c.y), min(uN - 1 - c.x, uN - 1 - c.y));
    v *= edge < 16 ? mix(uSpongeMin, uDamp, float(edge) / 16.0) : uDamp;
    if (edge == 0) { v = 0.0; uc = 0.0; }
    bool solid = texelFetch(uSolid, c, 0).r > 0.5;
    // foam: breaking crests near the boat; fresh white foam bleaches fast, aeration lingers and spreads
    float steep = length(vec2(sE.r - sW.r, sN.r - sS.r)) / (2.0 * uCell);
    float fAvg = (sE.b + sW.b + sN.b + sS.b) * 0.25;
    float f0 = s0.b + (fAvg - s0.b) * uFoamDiff;
    float q = 1.0 - min(f0, 1.0);
    float gate = max(1.0 - clamp((distance(p, uB0.xy) - 10.0) / 10.0, 0.0, 1.0), gateV);
    float f = f0 * exp(-uDt / (1.1 + 4.0 * q * q)) + (max(0.0, steep - 0.12) * 3.0 + max(0.0, u0 - 0.16) * 3.0) * uDt * gate;
    float aAvg = (sE.a + sW.a + sN.a + sS.a) * 0.25;
    float a = (s0.a + (aAvg - s0.a) * uAerDiff) * exp(-uDt / 25.0);
    for (int k = 0; k < 16; k++) {
        if (k >= uNSplat) break;
        vec4 sp = uSplat[k];
        float dd = distance(p, sp.xy) / sp.z;
        if (dd < 1.0) { float w = sp.w * (1.0 - dd * dd); f += w; a = min(1.0, a + w * 0.5); }
    }
    if (solid) { v = 0.0; f = 0.0; a = 0.0; }
    // never let a bad value live on: one NaN or runaway cell spreads over the whole grid in a second, and the water
    // mesh displaced by it shoots up into black walls. Reset it, and keep heights physical (a wake is centimetres)
    if (isnan(v) || isinf(v) || isnan(uc) || isinf(uc)) { v = 0.0; uc = 0.0; }
    if (isnan(f) || isinf(f)) f = 0.0;
    if (isnan(a) || isinf(a)) a = 0.0;
    o = vec4(clamp(v, -1.0, 1.0), clamp(uc, -1.0, 1.0), clamp(f, 0.0, 1.0), clamp(a, 0.0, 1.0));
}
"""

    // ------------------------------------------------------------------ wake foam ribbons
    const val WAKE_VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aInfo;
uniform mat4 uVP; uniform vec3 uCam;
out vec3 vW; out vec3 vI;
// pulled slightly toward the camera so the wave surface between ribbon vertices never cuts through the foam
void main() { vW = aPos; vI = aInfo; gl_Position = uVP * vec4(aPos + normalize(uCam - aPos) * 0.18, 1.0); }
"""
    val WAKE_FS = """#version 300 es
precision highp float;
$COMMON
in vec3 vW; in vec3 vI;
out vec4 o;
void main() {
    // lacy foam: coverage threshold drops with intensity, so fresh wash is solid white and old wake breaks into lace
    float across = abs(vI.y);
    float prof = 1.0 - smoothstep(0.35, 1.0, across);
    float I = clamp(vI.z, 0.0, 1.3) * prof;
    vec2 q = vW.xy;
    float lace = fbm(q * 0.85 + vec2(vI.x * 0.07, uTime * 0.04)) * 0.62 + noise(q * 3.6) * 0.30 + noise(q * 9.0) * 0.12;
    float thr = 1.10 - I * 0.56;
    float foam = smoothstep(thr - 0.06, thr + 0.10, lace);
    float milk = I * 0.22;                                  // aerated, lighter water around the foam
    float a = max(foam * 0.92, milk * 0.5);
    if (a < 0.015) discard;
    float sh = sunShadow(vW, vec3(0, 0, 1));
    vec3 white = vec3(0.80, 0.83, 0.82) * (uSunCol * 0.6 * sh + uSkyAmb * 1.1);
    vec3 aer = vec3(0.10, 0.24, 0.20) * (uSunCol * 0.35 * sh + uSkyAmb * 1.2);
    vec3 c = mix(aer, white, foam);
    o = vec4(fog(c, vW), a);
}
"""

    // ------------------------------------------------------------------ spray particles (point sprites)
    const val SPRAY_VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aVel; layout(location=2) in vec2 aInfo; layout(location=3) in vec2 aCorner;
uniform mat4 uVP; uniform vec3 uCam;
out float vA; out vec3 vW; out vec2 vC; out float vSize;
void main() {
    // camera-facing quad stretched along the droplet's velocity (reads as a moving streak, not a dot)
    vec3 view = normalize(aPos - uCam);
    vec3 vel = aVel; vec3 vp = vel - view * dot(vel, view); float sp = length(vp);
    vec3 along = sp > 0.2 ? vp / sp : normalize(cross(view, vec3(0.0, 0.0, 1.0)));
    vec3 side = normalize(cross(view, along));
    float size = aInfo.y;
    float stretch = 1.0 + min(sp * 0.03 / max(size, 0.03), 2.2);
    vec3 p = aPos + along * aCorner.y * size * stretch + side * aCorner.x * size;
    vW = p;
    p += normalize(uCam - p) * min(0.5, size * 2.0);   // keep the sprite from being cut by the water surface
    vA = aInfo.x; vC = aCorner; vSize = size;
    gl_Position = uVP * vec4(p, 1.0);
}
"""
    val SPRAY_FS = """#version 300 es
precision highp float;
$COMMON
uniform sampler2D uFoamT;
in float vA; in vec3 vW; in vec2 vC; in float vSize;
out vec4 o;
void main() {
    float r = dot(vC, vC);
    float ragged = r * (0.85 + 0.4 * texture(uFoamT, vC * 0.25 + vW.xy * 0.9).g);
    if (ragged > 1.0) discard;
    bool mist = vSize > 0.25;
    float near = smoothstep(1.2, 4.5, distance(vW, uCam));        // mist on the lens = full-screen overdraw: fade it
    float a = exp(-ragged * (mist ? 2.5 : 3.5)) * smoothstep(0.0, 0.3, vA) * (mist ? 0.08 * near : 0.38);
    if (a < 0.004) discard;
    float sh = sunShadow(vW, vec3(0, 0, 1));
    vec3 v = normalize(uCam - vW);
    float fwd = pow(max(dot(-v, uSunDir), 0.0), 6.0);       // droplets glow when backlit
    vec3 col = vec3(0.90, 0.94, 0.95) * (uSunCol * (0.55 + 1.4 * fwd) * sh + uSkyAmb * 1.25);
    o = vec4(fog(col, vW), a);
}
"""

    // ------------------------------------------------------------------ ACTV exhaust smoke (instanced soft puffs)
    const val SMOKE_VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aInfo; layout(location=3) in vec2 aCorner;
uniform mat4 uVP; uniform vec3 uCam;
out vec3 vW; out vec2 vC; out float vA; out float vDark; out vec2 vT;
void main() {
    float dist = distance(aPos, uCam);
    // puffs right in front of the lens are the expensive ones (full-screen overdraw): fade them out and drop them
    float a = aInfo.y * smoothstep(3.0, 9.0, dist);
    if (a < 0.004) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; }
    vec3 view = (aPos - uCam) / dist;
    vec3 side = normalize(cross(view, vec3(0.0, 0.0, 1.0)));
    vec3 up = cross(side, view);
    vec3 p = aPos + (side * aCorner.x + up * aCorner.y) * aInfo.x;
    vW = p; vC = aCorner; vA = a; vDark = aInfo.z;
    vT = aCorner * 0.18 + aPos.xy * 0.05;
    gl_Position = uVP * vec4(p, 1.0);
}
"""
    val SMOKE_FS = """#version 300 es
precision highp float;
$COMMON
uniform sampler2D uFoamT;
in vec3 vW; in vec2 vC; in float vA; in float vDark; in vec2 vT;
out vec4 o;
void main() {
    float r = dot(vC, vC);
    float d = r * (0.8 + 0.5 * texture(uFoamT, vT).b);
    if (d > 1.0) discard;
    float a = (1.0 - d) * (1.0 - d) * vA;
    vec3 base = mix(vec3(0.62, 0.63, 0.64), vec3(0.16, 0.16, 0.17), vDark);
    o = vec4(fog(base * (uSunCol * 0.55 + uSkyAmb * 1.2), vW), a);
}
"""

    // ------------------------------------------------------------------ foam trails behind the ACTV boats
    const val TRAIL_VS = """#version 300 es
layout(location=0) in vec2 aPos; layout(location=1) in vec3 aInfo;
uniform mat4 uVP;
out vec3 vW; out vec3 vInfo;
void main() { vW = vec3(aPos, 0.07); vInfo = aInfo; gl_Position = uVP * vec4(vW, 1.0); }
"""
    val TRAIL_FS = """#version 300 es
precision highp float;
$COMMON
uniform sampler2D uFoamT;
in vec3 vW; in vec3 vInfo;
out vec4 o;
void main() {
    float age = vInfo.x, across = abs(vInfo.y), str = vInfo.z;
    // churned core right behind the stern, breaking up into streaks and patches as it ages and spreads
    float n1 = texture(uFoamT, vW.xy * 0.069).r, n2 = texture(uFoamT, vW.xy * 0.26 + 0.37).g;
    float core = smoothstep(1.0, 0.25 + 0.4 * age, across);
    float edge = smoothstep(0.55, 0.95, across) * smoothstep(1.0, 0.92, across) * 0.6;
    float breakup = smoothstep(0.25 + 0.55 * age, 0.85, n1 * 0.6 + n2 * 0.4);
    float a = (core + edge) * breakup * str * pow(1.0 - age, 1.6) * 0.75;
    if (a < 0.01) discard;
    vec3 col = vec3(0.93, 0.95, 0.96) * (uSunCol * 0.6 + uSkyAmb * 1.3);
    o = vec4(fog(col, vW), min(a, 0.85));
}
"""

    /** "Realistico" boat trail: churned water at the stern, a turbulent centre wake that breaks into streaks along
     *  the direction of travel, lacy foam lines where the wake meets calm water, and the long glassy slick the wake
     *  leaves behind once the foam has gone. Soft edges everywhere: no ribbon. */
    val TRAIL_REAL_FS = """#version 300 es
precision highp float;
$COMMON
uniform sampler2D uFoamT;
in vec3 vW; in vec3 vInfo;
out vec4 o;
void main() {
    float age = vInfo.x, side = vInfo.y, across = abs(side), str = vInfo.z;
    float fade = pow(1.0 - age, 1.3);
    // flow-aligned coordinates: along the trail (its age) and across it, so the foam streaks run with the wake
    float streak = texture(uFoamT, vec2(age * 7.0, side * 0.75 + 0.13)).r;
    float lace = texture(uFoamT, vW.xy * 0.21 + vec2(0.31, 0.7)).g;
    float fine = texture(uFoamT, vW.xy * 0.73).r;
    float wob = (lace - 0.5) * 0.22 + (streak - 0.5) * 0.18;
    float a2 = across + wob;
    // churned white water right behind the stern
    float churn = (1.0 - smoothstep(0.0, 0.07, age)) * (1.0 - smoothstep(0.25, 1.0, a2));
    // turbulent centre wake: narrower than the trail, torn into streaks, thinning out with age
    float centre = (1.0 - smoothstep(0.15, 0.7, a2)) * smoothstep(0.3 + 0.55 * age, 0.85, streak * 0.55 + lace * 0.45);
    // lacy lines at the outer edges, broken up
    float d = (a2 - 0.84) / 0.075;
    float edge = exp(-d * d) * smoothstep(0.4, 0.75, fine * 0.5 + streak * 0.5) * (1.0 - age);
    float foam = clamp(churn * 1.3 + centre + edge * 0.6, 0.0, 1.0) * str * fade;
    foam *= smoothstep(0.35, 0.65, fine * 0.4 + lace * 0.6 + churn);        // sparkle holes in the foam
    // the slick: smoothed, darker water that lingers long after the foam has gone
    float slick = (1.0 - smoothstep(0.35, 1.0, a2)) * (1.0 - 0.6 * age) * 0.16 * min(1.0, str * 1.5);
    float a = max(foam * 0.88, slick);
    a *= 1.0 - smoothstep(0.82, 1.0, across);
    if (a < 0.008) discard;
    vec3 foamCol = vec3(0.92, 0.95, 0.96) * (uSunCol * 0.55 + uSkyAmb * 1.3);
    vec3 slickCol = vec3(0.02, 0.05, 0.06) * (uSkyAmb * 2.2 + uSunCol * 0.1);
    vec3 col = mix(slickCol, foamCol, clamp(foam * 0.88 / max(a, 1e-4), 0.0, 1.0));
    o = vec4(fog(col, vW), min(a, 0.9));
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

    // ------------------------------------------------------------------ sky (HDR)
    const val SKY_VS = """#version 300 es
layout(location=0) in vec2 aPos;
out vec2 vP;
void main() { vP = aPos; gl_Position = vec4(aPos, 0.9999, 1.0); }
"""
    const val SKY_FS = """#version 300 es
precision highp float;
in vec2 vP;
uniform mat4 uInvVP; uniform vec3 uSunDir; uniform vec3 uFogCol; uniform vec3 uSkyZenith; uniform vec3 uSunCol; uniform float uMirrorSky;
uniform float uTime; uniform float uCloudCover; uniform float uHazeH; uniform vec2 uWind;
uniform float uStars; uniform float uDusk; uniform vec3 uTrueSun;
out vec4 o;
float h31(vec3 p) { p = fract(p * vec3(.1031, .1030, .0973)); p += dot(p, p.yxz + 33.33); return fract((p.x + p.y) * p.z); }
float h21(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * .1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
float vn(vec2 p) { vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(h21(i), h21(i + vec2(1, 0)), f.x), mix(h21(i + vec2(0, 1)), h21(i + vec2(1, 1)), f.x), f.y); }
float fbm5(vec2 p) { float a = 0.5, s = 0.0; for (int i = 0; i < 5; i++) { s += a * vn(p); p = p * 2.02 + vec2(1.7, 9.2); a *= 0.5; } return s; }
float fbm3(vec2 p) { float a = 0.5, s = 0.0; for (int i = 0; i < 3; i++) { s += a * vn(p); p = p * 2.03 + vec2(4.1, 1.3); a *= 0.5; } return s; }
// fair-weather cumulus: domain-warped fbm thresholded into puffy cells with soft, broken edges
float cumulus(vec2 q) {
    vec2 w = vec2(fbm3(q * 0.55 + 3.1), fbm3(q * 0.55 + 7.7));
    float d = fbm5(q + (w - 0.5) * 1.3);
    return smoothstep(0.58 - uCloudCover * 0.18, 0.80, d);
}
void main() {
    vec4 a = uInvVP * vec4(vP, -1.0, 1.0); vec4 b = uInvVP * vec4(vP, 1.0, 1.0);
    vec3 d = normalize(b.xyz / b.w - a.xyz / a.w);
    if (uMirrorSky > 0.5) d.z = -d.z;
    float h = clamp(d.z, -0.2, 1.0);
    vec3 col = mix(uFogCol * 1.15, uSkyZenith, pow(max(h, 0.0), 0.5));
    float s = max(dot(d, uSunDir), 0.0);
    col += uSunCol * (pow(s, 1800.0) * 60.0 + pow(s, 64.0) * 0.5 + pow(s, 6.0) * 0.18);
    // around sunset and dawn: the glowing band on the horizon under the sun, and the pink belt opposite it
    if (uDusk > 0.001) {
        vec2 sd = normalize(uTrueSun.xy + 1e-4); vec2 hd = normalize(d.xy + 1e-4);
        float toward = max(dot(hd, sd), 0.0);
        float low = 1.0 - smoothstep(0.0, 0.32, h);
        col += vec3(1.0, 0.42, 0.14) * uDusk * low * (0.25 + 0.9 * pow(toward, 3.0)) * 0.55;
        col += vec3(0.55, 0.30, 0.42) * uDusk * (1.0 - smoothstep(0.02, 0.25, h)) * pow(max(-dot(hd, sd), 0.0), 2.0) * 0.18;
    }
    // night: stars (fewer near the hazy horizon), twinkling
    if (uStars > 0.001 && d.z > 0.0) {
        vec3 sp = d * 420.0; vec3 cell = floor(sp);
        float hs = h31(cell);
        if (hs > 0.9975) {
            vec3 c = cell + 0.5 + (vec3(h31(cell + 1.3), h31(cell + 2.7), h31(cell + 5.1)) - 0.5) * 0.6;
            float r = length(sp - c);
            float mag = (hs - 0.9975) / 0.0025;
            float tw = 0.75 + 0.25 * sin(uTime * (2.0 + 5.0 * h31(cell + 9.0)) + hs * 60.0);
            col += vec3(0.85 + 0.15 * h31(cell + 4.0), 0.88, 1.0) * smoothstep(0.55, 0.0, r) * (0.3 + 1.6 * mag * mag) * tw * uStars * smoothstep(0.03, 0.3, d.z);
        }
        // the Milky Way, faint
        col += vec3(0.010, 0.011, 0.016) * uStars * smoothstep(0.55, 0.85, fbm3(d.xy / (d.z + 0.35) * 2.2 + 3.0)) * smoothstep(0.05, 0.4, d.z);
    }
    if (d.z > 0.015) {
        // cloud layer ~1.5 km up, drifting with the wind
        vec2 P = d.xy / d.z * 1.5;                      // km on the cloud deck
        vec2 wind = uWind * uTime * 0.004;
        vec2 q = P * 1.1 + wind;
        float den = cumulus(q);
        // thin cirrus higher up
        vec2 cq = d.xy / d.z * 3.0 * vec2(0.35, 1.4) + wind * 0.6;
        float ci = smoothstep(0.55, 0.85, fbm5(cq + 11.0)) * 0.35;
        if (den > 0.001 || ci > 0.001) {
            // self-shadowing: density a little way towards the sun darkens the cloud; thin edges glow (silver lining)
            float toward = cumulus(q + normalize(uSunDir.xy + 1e-4) * 0.06);
            float lit = clamp(1.0 - toward * 0.85, 0.0, 1.0);
            vec3 sunLit = uSunCol * (0.55 + 0.45 * lit) * 0.42;
            vec3 shade = mix(uSkyZenith * 0.9, uFogCol * 0.85, 0.5) * 0.85;
            float edge = 1.0 - smoothstep(0.0, 0.6, den);
            vec3 cc = mix(shade, sunLit + shade * 0.4, lit) + uSunCol * pow(s, 8.0) * edge * 0.9;
            float far = smoothstep(0.015, 0.22, d.z);   // clouds near the horizon melt into the haze
            col = mix(col, cc, den * 0.92 * far);
            col = mix(col, uSunCol * 0.28 + uSkyZenith * 0.5, ci * far * (1.0 - den));
        }
    }
    // haze band on the horizon, as tall as the fogged skyline at the render distance: the fogged buildings at the
    // edge melt into it instead of standing out as flat grey cut-outs against the blue
    col = mix(col, uFogCol * 1.15, (1.0 - smoothstep(0.0, max(uHazeH, 0.005), h)) * 0.95);
    col = mix(col, uFogCol * 1.15, smoothstep(0.02, -0.15, d.z));
    o = vec4(col, 1.0);
}
"""

    // ------------------------------------------------------------------ post: bright pass, blur, composite
    const val POST_VS = """#version 300 es
layout(location=0) in vec2 aPos;
out vec2 vUv;
void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
"""
    const val DIM_FS = """#version 300 es
precision mediump float;
uniform vec4 uCol; in vec2 vUv; out vec4 o;
void main() { o = uCol; }
"""
    const val BRIGHT_FS = """#version 300 es
precision mediump float;
in vec2 vUv; uniform sampler2D uTex; uniform vec2 uTexel;
out vec4 o;
void main() {
    vec3 c = (texture(uTex, vUv + uTexel * vec2(-1, -1)).rgb + texture(uTex, vUv + uTexel * vec2(1, -1)).rgb
            + texture(uTex, vUv + uTexel * vec2(-1, 1)).rgb + texture(uTex, vUv + uTexel * vec2(1, 1)).rgb) * 0.25;
    float l = max(c.r, max(c.g, c.b));
    float k = clamp((l - 2.2) / 2.5, 0.0, 1.0);
    o = vec4(c * k * k, 1.0);
}
"""
    // ------------------------------------------------------------------ "Semplice" water (low-end phones)
    // Self-contained on purpose: a flat plane at sea level (no wave displacement, nothing read from the wake
    // simulation or the reflection pass, which don't run in this mode). Two lookups of the baked ripple map, the sky
    // projected by the ripple normal (a gradient, not a second render of the city), an anti-aliased sun glint, fog.
    const val WATER_FLAT_VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uVP; uniform vec3 uCenter;
out vec3 vW;
void main() { vW = vec3(aPos.xy + uCenter.xy, 0.0); gl_Position = uVP * vec4(vW, 1.0); }
"""
    val WATER_FLAT_FS = """#version 300 es
precision highp float;
$COMMON
in vec3 vW;
uniform sampler2D uWaterN; uniform float uWaveAmp;
out vec4 o;
void main() {
    vec2 p = vW.xy;
    // two scales of baked ripples drifting against each other: a calm canal surface
    vec2 r = (texture(uWaterN, p * 0.045 + uTime * vec2(0.010, 0.006)).rg * 2.0 - 1.0) * 0.6
           + (texture(uWaterN, p * 0.11 - uTime * vec2(0.012, 0.017)).rg * 2.0 - 1.0) * 0.4;
    vec3 n = normalize(vec3(-r * 0.22, 1.0));
    vec3 v = normalize(uCam - vW);
    float nv = clamp(dot(n, v), 0.0, 1.0);
    float fx5 = clamp(1.0 - nv, 0.0, 1.0); float fres = 0.03 + 0.97 * fx5 * fx5 * fx5 * fx5 * fx5;
    vec3 rd = reflect(-v, n);
    vec3 sky = mix(uFogCol, uSkyZenith, clamp(rd.z * 1.6, 0.0, 1.0));
    // Venetian canal water: green-teal, a little milky
    vec3 deep = vec3(0.045, 0.115, 0.095) * (uSkyAmb * 1.5 + uSunCol * 0.32);
    vec3 col = mix(deep, sky * 0.9, clamp(fres, 0.0, 1.0) * 0.8);
    // sun glint, widened where the ripples are finer than a pixel (no sparkle)
    // glint widens with distance instead of with screen derivatives (no fwidth: undefined at triangle edges on some GPUs)
    float pw = mix(260.0, 40.0, clamp(length(uCam - vW) / 150.0, 0.0, 1.0));
    vec3 h = normalize(uSunDir + v);
    col += uSunCol * pow(clamp(dot(n, h), 0.0, 1.0), pw) * 1.5 * (pw / 260.0);
    o = vec4(fog(clamp(col, vec3(0.0), vec3(30.0)), vW), 1.0);
}
"""

    // ------------------------------------------------------------------ barene grass (instanced clumps)
    const val GRASS_VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=2) in vec4 aInst;
uniform mat4 uVP; uniform vec3 uCam; uniform vec2 uWind; uniform float uTime; uniform float uRadius;
out vec3 vW; out float vZ; out float vSeed; out float vBlade;
void main() {
    float s = aInst.w; float ang = s * 43.98;
    float bl = fract(sin(float(gl_VertexID / 3) * 12.9898 + s * 78.233) * 43758.5453);   // this blade
    float c = cos(ang), sn = sin(ang);
    vec2 lp = vec2(aPos.x * c - aPos.y * sn, aPos.x * sn + aPos.y * c);
    float d = distance(aInst.xy, uCam.xy);
    float h = aInst.z * (1.0 - smoothstep(uRadius * 0.85, uRadius, d));    // sinks away at the hand-over edge
    float z = aPos.z;
    float w = length(uWind);
    vec2 wd = w > 0.05 ? uWind / w : vec2(0.8, 0.6);
    // gusts rolling across the field downwind + each blade's own flutter; stronger wind = bent further over
    float gust = sin(dot(aInst.xy, wd) * 0.32 - uTime * (1.1 + w * 0.22)) * 0.5 + 0.5;
    float flutter = sin(uTime * (2.2 + s * 1.9) + s * 40.0);
    float bend = (0.06 + w * 0.045) * (0.45 + gust);
    // the taller the stem, the further it bends and the more it waves
    vec2 off = (wd * bend + vec2(-wd.y, wd.x) * flutter * (0.03 + w * 0.006)) * z * z * (0.2 + 1.5 * h);
    vec3 p = vec3(aInst.xy + lp * (0.8 + 0.4 * h) + off, -0.18 + z * (h * (0.75 + 0.5 * bl) + 0.18));
    vW = p; vZ = z; vSeed = s; vBlade = bl;
    gl_Position = uVP * vec4(p, 1.0);
}
"""
    val GRASS_FS = """#version 300 es
precision highp float;
$COMMON
in vec3 vW; in float vZ; in float vSeed; in float vBlade;
out vec4 o;
void main() {
    // the barene as they look most of the year: grey-green, straw, pale ochre, a little rust of salicornia
    vec3 c0 = vec3(0.30, 0.33, 0.22), c1 = vec3(0.42, 0.43, 0.34), c2 = vec3(0.55, 0.50, 0.33), c3 = vec3(0.62, 0.53, 0.30), c4 = vec3(0.45, 0.26, 0.18);
    float s = fract(vSeed * 0.7 + vBlade * 0.45);
    vec3 col = s < 0.3 ? mix(c0, c1, s / 0.3) : (s < 0.62 ? mix(c1, c2, (s - 0.3) / 0.32) : (s < 0.93 ? mix(c2, c3, (s - 0.62) / 0.31) : c4));
    col *= 0.85 + 0.3 * vBlade;                                // blade to blade
    col *= 0.5 + 0.6 * vZ;                                     // darker, wet bases; sunlit tips
    vec3 lit = col * (uSkyAmb * 1.25 + uSunCol * (0.35 + 0.45 * max(uSunDir.z, 0.0)));
    lit += uSunCol * col * 0.25 * vZ * vZ;                       // light through the blades
    o = vec4(fog(lit, vW), 1.0);
}
"""

    // ------------------------------------------------------------------ gulls (instanced, wing beat in the vertex shader)
    const val GULL_VS = """#version 300 es
layout(location=0) in vec4 aPos; layout(location=2) in vec4 aI0; layout(location=4) in vec4 aI1;
uniform mat4 uVP;
out vec3 vW; out float vPart; out float vSpan;
void main() {
    vec3 p = aPos.xyz * 1.1; float part = aPos.w;
    float phase = aI1.x, amp = aI1.y, fold = aI1.z, bank = aI1.w;
    float span = abs(p.y); float sg = p.y < 0.0 ? -1.0 : 1.0;
    if (part > 0.5 && part < 2.5) {
        float sh = 0.06, el = 0.40;
        // the arm about the shoulder, the hand about the elbow, a little behind it (the wing tip lags)
        float a1 = amp * 0.75 * sin(phase);
        float a2 = a1 + amp * 0.45 * sin(phase - 0.7);
        float r1 = min(span, el) - sh;
        float yy = sh + r1 * cos(a1); float zz = p.z + r1 * sin(a1);
        if (span > el) { float r2 = span - el; yy += r2 * cos(a2); zz += r2 * sin(a2); }
        vec3 spread = vec3(p.x, sg * yy, zz);
        // folded: laid back along the body, the tips crossed over the tail
        vec3 folded = vec3(p.x - (span - sh) * 0.62, sg * (sh + (span - sh) * 0.10), p.z + 0.03 + (span - sh) * 0.04);
        p = mix(spread, folded, fold);
    }
    float cb = cos(bank), sb = sin(bank);
    p = vec3(p.x, p.y * cb - p.z * sb, p.y * sb + p.z * cb);
    float ch = cos(aI0.w), shh = sin(aI0.w);
    vec3 w = vec3(aI0.x + p.x * ch - p.y * shh, aI0.y + p.x * shh + p.y * ch, aI0.z + p.z);
    vW = w; vPart = part; vSpan = span;
    gl_Position = uVP * vec4(w, 1.0);
}
"""
    val GULL_FS = """#version 300 es
precision highp float;
$COMMON
in vec3 vW; in float vPart; in float vSpan;
out vec4 o;
void main() {
    vec3 n = normalize(cross(dFdx(vW), dFdy(vW)));
    vec3 v = normalize(uCam - vW);
    if (dot(n, v) < 0.0) n = -n;
    vec3 col = vec3(0.88, 0.89, 0.88);                                   // white head and body
    if (vPart > 0.5 && vPart < 2.5) {
        col = n.z > 0.0 ? vec3(0.55, 0.60, 0.65) : vec3(0.82, 0.83, 0.83); // grey mantle above, white below
        if (vSpan > 0.60) col = vec3(0.05);                              // black wing tips
    }
    if (vPart > 2.5) col = vec3(0.90, 0.72, 0.12);                       // yellow bill
    col = lin(col);
    float dif = max(dot(n, uSunDir), 0.0);
    vec3 lit = col * (uSkyAmb * 1.2 + uSunCol * 0.5 * dif);
    o = vec4(fog(lit, vW), 1.0);
}
"""

    const val COPY_FS = """#version 300 es
precision mediump float;
in vec2 vUv; uniform sampler2D uTex; uniform vec3 uTint;
out vec4 o;
void main() { o = vec4(texture(uTex, vUv).rgb * uTint, 1.0); }
"""
    const val BLUR_FS = """#version 300 es
precision mediump float;
in vec2 vUv; uniform sampler2D uTex; uniform vec2 uDir;
out vec4 o;
void main() {
    vec3 c = texture(uTex, vUv).rgb * 0.227;
    c += (texture(uTex, vUv + uDir * 1.385).rgb + texture(uTex, vUv - uDir * 1.385).rgb) * 0.316;
    c += (texture(uTex, vUv + uDir * 3.231).rgb + texture(uTex, vUv - uDir * 3.231).rgb) * 0.070;
    o = vec4(c, 1.0);
}
"""
    const val COMPOSITE_FS = """#version 300 es
precision highp float;
in vec2 vUv; uniform sampler2D uScene; uniform sampler2D uBloom; uniform vec2 uTexel; uniform float uExposure;
uniform float uSharpen; uniform float uBloomK;
out vec4 o;
vec3 aces(vec3 x) { const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14; return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0); }
float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
void main() {
    vec3 cM = texture(uScene, vUv).rgb;
    vec3 cN = texture(uScene, vUv + vec2(0.0, uTexel.y)).rgb, cS = texture(uScene, vUv - vec2(0.0, uTexel.y)).rgb;
    vec3 cE = texture(uScene, vUv + vec2(uTexel.x, 0.0)).rgb, cW = texture(uScene, vUv - vec2(uTexel.x, 0.0)).rgb;
    float lM = luma(cM / (1.0 + cM)), lN = luma(cN / (1.0 + cN)), lS = luma(cS / (1.0 + cS)), lE = luma(cE / (1.0 + cE)), lW = luma(cW / (1.0 + cW));
    float range = max(max(lN, lS), max(max(lE, lW), lM)) - min(min(lN, lS), min(min(lE, lW), lM));
    vec3 col = cM;
    if (range > 0.08) {
        vec2 dir = normalize(vec2(-((lN + lS) - (lE + lW)), (lN - lS) + (lE - lW) + 1e-4)) * uTexel;
        col = (texture(uScene, vUv + dir * 0.5).rgb + texture(uScene, vUv - dir * 0.5).rgb + cM) / 3.0;
    }
    // contrast-adaptive sharpening: restores the crispness lost to the internal resolution and FXAA
    if (uSharpen > 0.0) {
        vec3 avg = (cN + cS + cE + cW) * 0.25;
        float amt = uSharpen * (1.0 - smoothstep(0.15, 0.6, range));     // less on hard edges (no halos)
        col = max(col + (col - avg) * amt * 1.6, vec3(0.0));
    }
    // a branch, not a multiply: with bloom off the bloom buffer may hold garbage, and NaN * 0 is still NaN
    if (uBloomK > 0.0) { vec3 bl = texture(uBloom, vUv).rgb; if (!any(isnan(bl)) && !any(isinf(bl))) col += bl * 0.35 * uBloomK; }
    // last line of defence: a NaN pixel (it would be black) takes its neighbours' colour
    if (any(isnan(col)) || any(isinf(col))) { vec3 nb = (cN + cS + cE + cW) * 0.25; col = (any(isnan(nb)) || any(isinf(nb))) ? vec3(0.3, 0.32, 0.34) : nb; }
    col = clamp(col, vec3(0.0), vec3(64.0));
    col = aces(col * uExposure);
    col = mix(vec3(luma(col)), col, 1.08);
    col *= vec3(1.02, 1.0, 0.97);
    vec2 vc = vUv - 0.5;
    col *= 1.0 - dot(vc, vc) * 0.35;
    o = vec4(pow(clamp(col, vec3(0.0), vec3(1.0)), vec3(1.0 / 2.2)), 1.0);
}
"""
}
