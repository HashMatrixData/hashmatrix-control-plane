-- 配额结构对齐契约 QuotaSpec（openapi/control-plane-v1，issue #9）：
--   字节 → GiB 单位（quota_max_data_bytes → quota_max_storage_gi）、
--   重命名 quota_max_jobs → quota_max_concurrent_jobs、
--   新增计算配额 compute（CPU 核 / 内存 GiB）。
-- 占位/示例一律脱敏——红线见 CLAUDE.md。

ALTER TABLE tenant ADD COLUMN quota_max_storage_gi      INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tenant ADD COLUMN quota_max_concurrent_jobs INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tenant ADD COLUMN quota_compute_cpu_cores   INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tenant ADD COLUMN quota_compute_memory_gi   INTEGER NOT NULL DEFAULT 0;

-- 字节 → GiB：向上取整并对已有非零配额保底 1GiB，避免把存量配额抹平为 0。
UPDATE tenant
   SET quota_max_storage_gi = GREATEST(1, CEIL(quota_max_data_bytes::numeric / (1024 * 1024 * 1024)))
 WHERE quota_max_data_bytes > 0;

UPDATE tenant SET quota_max_concurrent_jobs = quota_max_jobs;

ALTER TABLE tenant DROP COLUMN quota_max_data_bytes;
ALTER TABLE tenant DROP COLUMN quota_max_jobs;
