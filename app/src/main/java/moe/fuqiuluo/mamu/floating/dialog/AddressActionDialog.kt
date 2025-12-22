package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.databinding.DialogAddressActionBinding
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.utils.ValueTypeUtils
import moe.fuqiuluo.mamu.widget.NotificationOverlay

/**
 * 地址操作对话框
 */
class AddressActionDialog(
    context: Context,
    private val notification: NotificationOverlay,
    private val clipboardManager: ClipboardManager,
    private val address: Long,
    private val value: String,
    private val valueType: DisplayValueType,
    private val coroutineScope: CoroutineScope,
    private val callbacks: Callbacks
) : BaseDialog(context) {

    /**
     * 回调接口
     */
    interface Callbacks {
        /**
         * 显示偏移量计算器（传入选中的地址）
         */
        fun onShowOffsetCalculator(address: Long)

        /**
         * 跳转到指定地址（在内存预览中）
         */
        fun onJumpToAddress(address: Long)
    }

    private data class ActionItem(
        val title: String,
        val icon: Int,
        val action: () -> Unit
    )

    @SuppressLint("SetTextI18n")
    override fun setupDialog() {
        val binding = DialogAddressActionBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        // 应用透明度设置
        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.getDialogOpacity()
        binding.rootContainer.background?.alpha = (opacity * 255).toInt()

        // 显示地址信息
        binding.addressInfoText.text = "地址: 0x${address.toString(16).uppercase()}"
        binding.valueInfoText.text = "值: $value (${valueType.displayName})"

        // 定义操作列表
        val actions = listOf(
            ActionItem("偏移量计算器", R.drawable.calculate_24px) {
                dismiss()
                callbacks.onShowOffsetCalculator(address)
            },
            ActionItem(
                "转到此地址: ${"%X".format(address)}",
                R.drawable.icon_arrow_right_alt_24px
            ) {
                dismiss()
                callbacks.onJumpToAddress(address)
            },
            ActionItem(
                "跳转到指针: ${"%X".format(value.toLongOrNull() ?: 0)}",
                R.drawable.icon_arrow_right_alt_24px
            ) {
                // 跳转到指针地址
                dismiss()
                val pointerAddress = value.toLongOrNull() ?: return@ActionItem
                callbacks.onJumpToAddress(pointerAddress)
                notification.showSuccess("跳转到指针: 0x${pointerAddress.toString(16).uppercase()}")
            },
            ActionItem("复制此地址: ${"%X".format(address)}", R.drawable.content_copy_24px) {
                copyAddress()
            },
            ActionItem("复制此值: $value", R.drawable.content_copy_24px) {
                copyValue()
            },
            ActionItem(
                "复制16进制值: ${"%X".format(value.toLongOrNull() ?: 0)}",
                R.drawable.content_copy_24px
            ) {
                copyHexValue()
            },
            ActionItem(
                "复制反16进制值: ${"%X".format(value.toLongOrNull() ?: 0).reversed()}",
                R.drawable.content_copy_24px
            ) {
                copyReverseHexValue()
            }
        )

        // 设置ListView适配器
        val adapter = ActionAdapter(context, actions)
        binding.actionList.adapter = adapter

        // 列表项点击事件
        binding.actionList.setOnItemClickListener { _, _, position, _ ->
            actions[position].action()
        }

        // 取消按钮
        binding.btnCancel.setOnClickListener {
            dismiss()
        }
    }

    /**
     * 复制地址
     */
    private fun copyAddress() {
        val addressText = address.toString(16).uppercase()
        val clip = ClipData.newPlainText("address", addressText)
        clipboardManager.setPrimaryClip(clip)
        notification.showSuccess("已复制地址: $addressText")
        dismiss()
    }

    /**
     * 复制值
     */
    private fun copyValue() {
        val clip = ClipData.newPlainText("value", value)
        clipboardManager.setPrimaryClip(clip)
        notification.showSuccess("已复制值: $value")
        dismiss()
    }

    /**
     * 复制16进制值
     */
    private fun copyHexValue() {
        try {
            // 将值转换为字节数组
            val bytes = ValueTypeUtils.parseExprToBytes(value, valueType)
            // 转换为16进制字符串
            val hexString = bytes.joinToString("") { "%02X".format(it) }
            val clip = ClipData.newPlainText("hex_value", hexString)
            clipboardManager.setPrimaryClip(clip)
            notification.showSuccess("已复制16进制: $hexString")
            dismiss()
        } catch (e: Exception) {
            notification.showError("转换失败: ${e.message}")
        }
    }

    /**
     * 复制反16进制值（字节顺序反转）
     */
    private fun copyReverseHexValue() {
        try {
            // 将值转换为字节数组
            val bytes = ValueTypeUtils.parseExprToBytes(value, valueType)
            // 反转字节顺序并转换为16进制字符串
            val hexString = bytes.reversedArray().joinToString("") { "%02X".format(it) }
            val clip = ClipData.newPlainText("reverse_hex_value", hexString)
            clipboardManager.setPrimaryClip(clip)
            notification.showSuccess("已复制反16进制: $hexString")
            dismiss()
        } catch (e: Exception) {
            notification.showError("转换失败: ${e.message}")
        }
    }

    /**
     * ListView适配器
     */
    private class ActionAdapter(
        context: Context,
        private val actions: List<ActionItem>
    ) : ArrayAdapter<ActionItem>(context, R.layout.dialog_option_item, actions) {

        @SuppressLint("ViewHolder")
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = LayoutInflater.from(context)
                .inflate(R.layout.dialog_option_item, parent, false) as TextView

            val action = actions[position]
            view.text = action.title
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(action.icon, 0, 0, 0)
            view.compoundDrawablePadding = 16

            return view
        }
    }
}
