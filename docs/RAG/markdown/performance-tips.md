---
database: "PostgreSQL"
version: "18"
source: "official"
source_url: "https://www.postgresql.org/docs/18/performance-tips.html"
document: "performance-tips"
title: "PostgreSQL: Documentation: 18: Chapter 14. Performance Tips"
chapter: "Chapter 14. Performance Tips"
---

---

## Chapter 14. Performance Tips

**Table of Contents**

14.1. Using EXPLAIN
:   14.1.1. EXPLAIN Basics

    14.1.2. EXPLAIN ANALYZE

    14.1.3. Caveats

14.2. Statistics Used by the Planner
:   14.2.1. Single-Column Statistics

    14.2.2. Extended Statistics

14.3. Controlling the Planner with Explicit JOIN Clauses

14.4. Populating a Database
:   14.4.1. Disable Autocommit

    14.4.2. Use COPY

    14.4.3. Remove Indexes

    14.4.4. Remove Foreign Key Constraints

    14.4.5. Increase maintenance\_work\_mem

    14.4.6. Increase max\_wal\_size

    14.4.7. Disable WAL Archival and Streaming Replication

    14.4.8. Run ANALYZE Afterwards

    14.4.9. Some Notes about pg\_dump

14.5. Non-Durable Settings

Query performance can be affected by many things. Some of these can be controlled by the user, while others are fundamental to the underlying design of the system. This chapter provides some hints about understanding and tuning PostgreSQL performance.

---
