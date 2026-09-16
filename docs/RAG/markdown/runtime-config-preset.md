---
database: "PostgreSQL"
version: "18"
source: "official"
source_url: "https://www.postgresql.org/docs/18/runtime-config-preset.html"
document: "runtime-config-preset"
title: "PostgreSQL: Documentation: 18: 19.15. Preset Options"
chapter: "PostgreSQL: Documentation: 18: 19.15. Preset Options"
---

---

## 19.15. Preset Options #

The following “parameters” are read-only. As such, they have been excluded from the sample `postgresql.conf` file. These options report various aspects of PostgreSQL behavior that might be of interest to certain applications, particularly administrative front-ends. Most of them are determined when PostgreSQL is compiled or when it is installed.

`block_size` (`integer`) #
:   Reports the size of a disk block. It is determined by the value of `BLCKSZ` when building the server. The default value is 8192 bytes. The meaning of some configuration variables (such as shared\_buffers) is influenced by `block_size`. See Section 19.4 for information.

`data_checksums` (`boolean`) #
:   Reports whether data checksums are enabled for this cluster. See -k for more information.

`data_directory_mode` (`integer`) #
:   On Unix systems this parameter reports the permissions the data directory (defined by data\_directory) had at server startup. (On Microsoft Windows this parameter will always display `0700`.) See the initdb -g option for more information.

`debug_assertions` (`boolean`) #
:   Reports whether PostgreSQL has been built with assertions enabled. That is the case if the macro `USE_ASSERT_CHECKING` is defined when PostgreSQL is built (accomplished e.g., by the `configure` option `--enable-cassert`). By default PostgreSQL is built without assertions.

`huge_pages_status` (`enum`) #
:   Reports the state of huge pages in the current instance: `on`, `off`, or `unknown` (if displayed with `postgres -C`). This parameter is useful to determine whether allocation of huge pages was successful under `huge_pages=try`. See huge\_pages for more information.

`integer_datetimes` (`boolean`) #
:   Reports whether PostgreSQL was built with support for 64-bit-integer dates and times. As of PostgreSQL 10, this is always `on`.

`in_hot_standby` (`boolean`) #
:   Reports whether the server is currently in hot standby mode. When this is `on`, all transactions are forced to be read-only. Within a session, this can change only if the server is promoted to be primary. See Section 26.4 for more information.

`max_function_args` (`integer`) #
:   Reports the maximum number of function arguments. It is determined by the value of `FUNC_MAX_ARGS` when building the server. The default value is 100 arguments.

`max_identifier_length` (`integer`) #
:   Reports the maximum identifier length. It is determined as one less than the value of `NAMEDATALEN` when building the server. The default value of `NAMEDATALEN` is 64; therefore the default `max_identifier_length` is 63 bytes, which can be less than 63 characters when using multibyte encodings.

`max_index_keys` (`integer`) #
:   Reports the maximum number of index keys. It is determined by the value of `INDEX_MAX_KEYS` when building the server. The default value is 32 keys.

`num_os_semaphores` (`integer`) #
:   Reports the number of semaphores that are needed for the server based on the configured number of allowed connections (max\_connections), allowed autovacuum worker processes (autovacuum\_max\_workers), allowed WAL sender processes (max\_wal\_senders), allowed background processes (max\_worker\_processes), etc.

`segment_size` (`integer`) #
:   Reports the number of blocks (pages) that can be stored within a file segment. It is determined by the value of `RELSEG_SIZE` when building the server. The maximum size of a segment file in bytes is equal to `segment_size` multiplied by `block_size`; by default this is 1GB.

`server_encoding` (`string`) #
:   Reports the database encoding (character set). It is determined when the database is created. Ordinarily, clients need only be concerned with the value of client\_encoding.

`server_version` (`string`) #
:   Reports the version number of the server. It is determined by the value of `PG_VERSION` when building the server.

`server_version_num` (`integer`) #
:   Reports the version number of the server as an integer. It is determined by the value of `PG_VERSION_NUM` when building the server.

`shared_memory_size` (`integer`) #
:   Reports the size of the main shared memory area, rounded up to the nearest megabyte.

`shared_memory_size_in_huge_pages` (`integer`) #
:   Reports the number of huge pages that are needed for the main shared memory area based on the specified huge\_page\_size. If huge pages are not supported, this will be `-1`.

    This setting is supported only on Linux. It is always set to `-1` on other platforms. For more details about using huge pages on Linux, see Section 18.4.5.

`ssl_library` (`string`) #
:   Reports the name of the SSL library that this PostgreSQL server was built with (even if SSL is not currently configured or in use on this instance), for example `OpenSSL`, or an empty string if none.

`wal_block_size` (`integer`) #
:   Reports the size of a WAL disk block. It is determined by the value of `XLOG_BLCKSZ` when building the server. The default value is 8192 bytes.

`wal_segment_size` (`integer`) #
:   Reports the size of write ahead log segments. The default value is 16MB. See Section 28.5 for more information.

---
