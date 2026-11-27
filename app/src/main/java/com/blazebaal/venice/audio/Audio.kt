// Procedural audio: everything is synthesised in real time on its own thread (AudioTrack, 24 kHz stereo), so the
// game ships no sound files.
//  - your outboard: firing harmonics + intake/exhaust noise + prop whine; RPM follows throttle, load and speed,
//    bogs down trimmed in and frees up trimmed out;
//  - water: hull rush and slap growing with speed, landing splashes, impacts (thud) and scraping;
//  - ACTV boats within ~250 m: low diesel throb, panned left/right; docking = reverse churn + rubber/wood thump on
//    the pontoon + gangway clank; departing = rev + churn;
//  - ambience: lapping water, gulls, church bells on the hour, rain and wind from the live weather.
// The game writes the @Volatile inputs every frame; nothing here touches GL.
package com.blazebaal.venice.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Calendar
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.*

object Audio {
    // ---- inputs (written by the game)
    @Volatile var running = false
    @Volatile var paused = false
    @Volatile var master = 0.8f; @Volatile var engineVol = 0.8f; @Volatile var ambienceVol = 0.6f
    @Volatile var engineOn = true
    @Volatile var throttle = 0f; @Volatile var speed = 0f; @Volatile var trim = 0f; @Volatile var airborne = false
    @Volatile var nearLand = 0f            // 0 open lagoon .. 1 narrow canal (more slap, more echo-ish)
    @Volatile var rain = 0f; @Volatile var wind = 0f
    @Volatile var lx = 0f; @Volatile var ly = 0f; @Volatile var lyaw = 0f   // listener (camera) position + yaw
    /** nearby boats: x, y, speed, kind, docked, rpm */
    @Volatile var boats: List<FloatArray> = emptyList()
    private val events = ConcurrentLinkedQueue<FloatArray>()
    const val EV_SPLASH = 1f; const val EV_IMPACT = 2f; const val EV_SCRAPE = 3f; const val EV_DOCK = 4f; const val EV_DEPART = 5f; const val EV_THUNDER = 6f

    /** one-shot sound at a world position (NaN = at the player): type, strength, x, y */
    fun event(type: Float, strength: Float, x: Float = Float.NaN, y: Float = Float.NaN) { if (events.size < 32) events.add(floatArrayOf(type, strength, x, y)) }

    private var thread: Thread? = null
    fun start() {
        if (thread != null) return
        running = true
        thread = Thread({ try { loop() } catch (_: Throwable) { } }, "audio").apply { priority = Thread.MAX_PRIORITY - 1; isDaemon = true; start() }
    }
    fun stop() { running = false; thread?.join(300); thread = null }

    // ================================================================ synthesis
    private const val SR = 24000
    private const val N = 512
    private val rnd = java.util.Random(7)
    private fun noise() = rnd.nextFloat() * 2f - 1f

    private class OnePole { var z = 0f; fun lp(x: Float, a: Float): Float { z += (x - z) * a; return z } }
    /** one-pole low-pass coefficient for a cutoff: a table lookup (it was an exp() per filter per sample, ~20 per sample) */
    private val COEF = FloatArray(1601) { (1f - exp(-2f * PI.toFloat() * (it * 10f) / SR)).coerceIn(0.0001f, 1f) }
    private fun coef(fc: Float): Float = COEF[(fc * 0.1f).toInt().coerceIn(0, 1600)]

