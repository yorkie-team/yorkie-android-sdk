plugins {
    alias(libs.plugins.yorkie.android.feature)
    alias(libs.plugins.yorkie.android.library.compose)
}

android {
    namespace = "com.example.feature.enterdocumentkey"
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.compose.material)
}
