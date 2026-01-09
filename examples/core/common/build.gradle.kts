import dev.yorkie.dsl.implementation

plugins {
    alias(libs.plugins.yorkie.examples.android.library)
}

android {
    namespace = "dev.yorkie.example.core.common"
}

dependencies {
    implementation(projects.yorkie)

    implementation(libs.androidx.core)
    implementation(libs.timber)
}
