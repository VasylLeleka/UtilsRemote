
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Properties
import java.io.FileInputStream

/**
 * Represents the base URL format for the repository.
 */
val repository = "https://%sgithub.com/SpaceBank/Android-Space"

val defaultBranches = hashMapOf<String, String>()


fun parseModules(filePath: String): HashMap<String, List<ModuleConfig>?> {
    val modulesMap = HashMap<String, List<ModuleConfig>?>()
    val properties = Properties()
    File(filePath).inputStream().use { inputStream ->
        properties.load(inputStream)
    }

    for (entry in properties.entries) {
        val moduleName = entry.key as String
        val configsValue = entry.value as String
        val included = configsValue.contains("included=true")
        if (!included) {
            
            if (configsValue.contains("configs=null")) {
                modulesMap[moduleName] = null
            } else {
                val configsList = configsValue
                    .substringAfter("configs=")
                    .split(";")
                    .map {
                        val configParts = it.split(",")
                        ModuleConfig(parentPath = configParts[0], modulePath = configParts[1])
                    }
                modulesMap[moduleName] = configsList
            }
        }
    }

    return modulesMap
}

val modulesMap = parseModules("$rootDir/modules.properties")

/**
 * A mapping of module names to their respective configuration, if applicable.
 */
val modules =  modulesMap

/**
 * Defines the configuration for a module including its parent path and module path.
 */
data class ModuleConfig(val parentPath: String, val modulePath: String)

/**
 * Enum representing ANSI colors for terminal output.
 */
enum class AnsiColor(val code: String) {
    RESET("\u001b[0m"),
    BLUE("\u001b[94m"),
    GREEN("\u001b[92m"),
    RED("\u001b[91m"),
    MAGENTA_BOLD("\u001b[1;35m"),
    MAGENTA("\u001b[38;2;255;0;191m"),
    YELLOW("\u001b[93m");
}

/**
 * Interface defining callbacks for Git operation outcomes.
 */
interface GitShellCallback {
    fun onSuccess(message: String, module: String)
    fun onInfo(message: String)
    fun onError(errorMessage: String)
}

/**
 * Executes the appropriate Git command for the given module and handles the callback responses.
 *
 * @param module The name of the module to be processed.
 * @param callback The callback interface implementation for handling Git operation outcomes.
 */
fun cloneOrPullModule(module: String, callback: GitShellCallback) {
    val token: String = settings.extra.properties["repo_token"] as? String ?: ""
    val command = determineGitCommand(module, token.tokenAsParameter())
    callback.onInfo("Executing command: ${command.joinToString(" ")}".colorize(AnsiColor.BLUE))

    val processBuilder = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = StringBuilder()
    BufferedReader(InputStreamReader(processBuilder.inputStream)).use { reader ->
        var previousLineLength = 0
        var line = reader.readLine()
        while (line != null) {
            output.append(line).append("\n")
            handleProgressUpdate(line, previousLineLength).let {
                previousLineLength = it
            }
            line = reader.readLine()
        }
    }

    // Adds a newline for clarity if the command was "clone"
    if (command.contains("clone")) println()

    // Process completion
    if (processBuilder.waitFor() == 0) {
        callback.onSuccess("Operation completed successfully for module $module.", module)
    } else {
        callback.onError("Operation failed for module $module. See output for details:\n$output")
    }
    println()
}

/**
 * Checks if current branch is detached head before pulling changes to avoid DETACHED HEAD merging issue
 *
 * If we are on detached head (tag) we need to move on dummy branch(master) and after that pull changes
 *
 * @param repoPath
 */
fun checkForDetachedHead(repoPath: String) {
    val cmd = arrayOf(
        "git",
        "-C",
        repoPath,
        "rev-parse",
        "--abbrev-ref",
        "HEAD",
    )
    val processBuilder = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val output = StringBuilder()
    BufferedReader(InputStreamReader(processBuilder.inputStream)).use { reader ->
        var previousLineLength = 0
        var line = reader.readLine()
        if (line == "HEAD") {
            val cmd = arrayOf(
                "git",
                "-C",
                repoPath,
                "checkout",
                "master"
            )
            ProcessBuilder(*cmd).start().waitFor()
        }
    }
}

/**
 * Determines the Git command to execute based on whether the module repository exists locally.
 *
 * @param module The module name.
 * @param token The Git access token.
 * @return An array representing the Git command to execute.
 */
fun determineGitCommand(module: String, token: String): Array<String> {
    val repoPath = getRepoPath(module)
    return if (File(repoPath).exists()) {
        checkForDetachedHead(module)
        arrayOf("git", "-C", repoPath, "pull", "--progress")
    } else {
        arrayOf(
            "git",
            "clone",
            "--progress",
            "${String.format(repository, token)}-$module.git",
            repoPath
        )
    }
}

fun getRepoPath(module: String) = "./$module"

/**
 * Updates the console output based on the current line of Git progress.
 *
 * @param line The current line of output from Git.
 * @param previousLineLength The length of the previous line for overwriting purposes.
 * @return The length of the current line to be used for the next update.
 */
fun handleProgressUpdate(line: String, previousLineLength: Int): Int {
    if (line.startsWith("remote: Counting objects:") || line.startsWith("remote: Compressing objects:") ||
        line.startsWith("Receiving objects:") || line.startsWith("Resolving deltas:")
    ) {
        System.out.print("\r\u001b[K${line.padEnd(previousLineLength, ' ')}")
        System.out.flush()
        return line.length
    } else {
        println(line)
        return 0
    }
}

/**
 * Checks if the specified directory contains a build.gradle.kts file.
 *
 * @param directoryPath The path of the directory to check.
 * @return `true` if the directory contains a build.gradle.kts file, `false` otherwise.
 */
