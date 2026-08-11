# Attribution and provenance

MartHub is an independent implementation, written to practise and demonstrate the mechanisms below.

Architectural references consulted:
- HMDP / 黑马点评 public learning repository: https://github.com/cs001020/hmdp
- PJB0911/SecKill-ii public high-concurrency seckill repository and README: https://github.com/PJB0911/SecKill-ii

The public repository pages did not expose a clear repository-level LICENSE during reconstruction, so MartHub intentionally does **not** copy their source files. The implementation here uses common backend patterns described publicly in those learning materials: Redis token sessions and interceptors, multi-level caching, eligibility tokens, traffic gating/rate limiting, and horizontal scaling.
