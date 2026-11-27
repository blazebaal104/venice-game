// Pre-bakes the city: runs the game's own CityMesher (app/.../core, shared source) on the build machine for every
// tile of the map and writes the meshes, deflated, as APK assets (tiles/*.gtc). The phone then only loads them -
// no meshing on the device, no first-run stutter, and an update no longer throws the cache away.
plugins { id("org.jetbrains.kotlin.jvm") }

kotlin { jvmToolchain(17) }

sourceSets { main { kotlin.srcDir("../app/src/main/java/com/blazebaal/venice/core") } }

val bakeTiles by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Meshes every map tile and writes app/build/generated/baked/tiles/*.gtc"
    mainClass.set("baker.BakeKt")
    classpath = sourceSets["main"].runtimeClasspath
    val map = rootProject.file("app/src/main/assets/venice.map")
    val out = rootProject.layout.projectDirectory.dir("app/build/generated/baked/tiles").asFile
    args(map.path, out.path)
    inputs.file(map)
    inputs.dir("../app/src/main/java/com/blazebaal/venice/core")
    inputs.dir("src/main/kotlin")
    outputs.dir(out)
    maxHeapSize = "3g"
}
