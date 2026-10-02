// Корневой build-файл: сами модули настраиваются в app/build.gradle.kts и
// core/build.gradle.kts, здесь только регистрация версий плагинов (apply false).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
