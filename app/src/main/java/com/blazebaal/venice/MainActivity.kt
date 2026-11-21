package com.blazebaal.venice

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.blazebaal.venice.render.GameRenderer
import com.blazebaal.venice.ui.Controls
import com.blazebaal.venice.ui.HudView

class MainActivity : Activity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GameRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        renderer = GameRenderer(this)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        val root = FrameLayout(this)
        root.addView(glView)
        root.addView(HudView(this))
        setContentView(root)
        hideSystemUi()
    }

    private fun hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemUi() }
    override fun onResume() { super.onResume(); glView.onResume() }
    override fun onPause() { super.onPause(); glView.onPause() }
    override fun onDestroy() { renderer.shutdown(); super.onDestroy() }

    // keyboard (W/A/S/D or arrows) for playing with a keyboard connected to the phone
    private fun key(code: Int, down: Boolean): Boolean {
        when (code) {
            KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BUTTON_R2 -> Controls.kUp = down
            KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BUTTON_L2 -> Controls.kDown = down
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_DPAD_LEFT -> Controls.kLeft = down
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_DPAD_RIGHT -> Controls.kRight = down
            else -> return false
        }
        return true
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?) = key(keyCode, true) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent?) = key(keyCode, false) || super.onKeyUp(keyCode, event)
}
