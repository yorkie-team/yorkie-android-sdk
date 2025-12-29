import dev.yorkie.configureProtocolBufferGeneration
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Convention plugin for Protocol Buffer generation using buf.
 *
 * This plugin provides:
 * - Optimized buf generation with incremental build support
 * - Build cache configuration
 * - Proto file cleanup tasks
 * - Automatic integration with Kotlin compilation and source JAR generation
 */
class ProtocolBufferGenerationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // Apply buf plugin
            pluginManager.apply("build.buf")
            configureProtocolBufferGeneration()
        }
    }
}
