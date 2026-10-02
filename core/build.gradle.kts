// :core - чистый Kotlin/JVM модуль: протокол ELM327/OBD-II, разбор PID и
// кодов ошибок, модель-симулятор ЭБУ. Без Android SDK и без корутин, поэтому
// весь протокол покрывается обычными JUnit-тестами на JVM (./gradlew :core:test).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Kotlin по умолчанию целится в ту JDK, на которой запущен Gradle, и на JDK 21
// сборка падает с "Inconsistent JVM-target compatibility" из-за java-блока выше.
// Явный байт-код 17 делает сборку одинаковой и на машине разработчика, и в CI.
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    testImplementation(libs.junit4)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
