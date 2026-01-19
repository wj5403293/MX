//! Pointer Scan Manager
//!
//! This module provides the main entry point for pointer scanning.
//! It coordinates Phase 1 (pointer scanning) and Phase 2 (chain building),
//! manages async execution, and provides JNI-accessible state.

use crate::core::globals::TOKIO_RUNTIME;
use crate::pointer_scan::chain_builder::bfs_v2::BfsV2Scanner;
use crate::pointer_scan::mapqueue_v2;
use crate::pointer_scan::scanner::{self, ScanRegion};
use crate::pointer_scan::shared_buffer::PointerScanSharedBuffer;
use crate::pointer_scan::types::{PointerData, PointerScanConfig, ScanErrorCode, ScanPhase, VmStaticData};
use anyhow::{anyhow, Result};
use lazy_static::lazy_static;
use log::{error, info, log_enabled, Level};
use std::path::PathBuf;
use std::sync::RwLock;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

lazy_static! {
    pub static ref POINTER_SCAN_MANAGER: RwLock<PointerScanManager> = RwLock::new(PointerScanManager::new());
}

/// 扫描完成结果
#[derive(Debug, Clone)]
pub struct ScanCompleteResult {
    /// 找到的指针链数量
    pub total_count: usize,
    /// 输出文件路径
    pub output_file: String,
}

/// Manages pointer scan operations.
pub struct PointerScanManager {
    /// Current scan configuration
    config: PointerScanConfig,
    /// Shared buffer for progress communication
    shared_buffer: PointerScanSharedBuffer,
    /// Cancellation token for current scan
    cancel_token: Option<CancellationToken>,
    /// Handle to the async scan task
    scan_handle: Option<JoinHandle<()>>,
    /// Cache directory for temporary files
    cache_dir: PathBuf,
    /// Current scan phase
    current_phase: ScanPhase,
    /// Last error code
    last_error: ScanErrorCode,
    /// 扫描完成结果
    scan_result: Option<ScanCompleteResult>,
}

impl PointerScanManager {
    /// Create a new manager instance.
    pub fn new() -> Self {
        Self {
            config: PointerScanConfig::default(),
            shared_buffer: PointerScanSharedBuffer::new(),
            cancel_token: None,
            scan_handle: None,
            cache_dir: PathBuf::from("/data/data/moe.fuqiuluo.mamu/cache"),
            current_phase: ScanPhase::Idle,
            last_error: ScanErrorCode::None,
            scan_result: None,
        }
    }

    /// Initialize the manager with a cache directory.
    pub fn init(&mut self, cache_dir: String) -> Result<()> {
        self.cache_dir = PathBuf::from(&cache_dir);
        if !self.cache_dir.exists() {
            std::fs::create_dir_all(&self.cache_dir)?;
        }

        // 设置 MapQueue 的缓存目录（Android 兼容）
        mapqueue_v2::set_cache_dir(&cache_dir)?;

        info!("PointerScanManager initialized with cache_dir: {:?}", self.cache_dir);
        Ok(())
    }

    /// Set the shared buffer for progress communication.
    pub fn set_shared_buffer(&mut self, ptr: *mut u8, len: usize) -> bool {
        self.shared_buffer.set(ptr, len)
    }

    /// Check if a scan is currently in progress.
    pub fn is_scanning(&self) -> bool {
        if let Some(ref handle) = self.scan_handle {
            !handle.is_finished()
        } else {
            false
        }
    }

    /// Request cancellation of the current scan.
    pub fn request_cancel(&self) {
        if let Some(ref token) = self.cancel_token {
            token.cancel();
        }
    }

    /// Get the current scan phase.
    pub fn get_phase(&self) -> ScanPhase {
        self.current_phase
    }

    /// Get the last error code.
    pub fn get_error(&self) -> ScanErrorCode {
        self.last_error
    }

    /// Get scan result (total count and output file path)
    pub fn get_scan_result(&self) -> Option<ScanCompleteResult> {
        self.scan_result.clone()
    }

    /// Clear all results and reset state.
    pub fn clear(&mut self) {
        self.current_phase = ScanPhase::Idle;
        self.last_error = ScanErrorCode::None;
        self.shared_buffer.reset();
        self.scan_result = None;
    }

