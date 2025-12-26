plugins {
    alias(libs.plugins.yorkie.examples.android.feature)
    alias(libs.plugins.yorkie.examples.android.library.compose)
}

android {
    namespace = "dev.yorkie.example.feature.enterdocumentkey"
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.compose.material)
}
