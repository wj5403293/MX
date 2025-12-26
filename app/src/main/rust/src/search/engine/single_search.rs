use super::super::types::{SearchValue, ValueType};
use super::manager::{ValuePair, BPLUS_TREE_ORDER, PAGE_MASK, PAGE_SIZE};
use crate::core::DRIVER_MANAGER;
use crate::search::engine::memchr_ext::MemchrExt;
use crate::wuwa::PageStatusBitmap;
use anyhow::{anyhow, Result};
use bplustree::BPlusTreeSet;
use log::{debug, error, info, log_enabled, warn, Level};
use memchr::*;
use rayon::prelude::*;
use std::sync::atomic::{AtomicI64, AtomicUsize};
use std::sync::Arc;

/// 每个 rayon 任务扫描的粒度
const PAR_SCAN_GRAIN: usize = 256 * 1024;

pub(crate) fn search_region_single(
    target: &SearchValue,
    start: u64,        // 区域起始地址
    end: u64,          // 区域结束地址
    chunk_size: usize, // 每次读取的块大小
) -> Result<BPlusTreeSet<ValuePair>> {
    let driver_manager = DRIVER_MANAGER.read().map_err(|_| anyhow!("Failed to acquire DriverManager lock"))?;

    let value_type = target.value_type();
    let element_size = value_type.size();

    let mut results = BPlusTreeSet::new(BPLUS_TREE_ORDER);
    let mut read_success = 0usize;
    let mut read_failed = 0usize;

    let mut current = start & !(*PAGE_SIZE as u64 - 1); // 当前的页对齐地址
    let mut chunk_buffer = vec![0u8; chunk_size]; // 读取缓冲区

    while current < end {
        let chunk_end = (current + chunk_size as u64).min(end); // 当前块的结束地址，如果超过end则取end
        let chunk_len = (chunk_end - current) as usize; // 当前块的实际长度

        let mut page_status = PageStatusBitmap::new(chunk_len, current as usize);

        // 这里读取内存，这里的current一定页对齐的
        let read_result = driver_manager.read_memory_unified(current, &mut chunk_buffer[..chunk_len], Some(&mut page_status));

        match read_result {
            Ok(_) => {
                let success_pages = page_status.success_count();
                if success_pages > 0 {
                    read_success += 1;
                    search_in_chunks_with_status(
                        &chunk_buffer[..chunk_len],
                        current,
                        start,
                        end,
                        element_size,
                        target,
                        value_type,
                        &page_status,
                        &mut results,
                    );
                } else {
                    read_failed += 1;
                }
            },
            Err(error) => {
                if log_enabled!(Level::Debug) {
                    warn!("Failed to read memory at 0x{:X} - 0x{:X}, err: {:?}", current, chunk_end, error);
                }
                read_failed += 1;
            },
        }

        current = chunk_end;
    }

    // if log_enabled!(Level::Debug) {
    //     let region_size = end - start;
    //     debug!(
    //         "Region stats: size={}MB, reads={} success + {} failed, matches_checked={}, found={}",
    //         region_size / 1024 / 1024,
    //         read_success,
    //         read_failed,
    //         matches_checked,
    //         results.len()
    //     );
    // }

    Ok(results)
}

