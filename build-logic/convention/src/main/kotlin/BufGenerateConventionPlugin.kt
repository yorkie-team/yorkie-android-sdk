import com.android.build.gradle.tasks.SourceJarTask
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Convention plugin for Protocol Buffer generation using buf.
 *
 * This plugin provides:
 * - Optimized buf generation with incremental build support
 * - Build cache configuration
 * - Proto file cleanup tasks
 * - Automatic integration with Kotlin compilation and source JAR generation
 */
class BufGenerateConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // Apply buf plugin
            pluginManager.apply("build.buf")

            /*
             * === Enhanced Protocol Buffer Generation with Incremental Build Support ===
             *
             * This configuration optimizes buf generation to only run when necessary:
             *
             * 1. **Incremental Builds**: bufGenerate only runs when proto files or buf config change
             * 2. **Build Cache**: Generated files are cached and reused when possible
             * 3. **Rate Limit Protection**: Avoids unnecessary network calls to buf.build
             *
             * Available tasks:
             * - `./gradlew :yorkie:cleanGeneratedProto` - Clean generated proto files
             * - `./gradlew :yorkie:bufGenerate` - Generate proto files (only if needed)
             */

            // Configure buf generation with incremental build support
            tasks.named("bufGenerate") {
                notCompatibleWithConfigurationCache("buf generation requires network access")

                // Configure inputs - proto files and buf configuration
                // This ensures the task only runs when these files change
                inputs.files(
                    fileTree("proto") {
                        include("**/*.proto")
                    },
                )
                inputs.files("buf.gen.yaml", "buf.work.yaml", "buf.yaml")

                // Configure outputs - generated code directory
                // This enables Gradle's up-to-date checking and build cache
                val generatedDir = "${layout.buildDirectory.asFile.get()}/bufbuild/generated"
                outputs.dir(generatedDir)
                outputs.cacheIf { true }

                doFirst {
                    println("Running buf generate - proto files or configuration changed")
                }
            }

            // Register task to clean generated proto files
            tasks.register("cleanGeneratedProto") {
                description = "Clean generated proto files"
                group = "build"
                notCompatibleWithConfigurationCache("Deletes files")

                doLast {
                    val generatedDir =
                        File(layout.buildDirectory.asFile.get(), "bufbuild/generated")
                    if (generatedDir.exists()) {
                        generatedDir.deleteRecursively()
                        println("Cleaned generated proto files")
                    }
                }
            }

            // Make bufGenerate depend on cleaning when proto files change
            tasks.named("bufGenerate") {
                // Only clean and regenerate if proto files actually changed
                inputs.files(fileTree("proto")).skipWhenEmpty()
                mustRunAfter("cleanGeneratedProto")
            }

            // Ensure buf generation runs before Kotlin compilation
            tasks.withType<KotlinCompile>().configureEach {
                dependsOn("bufGenerate")
            }

            // Ensure buf generation runs before source JAR creation
            tasks.withType<SourceJarTask>().configureEach {
                if (name == "sourceReleaseJar") {
                    dependsOn("bufGenerate")
                }
            }

            // Configure Android source sets to include generated proto files
            afterEvaluate {
                extensions.findByName("android")?.let { androidExt ->
                    // Use reflection to access sourceSets for compatibility
                    val sourceSetsMethod = androidExt.javaClass.getMethod("getSourceSets")
                    val sourceSets =
                        sourceSetsMethod.invoke(androidExt) as org.gradle.api.NamedDomainObjectContainer<*>

                    val mainSourceSet = sourceSets.findByName("main")
                    mainSourceSet?.let { sourceSet ->
                        val javaMethod = sourceSet.javaClass.getMethod("getJava")
                        val javaSourceSet = javaMethod.invoke(sourceSet)

                        val srcDirMethod =
                            javaSourceSet.javaClass.getMethod("srcDirs", Array<Any>::class.java)
                        val generatedDir =
                            "${layout.buildDirectory.asFile.get()}/bufbuild/generated"
                        srcDirMethod.invoke(
                            javaSourceSet,
                            arrayOf("${generatedDir}/kotlin", "${generatedDir}/java"),
                        )
                    }
                }
            }
        }
    }
}

