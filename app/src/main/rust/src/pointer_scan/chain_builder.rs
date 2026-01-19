//! 第二阶段：指针链构造器
//!
//! 本模块从目标地址反向构建指针链，追溯到静态模块。
//! 使用第一阶段构建的指针库来查找所有可能的路径。
//!
//! ## 算法
//! 使用来自 PointerScan-rust 的 BFS V2 算法：
//! - MapQueue (tmpfile + mmap) 避免内存爆炸
//! - PointerDir 隐式树结构 (start/end 索引)
//! - 多级 BFS 迭代，二分查找优化
//! - 直接写入文件，避免内存问题

pub mod bfs_v2;

// Re-export BFS V2 scanner
pub use bfs_v2::{BfsV2Scanner, ScanResult};
