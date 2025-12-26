import dev.yorkie.configureMavenPublish
import org.gradle.api.Plugin
import org.gradle.api.Project

class MavenPublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("maven-publish")
                apply("signing")
                apply("org.jetbrains.dokka")
            }

            configureMavenPublish()
        }
    }
}
