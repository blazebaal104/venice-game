// Game audio: real CC0 field recordings (assets/sfx.bank, built by tools/audio/build_bank.py, credits in
// tools/audio/CREDITS.md) played through a small real-time engine on its own thread (AudioTrack, 24 kHz stereo).
//  - your outboard: granular, the way racing games do it. A recording of a real outboard running steady
//    (~103 firings/s) is cut into its single firing pulses (pitch marks found offline); they are laid back down at
//    the rate the simulated engine fires (rpm/30 for a 4-cylinder 4-stroke), so idle, cruise and full revs keep
//    the recorded timbre instead of the chipmunk/slow-motion of plain pitch shifting. A recorded low rumble fills in
//    at idle; throttle opens the tone; the prop unloading on a wave makes it flare (vroom) and bog on landing.
//  - water: the rush along the hull grows and brightens with speed; at rest the hull slaps and creaks in the swell,
//    laps against the fondamenta near the walls; slaps from waves and from vaporetto wakes reaching you.
//  - ACTV boats: a recorded diesel per boat type, revving with the boat's engine, plus their bow wash;
//    distance, air absorption and doppler; horns when they swing round a corner, and angrily when you are in their way
//    (they don't stop); docking and departing (reverse churn, fender thump on the steel pontoon, ropes, gangway).
//  - collisions sound like what you hit: stone fondamenta, pontoon, a vaporetto's steel hull, a wooden bricola.
//  - weather: rain on the water and single drops on the boat (clearest when you're still), wind, thunder near or far.
//  - canals echo: a short ping-pong delay, stronger between the walls.
//  - real gull calls (from the gulls you see, and distant ones near the city), terns over the open lagoon; the hour
//    struck on a big bell, and at noon the bells of the whole city ringing together.
// The game writes the @Volatile inputs every frame; nothing here touches GL.
package com.blazebaal.venice.audio