#[inline]
pub(crate) fn search_in_chunks_with_status(
    buffer: &[u8],
    buffer_addr: u64,                      // 当前读取的缓冲区对应目标进程的一块内存的起始地址
    region_start: u64,                     // 搜索区域的起始地址
    region_end: u64,                       // 搜索区域的结束地址
    element_size: usize,                   // 元素大小
    target: &SearchValue,                  // 目标搜索值
    value_type: ValueType,                 // 目标值类型
    page_status: &PageStatusBitmap,        // 页面状态位图
    results: &mut BPlusTreeSet<ValuePair>, // 搜索结果
) {
    assert_eq!(buffer_addr as usize % *PAGE_SIZE, 0);

    let buffer_end = buffer_addr + buffer.len() as u64; // 结束地址
    let search_start = buffer_addr.max(region_start); // 实际搜索起始地址
    let search_end = buffer_end.min(region_end); // 实际搜索结束地址

    if search_start >= search_end {
        return;
    }

    let filter_chunks = buffer
        .chunks(*PAGE_SIZE)
        .enumerate()
        .filter(|(idx, _)| page_status.is_page_success(*idx))
        .map(|(idx, chunk)| {
            let start_addr = (idx * *PAGE_SIZE + buffer_addr as usize) as u64;
            let end_addr = start_addr + chunk.len() as u64;
            (start_addr, end_addr, chunk)
        })
        // 只保留与 [search_start, search_end) 有交集的块
        .filter(|(cs, ce, _ck)| *ce > search_start && *cs < search_end)
        .collect::<Vec<_>>();

    if target.is_fixed_int()
        && let Ok(bytes) = target.bytes()
        && !bytes.is_empty()
        && !bytes.iter().all(|&b| b == 0)
    // 保证不是奇怪的候选全0情况避免候选爆炸
    {
        // 这里有两种方案去实现整数的对比 (==)
        // - memchr找到所有锚点再过滤掉不对齐的地址，
        // memchr 这条路径里，SIMD 加速的是“找锚点字节”（一次扫很多字节找 needle[0]），它不依赖你后续的 align
        //
        // - step_by对齐一个个对比
        // step_by(align) 这条路径里，你在每个对齐位置做 &haystack[pos..pos+len] == needle。这个比较的成本主要是：
        // 很多情况下只比 1~几字节就失败（随机数据）；
        // 以及“候选点数量 = hay_len / align”，align 越大，次数越少，所以线性变快。
        // 这里的“SIMD”更多发生在 memcmp/slice compare 内部，但随机数据会很早退出，SIMD 根本来不及展开。
        //
        // 当对齐大小大于64字节的时候memchr方案将不会有提升，反而成为累赘，
        // 这个“交叉点”本质上是：当 align 足够大时，step_by 的比较次数少到离谱，哪怕每次比较比较“笨”，总成本也低。
        //
        // 这里有一个拐点的边界情况，候选爆炸！
        // uniform 全 0，needle 也全 0 时，memchr_iter(0x00, haystack) 会返回几乎每一个位置（1MiB 个候选）
        // 然后你还要对每个候选做对齐判断 + 16字节比较（而且还经常成功，比较不会早退，反而更贵）
        if log_enabled!(Level::Debug) {
            info!("整数快速搜索路径");
        }

        let matched_addrs = filter_chunks
            .into_par_iter()
            .map(|(cs, ce, ck)| {
                ck.find_aligned(bytes, element_size)
                    .into_iter()
                    .map(|pos| pos as u64 + cs)
                    .filter(|addr| *addr >= search_start && *addr < search_end)
                    .collect::<Vec<_>>()
            })
            .flatten()
            .collect::<Vec<_>>();

        for addr in matched_addrs {
            results.insert(ValuePair::new(addr, value_type));
        }
        return;
    }

    // 这里下降到朴实无华的step_by
    let matched_addrs = filter_chunks
        .into_par_iter()
        .map(|(cs, ce, ck)| {
            let mut addrs = vec![];
            for pos in (0..=ck.len()).step_by(element_size) {
                if pos + element_size > ck.len() {
                    break;
                }
                let other = &ck[pos..pos + element_size];
                match target.matched(other) {
                    Ok(true) => {
                        addrs.push(pos as u64 + cs);
                    }
                    Ok(false) => continue,
                    Err(e) => error!("target.matched error, {}", e),
                }
            }
            addrs
        })
        .flatten()
        .collect::<Vec<_>>();

    for addr in matched_addrs {
        results.insert(ValuePair::new(addr, value_type));
    }
}

