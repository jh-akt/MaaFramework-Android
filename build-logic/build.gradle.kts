plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    compileOnly("com.android.tools.build:gradle:8.13.2")
}

gradlePlugin {
    plugins {
        create("maaHostApplication") {
            id = "com.maaframework.android.host-application"
            implementationClass = "com.maaframework.android.buildlogic.MaaHostApplicationPlugin"
        }
    }
}
