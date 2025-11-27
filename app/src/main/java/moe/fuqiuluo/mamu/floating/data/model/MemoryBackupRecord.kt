package moe.fuqiuluo.mamu.floating.data.model

/**
 * 内存修改备份记录（只保存首次修改前的值）
 */
data class MemoryBackupRecord(
    val address: Long,
    val originalValue: String,      // 首次修改前的值（用于恢复）
    val originalType: DisplayValueType,
    val firstModifiedTime: Long     // 首次修改时间
)