    // engine state
    private var rpm = 750f; private var ePhase = 0.0; private var shaftPhase = 0.0
    private val eLp = OnePole(); private val eLp2 = OnePole(); private val nLp = OnePole(); private val exLp = OnePole()
    private val harmPh = DoubleArray(10)
    // water
    private val wLp = OnePole(); private val wHp = OnePole(); private val rumble = OnePole(); private val slapLp = OnePole()
    private var slapEnv = 0f; private var slapT = 0f
    // one-shots: simple voices with an envelope
    private class Voice(val type: Int, var t: Float, val dur: Float, val gain: Float, val panL: Float, val panR: Float, val f: Float) { val lp = OnePole() }
    private val voices = ArrayList<Voice>()
    // boats (up to 4 nearest), each with its own phases
    private val TAB = 1024
    /** diesel firing waveform and its combustion-pulse envelope, one cycle each (built once) */
    private val DIESEL = FloatArray(TAB) { val ph = it * 2.0 * PI / TAB; (sin(ph) + 0.6 * sin(2 * ph) + 0.35 * sin(3 * ph) + 0.2 * sin(4 * ph + 1.0) + 0.12 * sin(6 * ph)).toFloat() }
    private val PULSE = FloatArray(TAB) { sin(PI * it / TAB).toFloat().pow(6f) }
    private val bPh = DoubleArray(4); private val bLp = Array(4) { OnePole() }; private val bChurn = Array(4) { OnePole() }
    // ambience
    private val lapLp = OnePole(); private val lapLp2 = OnePole(); private var lapMod = 0f
    private val rainHp = OnePole(); private val rainLp = OnePole(); private val windLp = OnePole(); private val windLp2 = OnePole()
    private var gullT = 8f; private var gullCalls = 0; private var gullPh = 0.0; private var gullAge = 1f; private var gullPan = 0.5f; private var gullGap = 0f
    private var bellsLeft = 0; private var bellT = 0f; private var lastBellHour = -1
    private val bellPh = DoubleArray(5)
    private var fade = 0f

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(max(minBuf, N * 4 * 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track.play()
        val out = ShortArray(N * 2)
        val l = FloatArray(N); val r = FloatArray(N)
        try {
            while (running) {
                renderBlock(l, r)
                for (i in 0 until N) {
                    out[i * 2] = (tanh(l[i] * 1.1f) * 30000f).toInt().toShort()
                    out[i * 2 + 1] = (tanh(r[i] * 1.1f) * 30000f).toInt().toShort()
                }
                track.write(out, 0, out.size)
            }
        } finally { track.stop(); track.release() }
    }

    private fun tanh(x: Float): Float { val e = exp(2f * x.coerceIn(-10f, 10f)); return (e - 1f) / (e + 1f) }

    private fun renderBlock(L: FloatArray, R: FloatArray) {
        val dt = 1f / SR
        val targetFade = if (paused) 0.0f else 1f
        // ---- parameters for this block
        val spd = speed; val thr = if (engineOn) abs(throttle) else 0f
        val load = (1f - (spd / 38f).coerceIn(0f, 1f)) * thr               // pushing hard at low speed = load
        val trimFree = 1f + 0.12f * trim                                     // trimmed out revs a little higher
        val targetRpm = if (!engineOn) 0f else (750f + 5100f * (0.55f * thr + 0.45f * (spd / 39f).coerceIn(0f, 1f)) * trimFree - 450f * load * (1f - trim) * 0.5f +
            (if (airborne) 900f * thr else 0f)).coerceIn(650f, 6300f)
        // spin up slower than down, like a real motor
        val mv = master * engineVol; val amb = master * ambienceVol
        // nearest boats
        val nb = boats.sortedBy { hypot(it[0] - lx, it[1] - ly) }.take(4)
        // events -> voices
        while (true) {
            val e = events.poll() ?: break
            val (pl, pr, att) = pan(e[2], e[3])
            when (e[0]) {
                EV_SPLASH -> voices.add(Voice(1, 0f, 0.9f, min(1f, e[1] / 6f) * 0.7f * att, pl, pr, 0f))
                EV_IMPACT -> voices.add(Voice(2, 0f, 0.7f, min(1f, e[1] / 6f) * 0.9f * att, pl, pr, 60f + rnd.nextFloat() * 25f))
                EV_SCRAPE -> voices.add(Voice(3, 0f, 0.5f, 0.35f * att, pl, pr, 0f))
                EV_DOCK -> { voices.add(Voice(4, 0f, 3.0f, 0.55f * att, pl, pr, 0f)); voices.add(Voice(2, -1.6f, 0.8f, 0.5f * att, pl, pr, 48f)); voices.add(Voice(5, -2.4f, 0.5f, 0.35f * att, pl, pr, 0f)) }
                EV_DEPART -> voices.add(Voice(4, 0f, 3.5f, 0.6f * att, pl, pr, 1f))
                EV_THUNDER -> voices.add(Voice(6, 0f, 5.5f, 0.9f, 0.55f, 0.55f, 0f))
            }
        }
        if (voices.size > 24) voices.subList(0, voices.size - 24).clear()
        // bells on the hour (a few strokes), seagulls now and then
        val cal = Calendar.getInstance()
        val hr = cal.get(Calendar.HOUR_OF_DAY)
        if (cal.get(Calendar.MINUTE) == 0 && hr != lastBellHour) { lastBellHour = hr; bellsLeft = ((hr + 11) % 12) + 1; bellT = 0f }
        val nb0 = nb.map { b -> pan(b[0], b[1]) }

        for (i in 0 until N) {
            fade += (targetFade - fade) * 0.0005f
            rpm += (targetRpm - rpm) * (if (targetRpm > rpm) 0.00018f else 0.00012f)
            // ---------------- outboard: V6 4-stroke -> firing frequency = rpm/60 * 3
            var eng = 0f
            if (rpm > 100f) {
                val f = rpm / 60f * 3f
                ePhase += f / SR; if (ePhase > 1.0) ePhase -= 1.0
                var h = 0f
                for (k in 1..7) { harmPh[k] += k * f / SR; if (harmPh[k] > 1.0) harmPh[k] -= 1.0; h += sin(2.0 * PI * harmPh[k]).toFloat() / k.toFloat().pow(1.15f) }
                // each firing: a short burst of exhaust noise
                val fireEnv = sin(PI * ePhase).toFloat().pow(10f)
                val exhaust = exLp.lp(noise() * fireEnv, coef(900f + rpm * 0.6f))
                val bright = 0.25f + 0.6f * thr
                eng = eLp2.lp(eLp.lp(h * 0.55f + exhaust * 2.2f, coef(500f + rpm * 0.35f * bright)), coef(2500f + rpm * 0.4f))
                // prop / gearcase whine at speed
                shaftPhase += rpm / 60f / 2.08f * 3f * 7f / SR; if (shaftPhase > 1.0) shaftPhase -= 1.0
                eng += sin(2.0 * PI * shaftPhase).toFloat() * 0.05f * (spd / 39f).coerceIn(0f, 1f)
                // idle burble through the exhaust in the water
                eng += nLp.lp(noise(), coef(120f)) * (0.6f - thr * 0.4f) * 0.8f
                eng *= (0.35f + 0.65f * (rpm / 6300f)) * (if (airborne) 0.75f else 1f)
            }
            eng *= mv * 0.55f
            // ---------------- water rush + slap
            val sp = (spd / 39f).coerceIn(0f, 1f)
            val wn = noise()
            val rush = (wn - wHp.lp(wn, coef(300f))).let { wLp.lp(it, coef(1800f + 2500f * sp)) } * (0.04f + sp.pow(1.2f) * 0.55f) * (if (airborne) 0.1f else 1f)
            slapT -= dt
            if (slapT <= 0f && !airborne && spd > 1f) { slapEnv = 0.4f + rnd.nextFloat() * 0.6f; slapT = 0.25f + rnd.nextFloat() * (1.2f - sp) }
            slapEnv *= 0.9985f
            val slap = slapLp.lp(noise(), coef(500f)) * slapEnv * (0.1f + 0.3f * sp + 0.2f * nearLand)
            val wash = rumble.lp(noise(), coef(80f)) * (0.3f * sp)
            val water = (rush + slap + wash) * mv * 0.8f
            var sl = eng + water; var sr = eng + water
            // ---------------- ACTV boats
            for ((bi, b) in nb.withIndex()) {
                val (pl, pr, att) = nb0[bi]
                if (att < 0.003f) continue
                val bs = abs(b[2]); val rpm = if (b.size > 5) b[5] else (if (b[4] > 0.5f) 0.12f else 0.5f)
                // diesel firing frequency follows the engine rpm from the behaviour layer (idle ~15 Hz, flat out ~42 Hz)
                val f = (13f + 29f * rpm) * (if (b[3] == 2f) 0.8f else 1f)
                bPh[bi] += f / SR; if (bPh[bi] > 1.0) bPh[bi] -= 1.0
                val ti = (bPh[bi] * TAB).toInt() and (TAB - 1)
                var d = DIESEL[ti]                                   // one firing cycle, precomputed (no sin per sample)
                d += bLp[bi].lp(noise() * PULSE[ti], coef(400f)) * 1.5f
                val churn = bChurn[bi].lp(noise(), coef(600f)) * (0.1f + bs * 0.06f + rpm * 0.25f)
                val v = (d * (0.18f + 0.3f * rpm) + churn) * att * mv * 0.9f
                sl += v * pl; sr += v * pr
            }
            // ---------------- one-shot voices
            var k = 0
            while (k < voices.size) {
                val vc = voices[k]
                vc.t += dt
                if (vc.t >= vc.dur) { voices.removeAt(k); continue }
                if (vc.t < 0f) { k++; continue }
                val x = vc.t / vc.dur
                val s = when (vc.type) {
                    1 -> vc.lp.lp(noise(), coef(1400f * (1f - x) + 200f)) * (1f - x).pow(2f) * 2.2f                       // splash
                    2 -> (sin(2.0 * PI * vc.f * vc.t).toFloat() * exp(-vc.t * 9f) * 1.6f + noise() * exp(-vc.t * 60f) * 0.6f) // thud
                    3 -> (noise() - vc.lp.lp(noise(), coef(2500f))) * sin(PI * x).toFloat() * 0.8f                        // scrape
                    4 -> vc.lp.lp(noise(), coef(350f + 250f * sin(PI * x).toFloat())) * sin(PI * x).toFloat() * 2.4f       // prop churn (reverse / rev)
                    6 -> vc.lp.lp(noise(), coef(90f + 160f * exp(-vc.t * 1.5f))) * (min(1f, vc.t * 8f) * exp(-vc.t * 0.7f) * (0.7f + 0.3f * sin(vc.t * 7f))) * 4f   // thunder
                    5 -> (sin(2.0 * PI * 820.0 * vc.t) + 0.6 * sin(2.0 * PI * 1370.0 * vc.t)).toFloat() * exp(-vc.t * 18f) * 0.5f // gangway clank
                    else -> 0f
                }
                sl += s * vc.gain * vc.panL * master; sr += s * vc.gain * vc.panR * master
                k++
            }
            // ---------------- ambience
            lapMod += dt * 0.6f
            val lap = lapLp2.lp(lapLp.lp(noise(), coef(700f)), coef(400f)) * (0.5f + 0.5f * sin(lapMod * 2.3f) * sin(lapMod * 0.7f)) * (0.12f + 0.2f * nearLand)
            val rn = noise()
            val rainS = if (rain > 0.01f) (rainLp.lp(rn - rainHp.lp(rn, coef(900f)), coef(7000f)) * 0.5f + (if (rnd.nextFloat() < rain * 0.002f) noise() * 1.2f else 0f)) * rain else 0f
            val windS = if (wind > 0.02f) windLp2.lp(windLp.lp(noise(), coef(250f + 200f * sin(lapMod * 0.31f))), coef(500f)) * wind * (0.6f + 0.4f * sin(lapMod * 0.17f)) else 0f
            var a = (lap + rainS * 0.9f + windS * 1.3f) * amb
            // gulls: a few descending calls every so often
            gullT -= dt
            if (gullT <= 0f && gullCalls == 0) { gullCalls = 2 + rnd.nextInt(4); gullPan = rnd.nextFloat(); gullGap = 0f; gullT = 14f + rnd.nextFloat() * 30f }
            if (gullCalls > 0) {
                if (gullGap > 0f) gullGap -= dt
                else {
                    gullAge += dt / 0.32f
                    if (gullAge >= 1f) { gullAge = 0f; gullCalls--; gullGap = 0.12f + rnd.nextFloat() * 0.25f }
                    val fq = 2300f - 900f * gullAge + 120f * sin(gullAge * 60f)
                    gullPh += fq / SR
                    val g = (sin(2.0 * PI * gullPh) + 0.4 * sin(4.0 * PI * gullPh)).toFloat() * sin(PI * gullAge).toFloat() * 0.07f * amb
                    sl += g * (1f - gullPan * 0.7f); sr += g * (0.3f + gullPan * 0.7f)
                }
            }
            // bells (inharmonic partials), one stroke every 2.2 s
            if (bellsLeft > 0) {
                bellT += dt
                if (bellT > 2.2f) { bellT = 0f; bellsLeft-- }
                val env = exp(-bellT * 1.3f) * 0.06f * amb
                val parts = floatArrayOf(1f, 2f, 2.4f, 3f, 4.2f); val base = 392f
                var bs = 0f
                for (p in 0 until 5) { bellPh[p] += base * parts[p] / SR; bs += sin(2.0 * PI * bellPh[p]).toFloat() / (p + 1) }
                a += bs * env
            }
            L[i] = (sl + a) * fade; R[i] = (sr + a) * fade
        }
    }

    /** stereo pan + distance attenuation for a world position (NaN = at the listener) */
    private fun pan(x: Float, y: Float): Triple<Float, Float, Float> {
        if (x.isNaN()) return Triple(1f, 1f, 1f)
        val dx = x - lx; val dy = y - ly
        val d = hypot(dx, dy)
        val att = 1f / (1f + (d / 18f).pow(2f)) * (if (d > 260f) 0f else 1f)
        val ang = atan2(dy, dx) - lyaw
        val side = -sin(ang)          // +1 = to the right of the camera
        return Triple(0.5f - 0.45f * side + 0.05f, 0.5f + 0.45f * side + 0.05f, att)
    }
}
