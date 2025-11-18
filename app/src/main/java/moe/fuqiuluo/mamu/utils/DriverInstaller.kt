package moe.fuqiuluo.mamu.utils

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import moe.fuqiuluo.mamu.driver.WuwaDriver
import org.json.JSONObject
import java.io.File

/**
 * 驱动安装器
 * 负责驱动的检查和安装操作
 */
object DriverInstaller {
    private const val TAG = "DriverInstaller"

    /**
     * 检查驱动是否已安装
     * @return 是否已安装
     */
    fun isDriverInstalled(app: Context): Boolean {
        if (checkAndSetupDriver(app).first)  {
            return true
        }
        return WuwaDriver.loaded
    }

    /**
     * 检查并设置驱动FD
     * @param app Application实例
     * @return Pair<是否已安装, 驱动FD>
     */
    fun checkAndSetupDriver(app: Context): Pair<Boolean, Int?> {
        return try {
            // 释放supreme可执行文件
            val supremeFile = extractSupremeExecutable(app) ?: return Pair(false, null)

            // 执行supreme检查驱动
            val pid = Process.myPid()
            val result = RootShellExecutor.exec(
                suCmd = RootConfigManager.getCustomRootCommand(),
                "${supremeFile.absolutePath} $pid"
            )

            when (result) {
                is ShellResult.Success -> {
                    val output = result.output.trim()
                    try {
                        val json = JSONObject(output)
                        val status = json.getString("status")
                        if (status == "success") {
                            val driverFd = json.getInt("driver_fd")
                            Log.d(TAG, "Driver installed successfully, fd: $driverFd")
                            // 设置驱动fd
                            WuwaDriver.setDriverFd(driverFd)
                            return Pair(true, driverFd)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse supreme output", e)
                    }
                    Pair(false, null)
                }

                else -> Pair(false, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking driver", e)
            Pair(false, null)
        }
    }

    /**
     * 从assets中释放supreme可执行文件
     */
    private fun extractSupremeExecutable(app: Context): File? {
        try {
            val assetName = when {
                android.os.Build.SUPPORTED_ABIS.any { it.contains("arm64") } -> "supreme_arm64"
                android.os.Build.SUPPORTED_ABIS.any { it.contains("x86_64") } -> "supreme_x64"
                else -> {
                    Log.e(
                        TAG,
                        "Unsupported architecture: ${android.os.Build.SUPPORTED_ABIS.joinToString()}"
                    )
                    return null
                }
            }

            val outputFile = File(app.filesDir, "supreme")

            // 如果文件已存在且可执行，直接返回
            if (outputFile.exists() && outputFile.canExecute()) {
                return outputFile
            }

            // 从assets复制文件
            app.assets.open(assetName).use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // 设置可执行权限
            val chmodResult = RootShellExecutor.exec(
                suCmd = RootConfigManager.getCustomRootCommand(),
                "chmod 755 ${outputFile.absolutePath}"
            )
            if (chmodResult !is ShellResult.Success) {
                Log.e(TAG, "Failed to chmod supreme")
                return null
            }

            return outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract supreme executable", e)
            return null
        }
    }
}