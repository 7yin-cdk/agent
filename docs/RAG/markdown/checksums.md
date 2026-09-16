---
database: "PostgreSQL"
version: "18"
source: "official"
source_url: "https://www.postgresql.org/docs/18/checksums.html"
document: "checksums"
title: "PostgreSQL: Documentation: 18: 28.2. Data Checksums"
chapter: "PostgreSQL: Documentation: 18: 28.2. Data Checksums"
---

---

## 28.2. Data Checksums #

28.2.1. Off-line Enabling of Checksums

By default, data pages are protected by checksums, but this can optionally be disabled for a cluster. When enabled, each data page includes a checksum that is updated when the page is written and verified each time the page is read. Only data pages are protected by checksums; internal data structures and temporary files are not.

Checksums can be disabled when the cluster is initialized using initdb. They can also be enabled or disabled at a later time as an offline operation. Data checksums are enabled or disabled at the full cluster level, and cannot be specified individually for databases or tables.

The current state of checksums in the cluster can be verified by viewing the value of the read-only configuration variable data\_checksums by issuing the command `SHOW data_checksums`.

When attempting to recover from page corruptions, it may be necessary to bypass the checksum protection. To do this, temporarily set the configuration parameter ignore\_checksum\_failure.

### 28.2.1. Off-line Enabling of Checksums #

The pg\_checksums application can be used to enable or disable data checksums, as well as verify checksums, on an offline cluster.

---
