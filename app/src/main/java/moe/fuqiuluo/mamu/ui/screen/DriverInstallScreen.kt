package moe.fuqiuluo.mamu.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.fuqiuluo.mamu.data.model.DriverInfo
import moe.fuqiuluo.mamu.ui.theme.AdaptiveLayoutInfo
import moe.fuqiuluo.mamu.ui.theme.Dimens
import moe.fuqiuluo.mamu.ui.theme.MXTheme
import moe.fuqiuluo.mamu.ui.theme.rememberAdaptiveLayoutInfo
import moe.fuqiuluo.mamu.ui.viewmodel.DriverInstallViewModel
import moe.fuqiuluo.mamu.utils.RootConfigManager
import moe.fuqiuluo.mamu.utils.RootShellExecutor
import kotlin.system.exitProcess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverInstallScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    viewModel: DriverInstallViewModel = viewModel()
) {
    val adaptiveLayout = rememberAdaptiveLayoutInfo(windowSizeClass)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.shouldRestartApp) {
        if (uiState.shouldRestartApp) {
            restartApp(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("驱动安装") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadDrivers() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = adaptiveLayout.contentMaxWidth)
                    .fillMaxWidth()
                    .align(Alignment.Center)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    when {
                        uiState.isLoading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        uiState.drivers.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "无可用驱动",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }

                        else -> {
                            when (adaptiveLayout.windowSizeClass.widthSizeClass) {
                                WindowWidthSizeClass.Compact -> {
                                    // 竖屏布局：垂直Column
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        LazyColumn(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxWidth(),
                                            contentPadding = PaddingValues(Dimens.paddingLg(adaptiveLayout)),
                                            verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd(adaptiveLayout))
                                        ) {
                                            items(uiState.drivers) { driver ->
                                                DriverCard(
                                                    adaptiveLayout = adaptiveLayout,
                                                    driver = driver,
                                                    isSelected = uiState.selectedDriver == driver,
                                                    onSelect = { viewModel.selectDriver(driver) }
                                                )
                                            }
                                        }

                                        AnimatedVisibility(
                                            visible = uiState.selectedDriver != null,
                                            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                                            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                                        ) {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                tonalElevation = Dimens.elevationMd(adaptiveLayout)
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(Dimens.paddingLg(adaptiveLayout)),
                                                    verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm(adaptiveLayout))
                                                ) {
                                                    if (uiState.isInstalling) {
                                                        Text(
                                                            text = "正在下载并安装...",
                                                            style = MaterialTheme.typography.bodyMedium
                                                        )
                                                        LinearProgressIndicator(
                                                            modifier = Modifier.fillMaxWidth()
                                                        )
                                                    } else {
                                                        Button(
                                                            onClick = { showConfirmDialog = true },
                                                            modifier = Modifier.fillMaxWidth(),
                                                        ) {
                                                            Icon(
                                                                Icons.Default.Download,
                                                                contentDescription = null,
                                                                modifier = Modifier.size(Dimens.iconSm(adaptiveLayout))
                                                            )
                                                            Spacer(modifier = Modifier.width(Dimens.spacingSm(adaptiveLayout)))
                                                            Text("下载并安装")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    // 横屏布局：Master-Detail（列表左侧45% + 详情右侧55%）
                                    Row(modifier = Modifier.fillMaxSize()) {
                                        // 左侧：驱动列表
                                        LazyColumn(
                                            modifier = Modifier
                                                .weight(0.45f)
                                                .fillMaxHeight(),
                                            contentPadding = PaddingValues(Dimens.paddingLg(adaptiveLayout)),
                                            verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd(adaptiveLayout))
                                        ) {
                                            items(uiState.drivers) { driver ->
                                                DriverCard(
                                                    adaptiveLayout = adaptiveLayout,
                                                    driver = driver,
                                                    isSelected = uiState.selectedDriver == driver,
                                                    onSelect = { viewModel.selectDriver(driver) }
                                                )
                                            }
                                        }

                                        VerticalDivider()

                                        // 右侧：详情面板（始终显示）
                                        DriverDetailPanel(
                                            adaptiveLayout = adaptiveLayout,
                                            selectedDriver = uiState.selectedDriver,
                                            isInstalling = uiState.isInstalling,
                                            onInstallClick = { showConfirmDialog = true },
                                            modifier = Modifier
                                                .weight(0.55f)
                                                .fillMaxHeight()
                                        )
                                    }
                                }
                            }
                        }
                    }

                    uiState.successMessage?.let { message ->
                        Snackbar(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(Dimens.paddingLg(adaptiveLayout)),
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            action = {
                                TextButton(onClick = { viewModel.clearMessages() }) {
                                    Text("关闭")
                                }
                            }
                        ) {
                            Text(message)
                        }
                    }
                }
            }
        }
    }

    if (showConfirmDialog && uiState.selectedDriver != null) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("确认安装") },
            text = {
                Text("确定要安装驱动 ${uiState.selectedDriver!!.displayName} 吗？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        viewModel.downloadAndInstallDriver()
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    uiState.error?.let { errorLog ->
        ErrorLogDialog(
            adaptiveLayout = adaptiveLayout,
            errorLog = errorLog,
            onDismiss = { viewModel.clearMessages() }
        )
    }
}

@Composable
fun DriverCard(
    adaptiveLayout: AdaptiveLayoutInfo,
    driver: DriverInfo,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            CardDefaults.outlinedCardBorder()
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.paddingLg(adaptiveLayout)),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = driver.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(Dimens.spacingXs(adaptiveLayout)))
                Text(
                    text = driver.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (driver.installed) {
                AssistChip(
                    onClick = { },
                    label = { Text("已安装") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.iconSm(adaptiveLayout))
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            if (isSelected && !driver.installed) {
                Icon(
                    Icons.Default.RadioButtonChecked,
                    contentDescription = "已选中",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else if (!driver.installed) {
                Icon(
                    Icons.Default.RadioButtonUnchecked,
                    contentDescription = "未选中",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// Dialog 使用响应式尺寸
@Composable
fun ErrorLogDialog(
    adaptiveLayout: AdaptiveLayoutInfo,
    errorLog: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(Dimens.iconLg(adaptiveLayout))
            )
        },
        title = {
            Text(
                text = "驱动安装失败",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd(adaptiveLayout))
            ) {
                Text(
                    text = "错误详情：",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = Dimens.maxHeightLg(adaptiveLayout)),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = Dimens.elevationSm(adaptiveLayout)
                ) {
                    Box(
                        modifier = Modifier
                            .padding(Dimens.paddingMd(adaptiveLayout))
                            .verticalScroll(scrollState)
                    ) {
                        Text(
                            text = errorLog,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Text(
                    text = "您可以复制日志内容并反馈给开发者",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("驱动错误日志", errorLog)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.iconSm(adaptiveLayout))
                )
                Spacer(modifier = Modifier.width(Dimens.spacingSm(adaptiveLayout)))
                Text("复制日志")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun DriverDetailPanel(
    adaptiveLayout: AdaptiveLayoutInfo,
    selectedDriver: DriverInfo?,
    isInstalling: Boolean,
    onInstallClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        tonalElevation = Dimens.elevationSm(adaptiveLayout)
    ) {
        AnimatedContent(
            targetState = selectedDriver,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentKey = { it != null },
            label = "detail_panel"
        ) { driver ->
            if (driver == null) {
                // 未选中状态：居中提示
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.TouchApp,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.iconXl(adaptiveLayout)),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(Dimens.spacingMd(adaptiveLayout)))
                        Text(
                            text = "选择一个驱动查看详情",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // 已选中状态：驱动信息 + 安装操作
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Dimens.paddingLg(adaptiveLayout))
                ) {
                    // 驱动信息卡片
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(Dimens.paddingLg(adaptiveLayout)),
                            verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm(adaptiveLayout))
                        ) {
                            Text(
                                text = driver.displayName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = driver.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (driver.installed) {
                                AssistChip(
                                    onClick = { },
                                    label = { Text("已安装") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(Dimens.iconSm(adaptiveLayout))
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // 底部安装按钮/进度
                    if (isInstalling) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm(adaptiveLayout))
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(Dimens.iconXl(adaptiveLayout))
                            )
                            Text(
                                text = "正在下载并安装...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    } else {
                        Button(
                            onClick = onInstallClick,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(Dimens.iconSm(adaptiveLayout))
                            )
                            Spacer(modifier = Modifier.width(Dimens.spacingSm(adaptiveLayout)))
                            Text("下载并安装")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Preview(name = "Light Mode", showBackground = true, widthDp = 360, heightDp = 640)
@Preview(name = "Dark Mode", showBackground = true, widthDp = 360, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DriverInstallScreenCompactPreview() {
    MXTheme {
        val windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(360.dp, 640.dp))
        val adaptiveLayout = rememberAdaptiveLayoutInfo(windowSizeClass)
        Scaffold { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sampleDrivers) { driver ->
                        DriverCard(
                            adaptiveLayout = adaptiveLayout,
                            driver = driver,
                            isSelected = driver.name == "driver_a14",
                            onSelect = { }
                        )
                    }
                }
                Surface(tonalElevation = 2.dp) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Button(onClick = { }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("下载并安装")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Preview(name = "Tablet Light", showBackground = true, widthDp = 900, heightDp = 600)
@Preview(name = "Tablet Dark", showBackground = true, widthDp = 900, heightDp = 600, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DriverInstallScreenTabletPreview() {
    MXTheme {
        val windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(900.dp, 600.dp))
        val adaptiveLayout = rememberAdaptiveLayoutInfo(windowSizeClass)
        Scaffold { paddingValues ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                LazyColumn(
                    modifier = Modifier.weight(0.45f).fillMaxHeight(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sampleDrivers) { driver ->
                        DriverCard(
                            adaptiveLayout = adaptiveLayout,
                            driver = driver,
                            isSelected = driver.name == "driver_a14",
                            onSelect = { }
                        )
                    }
                }
                VerticalDivider()
                DriverDetailPanel(
                    adaptiveLayout = adaptiveLayout,
                    selectedDriver = sampleDrivers[1],
                    isInstalling = false,
                    onInstallClick = { },
                    modifier = Modifier.weight(0.55f).fillMaxHeight()
                )
            }
        }
    }
}

private val sampleDrivers = listOf(
    DriverInfo(name = "driver_a13", displayName = "Android 13 (5.10)", installed = true),
    DriverInfo(name = "driver_a14", displayName = "Android 14 (5.15)", installed = false),
    DriverInfo(name = "driver_a15", displayName = "Android 15 (6.1)", installed = false),
)

private fun restartApp(context: Context) {
    val pkg = context.packageName
    RootShellExecutor.execNoWait(
        suCmd = RootConfigManager.getCustomRootCommand(),
        command = "(sleep 1 && am start-activity -n $pkg/.PermissionSetupActivity) &"
    )
    exitProcess(0)
}
