// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // plugin dari version catalog kamu
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false

    // ▶ Tambahkan plugin Google Services (apply false di level project)
    id("com.google.gms.google-services") version "4.4.2" apply false
}