    /// Start an async pointer scan.
    ///
    /// This function returns immediately. Progress can be monitored via the shared buffer.
    pub fn start_scan_async(
        &mut self,
        target_address: u64,
        max_depth: u32,
        max_offset: u32,
        align: u32,
        regions: Vec<ScanRegion>,
        static_modules: Vec<VmStaticData>,
        _is_layer_bfs: bool, // 不再使用，保留参数兼容性
    ) -> Result<()> {
        if self.is_scanning() {
            self.last_error = ScanErrorCode::AlreadyScanning;
            return Err(anyhow!("Scan already in progress"));
        }

        if regions.is_empty() {
            self.last_error = ScanErrorCode::InvalidAddress;
            return Err(anyhow!("No memory regions provided"));
        }

        // Update config
        self.config = PointerScanConfig {
            target_address,
            max_depth,
            max_offset,
            align,
            is_layer_bfs: true, // 始终使用 BFS V2
            data_start: true,
            bss_start: false,
        };

        // Reset state
        self.clear();
        self.current_phase = ScanPhase::ScanningPointers;
        self.shared_buffer.write_phase(ScanPhase::ScanningPointers);

        // Create cancellation token
        let cancel_token = CancellationToken::new();
        self.cancel_token = Some(cancel_token.clone());

        // Clone data for the async task
        let config = self.config.clone();
        let cache_dir = self.cache_dir.clone();

        if log_enabled!(Level::Debug) {
            info!(
                "Starting pointer scan: target=0x{:X}, depth={}, offset=0x{:X}, regions={}",
                target_address,
                max_depth,
                max_offset,
                regions.len()
            );
        }

        // Spawn the scan task
        let handle = TOKIO_RUNTIME.spawn(async move {
            Self::run_scan_task(config, regions, static_modules, cache_dir, cancel_token).await;
        });

        self.scan_handle = Some(handle);
        Ok(())
    }

