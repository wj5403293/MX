package moe.fuqiuluo.mamu.utils

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 应用所需权限配置
 */
object PermissionConfig {
    /**
     * 应用需要通过root授予的权限列表
     */
    val REQUIRED_PERMISSIONS = listOf(
        "android.permission.QUERY_ALL_PACKAGES"
    )

    /**
     * 应用需要的AppOps权限
     */
    val REQUIRED_APP_OPS = listOf(
        "SYSTEM_ALERT_WINDOW" // 悬浮窗权限
    )
}

/**
 * 权限管理器
 * 负责授予应用所需的所有权限
 */
object PermissionManager {
    private const val TAG = "PermissionManager"

    /**
     * 检查结果
     */
    data class CheckResult(
        val hasRoot: Boolean,
        val allPermissionsGranted: Boolean,
        val driverInstalled: Boolean,
        val missingPermissions: List<String> = emptyList()
    ) {
        /**
         * 是否所有条件都满足
         */
        val allSatisfied: Boolean
            get() = hasRoot && allPermissionsGranted && driverInstalled
    }

    /**
     * 快速检查所有权限和驱动状态
     * @param context 应用上下文
     * @return 检查结果
     */
    suspend fun quickCheck(context: Context): CheckResult {
        return withContext(Dispatchers.IO) {
            // 检查Root权限
            val hasRoot = checkRootAccess()

            // 检查所有权限
            val missingPermissions = checkMissingPermissions(context)
            val allPermissionsGranted = missingPermissions.isEmpty()

            // 检查驱动
            val driverInstalled = DriverInstaller.isDriverInstalled(context)

            Log.d(
                TAG,
                "Quick check - hasRoot: $hasRoot, allPermissionsGranted: $allPermissionsGranted, driverInstalled: $driverInstalled"
            )

            CheckResult(
                hasRoot = hasRoot,
                allPermissionsGranted = allPermissionsGranted,
                driverInstalled = driverInstalled,
                missingPermissions = missingPermissions
            )
        }
    }

    /**
     * 检查Root权限
     */
    private fun checkRootAccess(): Boolean {
        return try {
            RootPermissionUtils.checkRootAccess()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking root access", e)
            false
        }
    }

    /**
     * 检查缺失的权限列表
     */
    private fun checkMissingPermissions(context: Context): List<String> {
        val missingPermissions = mutableListOf<String>()

        // 检查普通权限
        for (permission in PermissionConfig.REQUIRED_PERMISSIONS) {
            if (context.checkPermission(
                    permission,
                    Process.myPid(),
                    Process.myUid()
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                missingPermissions.add(permission)
            }
        }

        // 检查悬浮窗权限（AppOps）
        val hasOverlayPermission = Settings.canDrawOverlays(context)
        if (!hasOverlayPermission) {
            missingPermissions.add("SYSTEM_ALERT_WINDOW")
        }

        return missingPermissions
    }

    /**
     * 授予所有必需的权限
     * @param app Application实例
     * @param onProgress 进度回调 (current, total, permissionName)
     * @return Pair<授予成功的数量, 总数量>
     */
    suspend fun grantAllPermissions(
        app: Application,
        onProgress: (Int, Int, String) -> Unit
    ): Pair<Int, Int> {
        var grantedCount = 0
        val totalCount =
            PermissionConfig.REQUIRED_PERMISSIONS.size + PermissionConfig.REQUIRED_APP_OPS.size

        RootShellExecutor.withPersistentShell(suCmd = RootConfigManager.getCustomRootCommand()) {
            //Log.d(TAG, "开始自动授权 ${app.packageName}")

            // 授予普通权限
            PermissionConfig.REQUIRED_PERMISSIONS.forEachIndexed { index, permission ->
                Log.d(TAG, "授权权限: $permission")
                val current = index + 1
                onProgress(current, totalCount, permission)

                val result = grantPermission(this, app.packageName, permission)
                if (result) {
                    grantedCount++
                    Log.d(TAG, "Granted permission: $permission")
                } else {
                    Log.w(TAG, "Failed to grant permission: $permission")
                }
            }

            // 授予AppOps权限
            PermissionConfig.REQUIRED_APP_OPS.forEachIndexed { index, appOp ->
                Log.d(TAG, "授权 AppOp: $appOp")
                val current = PermissionConfig.REQUIRED_PERMISSIONS.size + index + 1
                onProgress(current, totalCount, appOp)

                val result = grantAppOp(this, app.packageName, appOp)
                if (result) {
                    grantedCount++
                    Log.d(TAG, "Granted AppOp: $appOp")
                } else {
                    Log.w(TAG, "Failed to grant AppOp: $appOp")
                }
            }

            return@withPersistentShell null
        }

        return Pair(grantedCount, totalCount)
    }

    /**
     * 授予单个权限
     */
    private suspend fun grantPermission(
        shell: PersistentRootShell,
        packageName: String,
        permission: String,
    ): Boolean {
        return withTimeoutOrNull(3.seconds) {
            suspendCancellableCoroutine { continuation ->
                val command = "pm grant $packageName $permission"
                shell.executeAsync(
                    suCmd = RootConfigManager.getCustomRootCommand(),
                    command = command,
                ) { result ->
                    when (result) {
                        is ShellResult.Success -> continuation.resume(true)
                        is ShellResult.Error -> {
                            Log.e(
                                TAG,
                                "Failed to grant permission $permission: ${result.message}, code: ${result.exitCode}"
                            )
                            continuation.resume(false)
                        }

                        is ShellResult.Timeout -> {
                            Log.e(TAG, "Timeout granting permission $permission")
                            continuation.resume(false)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    continuation.resume(false)
                }
            }
        } ?: false
    }

    /**
     * 授予AppOp权限
     */
    private suspend fun grantAppOp(
        shell: PersistentRootShell,
        packageName: String,
        appOp: String
    ): Boolean {
        return withTimeoutOrNull(3.seconds) {
            suspendCancellableCoroutine { continuation ->
                val command = "appops set $packageName $appOp allow"
                shell.executeAsync(
                    suCmd = RootConfigManager.getCustomRootCommand(),
                    command = command
                ) { result ->
                    when (result) {
                        is ShellResult.Success -> continuation.resume(true)
                        is ShellResult.Error -> {
                            Log.e(
                                TAG,
                                "Failed to grant AppOp $appOp: ${result.message}, code: ${result.exitCode}"
                            )
                            continuation.resume(false)
                        }

                        is ShellResult.Timeout -> {
                            Log.e(TAG, "Timeout granting AppOp $appOp")
                            continuation.resume(false)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    continuation.resume(false)
                }
            }
        } ?: false
    }
}