import android.content.Context
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
    /** on the main menu: the world is silent (you can't see it), only the buttons make a sound */
    @Volatile var menu = true
    @Volatile var master = 0.8f; @Volatile var engineVol = 0.8f; @Volatile var ambienceVol = 0.6f
    @Volatile var engineOn = true
    @Volatile var throttle = 0f; @Volatile var speed = 0f; @Volatile var trim = 0f; @Volatile var airborne = false
    /** how much the prop is biting (1 = fully in the water, 0 = out): bouncing on the trim/waves unloads it, the
     *  engine flares up, then bogs down as the hull slams back - the "vroom-vrom-vroom" of a porpoising boat */
    @Volatile var propLoad = 1f
    @Volatile var nearLand = 0f            // 0 open lagoon .. 1 narrow canal (more slap, more echo)
    @Volatile var waveAmp = 0.06f          // swell height where you are (m)
    @Volatile var rain = 0f; @Volatile var wind = 0f
    /** weed wound round the prop (0..1): the engine labours, misfires, the blades chop through it */
    @Volatile var tangle = 0f
    /** nearest ACTV pontoon: position and distance (they creak, the ropes groan, only near them) */
    @Volatile var pontoonX = 0f; @Volatile var pontoonY = 0f; @Volatile var pontoonD = 1e9f
    @Volatile var lx = 0f; @Volatile var ly = 0f; @Volatile var lyaw = 0f   // listener (camera) position + yaw
    /** nearby boats: x, y, speed, kind, docked, rpm, heading, id */
    @Volatile var boats: List<FloatArray> = emptyList()
    private val events = ConcurrentLinkedQueue<FloatArray>()
    const val EV_SPLASH = 1f; const val EV_IMPACT = 2f; const val EV_SCRAPE = 3f; const val EV_DOCK = 4f; const val EV_DEPART = 5f; const val EV_THUNDER = 6f
    /** a wave slapping your hull (strength = vertical speed, m/s) */
    const val EV_SLAP = 7f
    /** a vaporetto's horn at x, y: strength = 1 long blast (a corner), 2 = warning, 3 = angry; kind = boat type */
    const val EV_HORN = 8f
    /** a vaporetto's wake reaching you: a short train of slaps */
    const val EV_WAKE = 9f
    /** a gull calling at x, y (the birds you see) */
    const val EV_GULL = 10f
    /** a button: 1 = tap, 2 = back (lower) - plays even over the menus and pause */
    const val EV_UI = 11f

    /** the soft "plop" of a menu button */
    fun ui(back: Boolean = false) = event(EV_UI, if (back) 2f else 1f)

    /** one-shot sound at a world position (NaN = at the player): type, strength, x, y, kind (what was hit / boat type) */
    fun event(type: Float, strength: Float, x: Float = Float.NaN, y: Float = Float.NaN, kind: Float = 0f) {
        if (events.size < 48) events.add(floatArrayOf(type, strength, x, y, kind))
    }

    private var thread: Thread? = null
    private var appCtx: Context? = null
    fun start(ctx: Context? = null) {
        if (ctx != null) appCtx = ctx.applicationContext
        if (thread != null) return
        running = true
        thread = Thread({ try { if (bank.isEmpty()) appCtx?.let { loadBank(it) }; loop() } catch (_: Throwable) { } }, "audio").apply { priority = Thread.MAX_PRIORITY - 1; isDaemon = true; start() }
    }
    fun stop() { running = false; thread?.join(300); thread = null }

    // ================================================================ the sound bank
    private const val SR = 24000
    private const val N = 512
    private class Clip(val sr: Int, val pcm: ShortArray, val marks: IntArray) { val n = pcm.size; val step = sr / SR.toFloat() }
    @Volatile private var bank: Map<String, Clip> = emptyMap()
    private fun loadBank(c: Context) {
        try {
            val bytes = c.assets.open("sfx.bank").use { it.readBytes() }
            val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (bb.get() != 'S'.code.toByte() || bb.get() != 'F'.code.toByte() || bb.get() != 'X'.code.toByte() || bb.get() != '1'.code.toByte()) return
            val n = bb.short.toInt() and 0xFFFF
            val m = HashMap<String, Clip>()
            repeat(n) {
                val ln = bb.get().toInt() and 0xFF
                val nb = ByteArray(ln); bb.get(nb)
                val name = String(nb, Charsets.US_ASCII)
                val sr = bb.int; val frames = bb.int; val nm = bb.int
                val marks = IntArray(nm) { bb.int }
                val pcm = ShortArray(frames)
                bb.asShortBuffer().get(pcm)
                bb.position(bb.position() + frames * 2)
                m[name] = Clip(sr, pcm, marks)
            }
            bank = m
        } catch (_: Throwable) { }
    }
    private fun clip(name: String): Clip? = bank[name]
    /** one of name0, name1 ... at random */
    private fun pick(prefix: String, count: Int): Clip? = bank[prefix + rnd.nextInt(count)] ?: bank[prefix + "0"]

    private val rnd = java.util.Random(7)
    private fun noise() = rnd.nextFloat() * 2f - 1f
    private fun rr(a: Float, b: Float) = a + (b - a) * rnd.nextFloat()

    private class OnePole { var z = 0f; fun lp(x: Float, a: Float): Float { z += (x - z) * a; return z } }
    /** one-pole low-pass coefficient for a cutoff (table: no exp() per sample) */
    private val COEF = FloatArray(1601) { (1f - exp(-2f * PI.toFloat() * (it * 10f) / SR)).coerceIn(0.0001f, 1f) }
    private fun coef(fc: Float): Float = COEF[(fc * 0.1f).toInt().coerceIn(0, 1600)]
    /** Hann window for the engine grains */
    private val WIN = FloatArray(1024) { sin(PI * it / 1023.0).pow(2.0).toFloat() }

    /** a looping recording read at a variable rate (loops were crossfaded seamless offline) */
    private class Loop(val c: Clip, start: Double) {
        var pos = start * c.n
        fun next(rate: Float): Float {
            val i = pos.toInt(); val f = (pos - i).toFloat()
            val a = c.pcm[i].toFloat(); val b = c.pcm[if (i + 1 >= c.n) 0 else i + 1].toFloat()
            pos += rate * c.step
            if (pos >= c.n) pos -= c.n
            return (a + (b - a) * f) * (1f / 32768f)
        }
    }
    private fun loopOf(name: String): Loop? = clip(name)?.let { Loop(it, rnd.nextDouble()) }

    // ================================================================ one-shot voices
    private const val T_CLIP = 0; private const val T_THUD = 1; private const val T_CHURN = 2; private const val T_SUB = 3; private const val T_SCRAPE = 4
    private class Voice(val c: Clip?, val type: Int, val rate: Float, val gain: Float, val pl: Float, val pr: Float,
                        var delay: Int, val lpA: Float, val send: Float, val dur: Float = 0f, val f: Float = 0f) {
        var pos = 0.0; var t = 0f; val lp = OnePole(); val lp2 = OnePole(); var done = false
    }
    private val voices = ArrayList<Voice>()
    private val uiVoices = ArrayList<Voice>()
    private fun play(c: Clip?, gain: Float, rate: Float = 1f, pan: FloatArray = CENTRE, delay: Float = 0f, lp: Float = 20000f, send: Float = 0.3f) {
        if (c == null || gain < 0.002f || voices.size >= 40) return
        voices.add(Voice(c, T_CLIP, rate, gain * pan[2], pan[0], pan[1], (delay * SR).toInt(), coef(lp), send))
    }
    private fun synth(type: Int, gain: Float, dur: Float, pan: FloatArray = CENTRE, delay: Float = 0f, f: Float = 0f, send: Float = 0.3f) {
        if (gain < 0.002f || voices.size >= 40) return
        voices.add(Voice(null, type, 1f, gain * pan[2], pan[0], pan[1], (delay * SR).toInt(), 1f, send, dur, f))
    }
    private val CENTRE = floatArrayOf(0.55f, 0.55f, 1f)

    /** an air horn: the recorded attack, a sustain held as long as wanted (two read heads crossfading over its
     *  steady part), a smooth release; a low reed body under it (a ship's horn has a fuller bottom than the recording),
     *  darker with distance */
    private class Horn(val c: Clip, val rate: Float, val gain: Float, val pl: Float, val pr: Float, var delay: Int, val hold: Float, val lpA: Float) {
        val s0 = c.marks[0].toDouble(); val s1 = c.marks[1].toDouble(); val half = (s1 - s0) / 2.0
        var a = 0.0; var b = -1.0; var held = 0f; var done = false; var env = 0f
        var ph = 0.0; val f0 = 1290.0 * rate * 0.5; val lp = OnePole(); val lp2 = OnePole(); val bodyLp = OnePole()
        fun next(): Float {
            if (delay > 0) { delay--; return 0f }
            val st = rate * c.step
            held += 1f / SR
            // attack 30 ms, release 220 ms (instead of the recording's own tail)
            env = if (held < hold) min(1f, env + 1f / (0.03f * SR)) else env * 0.99981f
            if (held >= hold && env < 0.002f) { done = true; return 0f }
            var out = at(a)
            if (b >= 0.0) {
                val w = ((b - s0) / half).toFloat().coerceIn(0f, 1f)
                out = out * cos(w * 1.5708f) + at(b) * sin(w * 1.5708f)
                b += st
                if (w >= 1f) { a = b; b = -1.0 }
            } else if (a >= s1 - half) b = s0                    // keep looping the steady part through the release
            a += st
            if (a >= c.n - 2) a = s0
            // the body: a reedy tone an octave under, a touch of vibrato
            ph += f0 * (1.0 + 0.003 * sin(held * 31.0)) / SR; if (ph > 1.0) ph -= 1.0
            val p2 = 2.0 * PI * ph
            val body = bodyLp.lp((sin(p2) + 0.55 * sin(2 * p2) + 0.32 * sin(3 * p2) + 0.18 * sin(4 * p2)).toFloat(), 0.25f) * 0.22f
            return lp2.lp(lp.lp(out + body, lpA), lpA) * gain * env
        }
        private fun at(p: Double): Float { val i = p.toInt().coerceIn(0, c.n - 2); val f = (p - i).toFloat(); return (c.pcm[i] + (c.pcm[i + 1] - c.pcm[i]) * f) / 32768f }
    }
    private val horns = ArrayList<Horn>()

    // ================================================================ engine (granular)
    private class Grain { var active = false; var src = 0.0; var step = 0.0; var k = 0; var len = 1; var gain = 0f }
    private val grains = Array(12) { Grain() }
    private var rpm = 0f; private var grainT = 0f; private var gj = 0; private var gJump = 64; private var lastOn = false
    private val eLp = OnePole(); private val eLp2 = OnePole(); private val burble = OnePole()
    private var shaftPhase = 0.0
    private var engIdle: Loop? = null

    // water + ambience beds
    private var bow: Loop? = null; private var hullLap: Loop? = null; private var lap: Loop? = null; private var gurgle: Loop? = null
    private var rainL: Loop? = null; private var windL: Loop? = null
    private val rushHp = OnePole(); private val rushLp = OnePole(); private val bowLp = OnePole(); private val windLp = OnePole(); private val rainLp = OnePole()
    private var slapT = 1f; private var wakeLeft = 0; private var wakeT = 0f; private var wakeS = 0f; private var wingT = 30f
    private var creakT = 2f; private var farGullT = 10f; private var ternT = 30f
    private var lugPh = 0.0; private var bladePh = 0.0; private val chopLp = OnePole(); private val gurgleLp = OnePole()

    // ACTV boats: one voice per boat (by id), fading in/out as boats come and go
    private class BoatVoice(val id: Int, val eng: Loop?, val wash: Loop?, val base: Float) {
        var g = 0f; var tg = 0f; var pl = 0.5f; var pr = 0.5f; var rate = 1f; var tRate = 1f
        var washG = 0f; var tWash = 0f; var fc = 0.2f; var tFc = 0.2f; var dPrev = -1f; var dop = 1f; var seen = true
        val lp = OnePole(); val lp2 = OnePole()
    }
    private val boatVoices = HashMap<Int, BoatVoice>()

    // canal echo (ping-pong)
    private val dlL = FloatArray(8192); private val dlR = FloatArray(8192); private var dlPos = 0
    private val echoLpL = OnePole(); private val echoLpR = OnePole()

    // bells: the hour struck on a big bell; at noon the whole city rings (several peals, near and far)
    private var bellsLeft = 0; private var bellT = 0f; private var lastBellHour = -1
    private class Peal(val l: Loop, val rate: Float, val gain: Float, val pl: Float, val pr: Float, val lpA: Float, var delay: Int) { var t = 0f; val lp = OnePole() }
    private val peals = ArrayList<Peal>()
    private const val PEAL_LEN = 75f
    private var fade = 0f

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(max(minBuf, N * 4 * 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track.play()
        if (bow == null) {
            engIdle = loopOf("eng_idle"); bow = loopOf("bow"); hullLap = loopOf("hlap"); lap = loopOf("plap") ?: loopOf("lap"); gurgle = loopOf("bubbles")
            rainL = loopOf("rain"); windL = loopOf("wind")
        }
        val out = ShortArray(N * 2)
        val l = FloatArray(N); val r = FloatArray(N)
        try {
            while (running) {
                renderBlock(l, r)
                for (i in 0 until N) {
                    out[i * 2] = (soft(l[i]) * 30000f).toInt().toShort()
                    out[i * 2 + 1] = (soft(r[i]) * 30000f).toInt().toShort()
                }
                track.write(out, 0, out.size)
            }
        } finally { track.stop(); track.release() }
    }

    /** gentle limiter: linear up to 0.6, then a smooth knee into +-1 */
    private fun soft(x: Float): Float { val a = abs(x); if (a < 0.6f) return x; val y = 0.6f + 0.4f * tanh((a - 0.6f) / 0.4f); return if (x < 0) -y else y }
    private fun tanh(x: Float): Float { val e = exp(2f * x.coerceIn(-10f, 10f)); return (e - 1f) / (e + 1f) }

    // ================================================================ events
    private fun handle(e: FloatArray) {
        val type = e[0]; val s = e[1]
        val p = pan(e[2], e[3])
        if (type == EV_UI) {
            // a drop falling into water, short and close: the menus' click (on its own bus, never faded out)
            val c = pick("plip", 2) ?: return
            if (uiVoices.size < 6) uiVoices.add(Voice(c, T_CLIP, (if (s > 1.5f) 0.82f else 1.12f) * rr(0.97f, 1.03f), 0.42f, 0.55f, 0.55f, 0, coef(7000f), 0f))
            return
        }
        if (menu && type != EV_THUNDER) return                       // nothing of the world while on the menu
        when (type) {
            EV_SPLASH -> {                                  // landing after a jump
                val g = min(1f, s / 5f)
                play(pick("splash", 4), 0.75f * g, rr(0.85f, 1.1f), p, lp = 3000f + 5000f * g)
                play(pick("slap", 5), 0.6f * g, rr(0.6f, 0.8f), p, lp = 1200f)
                synth(T_THUD, 0.35f * g, 0.5f, p, f = 50f)
            }
            EV_SLAP -> {                                    // the hull meeting a wave
                val g = (s / 0.6f).coerceIn(0.05f, 1f)
                play(pick("hslap", 5) ?: pick("slap", 5), (0.2f + 0.55f * g), rr(0.8f, 1.05f), p, lp = 1500f + 3000f * g, send = 0.15f)
                if (g > 0.5f) synth(T_THUD, 0.12f * g, 0.25f, p, f = 70f)
            }
            EV_WAKE -> { wakeLeft = 3 + rnd.nextInt(3); wakeS = s.coerceIn(0.1f, 1.5f); wakeT = 0f }
            EV_IMPACT -> impact(s, e[4].toInt(), p)
            EV_SCRAPE -> {
                play(clip("scrape"), 0.28f, rr(0.75f, 0.95f), p, lp = 3500f)
                synth(T_SCRAPE, 0.22f, 0.5f, p)
            }
            EV_DOCK -> {
                // reverse churn, the bump against the pontoon, the rope made fast, the gangway gate
                synth(T_CHURN, 0.55f, 3.0f, p)
                play(clip("bump"), 0.6f, 0.55f, p, 1.6f, lp = 1100f, send = 0.5f)
                play(clip("metal0"), 0.35f, 0.5f, p, 1.62f, lp = 1400f, send = 0.5f)
                synth(T_THUD, 0.4f, 0.8f, p, 1.6f, f = 45f)
                play(pick("creak", 2), 0.3f, rr(0.75f, 0.9f), p, 2.1f, send = 0.4f)
                play(pick("clank", 2), 0.3f, rr(0.85f, 1f), p, 2.7f, lp = 6000f, send = 0.5f)
                play(pick("chain", 2), 0.15f, rr(0.9f, 1.05f), p, 3.0f, lp = 5000f)
            }
            EV_DEPART -> {
                play(pick("clank", 2), 0.3f, rr(0.85f, 1f), p, 0f, lp = 6000f, send = 0.5f)
                play(pick("creak", 2), 0.25f, rr(0.75f, 0.9f), p, 0.3f, send = 0.4f)
                synth(T_CHURN, 0.6f, 3.5f, p, 0.4f, f = 1f)
            }
            EV_HORN -> horn(s.toInt(), e[4].toInt(), e[2], e[3])
            EV_GULL -> {
                val pg = pan(e[2], e[3]).let { floatArrayOf(it[0], it[1], (1f / (1f + (hypotL(e[2], e[3]) / 35f).pow(2f))).coerceAtMost(1f)) }
                play(pick("gull", 6), 0.5f * pg[2] * max(0.3f, s), rr(0.92f, 1.08f), pg, lp = 9000f, send = 0.5f)
            }
            EV_THUNDER -> thunder()
        }
    }

    /** what you hit decides the sound: 0/1 stone fondamenta, 2 pontoon, 3 a vaporetto, 4 a wooden bricola */
    private fun impact(s: Float, kind: Int, p: FloatArray) {
        val g = min(1f, s / 6f).coerceAtLeast(0.15f)
        // your own hull: a hollow fibreglass knock + the boat's mass
        synth(T_THUD, 0.6f * g, 0.6f, p, f = rr(55f, 75f))
        play(pick("wood", 4), 0.35f * g, rr(0.7f, 0.85f), p, lp = 2500f)
        play(pick("slap", 5), 0.3f * g, rr(0.6f, 0.8f), p, lp = 1500f, delay = 0.05f)
        when (kind) {
            2 -> {   // pontoon: a steel box on rubber fenders
                play(clip("metal0"), 0.6f * g, rr(0.6f, 0.75f), p, lp = 2500f, send = 0.6f)
                play(clip("bump"), 0.5f * g, 0.6f, p, lp = 1500f)
                play(pick("creak", 2), 0.2f * g, 0.8f, p, delay = 0.2f)
            }
            3 -> {   // a vaporetto's steel hull: a deep boom and rattle
                play(clip("metal0"), 0.85f * g, rr(0.42f, 0.5f), p, lp = 1800f, send = 0.6f)
                play(pick("metal", 4), 0.45f * g, rr(0.75f, 0.9f), p, delay = 0.03f, send = 0.5f)
                synth(T_THUD, 0.5f * g, 0.9f, p, f = 38f)
            }
            5 -> {   // a moored fibreglass boat: hollow plastic knock, it rocks and its fenders squeak
                synth(T_THUD, 0.45f * g, 0.5f, p, f = rr(95f, 130f))
                play(clip("bump"), 0.55f * g, rr(0.9f, 1.15f), p, lp = 3200f)
                play(pick("slap", 5), 0.35f * g, rr(0.9f, 1.1f), p, delay = 0.12f, lp = 2500f)
            }
            6 -> {   // a moored wooden boat: planks knocking, the frames creaking
                play(pick("wood", 4), 0.7f * g, rr(0.65f, 0.8f), p, lp = 2800f, send = 0.4f)
                play(pick("thock", 2), 0.4f * g, rr(0.8f, 0.95f), p, delay = 0.02f)
                play(pick("creak", 2), 0.3f * g, rr(0.8f, 1f), p, delay = 0.25f, send = 0.4f)
            }
            7 -> {   // a rib's inflatable tube: a soft rubbery bounce
                synth(T_THUD, 0.35f * g, 0.45f, p, f = rr(60f, 80f))
                play(clip("bump"), 0.45f * g, rr(0.55f, 0.7f), p, lp = 900f)
            }
            8 -> {   // a moored steel work boat: a clang and a long ring
                play(clip("metal0"), 0.7f * g, rr(0.55f, 0.7f), p, lp = 2600f, send = 0.6f)
                play(pick("metal", 4), 0.35f * g, rr(0.8f, 1f), p, delay = 0.04f, send = 0.5f)
            }
            4 -> {   // bricola: a bundle of oak piles - a dull thock, the pole groaning after
                play(pick("thock", 2), 0.9f * g, rr(0.55f, 0.7f), p, lp = 3000f, send = 0.5f)
                play(clip("wood0"), 0.5f * g, rr(0.75f, 0.9f), p)
                play(pick("creak", 2), 0.35f * g, rr(0.6f, 0.75f), p, delay = 0.18f, send = 0.4f)
            }
            else -> { // stone: Istrian stone and brick along the fondamenta
                play(pick("stone", 5), 0.8f * g, rr(0.7f, 0.95f), p, send = 0.6f)
                if (g > 0.6f) play(pick("stone", 5), 0.5f * g, rr(0.6f, 0.8f), p, delay = 0.04f, send = 0.6f)
            }
        }
        if (s > 9f) play(clip("wood0"), 0.6f * g, rr(0.6f, 0.7f), p, delay = 0.02f)   // something cracked
    }

    /** ACTV horns: one long blast round a corner, a couple of warnings, or an angry burst when you're in the way */
    private fun horn(pattern: Int, kind: Int, x: Float, y: Float) {
        val c = clip("horn") ?: return
        if (c.marks.size < 2 || horns.size > 6) return
        val dx = x - lx; val dy = y - ly; val d = hypot(dx, dy)
        val att = 1f / (1f + (d / 70f).pow(2f)) * (if (d > 700f) 0f else 1f)
        if (att < 0.01f) return
        val side = -sin(atan2(dy, dx) - lyaw)
        val pl = 0.5f - 0.45f * side + 0.05f; val pr = 0.5f + 0.45f * side + 0.05f
        // pitch by size: the motonavi are deepest, the motoscafi highest
        val rate = when (kind) { 2 -> 0.36f; 1 -> 0.48f; 3 -> 0.5f; else -> 0.42f } * rr(0.98f, 1.02f)
        val g = 0.6f * att
        val lpA = coef(9000f / (1f + d / 120f) + 600f)
        fun blast(at: Float, hold: Float) { horns.add(Horn(c, rate, g, pl, pr, (at * SR).toInt(), hold, lpA)) }
        when (pattern) {
            3 -> { blast(0f, 0.16f); blast(0.4f, 0.16f); blast(0.8f, 0.16f); blast(1.25f, 1.2f) }
            2 -> { blast(0f, 0.35f); blast(0.7f, 0.35f) }
            else -> blast(0f, 1.4f)
        }
    }

    private fun hypotL(x: Float, y: Float) = if (x.isNaN()) 0f else hypot(x - lx, y - ly)

    /** noon: the bells of the city answering each other - several peals, pitched and placed apart, the far ones darker */
    private fun noonPeal() {
        val c = clip("peal") ?: return
        peals.clear()
        val rates = floatArrayOf(0.62f, 0.74f, 0.86f, 1.0f, 1.13f, 0.8f)
        for ((i, r) in rates.withIndex()) {
            val far = i / (rates.size - 1f)
            val side = rr(-0.9f, 0.9f)
            peals.add(Peal(Loop(c, rnd.nextDouble()), r * rr(0.98f, 1.02f), 0.22f * (1f - 0.6f * far), 0.5f - 0.45f * side + 0.05f, 0.5f + 0.45f * side + 0.05f,
                coef(6000f - 4800f * far), (rr(0f, 6f) * SR).toInt()))
        }
    }

    private fun thunder() {
        val near = rnd.nextFloat()
        if (near < 0.35f) {
            // close: the crack, then the roll rumbling away
            play(if (rnd.nextBoolean()) clip("thX") else clip("thB"), 0.9f, rr(0.9f, 1f), floatArrayOf(rr(0.4f, 0.7f), rr(0.4f, 0.7f), 1f), send = 0.3f)
            play(if (rnd.nextBoolean()) clip("thA") else clip("thB"), 0.7f, rr(0.85f, 0.95f), CENTRE, 0.15f, send = 0.3f)
            play(pick("thR", 2), 0.6f, rr(0.85f, 1f), CENTRE, 1.2f, lp = 1500f)
            synth(T_SUB, 0.5f, 5f)
        } else {
            // far: only the roll, darker and slower
            val k = (near - 0.35f) / 0.65f
            val c = if (rnd.nextBoolean()) pick("thR", 2) else pick("thS", 2)
            play(c, 0.7f - 0.35f * k, rr(0.72f, 0.88f), floatArrayOf(rr(0.35f, 0.75f), rr(0.35f, 0.75f), 1f), lp = 1200f - 700f * k)
            synth(T_SUB, 0.35f - 0.15f * k, 5.5f)
        }
    }

    // ================================================================ the mix
    private fun renderBlock(L: FloatArray, R: FloatArray) {
        val dt = 1f / SR
        val targetFade = if (paused || menu) 0.0f else 1f
        val spd = speed; val on = engineOn; val thr = if (on) abs(throttle) else 0f
        val sp = (spd / 39f).coerceIn(0f, 1f)
        val load = (1f - (spd / 38f).coerceIn(0f, 1f)) * thr               // pushing hard at low speed = load
        val trimFree = 1f + 0.12f * trim                                     // trimmed out revs a little higher
        val tg = tangle
        val targetRpm = if (!on) 0f else (750f + 5100f * (0.55f * thr + 0.45f * sp) * trimFree - 450f * load * (1f - trim) * 0.5f +
            (if (airborne) 900f * thr else 0f) + 1300f * thr * (1f - propLoad) - 2600f * tg * thr).coerceIn(if (tg > 0.6f) 480f else 650f, 6300f)
        if (on && !lastOn && rpm < 300f) rpm = 220f                         // starting: it catches and spins up
        lastOn = on
        val mv = master * engineVol; val amb = master * ambienceVol
        val still = (1f - spd / 2.5f).coerceIn(0f, 1f)

        while (true) { handle(events.poll() ?: break) }
        if (voices.size > 40) voices.subList(0, voices.size - 40).clear()

        // ---- ACTV boats: the 5 nearest get a voice; the others fade out
        updateBoats(mv)

        // bells on the hour (a few strokes), seagulls now and then
        val cal = Calendar.getInstance()
        val hr = cal.get(Calendar.HOUR_OF_DAY)
        if (cal.get(Calendar.MINUTE) == 0 && hr != lastBellHour) {
            lastBellHour = hr; bellsLeft = if (hr in 7..22) ((hr + 11) % 12) + 1 else 0; bellT = 2.5f
            if (hr == 12 && !menu) noonPeal()
        }
        if (bellsLeft > 0) {
            bellT += N * dt
            if (bellT > 2.6f) { bellT = 0f; bellsLeft--; play(pick("toll", 3), 0.32f * amb, rr(0.44f, 0.46f), floatArrayOf(0.52f, 0.58f, 1f), lp = 5000f, send = 0.9f) }
        }
        // the pontoons: steel boxes on chains, timber and ropes, groaning in the swell - only when you're near one
        creakT -= N * dt
        if (creakT <= 0f) {
            creakT = rr(2.5f, 7f)
            if (pontoonD < 45f) {
                val pp = pan(pontoonX, pontoonY); val k = (1f - pontoonD / 45f).pow(1.5f)
                val g = k * (0.35f + waveAmp * 2.5f) * amb
                when (rnd.nextInt(4)) {
                    0 -> play(pick("groan", 2), 0.45f * g, rr(0.8f, 1.05f), pp, lp = 4000f, send = 0.4f)
                    1 -> play(clip("rope"), 0.35f * g, rr(0.85f, 1.1f), pp, send = 0.3f)
                    2 -> play(pick("creak", 2), 0.3f * g, rr(0.6f, 0.8f), pp, send = 0.4f)
                    else -> play(pick("clank", 2), 0.12f * g, rr(0.7f, 0.9f), pp, lp = 3000f, send = 0.5f)
                }
            }
        }
        // gulls far off over the city (the near ones call from the birds you see); terns out on the lagoon
        farGullT -= N * dt
        if (farGullT <= 0f) {
            farGullT = rr(8f, 30f) / (0.4f + nearLand)
            if (nearLand > 0.25f) {
                val n = 1 + rnd.nextInt(3); val pl = rr(0.25f, 0.85f)
                for (k in 0 until n) play(pick("gull", 6), rr(0.06f, 0.16f) * amb, rr(0.9f, 1.1f), floatArrayOf(pl, 1.1f - pl, 1f), k * rr(0.3f, 0.9f), lp = 3500f, send = 0.6f)
            }
        }
        ternT -= N * dt
        if (ternT <= 0f) {
            ternT = rr(25f, 70f)
            if (nearLand < 0.3f) play(pick("tern", 3), rr(0.05f, 0.12f) * amb, rr(0.95f, 1.05f), floatArrayOf(rr(0.25f, 0.85f), rr(0.25f, 0.85f), 1f), lp = 7000f, send = 0.3f)
        }

        // idle slaps against the hull, the swell lifting the boat; pigeons near the walls
        slapT -= N * dt
        if (slapT <= 0f) {
            slapT = rr(0.5f, 2.4f) / (0.6f + waveAmp * 10f)
            val g = (0.15f + 0.3f * rnd.nextFloat()) * (0.45f + waveAmp * 8f) * (1f - sp * 5f).coerceIn(0f, 1f)
            play(pick("hslap", 5) ?: pick("slap", 5), g * amb, rr(0.85f, 1.1f), floatArrayOf(rr(0.35f, 0.75f), rr(0.35f, 0.75f), 1f), lp = rr(2500f, 6000f), send = 0.1f)
            if (rnd.nextFloat() < 0.25f) play(pick("plip", 2), 0.12f * amb * still, rr(0.8f, 1.2f), floatArrayOf(rr(0.3f, 0.8f), rr(0.3f, 0.8f), 1f), send = 0.2f)
        }
        if (wakeLeft > 0) {
            wakeT -= N * dt
            if (wakeT <= 0f) {
                wakeT = rr(0.35f, 0.6f); wakeLeft--
                val g = (0.3f + 0.45f * wakeS) * amb.coerceAtLeast(mv)
                play(pick("slap", 5), g, rr(0.55f, 0.75f), CENTRE, lp = 1600f, send = 0.2f)
                if (wakeS > 0.5f) synth(T_THUD, 0.15f * wakeS, 0.3f, CENTRE, f = 60f)
                wakeS *= 0.8f
            }
        }
        wingT -= N * dt
        if (wingT <= 0f) {
            wingT = rr(25f, 70f)
            if (nearLand > 0.5f && still > 0.5f) play(pick("wings", 2), 0.25f * amb, rr(0.9f, 1.1f), floatArrayOf(rr(0.3f, 0.8f), rr(0.3f, 0.8f), 1f), send = 0.4f)
        }
        // rain: single drops on the deck and windscreen, clearest when you sit still
        val dropP = rain * (14f + 30f * rain) * dt
        val dropG = (0.05f + 0.25f * sqrt(rain)) * (0.35f + 0.65f * still) * amb

        // engine block parameters
        val eng = clip("eng_grain")
        val idleW = (1f - (rpm - 900f) / 1100f).coerceIn(0f, 1f) * (if (rpm > 150f) 1f else 0f)
        val lvl = (0.45f + 0.55f * rpm / 6300f) * (0.6f + 0.4f * thr) * (if (airborne) 0.8f else 1f) * (rpm / 650f).coerceIn(0f, 1f)
        val tone = coef(1100f + 5500f * thr + rpm * 0.35f + 1500f * (1f - propLoad))
        val wet = 0.22f + 0.5f * nearLand

        for (i in 0 until N) {
            fade += (targetFade - fade) * 0.0005f
            // an unloaded prop spins up fast, and the load comes back just as quickly when it bites again
            rpm += (targetRpm - rpm) * (if (propLoad < 0.9f) 0.0007f else if (targetRpm > rpm) 0.00018f else 0.00035f)
            var send = 0f
            // ---------------- your outboard
            var e = 0f
            if (rpm > 120f && eng != null && eng.marks.size >= 8) {
                val f = rpm / 30f
                grainT += 1f
                val spacing = SR / f
                if (grainT >= spacing) {
                    grainT -= spacing
                    // weed in the prop: the engine is lugging, now and then a cylinder misses
                    if (tg < 0.05f || rnd.nextFloat() > tg * 0.28f * (0.3f + thr)) spawnGrain(eng, f)
                }
                var g = 0f
                for (gr in grains) {
                    if (!gr.active) continue
                    val p = gr.src + gr.k * gr.step
                    val ip = p.toInt(); val fr = (p - ip).toFloat()
                    val a = eng.pcm[ip].toFloat(); val b = eng.pcm[ip + 1].toFloat()
                    g += (a + (b - a) * fr) * WIN[(gr.k * 1023) / gr.len] * gr.gain
                    if (++gr.k >= gr.len) gr.active = false
                }
                e = g * (1f / 32768f) * (0.55f + 0.45f * (1f - idleW))
                engIdle?.let { e += it.next((rpm / 900f).coerceIn(0.6f, 1.7f)) * idleW * 0.9f }
                e = eLp2.lp(eLp.lp(e, tone), coef(7000f))
                // gearcase whine at speed, exhaust burble in the water at idle
                shaftPhase += rpm / 60f / 2.08f * 13f / SR; if (shaftPhase > 1.0) shaftPhase -= 1.0
                e += sin(2.0 * PI * shaftPhase).toFloat() * 0.012f * sp
                e += burble.lp(noise(), coef(140f)) * (0.5f - thr * 0.4f) * 0.12f * (rpm / 900f).coerceAtMost(1f)
                // labouring against the weed: an uneven surge, and the blades chopping through it
                if (tg > 0.02f) {
                    lugPh += (4.0 + 3.0 * rnd.nextFloat()) / SR
                    e *= 1f - 0.4f * tg * (0.5f + 0.5f * sin(2.0 * PI * lugPh).toFloat())
                    bladePh += rpm / 60f / 2.08f * 3f / SR; if (bladePh > 1.0) bladePh -= 1.0
                    val chop = sin(PI * bladePh).toFloat().pow(10f)
                    e += chopLp.lp(noise(), coef(700f)) * chop * tg * (0.2f + thr) * 1.6f
                    e += sin(2.0 * PI * shaftPhase).toFloat() * 0.03f * tg * thr       // the gearcase whining under the strain
                }
                e *= lvl
            } else grainT = 0f
            e *= mv * 2.8f
            // prop gurgle: the exhaust bubbling out through the hub, churned water at low speed
            if (on && rpm > 300f) gurgle?.let { e += gurgleLp.lp(it.next(0.8f + 0.5f * thr), coef(1400f + 1200f * thr)) * (0.12f + 0.55f * thr) * (1f - sp * 4f).coerceIn(0f, 1f) * (1f + tg) * mv }
            send += e * 0.12f
            // ---------------- water along your hull
            var w = 0f
            bow?.let { w += bowLp.lp(it.next(0.75f + 0.6f * sp), coef(900f + 4200f * sp)) * (0.02f + 0.8f * sp.pow(1.25f)) * (if (airborne) 0.12f else 1f) }
            val wn = noise()
            w += rushLp.lp(wn - rushHp.lp(wn, coef(400f)), coef(2200f + 2500f * sp)) * sp * sp * 0.07f * (if (airborne) 0.1f else 1f)
            w *= mv
            hullLap?.let { w += it.next(1f) * (1f - sp * 4f).coerceIn(0f, 1f) * (0.5f + 5f * waveAmp) * amb }
            lap?.let { w += it.next(1f) * nearLand * (1f - sp * 3f).coerceIn(0f, 1f) * 0.7f * amb }
            var sl = e + w; var sr = e + w
            // ---------------- ACTV boats
            for (bv in boatVoices.values) {
                bv.g += (bv.tg - bv.g) * 0.0015f
                if (bv.g < 0.0005f && bv.tg == 0f) continue
                bv.rate += (bv.tRate - bv.rate) * 0.002f; bv.washG += (bv.tWash - bv.washG) * 0.002f; bv.fc += (bv.tFc - bv.fc) * 0.003f
                var v = (bv.eng?.next(bv.rate * bv.base * bv.dop) ?: 0f)
                v += (bv.wash?.next(0.62f * bv.dop) ?: 0f) * bv.washG
                v = bv.lp2.lp(bv.lp.lp(v, bv.fc), bv.fc) * bv.g
                sl += v * bv.pl; sr += v * bv.pr
                send += v * 0.4f
            }
            // ---------------- one-shot voices
            var k = 0
            while (k < voices.size) {
                val vc = voices[k]
                if (vc.delay > 0) { vc.delay--; k++; continue }
                val s = voice(vc)
                if (vc.done) { voices.removeAt(k); continue }
                val o = s * vc.gain * master
                sl += o * vc.pl; sr += o * vc.pr
                send += o * vc.send
                k++
            }
            var h = 0
            while (h < horns.size) {
                val hn = horns[h]
                val s = hn.next() * master
                if (hn.done) { horns.removeAt(h); continue }
                sl += s * hn.pl; sr += s * hn.pr; send += s * 0.7f
                h++
            }
            // ---------------- weather
            var a = 0f
            if (rain > 0.01f) {
                rainL?.let { a += rainLp.lp(it.next(1f), coef(3000f + 6000f * rain)) * rain * 1.1f }
                if (rnd.nextFloat() < dropP) play(pick("drop", 10), dropG * rr(0.4f, 1f), rr(0.75f, 1.3f), floatArrayOf(rr(0.2f, 0.9f), rr(0.2f, 0.9f), 1f), lp = rr(2500f, 9000f), send = 0.1f)
            }
            val wv = wind * 0.6f + sp.pow(1.5f) * 0.2f
            if (wv > 0.02f) windL?.let { a += windLp.lp(it.next(0.9f + 0.25f * wind), coef(400f + 1800f * min(1f, wv))) * wv }
            a *= amb
            // noon: the city's bells
            if (peals.isNotEmpty()) {
                var q = 0
                while (q < peals.size) {
                    val pe = peals[q]
                    if (pe.delay > 0) { pe.delay--; q++; continue }
                    pe.t += dt
                    if (pe.t > PEAL_LEN) { peals.removeAt(q); continue }
                    val env = min(1f, pe.t / 4f) * min(1f, (PEAL_LEN - pe.t) / 8f)
                    val v = pe.lp.lp(pe.l.next(pe.rate), pe.lpA) * pe.gain * env * amb
                    sl += v * pe.pl; sr += v * pe.pr; send += v * 0.8f
                    q++
                }
            }
            // ---------------- canal echo: ping-pong delay, darker on each bounce
            val eL = dlL[(dlPos - 2280) and 8191]; val eR = dlR[(dlPos - 3410) and 8191]
            dlL[dlPos] = send + echoLpR.lp(eR, 0.35f) * 0.38f
            dlR[dlPos] = send + echoLpL.lp(eL, 0.35f) * 0.38f
            dlPos = (dlPos + 1) and 8191
            // menu buttons, on top of everything (not faded with the world)
            var ul = 0f
            if (uiVoices.isNotEmpty()) {
                var q = 0
                while (q < uiVoices.size) {
                    val uv = uiVoices[q]
                    val v = voice(uv)
                    if (uv.done) { uiVoices.removeAt(q); continue }
                    ul += v * uv.gain * master.coerceAtLeast(0.3f)
                    q++
                }
            }
            L[i] = (sl + a + eL * wet) * fade + ul; R[i] = (sr + a + eR * wet) * fade + ul
        }
    }

    /** one firing pulse of the recorded outboard, read at rate r, Hann-windowed over two periods */
    private fun spawnGrain(c: Clip, fire: Float) {
        val nm = c.marks.size / 2
        // walk the pulses in order (keeps the cylinder-to-cylinder variation), now and then jump elsewhere
        if (--gJump <= 0) { gJump = 48 + rnd.nextInt(64); gj = (rnd.nextInt(nm / 4) * 4 + (gj and 3)) % nm }
        gj = (gj + 1) % nm
        val centre = c.marks[gj * 2]; val period = c.marks[gj * 2 + 1] / 16f
        val r = (fire / 103f).pow(0.4f).coerceIn(0.72f, 1.35f) * c.step
        val len = (2f * period / r).toInt().coerceIn(16, 4000)
        val overlap = len / (SR / fire)
        val g = rr(0.9f, 1.1f) / max(1f, overlap / 2f).pow(0.7f)
        for (gr in grains) if (!gr.active) {
            gr.active = true; gr.src = (centre - period).toDouble().coerceAtLeast(0.0); gr.step = r.toDouble(); gr.k = 0; gr.len = len; gr.gain = g
            // never read past the end
            if (gr.src + len * r >= c.n - 2) gr.len = ((c.n - 3 - gr.src) / r).toInt().coerceAtLeast(1)
            return
        }
    }

    private fun voice(vc: Voice): Float {
        vc.t += 1f / SR
        when (vc.type) {
            T_CLIP -> {
                val c = vc.c!!
                val ip = vc.pos.toInt()
                if (ip >= c.n - 1) { vc.done = true; return 0f }
                val fr = (vc.pos - ip).toFloat()
                val s = (c.pcm[ip] + (c.pcm[ip + 1] - c.pcm[ip]) * fr) / 32768f
                vc.pos += vc.rate * c.step
                return if (vc.lpA < 0.999f) vc.lp2.lp(vc.lp.lp(s, vc.lpA), vc.lpA) else s
            }
            T_THUD -> {
                if (vc.t > vc.dur) { vc.done = true; return 0f }
                return sin(2.0 * PI * vc.f * vc.t).toFloat() * exp(-vc.t * 9f) * 1.4f + vc.lp.lp(noise(), coef(600f)) * exp(-vc.t * 40f) * 1.2f
            }
            T_CHURN -> {
                if (vc.t > vc.dur) { vc.done = true; return 0f }
                val x = vc.t / vc.dur
                return vc.lp2.lp(vc.lp.lp(noise(), coef(300f + 300f * sin(PI * x).toFloat())), coef(900f)) * sin(PI * x).toFloat() * 2.6f
            }
            T_SUB -> {
                if (vc.t > vc.dur) { vc.done = true; return 0f }
                return vc.lp2.lp(vc.lp.lp(noise(), coef(70f + 90f * exp(-vc.t * 1.5f))), coef(160f)) * (min(1f, vc.t * 6f) * exp(-vc.t * 0.7f) * (0.7f + 0.3f * sin(vc.t * 7f))) * 7f
            }
            T_SCRAPE -> {
                if (vc.t > vc.dur) { vc.done = true; return 0f }
                val x = vc.t / vc.dur; val n = noise()
                return (n - vc.lp.lp(n, coef(1800f))) * sin(PI * x).toFloat() * (0.6f + 0.4f * sin(vc.t * 90f)) * 0.7f
            }
        }
        vc.done = true; return 0f
    }

    /** ACTV boats: engines by type and rpm, bow wash by speed, distance (air absorption), doppler */
    private fun updateBoats(mv: Float) {
        val list = boats
        for (bv in boatVoices.values) bv.seen = false
        val near = list.sortedBy { hypot(it[0] - lx, it[1] - ly) }.take(5)
        val blockDt = N / SR.toFloat()
        for (b in near) {
            val id = if (b.size > 7) b[7].toInt() else (b[0].toInt() * 31 + b[1].toInt())
            val kind = b[3].toInt()
            val bv = boatVoices.getOrPut(id) {
                val (eng, base) = when (kind) { 2 -> "dsl_md22" to 0.8f; 1 -> "dsl_hood" to 0.85f; 3 -> "dsl_big" to 1f; else -> "dsl_md22" to 0.95f }
                BoatVoice(id, loopOf(eng) ?: loopOf("dsl_big"), loopOf("bow"), base)
            }
            bv.seen = true
            val dx = b[0] - lx; val dy = b[1] - ly; val d = hypot(dx, dy)
            val att = 1f / (1f + (d / 30f).pow(2f)) * (if (d > 340f) 0f else 1f)
            val side = -sin(atan2(dy, dx) - lyaw)
            bv.pl = 0.5f - 0.45f * side + 0.05f; bv.pr = 0.5f + 0.45f * side + 0.05f
            val rpm = if (b.size > 5) b[5] else (if (b[4] > 0.5f) 0.12f else 0.5f)
            val spd = abs(b[2])
            // full astern coming alongside: the diesel roars and the prop churns the water white
            val astern = if (b.size > 8 && b[8] < -0.1f && spd > 0.3f) rpm else 0f
            bv.tg = att * mv * (0.4f + 0.6f * rpm) * 2.6f * (1f + 0.6f * astern)
            bv.tRate = 0.82f + 0.42f * rpm + 0.015f * spd + 0.08f * astern
            bv.tWash = (spd / 6f).coerceIn(0f, 1f) * 0.6f + astern * 0.9f
            // air absorption: distant boats lose their top; an engine under load opens up
            bv.tFc = coef((700f + 3800f * rpm + 2000f * astern) / (1f + d / 60f) + 250f)
            // doppler from the change of distance (gentle: boats are slow)
            if (bv.dPrev >= 0f) {
                val vr = ((d - bv.dPrev) / blockDt).coerceIn(-20f, 20f)
                bv.dop += (343f / (343f + vr) - bv.dop) * 0.1f
            }
            bv.dPrev = d
        }
        val it = boatVoices.values.iterator()
        while (it.hasNext()) {
            val bv = it.next()
            if (!bv.seen) { bv.tg = 0f; bv.tWash = 0f; if (bv.g < 0.0005f) it.remove() }
        }
    }

    /** stereo pan + distance attenuation for a world position (NaN = at the listener): left, right, attenuation */
    private fun pan(x: Float, y: Float): FloatArray {
        if (x.isNaN()) return CENTRE
        val dx = x - lx; val dy = y - ly
        val d = hypot(dx, dy)
        val att = 1f / (1f + (d / 22f).pow(2f)) * (if (d > 300f) 0f else 1f)
        val side = -sin(atan2(dy, dx) - lyaw)          // +1 = to the right of the camera
        return floatArrayOf(0.5f - 0.45f * side + 0.05f, 0.5f + 0.45f * side + 0.05f, att)
    }
}
