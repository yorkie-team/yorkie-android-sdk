# Yorkie Android SDK ProGuard Rules

# =============================================================================
# Yorkie SDK Public API
# =============================================================================

# Core API
-keep class dev.yorkie.core.Client { *; }
-keep class dev.yorkie.core.Client$** { *; }

# Document API - Keep everything
-keep class dev.yorkie.document.** { *; }

# Utilities
-keep class dev.yorkie.util.Logger { *; }
-keep class dev.yorkie.util.Logger$** { *; }

# =============================================================================
# Protocol Buffers - CRITICAL: Prevent obfuscation for protobuf to work
# =============================================================================

# Keep generated protobuf API - allow optimization but preserve field names
-keepclassmembers class dev.yorkie.api.v1.** {
    <fields>;
}
-keepnames class dev.yorkie.api.v1.**

# Keep only necessary protobuf runtime classes
-keep class com.google.protobuf.MessageLite { *; }
-keep class com.google.protobuf.GeneratedMessageLite { *; }
-keep class com.google.protobuf.ExtensionRegistryLite { *; }
-dontwarn com.google.protobuf.**

# Keep protobuf generated classes with @Generated annotation
-keep @com.google.protobuf.Generated class dev.yorkie.api.v1.** { *; }

# Keep protobuf MessageLite hierarchy (for protobuf-javalite)
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepnames class * extends com.google.protobuf.GeneratedMessageLite
-keepclassmembernames class * extends com.google.protobuf.GeneratedMessageLite { *; }

# Keep protobuf Message hierarchy (for standard protobuf-java)
-keep class * extends com.google.protobuf.GeneratedMessage { *; }
-keepnames class * extends com.google.protobuf.GeneratedMessage
-keepclassmembernames class * extends com.google.protobuf.GeneratedMessage { *; }
