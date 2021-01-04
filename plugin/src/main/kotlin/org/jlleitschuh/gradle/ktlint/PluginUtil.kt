package org.jlleitschuh.gradle.ktlint

import net.swiftzer.semver.SemVer
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.configuration.ConsoleOutput
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.HelpTasksPlugin
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin
import java.io.File
import java.nio.file.Path

internal inline fun <reified T : Task> Project.registerTask(
    name: String,
    noinline configuration: T.() -> Unit
): TaskProvider<T> {
    return this.tasks.register(name, T::class.java, configuration)
}

internal const val EDITOR_CONFIG_FILE_NAME = ".editorconfig"

internal fun getEditorConfigFiles(
    currentProject: Project,
    additionalEditorconfigFile: RegularFileProperty
): FileCollection {
    var editorConfigFileCollection = searchEditorConfigFiles(
        currentProject,
        currentProject.projectDir.toPath(),
        currentProject.files()
    )

    if (additionalEditorconfigFile.isPresent) {
        editorConfigFileCollection = editorConfigFileCollection.plus(
            currentProject.files(additionalEditorconfigFile.asFile.get().toPath())
        )
    }

    return editorConfigFileCollection
}

private tailrec fun searchEditorConfigFiles(
    project: Project,
    projectPath: Path,
    fileCollection: FileCollection
): FileCollection {
    val editorConfigFC = projectPath.resolve(EDITOR_CONFIG_FILE_NAME)
    val outputCollection = if (editorConfigFC.toFile().exists()) {
        fileCollection.plus(project.files(editorConfigFC))
    } else {
        fileCollection
    }

    val parentDir = projectPath.parent
    return if (parentDir != null &&
        projectPath != project.rootDir.toPath() &&
        !editorConfigFC.isRootEditorConfig()
    ) {
        searchEditorConfigFiles(project, parentDir, outputCollection)
    } else {
        outputCollection
    }
}

private val editorConfigRootRegex = "^root\\s?=\\s?true\\R".toRegex()

private fun Path.isRootEditorConfig(): Boolean {
    val asFile = toFile()
    if (!asFile.exists() || !asFile.canRead()) return false

    val reader = asFile.bufferedReader()
    var fileLine = reader.readLine()
    while (fileLine != null) {
        if (fileLine.contains(editorConfigRootRegex)) {
            return true
        }
        fileLine = reader.readLine()
    }

    return false
}

internal const val VERIFICATION_GROUP = LifecycleBasePlugin.VERIFICATION_GROUP
internal const val FORMATTING_GROUP = "Formatting"
internal const val HELP_GROUP = HelpTasksPlugin.HELP_GROUP
internal const val CHECK_PARENT_TASK_NAME = "ktlintCheck"
internal const val FORMAT_PARENT_TASK_NAME = "kllintFormat"
internal const val APPLY_TO_IDEA_TASK_NAME = "ktlintApplyToIdea"
internal const val APPLY_TO_IDEA_GLOBALLY_TASK_NAME = "ktlintApplyToIdeaGlobally"
internal const val INSTALL_GIT_HOOK_CHECK_TASK = "addKtlintCheckGitPreCommitHook"
internal const val INSTALL_GIT_HOOK_FORMAT_TASK = "addKtlintFormatGitPreCommitHook"
internal val KOTLIN_EXTENSIONS = listOf("kt", "kts")
internal val INTERMEDIATE_RESULTS_PATH = "intermediates${File.separator}ktLint${File.separator}"

internal inline fun <reified T> ObjectFactory.property(
    configuration: Property<T>.() -> Unit = {}
) = property(T::class.java).apply(configuration)

internal inline fun <reified T> ObjectFactory.setProperty(
    configuration: SetProperty<T>.() -> Unit = {}
) = setProperty(T::class.java).apply(configuration)

internal fun Project.isConsolePlain() = gradle.startParameter.consoleOutput == ConsoleOutput.Plain

/**
 * Get file path where tasks could put their intermediate results, that could be consumed by other plugin tasks.
 */
internal fun ProjectLayout.intermediateResultsBuildDir(
    resultsFile: String
): Provider<RegularFile> = buildDirectory.file("$INTERMEDIATE_RESULTS_PATH$resultsFile")

/**
 * Logs into Gradle console KtLint debug message.
 */
internal fun Logger.logKtLintDebugMessage(
    debugIsEnabled: Boolean,
    logProducer: () -> List<String>
) {
    if (debugIsEnabled) {
        logProducer().forEach {
            warn("[KtLint DEBUG] $it")
        }
    }
}

internal fun checkMinimalSupportedKtLintVersion(ktLintVersion: String) {
    if (SemVer.parse(ktLintVersion) < SemVer(0, 34, 0)) {
        throw GradleException(
            "KtLint versions less than 0.34.0 are not supported. " +
                "Detected KtLint version: $ktLintVersion."
        )
    }
}
