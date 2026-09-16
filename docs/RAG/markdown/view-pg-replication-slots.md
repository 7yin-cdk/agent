---
database: "PostgreSQL"
version: "18"
source: "official"
source_url: "https://www.postgresql.org/docs/18/view-pg-replication-slots.html"
document: "view-pg-replication-slots"
title: "PostgreSQL: Documentation: 18: 53.20. pg_replication_slots"
chapter: "PostgreSQL: Documentation: 18: 53.20. pg_replication_slots"
---

---

## 53.20. `pg_replication_slots` #

The `pg_replication_slots` view provides a listing of all replication slots that currently exist on the database cluster, along with their current state.

For more on replication slots, see Section 26.2.6 and Chapter 47.

**Table 53.20. `pg_replication_slots` Columns**

| Column Type  Description |
| --- |
| `slot_name` `name`  A unique, cluster-wide identifier for the replication slot |
| `plugin` `name`  The base name of the shared object containing the output plugin this logical slot is using, or null for physical slots. |
| `slot_type` `text`  The slot type: `physical` or `logical` |
| `datoid` `oid` (references pg\_database.`oid`)  The OID of the database this slot is associated with, or null. Only logical slots have an associated database. |
| `database` `name` (references pg\_database.`datname`)  The name of the database this slot is associated with, or null. Only logical slots have an associated database. |
| `temporary` `bool`  True if this is a temporary replication slot. Temporary slots are not saved to disk and are automatically dropped on error or when the session has finished. |
| `active` `bool`  True if this slot is currently being streamed |
| `active_pid` `int4`  The process ID of the session streaming data for this slot. `NULL` if inactive. |
| `xmin` `xid`  The oldest transaction that this slot needs the database to retain. `VACUUM` cannot remove tuples deleted by any later transaction. |
| `catalog_xmin` `xid`  The oldest transaction affecting the system catalogs that this slot needs the database to retain. `VACUUM` cannot remove catalog tuples deleted by any later transaction. |
| `restart_lsn` `pg_lsn`  The address (`LSN`) of oldest WAL which still might be required by the consumer of this slot and thus won't be automatically removed during checkpoints unless this LSN gets behind more than max\_slot\_wal\_keep\_size from the current LSN. `NULL` if the `LSN` of this slot has never been reserved. |
| `confirmed_flush_lsn` `pg_lsn`  The address (`LSN`) up to which the logical slot's consumer has confirmed receiving data. Data corresponding to the transactions committed before this `LSN` is not available anymore. `NULL` for physical slots. |
| `wal_status` `text`  Availability of WAL files claimed by this slot. Possible values are:  * `reserved` means that the claimed files are within `max_wal_size`. * `extended` means that `max_wal_size` is exceeded but the files are still retained, either by the replication slot or by `wal_keep_size`. * `unreserved` means that the slot no longer retains the required WAL files and some of them are to be removed at the next checkpoint. This typically occurs when max\_slot\_wal\_keep\_size is set to a non-negative value. This state can return to `reserved` or `extended`. * `lost` means that this slot is no longer usable. |
| `safe_wal_size` `int8`  The number of bytes that can be written to WAL such that this slot is not in danger of getting in state "lost". It is NULL for lost slots, as well as if `max_slot_wal_keep_size` is `-1`. |
| `two_phase` `bool`  True if the slot is enabled for decoding prepared transactions. Always false for physical slots. |
| `two_phase_at` `pg_lsn`  The address (`LSN`) from which the decoding of prepared transactions is enabled. `NULL` for logical slots where `two_phase` is false and for physical slots. |
| `inactive_since` `timestamptz`  The time when the slot became inactive. `NULL` if the slot is currently being streamed. If the slot becomes invalid, this value will never be updated. For standby slots that are being synced from a primary server (whose `synced` field is `true`), the `inactive_since` indicates the time when slot synchronization (see Section 47.2.3) was most recently stopped. `NULL` if the slot has always been synchronized. This helps standby slots track when synchronization was interrupted. |
| `conflicting` `bool`  True if this logical slot conflicted with recovery (and so is now invalidated). When this column is true, check `invalidation_reason` column for the conflict reason. Always `NULL` for physical slots. |
| `invalidation_reason` `text`  The reason for the slot's invalidation. It is set for both logical and physical slots. `NULL` if the slot is not invalidated. Possible values are:  * `wal_removed` means that the required WAL has been removed. * `rows_removed` means that the required rows have been removed. It is set only for logical slots. * `wal_level_insufficient` means that the primary doesn't have a wal\_level sufficient to perform logical decoding. It is set only for logical slots. * `idle_timeout` means that the slot has remained inactive longer than the configured idle\_replication\_slot\_timeout duration. |
| `failover` `bool`  True if this is a logical slot enabled to be synced to the standbys so that logical replication can be resumed from the new primary after failover. Always false for physical slots. |
| `synced` `bool`  True if this is a logical slot that was synced from a primary server. On a hot standby, the slots with the synced column marked as true can neither be used for logical decoding nor dropped manually. The value of this column has no meaning on the primary server; the column value on the primary is default false for all slots but may (if leftover from a promoted standby) also be true. |

---
