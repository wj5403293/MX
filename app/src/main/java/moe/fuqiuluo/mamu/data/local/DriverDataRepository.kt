package moe.fuqiuluo.mamu.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.data.model.DriverInfo
import moe.fuqiuluo.mamu.data.model.DriverStatus
import moe.fuqiuluo.mamu.driver.WuwaDriver

/**
 * 驱动信息数据源
 * 负责查询驱动相关信息
 */
class DriverDataRepository {

    /**
     * 获取驱动信息
     */
    suspend fun getDriverInfo(): DriverInfo = withContext(Dispatchers.IO) {
        try {
            val loaded = WuwaDriver.loaded
            val status = if (loaded) DriverStatus.LOADED else DriverStatus.NOT_LOADED

            if (loaded) {
                DriverInfo(
                    status = status,
                    isProcessBound = WuwaDriver.isProcessBound,
                    boundPid = WuwaDriver.currentBindPid
                )
            } else {
                DriverInfo(status = status)
            }
        } catch (e: Exception) {
            DriverInfo(
                status = DriverStatus.ERROR,
                errorMessage = e.message
            )
        }
    }
}
