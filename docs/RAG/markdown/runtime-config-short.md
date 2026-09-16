---
database: "PostgreSQL"
version: "18"
source: "official"
source_url: "https://www.postgresql.org/docs/18/runtime-config-short.html"
document: "runtime-config-short"
title: "PostgreSQL: Documentation: 18: 19.18. Short Options"
chapter: "PostgreSQL: Documentation: 18: 19.18. Short Options"
---

---

## 19.18. Short Options #

For convenience there are also single letter command-line option switches available for some parameters. They are described in Table 19.5. Some of these options exist for historical reasons, and their presence as a single-letter option does not necessarily indicate an endorsement to use the option heavily.

**Table 19.5. Short Option Key**

| Short Option | Equivalent |
| --- | --- |
| `-B x` | `shared_buffers = x` |
| `-d x` | `log_min_messages = DEBUGx` |
| `-e` | `datestyle = euro` |
| `-fb`, `-fh`, `-fi`, `-fm`, `-fn`, `-fo`, `-fs`, `-ft` | `enable_bitmapscan = off`, `enable_hashjoin = off`, `enable_indexscan = off`, `enable_mergejoin = off`, `enable_nestloop = off`, `enable_indexonlyscan = off`, `enable_seqscan = off`, `enable_tidscan = off` |
| `-F` | `fsync = off` |
| `-h x` | `listen_addresses = x` |
| `-i` | `listen_addresses = '*'` |
| `-k x` | `unix_socket_directories = x` |
| `-l` | `ssl = on` |
| `-N x` | `max_connections = x` |
| `-O` | `allow_system_table_mods = on` |
| `-p x` | `port = x` |
| `-P` | `ignore_system_indexes = on` |
| `-s` | `log_statement_stats = on` |
| `-S x` | `work_mem = x` |
| `-tpa`, `-tpl`, `-te` | `log_parser_stats = on`, `log_planner_stats = on`, `log_executor_stats = on` |
| `-W x` | `post_auth_delay = x` |

---