    /// The async scan task that runs Phase 1 and Phase 2.
    async fn run_scan_task(
        config: PointerScanConfig,
        regions: Vec<ScanRegion>,
        static_modules: Vec<VmStaticData>,
        cache_dir: PathBuf,
        cancel_token: CancellationToken,
    ) {
        let check_cancelled = || cancel_token.is_cancelled();

        // Phase 1: Scan for pointers
        if log_enabled!(Level::Debug) {
            info!("Phase 1: Scanning for pointers...");
        }

        let cancel_token_clone = cancel_token.clone();
        let pointer_lib_result = tokio::task::spawn_blocking({
            let config = config.clone();
            let cache_dir = cache_dir.clone();
            move || {
                scanner::scan_all_pointers(
                    &regions,
                    &config,
                    &cache_dir,
                    |done, total, found| {
                        if let Ok(manager) = POINTER_SCAN_MANAGER.read() {
                            manager.shared_buffer.update_scanning_progress(done as i32, total as i32, found);
                        }
                    },
                    || cancel_token_clone.is_cancelled(),
                )
            }
        })
        .await;

        // Check cancellation
        if check_cancelled() {
            if log_enabled!(Level::Debug) {
                info!("Scan cancelled during Phase 1");
            }
            if let Ok(mut manager) = POINTER_SCAN_MANAGER.write() {
                manager.current_phase = ScanPhase::Cancelled;
                manager.shared_buffer.write_phase(ScanPhase::Cancelled);
            }
            return;
        }

        // Process Phase 1 result
        let pointer_lib = match pointer_lib_result {
            Ok(Ok(lib)) => lib,
            Ok(Err(e)) => {
                error!("Phase 1 failed: {}", e);
                if let Ok(mut manager) = POINTER_SCAN_MANAGER.write() {
                    manager.current_phase = ScanPhase::Error;
                    manager.last_error = ScanErrorCode::MemoryReadFailed;
                    manager.shared_buffer.write_phase(ScanPhase::Error);
                    manager.shared_buffer.write_error_code(ScanErrorCode::MemoryReadFailed);
                }
                return;
            },
            Err(e) => {
                error!("Phase 1 task panicked: {}", e);
                if let Ok(mut manager) = POINTER_SCAN_MANAGER.write() {
                    manager.current_phase = ScanPhase::Error;
                    manager.last_error = ScanErrorCode::InternalError;
                    manager.shared_buffer.write_phase(ScanPhase::Error);
                    manager.shared_buffer.write_error_code(ScanErrorCode::InternalError);
                }
                return;
            },
        };

        if log_enabled!(Level::Debug) {
            info!("Phase 1 complete. Found {} pointers", pointer_lib.len());
        }

        // Update phase
        if let Ok(mut manager) = POINTER_SCAN_MANAGER.write() {
            manager.current_phase = ScanPhase::BuildingChains;
            manager.shared_buffer.write_phase(ScanPhase::BuildingChains);
        }

        // Phase 2: Build chains using BFS V2 and write to file
        if log_enabled!(Level::Debug) {
            info!("Phase 2: Building pointer chains (BFS V2)...");
        }

        let config_clone = config.clone();
        let static_modules_clone = static_modules.clone();
        let cancel_token_clone2 = cancel_token.clone();

        // 生成输出文件路径
        let timestamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        let output_path = PathBuf::from(format!(
            "/sdcard/pointer_scan_0x{:X}_{}.txt",
            config.target_address,
            timestamp
        ));
        let output_path_clone = output_path.clone();

        let scan_result = tokio::task::spawn_blocking(move || {
            // 将 MmapQueue 中的数据转换为 Vec<PointerData> 用于 BFS V2
            // 注意：BFS V2 需要按 address 排序的指针数据
            let mut global_pointers: Vec<PointerData> = Vec::with_capacity(pointer_lib.len());

            for i in 0..pointer_lib.len() {
                if let Some(archived) = pointer_lib.get(i) {
                    global_pointers.push(PointerData::new(
                        archived.address.to_native(),
                        archived.value.to_native(),
                    ));
                }
            }

            // 按 address 排序（BFS V2 需要）
            global_pointers.sort_unstable_by_key(|p| p.address);

            // 创建 BFS V2 扫描器
            let scanner = BfsV2Scanner::new(
                &global_pointers,
                &static_modules_clone,
                &config_clone,
            );

            // 执行扫描，结果直接写入文件
            scanner.scan_to_file(
                output_path_clone,
                100000, // 最大写入链数
                |depth, max_depth, chains_found| {
                    if let Ok(manager) = POINTER_SCAN_MANAGER.read() {
                        manager
                            .shared_buffer
                            .update_building_progress(depth as i32, max_depth, chains_found);
                    }
                },
                || cancel_token_clone2.is_cancelled(),
            )
        })
        .await;

        // Check cancellation
        if check_cancelled() {
            if log_enabled!(Level::Debug) {
                info!("Scan cancelled during Phase 2");
            }

            if let Ok(mut manager) = POINTER_SCAN_MANAGER.write() {
                manager.current_phase = ScanPhase::Cancelled;
                manager.shared_buffer.write_phase(ScanPhase::Cancelled);
            }
            return;
        }

        // Store results
        match scan_result {
            Ok(Ok(result)) => {
                info!(
                    "Phase 2 complete. Found {} chains, written to {}",
                    result.total_count,
                    result.output_file.display()
                );
                if let Ok(mut manager) = POINTER_SCAN_MANAGER.write() {
                    manager.scan_result = Some(ScanCompleteResult {
                        total_count: result.total_count,
                        output_file: result.output_file.to_string_lossy().to_string(),
                    });
                    manager.current_phase = ScanPhase::Completed;
                    manager.shared_buffer.write_phase(ScanPhase::Completed);
                    manager.shared_buffer.write_progress(100);
                    manager.shared_buffer.write_chains_found(result.total_count as i64);
                }
            },
            Ok(Err(e)) => {
                error!("Phase 2 failed: {}", e);
                if let Ok(mut manager) = POINTER_SCAN_MANAGER.write() {
                    manager.current_phase = ScanPhase::Error;
                    manager.last_error = ScanErrorCode::InternalError;
                    manager.shared_buffer.write_phase(ScanPhase::Error);
                    manager.shared_buffer.write_error_code(ScanErrorCode::InternalError);
                }
            },
            Err(e) => {
                error!("Phase 2 task panicked: {}", e);
                if let Ok(mut manager) = POINTER_SCAN_MANAGER.write() {
                    manager.current_phase = ScanPhase::Error;
                    manager.last_error = ScanErrorCode::InternalError;
                    manager.shared_buffer.write_phase(ScanPhase::Error);
                    manager.shared_buffer.write_error_code(ScanErrorCode::InternalError);
                }
            },
        }

        if log_enabled!(Level::Debug) {
            info!("Pointer scan task completed");
        }
    }
}

impl Default for PointerScanManager {
    fn default() -> Self {
        Self::new()
    }
}
