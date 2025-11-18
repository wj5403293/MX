package moe.fuqiuluo.mamu.utils
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Root Shell 执行结果
 */
sealed class ShellResult {
    data class Success(val output: String, val exitCode: Int = 0) : ShellResult()
    data class Error(val message: String, val exitCode: Int = -1) : ShellResult()
    data class Timeout(val duration: Long) : ShellResult()
}

/**
 * Shell 配置
 */
data class ShellConfig(
    val timeoutMs: Long = 5000L,
    val mergeErrorStream: Boolean = true,
    val suCmd: String,
)

/**
 * Root Shell 接口
 */
interface RootShell : AutoCloseable {
    fun execute(
        suCmd: String,
        command: String,
        config: ShellConfig = ShellConfig(suCmd = suCmd)
    ): ShellResult

    fun executeAsync(
        suCmd: String,
        command: String,
        config: ShellConfig = ShellConfig(suCmd = suCmd),
        callback: (ShellResult) -> Unit
    )

    fun executeNoWait(suCmd: String, command: String)
}

/**
 * 一次性 Root Shell 实现
 */
internal object OneshotRootShell : RootShell {
    private const val TAG = "OneshotRootShell"

    override fun execute(suCmd: String, command: String, config: ShellConfig): ShellResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(suCmd, "-c", command))

            val outputBuilder = StringBuilder()
            val outputReader = Thread {
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        outputBuilder.appendLine(line)
                    }
                }
            }

            val errorBuilder = StringBuilder()
            val errorReader = if (config.mergeErrorStream) {
                null
            } else {
                Thread {
                    process.errorStream.bufferedReader().use { reader ->
                        reader.forEachLine { line ->
                            errorBuilder.appendLine(line)
                        }
                    }
                }
            }

            outputReader.start()
            errorReader?.start()

            val finished = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.waitFor(config.timeoutMs, TimeUnit.MILLISECONDS)
            } else run {
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < config.timeoutMs) {
                    try {
                        process.exitValue()
                        return@run true
                    } catch (e: IllegalThreadStateException) {
                        Thread.sleep(50)
                    }
                }
                false
            }

            if (!finished) {
                process.destroy()
                return ShellResult.Timeout(config.timeoutMs)
            }

            outputReader.join()
            errorReader?.join()

            val exitCode = process.exitValue()
            val output = if (config.mergeErrorStream) {
                outputBuilder.toString().trim()
            } else {
                (outputBuilder.toString() + errorBuilder.toString()).trim()
            }

            if (exitCode == 0) {
                ShellResult.Success(output, exitCode)
            } else {
                ShellResult.Error(
                    output.ifEmpty { "Command failed with exit code $exitCode" },
                    exitCode
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command: $command", e)
            ShellResult.Error(e.message ?: "Unknown error", -1)
        }
    }

    override fun executeAsync(
        suCmd: String,
        command: String,
        config: ShellConfig,
        callback: (ShellResult) -> Unit
    ) {
        Thread {
            callback(execute(suCmd, command, config))
        }.start()
    }

    override fun executeNoWait(suCmd: String, command: String) {
        Thread {
            try {
                Runtime.getRuntime().exec(arrayOf(suCmd, "-c", command))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute command (no wait): $command", e)
            }
        }.start()
    }

    override fun close() {
        // oneshot 无需清理
    }
}

/**
 * 持久化 Root Shell 实现
 */
