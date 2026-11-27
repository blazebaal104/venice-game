// Stand-in for android.util.Log when the game's mesher runs on the build machine (see baker/build.gradle.kts).
package android.util

object Log {
    @JvmStatic fun w(tag: String, msg: String, t: Throwable? = null): Int { System.err.println("$tag: $msg ${t ?: ""}"); return 0 }
    @JvmStatic fun e(tag: String, msg: String, t: Throwable? = null): Int { System.err.println("$tag: $msg ${t ?: ""}"); return 0 }
    @JvmStatic fun i(tag: String, msg: String): Int = 0
    @JvmStatic fun d(tag: String, msg: String): Int = 0
}