fun hasBuildGradleKts(directoryPath: String): Boolean {
    val directory = File("${rootProject.projectDir}/$directoryPath")
    return directory.isDirectory && directory.listFiles()
        ?.any { it.name == "build.gradle.kts" || it.name == "settings.gradle.kts" } ?: false
}

/**
 * Attaches the specified module to the project, configuring its directory based on predefined settings.
 * Prints a success message if the module has specific configurations, or a default attachment message otherwise.
 *
 * @param module The name of the module to attach.
 */
fun attachModuleToProject(module: String) {
    modules[module]?.forEach { config ->
        if (!hasBuildGradleKts(config.modulePath)) {
            println(
                "\nFailed to attach module '$module'. Please ensure the module configuration is correct and try again. If issues persist, consider syncing Gradle manually.\n".colorize(
                    AnsiColor.RED
                )
            )
            return
        }

        include(":${config.parentPath}")
        project(":${config.parentPath}").projectDir = file(config.modulePath)
        // Updated success message to be more informative and use a color indicating success
        println(
            "Module '${
                config.modulePath.split("/").last()
            }' successfully attached at '${config.parentPath}'".colorize(AnsiColor.MAGENTA)
        )
    } ?: println(
        "No specific configuration for module '$module', attached by default.".colorize(
            AnsiColor.YELLOW
        )
    )
}

fun setDefaultBranch(module: String) {
    val defaultBranch = defaultBranches[module]
    val cmd = arrayOf(
        "git",
        "-C",
        getRepoPath(module),
        "checkout",
        defaultBranch
    )
    val message = "Switching branch to $defaultBranch"
    println(message.colorize(AnsiColor.GREEN))
    ProcessBuilder(*cmd).start().waitFor()
}

/**
 * method will check enviroment if it's CI or local machine, if it's CI will returns true otherwise retuns false
 *
 * @return Boolean depend on enviroment
 */
fun isCIEnvironment(): Boolean {
    val ciEnv: String? = System.getenv("CI")
    return ciEnv != null && ciEnv.equals("true", ignoreCase = true)
}

/**
 * Fetches all specified modules by executing Git commands for each.
 */
fun fetchAllModules() {
    val isAutoCheckoutEnabled = isAutoCheckoutEnabled()
    if (isAutoCheckoutEnabled && (isSyncing() || isCIEnvironment())) {
        readEnvironmentConfig()
    }
    for (module in modules.keys) {
        if (isSyncing().not() && !isCIEnvironment()) {
            attachModuleToProject(module)
        } else {
            cloneOrPullModule(module, object : GitShellCallback {
                override fun onSuccess(message: String, module: String) {
                    println(message.colorize(AnsiColor.GREEN))
                    attachModuleToProject(module)
                    if (isAutoCheckoutEnabled) {
                        setDefaultBranch(module)
                    }
                }

                override fun onInfo(message: String) = println(message)
                override fun onError(errorMessage: String) {
                    println(errorMessage.colorize(AnsiColor.RED))
                    throw SyncFailedException("Sync failed, please check error message above")
                }
            })
        }
    }
}

fun isSyncing() = System.getProperty("idea.sync.active") == "true"

/**
 * Checks for is_auto_checkout_enabled property if we should automatically check out branches from dev.config on gradle sync
 */
fun isAutoCheckoutEnabled(): Boolean {
    return try {
        val localProperties = Properties().apply {
            load(File("local.properties").inputStream())
        }
        val isAutoCheckoutEnabled =
            localProperties.getOrDefault("is_auto_checkout_enabled", "true").toString().toBoolean()
        val status = if (isAutoCheckoutEnabled) "enabled" else "disabled"
        println(
            ("Auto checkout of branches from dev.config is $status when syncing gradle" +
                "\nIf you want to disable that functionality add is_auto_checkout_enabled=false in local.properties").colorize(
                AnsiColor.GREEN
            )
        )
        return isAutoCheckoutEnabled
    } catch (e: Exception) {
        true
    }
}

fun readEnvironmentConfig() {
    val envValue = settings.extra.get("app_env").toString()
    val env = Environment.fromValue(envValue)
    val infoFile = File(env.filePath)
    infoFile.readLines().forEach {
        val (module, branch) = it.split("=")
        defaultBranches[module] = branch
        println("Default branch for $module is $branch")
    }
}

enum class Environment(val envValue: String, val filePath: String) {
    Production("prod", "config/prod.config"),
    Development("dev", "config/dev.config");

    companion object {
        fun fromValue(value: String) = Environment.values().firstOrNull { it.envValue == value }
            ?: throw IllegalStateException(
                """
                    $value is not correct environment,
                    use: ${Production.envValue} or ${Development.envValue}
                """.trimIndent()
            )
    }
}

/**
 * Converts a nullable [String] token into a parameter format for use in URL strings.
 *
 * If the token is not null or empty, it appends an "@" symbol to the token,
 * making it suitable for embedding directly into URL credentials. If the token
 * is null or empty, it returns an empty string.
 *
 * @return The token formatted as a URL parameter if not null or empty; otherwise, an empty string.
 */
fun String?.tokenAsParameter(): String = if (!this.isNullOrEmpty()) "$this@" else ""

/**
 * Extension function to colorize console output.
 *
 * @param color The ANSI color to apply.
 * @return The colorized string.
 */
fun String.colorize(color: AnsiColor): String = "${color.code}$this${AnsiColor.RESET.code}"


/**
 * initiate module fetching.
 */
fetchAllModules()

data class SyncFailedException(override val message: String): Exception(message)