class PersistentRootShell internal constructor(
    private val defaultConfig: ShellConfig
) : RootShell {
    private val TAG = "PersistentRootShell"

    private val process: Process by lazy {
        if (defaultConfig.mergeErrorStream) {
            ProcessBuilder(defaultConfig.suCmd)
                .redirectErrorStream(true)
                .start()
        } else {
            Runtime.getRuntime().exec(defaultConfig.suCmd)
        }
    }

    private val writer: BufferedWriter by lazy {
        process.outputStream.bufferedWriter()
    }

    private val reader: BufferedReader by lazy {
        process.inputStream.bufferedReader()
    }

    private val marker = "<<<MAMU_CMD_END>>>"
    private var closed = false

    @Synchronized
    override fun execute(suCmd: String, command: String, config: ShellConfig): ShellResult {
        if (closed) {
            return ShellResult.Error("Shell is closed", -1)
        }

        return try {
            // 修复：用引号包围 marker，防止被解析为 here-string
            val cmd = "$command; echo '$marker' \$?\n".also {
                Log.d(TAG, "Executing command: $it")
            }

            writer.write(cmd)
            writer.flush()

            val startTime = System.currentTimeMillis()
            val output = StringBuilder()
            var exitCode = -1
            var foundMarker = false

            while (!foundMarker) {
                if (System.currentTimeMillis() - startTime > config.timeoutMs) {
                    return ShellResult.Timeout(config.timeoutMs)
                }

                val line = reader.readLine() ?: break

                if (line.startsWith(marker)) {
                    val parts = line.split(" ")
                    exitCode = parts.getOrNull(1)?.toIntOrNull() ?: -1
                    foundMarker = true
                } else {
                    output.appendLine(line)
                }
            }

            val result = output.toString().trim()
            if (exitCode == 0) {
                ShellResult.Success(result, exitCode)
            } else {
                ShellResult.Error(
                    result.ifEmpty { "Command failed with exit code $exitCode" },
                    exitCode
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command: $command", e)
            ShellResult.Error(e.message ?: "Unknown error", -1)
        }
    }

    override fun executeAsync(
        suCmd: String,
        command: String,
        config: ShellConfig,
        callback: (ShellResult) -> Unit
    ) {
        Thread {
            callback(execute(suCmd, command, config))
        }.start()
    }

    @Synchronized
    override fun executeNoWait(suCmd: String, command: String) {
        if (closed) {
            Log.w(TAG, "Shell is closed, cannot execute: $command")
            return
        }

        try {
            // 修复：不需要再次使用 suCmd，已经在 root shell 中了
            writer.write("$command\n")
            writer.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command (no wait): $command", e)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return

        try {
            writer.write("exit\n")
            writer.flush()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.waitFor(1000, TimeUnit.MILLISECONDS)
            } else {
                process.waitFor()
            }
            writer.close()
            reader.close()
            process.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close shell", e)
        } finally {
            closed = true
        }
    }
}

/**
 * Root Shell 执行器
 */
object RootShellExecutor {
    private const val TAG = "RootShellExecutor"

    /**
     * 一次性执行命令
     */
    fun exec(
        suCmd: String,
        command: String,
        timeoutMs: Long = 5000L
    ): ShellResult =
        OneshotRootShell.execute(suCmd, command, ShellConfig(suCmd = suCmd, timeoutMs = timeoutMs))

    /**
     * 批量一次性执行命令
     */
    fun execBatch(
        suCmd: String,
        commands: List<String>,
        timeoutMs: Long = 5000L
    ): List<ShellResult> {
        return commands.map { exec(suCmd, it, timeoutMs) }
    }

    /**
     * 创建持久化 Shell
     */
    fun persistent(
        suCmd: String,
        config: ShellConfig = ShellConfig(suCmd = suCmd)
    ): PersistentRootShell {
        return PersistentRootShell(config)
    }

    /**
     * DSL 风格：使用持久化 Shell 执行多条命令
     */
    inline fun <T> withPersistentShell(
        suCmd: String,
        config: ShellConfig = ShellConfig(suCmd = suCmd),
        block: PersistentRootShell.() -> T
    ): T = persistent(suCmd = suCmd, config = config).use(block)

    /**
     * Fire and forget - 不等待结果
     */
    fun execNoWait(suCmd: String, command: String) {
        OneshotRootShell.executeNoWait(suCmd, command)
    }
}

/**
 * String 扩展：直接作为 root 命令执行
 */
fun String.asRootCommand(suCmd: String, timeoutMs: Long = 5000L): ShellResult =
    RootShellExecutor.exec(suCmd, this, timeoutMs)

/**
 * ShellResult 扩展：成功时执行回调
 */
inline fun ShellResult.onSuccess(block: (String) -> Unit): ShellResult {
    if (this is ShellResult.Success) block(output)
    return this
}

/**
 * ShellResult 扩展：失败时执行回调
 */
inline fun ShellResult.onError(block: (String) -> Unit): ShellResult {
    if (this is ShellResult.Error) block(message)
    return this
}

/**
 * ShellResult 扩展：超时时执行回调
 */
inline fun ShellResult.onTimeout(block: (Long) -> Unit): ShellResult {
    if (this is ShellResult.Timeout) block(duration)
    return this
}

/**
 * ShellResult 扩展：获取结果或返回 null
 */
fun ShellResult.getOrNull(): String? =
    (this as? ShellResult.Success)?.output

/**
 * ShellResult 扩展：获取结果或返回默认值
 */
fun ShellResult.getOrDefault(default: String): String =
    (this as? ShellResult.Success)?.output ?: default

/**
 * ShellResult 扩展：获取结果或抛出异常
 */
fun ShellResult.getOrThrow(): String = when (this) {
    is ShellResult.Success -> output
    is ShellResult.Error -> throw RuntimeException("Command failed: $message (exit code: $exitCode)")
    is ShellResult.Timeout -> throw TimeoutException("Command timeout after ${duration}ms")
}

/**
 * ShellResult 扩展：判断是否成功
 */
fun ShellResult.isSuccess(): Boolean = this is ShellResult.Success

/**
 * ShellResult 扩展：判断是否失败
 */
fun ShellResult.isError(): Boolean = this is ShellResult.Error

/**
 * ShellResult 扩展：判断是否超时
 */
fun ShellResult.isTimeout(): Boolean = this is ShellResult.Timeout