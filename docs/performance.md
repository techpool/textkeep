# Measured synthetic benchmark

A local macOS Apple M4 client with Node 24.14.1 and a disposable PostgreSQL 18 Docker database ingested 50,000 synthetic records through the real transactional ingestion service in 521,364 ms (about 96 records/second). This includes encryption and source/retry bookkeeping. Batches contained 100 records.

Twenty no-match searches over that archive scanned exactly 500 candidates each and reported an incomplete scan plus continuation cursor. Search latency was 20 ms at p50 and 45 ms at p95. Client RSS sampled once per second during the final part of the run peaked at 89 MiB. That sampled value is not a lifetime peak or server/database memory measurement.

These results describe a local server benchmark, not Android import throughput, carrier latency, production load capacity or a device battery benchmark. Physical large-inbox and OEM measurements remain in the acceptance checklist.
