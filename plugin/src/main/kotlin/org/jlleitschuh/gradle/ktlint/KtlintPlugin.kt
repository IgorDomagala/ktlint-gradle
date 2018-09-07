package org.jlleitschuh.gradle.ktlint

import com.android.build.gradle.AppPlugin
import com.android.build.gradle.FeaturePlugin
import com.android.build.gradle.InstantAppPlugin
import com.android.build.gradle.LibraryPlugin
import com.android.build.gradle.TestPlugin
import com.android.build.gradle.internal.VariantManager
import net.swiftzer.semver.SemVer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.internal.HasConvention
import org.gradle.api.plugins.Convention
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.JavaExec
import org.jetbrains.kotlin.gradle.plugin.KonanArtifactContainer
import org.jetbrains.kotlin.gradle.plugin.KonanExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.experimental.KotlinNativeComponent
import org.jetbrains.kotlin.gradle.plugin.tasks.KonanCompileTask
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import java.io.File
import kotlin.reflect.KClass

/**
 * Plugin that provides a wrapper over the `ktlint` project.
 */
open class KtlintPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val extension = target.plugins.apply(KtlintBasePlugin::class.java).extension
        // Apply the idea plugin
        target.plugins.apply(KtlintIdeaPlugin::class.java)

        addKtLintTasksToKotlinPlugin(target, extension)
    }

    private fun addKtLintTasksToKotlinPlugin(target: Project, extension: KtlintExtension) {
        target.plugins.withId("kotlin", applyKtLint(target, extension))
        target.plugins.withId("kotlin2js", applyKtLint(target, extension))
        target.plugins.withId("kotlin-platform-common", applyKtLint(target, extension))
        target.plugins.withId("kotlin-android", applyKtLintToAndroid(target, extension))
        target.plugins.withId("konan", applyKtLintKonanNative(target, extension))
        target.plugins.withId(
            "org.jetbrains.kotlin.native",
            applyKtLintNative(target, extension)
        )
    }

    private fun applyKtLint(
        target: Project,
        extension: KtlintExtension
    ): (Plugin<in Any>) -> Unit {
        return { _ ->
            val ktLintConfig = createConfiguration(target, extension)

            target.theHelper<JavaPluginConvention>().sourceSets.forEach { sourceSet ->
                val kotlinSourceSet: SourceDirectorySet = (sourceSet as HasConvention)
                    .convention
                    .getPluginHelper<KotlinSourceSet>()
                    .kotlin
                val checkTask = createCheckTask(
                    target,
                    extension,
                    sourceSet.name,
                    ktLintConfig,
                    kotlinSourceSet.sourceDirectories
                )

                addKtlintCheckTaskToProjectMetaCheckTask(target, checkTask)
                setCheckTaskDependsOnKtlintCheckTask(target, checkTask)

                val runArgs = kotlinSourceSet.sourceDirectories.files.flatMap { baseDir ->
                    KOTLIN_EXTENSIONS.map { "${baseDir.path}/**/*.$it" }
                }.toMutableSet()
                addAdditionalRunArgs(extension, runArgs)

                val ktlintSourceSetFormatTask = createFormatTask(
                    target,
                    sourceSet.name,
                    ktLintConfig,
                    kotlinSourceSet,
                    runArgs
                )

                addKtlintFormatTaskToProjectMetaFormatTask(target, ktlintSourceSetFormatTask)
            }
        }
    }

    private fun applyKtLintToAndroid(
        target: Project,
        extension: KtlintExtension
    ): (Plugin<in Any>) -> Unit {
        return { _ ->
            val ktLintConfig = createConfiguration(target, extension)

            val createTasks: (VariantManager) -> Unit = { variantManager ->
                // If project has not been yet evaluated - variant data will be empty.
                // Calling this will ensure that variant data is available.
                variantManager.populateVariantDataList()

                variantManager.variantScopes.forEach { variantScope ->
                    val sourceDirs = variantScope.variantData.javaSources
                        .fold(mutableListOf<File>()) { acc, configurableFileTree ->
                            acc.add(configurableFileTree.dir)
                            acc
                        }
                    // Don't use it.variantData.javaSources directly as it will trigger some android tasks execution
                    val kotlinSourceDir = target.files(*sourceDirs.toTypedArray())
                    val runArgs = variantScope.variantData.javaSources.map { "${it.dir.path}/**/*.kt" }.toMutableSet()
                    addAdditionalRunArgs(extension, runArgs)

                    val checkTask = createCheckTask(
                        target,
                        extension,
                        variantScope.fullVariantName,
                        ktLintConfig,
                        sourceDirs
                    )

                    addKtlintCheckTaskToProjectMetaCheckTask(target, checkTask)
                    setCheckTaskDependsOnKtlintCheckTask(target, checkTask)

                    val ktlintSourceSetFormatTask = createFormatTask(
                        target,
                        variantScope.fullVariantName,
                        ktLintConfig,
                        kotlinSourceDir,
                        runArgs
                    )

                    addKtlintFormatTaskToProjectMetaFormatTask(target, ktlintSourceSetFormatTask)
                }
            }

            target.plugins.withId("com.android.application") { plugin ->
                (plugin as AppPlugin).variantManager.run(createTasks)
            }
            target.plugins.withId("com.android.library") { plugin ->
                (plugin as LibraryPlugin).variantManager.run(createTasks)
            }
            target.plugins.withId("com.android.instantapp") { plugin ->
                (plugin as InstantAppPlugin).variantManager.run(createTasks)
            }
            target.plugins.withId("com.android.feature") { plugin ->
                (plugin as FeaturePlugin).variantManager.run(createTasks)
            }
            target.plugins.withId("com.android.test") { plugin ->
                (plugin as TestPlugin).variantManager.run(createTasks)
            }
        }
    }

    private fun applyKtLintKonanNative(
        project: Project,
        extension: KtlintExtension
    ): (Plugin<in Any>) -> Unit {
        return { _ ->
            val ktLintConfig = createConfiguration(project, extension)

            val compileTargets = project.theHelper<KonanExtension>().targets
            project.theHelper<KonanArtifactContainer>().whenObjectAdded { buildConfig ->
                addTasksForNativePlugin(project, extension, buildConfig.name, ktLintConfig) {
                    compileTargets.fold(initial = emptyList()) { acc, target ->
                        val compileTask = buildConfig.findByTarget(target)
                        if (compileTask != null) {
                            val sourceFiles = (compileTask as KonanCompileTask).srcFiles
                            acc + sourceFiles
                        } else {
                            acc
                        }
                    }
                }
            }
        }
    }

    private fun applyKtLintNative(
        project: Project,
        extension: KtlintExtension
    ): (Plugin<in Any>) -> Unit {
        return { _ ->
            val ktLintConfig = createConfiguration(project, extension)

            project.components.withType(KotlinNativeComponent::class.java) { component ->
                addTasksForNativePlugin(project, extension, component.name, ktLintConfig) {
                    component.konanTargets.get()
                        .fold(initial = emptyList()) { acc, nativeTarget ->
                            acc + listOf(component.sources.getAllSources(nativeTarget))
                        }
                }
            }
        }
    }

    private fun addTasksForNativePlugin(
        project: Project,
        extension: KtlintExtension,
        sourceSetName: String,
        ktlintConfiguration: Configuration,
        gatherVariantSources: () -> List<FileCollection>
    ) {
        val sourceDirectoriesList = gatherVariantSources()
        if (sourceDirectoriesList.isNotEmpty()) {
            val checkTask = createCheckTask(
                project,
                extension,
                sourceSetName,
                ktlintConfiguration,
                sourceDirectoriesList
            )
            addKtlintCheckTaskToProjectMetaCheckTask(project, checkTask)
            setCheckTaskDependsOnKtlintCheckTask(project, checkTask)

            val kotlinSourceSet = sourceDirectoriesList.reduce { acc, fileCollection ->
                acc.plus(fileCollection)
            }
            val runArgs = kotlinSourceSet.files.map { "${it.path}/**/*.kt" }.toMutableSet()
            addAdditionalRunArgs(extension, runArgs)

            val ktlintSourceSetFormatTask = createFormatTask(
                project,
                sourceSetName,
                ktlintConfiguration,
                kotlinSourceSet,
                runArgs
            )
            addKtlintFormatTaskToProjectMetaFormatTask(project, ktlintSourceSetFormatTask)
        }
    }

    private fun addAdditionalRunArgs(extension: KtlintExtension, runArgs: MutableSet<String>) {
        if (extension.verbose) runArgs.add("--verbose")
        if (extension.debug) runArgs.add("--debug")
        if (extension.isAndroidFlagEnabled()) runArgs.add("--android")
        if (extension.ruleSets.isNotEmpty()) {
            extension.ruleSets.forEach { runArgs.add("--ruleset=$it") }
        }
    }

    private fun addKtlintCheckTaskToProjectMetaCheckTask(target: Project, checkTask: Task) {
        target.getMetaKtlintCheckTask().dependsOn(checkTask)
        if (target.rootProject != target) {
            target.rootProject.getMetaKtlintCheckTask().dependsOn(checkTask)
        }
    }

    private fun addKtlintFormatTaskToProjectMetaFormatTask(target: Project, formatTask: Task) {
        target.getMetaKtlintFormatTask().dependsOn(formatTask)
        if (target.rootProject != target) {
            target.rootProject.getMetaKtlintFormatTask().dependsOn(formatTask)
        }
    }

    private fun createFormatTask(
        target: Project,
        sourceSetName: String,
        ktLintConfig: Configuration,
        kotlinSourceSet: FileCollection,
        runArgs: MutableSet<String>
    ): Task {
        return target.taskHelper<JavaExec>("ktlint${sourceSetName.capitalize()}Format") {
            group = FORMATTING_GROUP
            description = "Runs a check against all .kt files to ensure that they are formatted according to ktlint."
            main = "com.github.shyiko.ktlint.Main"
            classpath = ktLintConfig
            inputs.files(kotlinSourceSet)
            // This copies the list
            val sourcePathsWithFormatFlag = runArgs.toMutableList()
            // Prepend the format flag to the beginning of the list
            sourcePathsWithFormatFlag.add(0, "-F")
            args(sourcePathsWithFormatFlag)
        }
    }

    private fun createCheckTask(
        target: Project,
        extension: KtlintExtension,
        sourceSetName: String,
        ktLintConfig: Configuration,
        kotlinSourceDirectories: Iterable<*>
    ): Task {
        return target.taskHelper<KtlintCheck>("ktlint${sourceSetName.capitalize()}Check") {
            group = VERIFICATION_GROUP
            description = "Runs a check against all .kt files to ensure that they are formatted according to ktlint."
            classpath.setFrom(ktLintConfig)
            sourceDirectories.setFrom(kotlinSourceDirectories)
            verbose.set(target.provider { extension.verbose })
            debug.set(target.provider { extension.debug })
            android.set(target.provider { extension.isAndroidFlagEnabled() })
            ignoreFailures.set(target.provider { extension.ignoreFailures })
            outputToConsole.set(target.provider { extension.outputToConsole })
            ruleSets.set(target.provider { extension.ruleSets.toList() })
            reports.forEach { _, report ->
                report.enabled.set(target.provider {
                    val reporterType = report.reporterType
                    reporterAvailable(extension.version, reporterType) && extension.reporters.contains(reporterType)
                })
                report.outputFile.set(target.layout.buildDirectory.file(target.provider {
                    "reports/ktlint/ktlint-$sourceSetName.${report.reporterType.fileExtension}"
                }))
            }
        }
    }

    private fun reporterAvailable(version: String, reporter: ReporterType) =
        SemVer.parse(version) >= reporter.availableSinceVersion

    private fun Project.getMetaKtlintCheckTask(): Task = this.tasks.findByName(CHECK_PARENT_TASK_NAME)
        ?: this.task(CHECK_PARENT_TASK_NAME).apply {
            group = VERIFICATION_GROUP
            description = "Runs ktlint on all kotlin sources in this project."
        }

    private fun Project.getMetaKtlintFormatTask(): Task = this.tasks.findByName(FORMAT_PARENT_TASK_NAME)
        ?: this.task(FORMAT_PARENT_TASK_NAME).apply {
            group = FORMATTING_GROUP
            description = "Runs the ktlint formatter on all kotlin sources in this project."
        }

    private fun setCheckTaskDependsOnKtlintCheckTask(project: Project, ktlintCheck: Task) {
        project.tasks.findByName("check")?.dependsOn(ktlintCheck)
    }

    /*
     * Helper functions used until Gradle Script Kotlin solidifies it's plugin API.
     */

    private inline fun <reified T : Any> Project.theHelper() =
        theHelper(T::class)

    private fun <T : Any> Project.theHelper(extensionType: KClass<T>) =
        convention.findPlugin(extensionType.java) ?: convention.getByType(extensionType.java)

    private inline fun <reified T> Convention.getPluginHelper() = getPlugin(T::class.java)
}
