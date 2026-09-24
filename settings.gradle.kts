pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // open-source native engines: sherpa-onnx (TTS/STT) + tesseract4android (OCR)
        maven("https://jitpack.io")
    }
}
rootProject.name = "LinguaMod"
include(":app")
