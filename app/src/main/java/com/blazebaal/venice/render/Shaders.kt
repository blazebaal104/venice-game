// GLSL ES 3.00 shaders: PBR-lite lighting in linear HDR, photo-textured materials (texture arrays),
// planar-reflected water on the shared wave field, wake foam, spray particles, filmic post-processing.
package com.blazebaal.venice.render

import com.blazebaal.venice.core.Waves

object Shaders {
    private val COMMON = """
uniform vec3 uSunDir; uniform vec3 uSunCol; uniform vec3 uSkyAmb; uniform vec3 uGroundAmb; uniform vec3 uFogCol; uniform vec3 uSkyZenith;
uniform float uFogDens; uniform vec3 uCam; uniform float uTime;
uniform highp sampler2DShadow uShadow; uniform mat4 uLightVP; uniform float uShadowOn;
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
    if (c.x < 0.0 || c.y < 0.0 || c.x > 1.0 || c.y > 1.0 || c.z > 1.0) return 1.0;
    float d = c.z - 0.0008; vec2 t = vec2(1.0 / 2048.0);
    float s = texture(uShadow, vec3(c.xy + vec2(-0.5, -0.5) * t, d)) + texture(uShadow, vec3(c.xy + vec2(0.5, -0.5) * t, d))
            + texture(uShadow, vec3(c.xy + vec2(-0.5, 0.5) * t, d)) + texture(uShadow, vec3(c.xy + vec2(0.5, 0.5) * t, d));
    vec2 e = smoothstep(vec2(0.0), vec2(0.08), c.xy) * smoothstep(vec2(1.0), vec2(0.92), c.xy);
    return mix(1.0, s * 0.25, e.x * e.y);
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
    float d = nh * nh * (a2 - 1.0) + 1.0; float D = a2 / (PI * d * d);
    float k = (rough + 1.0) * (rough + 1.0) / 8.0;
    float G = (nl / (nl * (1.0 - k) + k)) * (nv / (nv * (1.0 - k) + k));
    float F = f0 + (1.0 - f0) * pow(1.0 - vh, 5.0);
    vec3 spec = uSunCol * (D * G * F / max(4.0 * nl * nv, 1e-3)) * nl * sh;
    vec3 hemi = mix(uGroundAmb, uSkyAmb, n.z * 0.5 + 0.5);
    vec3 diff = alb * (uSunCol * nl * sh + hemi * ao) * (1.0 - F * 0.5);
    vec3 r = reflect(-v, n);
    // reflected environment: sky above the horizon, the dark canal/paving below it (soffits mirror water, not sky)
    vec3 env = r.z >= 0.0 ? mix(uFogCol, uSkyZenith, clamp(r.z * 1.5, 0.0, 1.0)) : mix(uFogCol * 0.5, uFogCol * 0.18, clamp(-r.z * 3.0, 0.0, 1.0));
    float Fa = f0 + (1.0 - f0) * pow(1.0 - nv, 5.0);
    return diff + spec + env * Fa * (1.0 - rough) * ao * 0.9;
}
vec3 fog(vec3 c, vec3 wpos) {
    float d = length(wpos - uCam);
    float f = 1.0 - exp(-pow(d * uFogDens, 1.4));
    f = max(f, (1.0 - exp(-d * 0.00035)) * 0.35);   // light lagoon haze
    return mix(c, uFogCol * 1.15, clamp(f, 0.0, 1.0));
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
out vec4 o;
void main() {
    if (vW.z < uClipZ) discard;
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
    val FACADE_FS = """#version 300 es
precision highp float;
$COMMON
in vec3 vW; in vec3 vN; in vec2 vUv; in vec4 vA;
out vec4 o;

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
        vec2 q = vUv;
        if (hash11(seed * 91.0) > 0.5) q = q.yx;
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
        // ---- wall
        float segLen = vA.x;
        float u = vUv.x; float z = vUv.y;
        float rel = z - 1.1;
        vec2 uv = vec2(u, z);
        float r1 = hash11(seed * 17.31), r2 = hash11(seed * 5.77), r3 = hash11(seed * 29.1);
        vec3 tint = paint(seed);
        float layer;
        if (r1 < 0.46) layer = floor(r2 * 6.0);              // painted plaster
        else if (r1 < 0.78) layer = 6.0 + floor(r2 * 5.0);   // plaster with exposed brick
        else if (r1 < 0.93) layer = 11.0 + floor(r2 * 4.0);  // fair-faced brick
        else layer = 15.0;                                    // stone-clad palazzo (Istrian)
        vec4 m = matSample(uv / layerScale(layer), layer, tint, tn);
        if (layer > 5.5 && layer < 10.5) {
            vec3 tnP; vec4 pl = matSample(uv / layerScale(0.0), floor(r3 * 3.0), tint, tnP);
            float zone = smoothstep(0.5, 0.62, fbm(uv * 0.12 + seed * 13.0) + smoothstep(5.0, 0.0, rel) * 0.2);
            m = mix(pl, m, zone); tn = mix(tnP, tn, zone);
        }
        alb = m.rgb; rough = m.a;
        float streak = noise(vec2(u * 1.9 + seed * 40.0, z * 0.07)) * smoothstep(0.2, 0.9, fbm(vec2(u * 0.4, z * 0.15) + 3.0));
        alb *= 1.0 - 0.22 * streak;
        alb *= 0.88 + 0.24 * fbm(uv * 0.2 + seed * 3.0);
        float groundH = 4.0; float floorH = 3.25;
        if (r3 > 0.72 && rel < groundH - 0.2) {
            vec3 tnA; vec4 as2 = matSample(uv / layerScale(17.0), 17.0, vec3(0.95), tnA);
            alb = as2.rgb; rough = as2.a; tn = tnA;
        }
        // ---- openings
        float fl = rel < groundH ? 0.0 : 1.0 + floor((rel - groundH) / floorH);
        float fz = rel < groundH ? rel : mod(rel - groundH, floorH);
        float nwin = max(1.0, floor(segLen / 2.9));
        float spacing = segLen / nwin;
        float idx = floor(u / spacing);
        float lu = mod(u, spacing) - spacing * 0.5;
        float wHalf = mix(0.42, 0.62, hash11(seed * 13.0));
        float archType = floor(hash11(seed * 7.0) * 4.0); // 0 rect, 1 round, 2 gothic pointed, 3 gothic ogee
        float inWin = 0.0, frame = 0.0, shutter = 0.0, balcony = 0.0;
        bool hasWin = segLen > 2.2 && rel > -0.5;
        bool poly = fl > 0.5 && fl < 1.5 && segLen > 13.0 && abs(u - segLen * 0.5) < 2.3;
        if (poly) {
            // piano nobile: central polifora (four arched lights on columns) above a stone balcony
            float gx = u - segLen * 0.5 + 2.0;
            float lx = fract(gx) - 0.5;
            float hw = 0.36;
            float y0 = 0.9, y1 = 2.45;
            float head = archType > 1.5 ? y1 + hw * 1.2 * (1.0 - pow(abs(lx) / hw, 0.75)) : y1 + sqrt(max(hw * hw - lx * lx, 0.0));
            float inG = step(0.0, gx) * step(gx, 4.0);
            inWin = step(abs(lx), hw) * step(y0, fz) * step(fz, head) * inG;
            frame = step(abs(lx), 0.5) * (1.0 - step(abs(lx), hw)) * step(y0, fz) * step(fz, y1 + 0.55) * inG;
            balcony = step(abs(u - segLen * 0.5), 2.4) * step(0.0, fz) * step(fz, 0.9);
        } else if (hasWin) {
            float y0, y1;
            if (fl < 0.5) {
                float door = step(0.62, hash21(vec2(idx, seed * 99.0)));
                y0 = door > 0.5 ? 0.0 : 1.5; y1 = door > 0.5 ? 2.9 : 2.6;
            } else { y0 = 0.95; y1 = 2.5; }
            float ax = abs(lu);
            float head = y1;
            if (archType > 2.5) head = y1 + wHalf * 1.25 * (1.0 - smoothstep(0.0, 1.0, ax / wHalf)) + 0.12 * sin(ax / wHalf * PI);
            else if (archType > 1.5) head = y1 + wHalf * 1.1 * (1.0 - pow(ax / wHalf, 0.8));
            else if (archType > 0.5) head = y1 + sqrt(max(wHalf * wHalf - ax * ax, 0.0));
            float inside = step(ax, wHalf) * step(y0, fz) * step(fz, head);
            float outer = step(ax, wHalf + 0.13) * step(y0 - 0.16, fz) * step(fz, head + 0.13);
            inWin = inside; frame = outer - inside;
            shutter = inside * step(0.58, hash21(vec2(idx * 7.0 + fl, seed * 51.0))) * step(0.5, fl);
        }
        vec3 tnI; vec4 ist = matSample(uv / layerScale(15.0), 15.0, vec3(1.0), tnI);
        alb = mix(alb, ist.rgb * 0.95, frame); tn = mix(tn, tnI, frame); rough = mix(rough, ist.a, frame);
        float scH = fl < 0.5 ? groundH : floorH;
        float sc = step(abs(fz - scH + 0.06), 0.08) * step(0.35, hash11(seed * 3.0));
        alb = mix(alb, ist.rgb, sc);
        if (balcony > 0.5) {
            float bal = step(0.5, fract((u - segLen * 0.5) / 0.19)) * step(0.12, fz) * step(fz, 0.75);
            alb = mix(ist.rgb, ist.rgb * 0.25, bal);
            ao = 0.85;
        }
        if (inWin > 0.5) {
            if (shutter > 0.5) {
                float slat = step(0.5, fract(fz / 0.085));
                vec3 sc2 = r2 > 0.82 ? lin(vec3(0.36, 0.25, 0.18)) : lin(vec3(0.12, 0.24, 0.17));
                vec3 tnS; vec4 sw = matSample(vec2(u * 2.0, z) / layerScale(32.0), 32.0, sc2 * 2.2, tnS);
                alb = sw.rgb * (0.78 + 0.22 * slat); rough = 0.55; tn = tnS;
            } else if (fl < 0.5 && fz < 2.95) {
                alb = lin(vec3(0.2, 0.12, 0.07)); rough = 0.6;           // wooden doors
            } else {
                vec3 vdir = normalize(uCam - vW);
                vec3 r = reflect(-vdir, n);
                vec3 env = mix(uFogCol, uSkyZenith, clamp(r.z * 1.4 + 0.2, 0.0, 1.0));
                float fres = 0.08 + 0.92 * pow(1.0 - max(dot(n, vdir), 0.0), 4.0);
                vec3 interior = lin(vec3(0.05, 0.045, 0.04)) * (0.6 + 0.8 * noise(vec2(idx, fl) * 3.7));
                if (hash21(vec2(idx, fl + seed)) > 0.7) interior = lin(vec3(0.55, 0.50, 0.42)) * 0.35;
                float mull = step(abs(lu), 0.025) * step(0.5, fl) * (poly ? 0.0 : 1.0);
                vec3 glass = interior * (uSkyAmb + uSunCol * 0.2) + env * fres * 0.9
                    + uSunCol * pow(max(dot(reflect(-uSunDir, n), vdir), 0.0), 400.0) * sunShadow(vW, n) * 4.0;
                glass = mix(glass, ist.rgb * (uSkyAmb + uSunCol * 0.4), mull);
                o = vec4(fog(glass, vW), 1.0);
                return;
            }
        }
        float tide = 0.2 + 0.15 * noise(vec2(u * 0.6, 1.0));
        float algae = smoothstep(tide + 0.25, tide - 0.05, z);
        alb = mix(alb, lin(vec3(0.10, 0.14, 0.08)), algae); rough = mix(rough, 0.25, algae);
        alb = mix(alb, alb * vec3(0.72, 0.74, 0.65) + 0.04, smoothstep(tide + 1.5, tide + 0.3, z) * 0.6);
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
uniform sampler2D uMask; uniform sampler2D uClass; uniform float uMarshPass;
out vec4 o;
void main() {
    if (vW.z < uClipZ) discard;
    vec2 uv = (vL + 1.0) / 258.0;
    vec2 mk = texture(uMask, uv).rg;
    float cov = uMarshPass > 0.5 ? mk.g : mk.r;
    if (cov < 0.5) discard;
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
uniform sampler2D uWake; uniform vec3 uWakeO; uniform vec2 uWakeDir;
out vec4 o;
// bubble cells: distance to the nearest of a jittered grid of points (Worley F1)
float cellF(vec2 x) {
    vec2 i = floor(x), f = fract(x); float d = 9.0;
    for (int y = -1; y <= 1; y++) for (int xx = -1; xx <= 1; xx++) {
        vec2 c = vec2(float(xx), float(y));
        vec2 h = fract(sin(vec2(dot(i + c, vec2(127.1, 311.7)), dot(i + c, vec2(269.5, 183.3)))) * 43758.5453);
        d = min(d, length(c + h - f));
    }
    return d;
}
void main() {
    vec2 p = vW.xy;
    vec2 g = waveG(p, uTime, uWaveAmp) * max(vFade, 0.35);
    // simulated wake: slope from the heightfield, foam from its second channel
    vec2 wuv = (p - uWakeO.xy) / uWakeO.z;
    float foam = 0.0; float wakeE = 0.0; float milkA = 0.0; float nearH = 0.0; vec2 wakeSlope = vec2(0.0);
    if (wuv.x > 0.004 && wuv.y > 0.004 && wuv.x < 0.996 && wuv.y < 0.996) {
        float wn = float(textureSize(uWake, 0).x); float e = 1.0 / wn; float cellM = uWakeO.z / wn;
        float hx = texture(uWake, wuv + vec2(e, 0.0)).r - texture(uWake, wuv - vec2(e, 0.0)).r;
        float hy = texture(uWake, wuv + vec2(0.0, e)).r - texture(uWake, wuv - vec2(0.0, e)).r;
        float border = smoothstep(0.0, 0.06, min(min(wuv.x, wuv.y), min(1.0 - wuv.x, 1.0 - wuv.y)));
        vec2 wg = vec2(hx, hy) / (2.0 * cellM) * border;
        float hc = texture(uWake, wuv).r;
        float lapW = (texture(uWake, wuv + vec2(e, 0.0)).r + texture(uWake, wuv - vec2(e, 0.0)).r
                    + texture(uWake, wuv + vec2(0.0, e)).r + texture(uWake, wuv - vec2(0.0, e)).r - 4.0 * hc) / (cellM * cellM);
        wakeE = clamp((abs(hc) * 2.5 + length(wg) * 3.0 + clamp(-lapW * 8.0, 0.0, 1.0)) * border, 0.0, 1.5);   // capillary chop on crests
        float fo = texture(uWake, wuv).g;
        g += wg * mix(5.0, 1.5, smoothstep(0.3, 0.8, fo)); wakeSlope = wg;    // strong slope on the clean arms, not on the wash
        vec4 wt = texture(uWake, wuv);
        foam = wt.g * border; milkA = wt.b * border; nearH = wt.a * border;
        wakeE = max(wakeE, nearH * 1.3) + max(hc, 0.0) * 4.0;   // churn next to the hull and on the stern mound
    }
    // frame aligned with the wash: along / across the boat's track
    vec2 al = uWakeDir, ac = vec2(-uWakeDir.y, uWakeDir.x);
    vec2 pw = vec2(dot(p, al) * 0.2, dot(p, ac));        // stretched 3x along the track -> long turbulent streaks
    // the boil: churned, bulging water where the wash is fresh (just behind the outboard) - strong animated normals
    if (foam > 0.35) {
        float bs = smoothstep(0.35, 0.9, foam);
        vec2 bq = p * 3.5 + uWakeDir * uTime * 2.0;           // streams aft with the wash
        float b0 = noise(bq) + 0.5 * noise(bq * 2.1 + 3.7) + 0.25 * noise(bq * 4.3 + 1.1);
        float bx = noise(bq + vec2(0.06, 0.0)) + 0.5 * noise((bq + vec2(0.06, 0.0)) * 2.1 + 3.7) + 0.25 * noise((bq + vec2(0.06, 0.0)) * 4.3 + 1.1);
        float by = noise(bq + vec2(0.0, 0.06)) + 0.5 * noise((bq + vec2(0.0, 0.06)) * 2.1 + 3.7) + 0.25 * noise((bq + vec2(0.0, 0.06)) * 4.3 + 1.1);
        g += vec2(bx - b0, by - b0) / 0.06 * 0.25 * bs;
        wakeE = max(wakeE, bs * 1.5);
    }
    // wake waves carry a field of short, steep wavelets: much rougher surface where the wake energy is
    if (wakeE > 0.01) {
        vec2 r1 = p * 4.5 + vec2(uTime * 1.1, -uTime * 0.7), r2 = p * 9.0 - vec2(uTime * 0.9, uTime * 1.3);
        float e2 = 0.05;
        float c0 = noise(r1), cx = noise(r1 + vec2(e2, 0.0)), cy = noise(r1 + vec2(0.0, e2));
        float d0 = noise(r2), dx = noise(r2 + vec2(e2, 0.0)), dy = noise(r2 + vec2(0.0, e2));
        g += (vec2(cx - c0, cy - c0) * 0.06 + vec2(dx - d0, dy - d0) * 0.03) / e2 * wakeE;
    }
    vec2 q1 = p * 1.3 + vec2(uTime * 0.31, uTime * 0.17);
    vec2 q2 = p * 3.7 - vec2(uTime * 0.23, -uTime * 0.41);
    float e = 0.1;
    float a0 = fbm(q1), ax = fbm(q1 + vec2(e, 0.0)), ay = fbm(q1 + vec2(0.0, e));
    float b0 = noise(q2), bx = noise(q2 + vec2(e, 0.0)), by = noise(q2 + vec2(0.0, e));
    // the turbulent track is a slick: the scar kills the small ripples (dark smooth stripe in glitter)
    float slick = 1.0 - 0.75 * clamp(milkA * 2.0, 0.0, 1.0) * (1.0 - clamp(foam * 2.0, 0.0, 1.0));
    g += (vec2(ax - a0, ay - a0) / e * 0.05 + vec2(bx - b0, by - b0) / e * 0.018) * slick;
    vec3 n = normalize(vec3(-g, 1.0));
    vec3 v = normalize(uCam - vW);
    float nv = max(dot(n, v), 0.0);
    float fres = 0.02 + 0.98 * pow(1.0 - nv, 5.0);
    // churned water breaks the mirror: extra distortion from wake energy and slope, clamped (no smears), none under
    // white foam, fading with distance
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
    float spec = pow(max(dot(n, h), 0.0), 600.0) * 40.0 + pow(max(dot(n, h), 0.0), 80.0) * 0.6;
    col += uSunCol * spec * sh * fres * 4.0 * (1.0 - 0.6 * clamp(milkA, 0.0, 1.0));
    // aerated water: the pale turquoise scar the wash leaves behind (lasts much longer than the white foam)
    float aerT = clamp(milkA * 0.9 + foam * 0.4, 0.0, 1.0);
    // the hollow behind the mound: water drops away and you see deeper into it - darker, glassier
    if (milkA > 0.15 && wakeSlope != vec2(0.0)) {
        float hcF = texture(uWake, wuv).r;
        col *= 1.0 - clamp(-hcF * 3.0, 0.0, 0.35) * (1.0 - clamp(foam * 1.5, 0.0, 1.0));
    }
    if (aerT > 0.005) {
        // fresh scar is turquoise, old scar fades toward green-brown canal water
        vec3 milk = mix(vec3(0.045, 0.075, 0.06), vec3(0.06, 0.15, 0.13), smoothstep(0.08, 0.6, milkA)) * (uSkyAmb * 1.2 + uSunCol * 0.4 * sh);
        col = mix(col, milk, aerT * 0.45);
    }
    if (foam > 0.01) {
        // lace streaked along the track: torn patches x bubble rafts x fine froth (10-30 cm), slowly boiling
        vec2 q = pw + n.xy * 0.4 + vec2(0.0, milkA * pw.x * 0.25);
        float lace = fbm(q * 0.8 + uTime * 0.05) * 0.25 + noise(q * 2.6 - uTime * 0.15) * 0.32
                   + noise(q * 7.0 + uTime * 0.3) * 0.22 + noise(q * 9.0 + uTime * 0.2) * 0.13 + noise(q * 26.0) * 0.08;
        // fresh churn right behind the motor is near-solid matte white, then it tears into lace
        float thr = 1.0 - clamp(foam, 0.0, 1.0) * mix(0.64, 0.86, smoothstep(0.6, 0.95, foam));
        float fw = smoothstep(thr - 0.06, thr + 0.06, lace);
        // lit foam: bubble-cell bumps on top of the wave normal, albedo varies 0.6-0.9
        vec2 cq = q * 6.0 + uTime * 0.2;
        float c0 = noise(cq), cx2 = noise(cq + vec2(0.05, 0.0)), cy2 = noise(cq + vec2(0.0, 0.05));
        vec3 nf = normalize(vec3(-g * 0.6 - vec2(cx2 - c0, cy2 - c0) / 0.05 * 0.35, 1.0));
        float alb = 0.62 + 0.28 * smoothstep(0.2, 0.8, c0) * (0.7 + 0.3 * noise(p * 3.0));
        float dif = max(dot(nf, uSunDir), 0.0);
        vec3 white = vec3(alb, alb * 1.03, alb * 1.02) * (uSunCol * 0.6 * dif * sh + uSkyAmb * (1.1 + 0.4 * nf.z));
        // submerged foam just under the surface: a subsurface teal glow where the lace is half there
        float sub = smoothstep(thr - 0.3, thr - 0.1, lace) * (1.0 - fw);
        col = mix(col, vec3(0.25, 0.50, 0.45) * (uSkyAmb * 1.1 + uSunCol * 0.35 * sh), sub * 0.45);
        // thin aerated fringe where the lace thins out, so the foam blends instead of looking cut out
        float fringe = smoothstep(thr - 0.22, thr - 0.04, lace) * (1.0 - fw);
        col = mix(col, vec3(0.20, 0.42, 0.38) * (uSkyAmb * 1.2 + uSunCol * 0.45 * sh), fringe * 0.5);
        col = mix(col, white, fw * 0.95);
    }
    o = vec4(fog(col, vW), 1.0);
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
    float stretch = 1.0 + min(sp * 0.045 / max(size, 0.03), 5.0);
    vec3 p = aPos + along * aCorner.y * size * stretch + side * aCorner.x * size;
    vW = p;
    p += normalize(uCam - p) * min(0.5, size * 2.0);   // keep the sprite from being cut by the water surface vA = aInfo.x; vC = aCorner; vSize = size;
    gl_Position = uVP * vec4(p, 1.0);
}
"""
    val SPRAY_FS = """#version 300 es
precision highp float;
$COMMON
in float vA; in vec3 vW; in vec2 vC; in float vSize;
out vec4 o;
void main() {
    float r = dot(vC, vC);
    float ragged = r * (0.85 + 0.4 * noise(vC * 2.5 + vW.xy * 9.0));
    if (ragged > 1.0) discard;
    bool mist = vSize > 0.25;
    float a = exp(-ragged * (mist ? 2.5 : 3.5)) * smoothstep(0.0, 0.3, vA) * (mist ? 0.10 : 0.7);
    float sh = sunShadow(vW, vec3(0, 0, 1));
    vec3 v = normalize(uCam - vW);
    float fwd = pow(max(dot(-v, uSunDir), 0.0), 6.0);       // droplets glow when backlit
    vec3 col = vec3(0.90, 0.94, 0.95) * (uSunCol * (0.55 + 1.4 * fwd) * sh + uSkyAmb * 1.25);
    o = vec4(fog(col, vW), a);
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
out vec4 o;
void main() {
    vec4 a = uInvVP * vec4(vP, -1.0, 1.0); vec4 b = uInvVP * vec4(vP, 1.0, 1.0);
    vec3 d = normalize(b.xyz / b.w - a.xyz / a.w);
    if (uMirrorSky > 0.5) d.z = -d.z;
    float h = clamp(d.z, -0.2, 1.0);
    vec3 col = mix(uFogCol * 1.15, uSkyZenith, pow(max(h, 0.0), 0.5));
    float s = max(dot(d, uSunDir), 0.0);
    col += uSunCol * (pow(s, 1800.0) * 60.0 + pow(s, 64.0) * 0.5 + pow(s, 6.0) * 0.18);
    col = mix(col, uFogCol * 0.9, smoothstep(0.02, -0.15, d.z));
    o = vec4(col, 1.0);
}
"""

    // ------------------------------------------------------------------ post: bright pass, blur, composite
    const val POST_VS = """#version 300 es
layout(location=0) in vec2 aPos;
out vec2 vUv;
void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
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
    col += texture(uBloom, vUv).rgb * 0.35;
    col = aces(col * uExposure);
    col = mix(vec3(luma(col)), col, 1.08);
    col *= vec3(1.02, 1.0, 0.97);
    vec2 vc = vUv - 0.5;
    col *= 1.0 - dot(vc, vc) * 0.35;
    o = vec4(pow(col, vec3(1.0 / 2.2)), 1.0);
}
"""
}