/// 单值细化搜索
/// 逐个读取地址的值，再用rayon并行判断
/// 返回仍然匹配的地址列表
#[deprecated]
pub(crate) fn refine_single_search(
    addresses: &[ValuePair],
    target: &SearchValue,
    processed_counter: Option<&Arc<AtomicUsize>>,
    total_found_counter: Option<&Arc<AtomicUsize>>,
) -> Result<Vec<ValuePair>> {
    use rayon::prelude::*;
    use std::sync::atomic::Ordering;

    if addresses.is_empty() {
        return Ok(Vec::new());
    }

    let driver_manager = DRIVER_MANAGER.read().map_err(|_| anyhow!("Failed to acquire DriverManager lock"))?;

    let target_type = target.value_type();
    let element_size = target_type.size();

    // 过滤类型不匹配的地址
    let filtered_addresses: Vec<_> = addresses.iter().filter(|p| p.value_type == target_type).cloned().collect();

    if filtered_addresses.is_empty() {
        return Ok(Vec::new());
    }

    // 逐个读取每个地址的值
    let mut address_values: Vec<(ValuePair, Vec<u8>)> = Vec::with_capacity(filtered_addresses.len());

    for pair in &filtered_addresses {
        let mut buffer = vec![0u8; element_size];
        if driver_manager.read_memory_unified(pair.addr, &mut buffer, None).is_ok() {
            address_values.push((pair.clone(), buffer));
        }

        // 更新已处理计数器
        if let Some(counter) = &processed_counter {
            counter.fetch_add(1, Ordering::Relaxed);
        }
    }

    drop(driver_manager);

    // 用rayon并行判断
    let results: Vec<ValuePair> = address_values
        .into_par_iter()
        .filter_map(|(pair, bytes)| {
            if let Ok(true) = target.matched(&bytes) {
                if let Some(counter) = &total_found_counter {
                    counter.fetch_add(1, Ordering::Relaxed);
                }
                Some(pair)
            } else {
                None
            }
        })
        .collect();

    if log_enabled!(Level::Debug) {
        debug!("Refine single search: {} -> {} results", filtered_addresses.len(), results.len());
    }

    Ok(results)
}

/// Single value refine search with cancel and progress callbacks.
/// This version supports cancellation checking and progress updates during the search.
pub(crate) fn refine_single_search_with_cancel<F, P>(
    addresses: &[ValuePair],
    target: &SearchValue,
    processed_counter: Option<&Arc<AtomicUsize>>,
    total_found_counter: Option<&Arc<AtomicUsize>>,
    check_cancelled: &F,
    update_progress: &P,
) -> Result<Vec<ValuePair>>
where
    F: Fn() -> bool + Sync,
    P: Fn(usize, usize) + Sync,
{
    use rayon::prelude::*;
    use std::sync::atomic::Ordering;

    if addresses.is_empty() {
        return Ok(Vec::new());
    }

    // Check cancellation before starting.
    if check_cancelled() {
        return Ok(Vec::new());
    }

    let driver_manager = DRIVER_MANAGER.read().map_err(|_| anyhow!("Failed to acquire DriverManager lock"))?;

    let target_type = target.value_type();
    let element_size = target_type.size();

    // Filter addresses with non-matching types.
    let filtered_addresses: Vec<_> = addresses.iter().filter(|p| p.value_type == target_type).cloned().collect();

    if filtered_addresses.is_empty() {
        return Ok(Vec::new());
    }

    let total_addresses = filtered_addresses.len();

    // Read values for each address sequentially.
    let mut address_values: Vec<(ValuePair, Vec<u8>)> = Vec::with_capacity(filtered_addresses.len());

    for (idx, pair) in filtered_addresses.iter().enumerate() {
        // Check cancellation periodically.
        if idx % 1000 == 0 && check_cancelled() {
            return Ok(Vec::new());
        }

        let mut buffer = vec![0u8; element_size];
        if driver_manager.read_memory_unified(pair.addr, &mut buffer, None).is_ok() {
            address_values.push((pair.clone(), buffer));
        }

        // Update processed counter and progress.
        if let Some(counter) = &processed_counter {
            let processed = counter.fetch_add(1, Ordering::Relaxed) + 1;
            // Update progress every 100 addresses.
            if processed % 100 == 0 {
                let found = total_found_counter.map(|c| c.load(Ordering::Relaxed)).unwrap_or(0);
                update_progress(processed, found);
            }
        }
    }

    drop(driver_manager);

    // Check cancellation before parallel matching.
    if check_cancelled() {
        return Ok(Vec::new());
    }

    // Use rayon for parallel matching.
    let results: Vec<ValuePair> = address_values
        .into_par_iter()
        .filter_map(|(pair, bytes)| {
            if let Ok(true) = target.matched(&bytes) {
                if let Some(counter) = &total_found_counter {
                    counter.fetch_add(1, Ordering::Relaxed);
                }
                Some(pair)
            } else {
                None
            }
        })
        .collect();

    // Final progress update.
    let found_count = total_found_counter.map(|c| c.load(Ordering::Relaxed)).unwrap_or(results.len());
    update_progress(total_addresses, found_count);

    if log_enabled!(Level::Debug) {
        debug!("Refine single search with cancel: {} -> {} results", filtered_addresses.len(), results.len());
    }

    Ok(results)
}
