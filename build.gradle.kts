plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

// Kotlin 2.4 deliberately omits an empty Wasm yarn.lock when the project has no
// user npm dependencies, but its aggregate test graph still schedules the store
// task and Gradle 9 rejects the missing input first (KT-79669). Re-enable this
// task when a direct npm(...) dependency is introduced and a lockfile exists.
tasks.matching { it.name == "kotlinWasmStoreYarnLock" }.configureEach {
    enabled = false
}
