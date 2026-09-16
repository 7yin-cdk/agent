---
database: "PostgreSQL"
version: "18"
source: "official"
source_url: "https://www.postgresql.org/docs/18/monitoring.html"
document: "monitoring"
title: "PostgreSQL: Documentation: 18: Chapter 27. Monitoring Database Activity"
chapter: "Chapter 27. Monitoring Database Activity"
---

---

## Chapter 27. Monitoring Database Activity

**Table of Contents**

27.1. Standard Unix Tools

27.2. The Cumulative Statistics System
:   27.2.1. Statistics Collection Configuration

    27.2.2. Viewing Statistics

    27.2.3. pg\_stat\_activity

    27.2.4. pg\_stat\_replication

    27.2.5. pg\_stat\_replication\_slots

    27.2.6. pg\_stat\_wal\_receiver

    27.2.7. pg\_stat\_recovery\_prefetch

    27.2.8. pg\_stat\_subscription

    27.2.9. pg\_stat\_subscription\_stats

    27.2.10. pg\_stat\_ssl

    27.2.11. pg\_stat\_gssapi

    27.2.12. pg\_stat\_archiver

    27.2.13. pg\_stat\_io

    27.2.14. pg\_stat\_bgwriter

    27.2.15. pg\_stat\_checkpointer

    27.2.16. pg\_stat\_wal

    27.2.17. pg\_stat\_database

    27.2.18. pg\_stat\_database\_conflicts

    27.2.19. pg\_stat\_all\_tables

    27.2.20. pg\_stat\_all\_indexes

    27.2.21. pg\_statio\_all\_tables

    27.2.22. pg\_statio\_all\_indexes

    27.2.23. pg\_statio\_all\_sequences

    27.2.24. pg\_stat\_user\_functions

    27.2.25. pg\_stat\_slru

    27.2.26. Statistics Functions

27.3. Viewing Locks

27.4. Progress Reporting
:   27.4.1. ANALYZE Progress Reporting

    27.4.2. CLUSTER Progress Reporting

    27.4.3. COPY Progress Reporting

    27.4.4. CREATE INDEX Progress Reporting

    27.4.5. VACUUM Progress Reporting

    27.4.6. Base Backup Progress Reporting

27.5. Dynamic Tracing
:   27.5.1. Compiling for Dynamic Tracing

    27.5.2. Built-in Probes

    27.5.3. Using Probes

    27.5.4. Defining New Probes

27.6. Monitoring Disk Usage
:   27.6.1. Determining Disk Usage

    27.6.2. Disk Full Failure

A database administrator frequently wonders, “What is the system doing right now?” This chapter discusses how to find that out.

Several tools are available for monitoring database activity and analyzing performance. Most of this chapter is devoted to describing PostgreSQL's cumulative statistics system, but one should not neglect regular Unix monitoring programs such as `ps`, `top`, `iostat`, and `vmstat`. Also, once one has identified a poorly-performing query, further investigation might be needed using PostgreSQL's EXPLAIN command. Section 14.1 discusses `EXPLAIN` and other methods for understanding the behavior of an individual query.

---
