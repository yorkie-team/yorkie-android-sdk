plugins {
    alias(libs.plugins.yorkie.android.library)
}

android {
    namespace = "dev.yorkie.core.common"
}

dependencies {
    implementation(project(":yorkie"))

    implementation(libs.androidx.core)
    implementation(libs.timber)
}
