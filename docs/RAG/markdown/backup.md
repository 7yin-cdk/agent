---
database: "PostgreSQL"
version: "18"
source: "official"
source_url: "https://www.postgresql.org/docs/18/backup.html"
document: "backup"
title: "PostgreSQL: Documentation: 18: Chapter 25. Backup and Restore"
chapter: "Chapter 25. Backup and Restore"
---

---

## Chapter 25. Backup and Restore

**Table of Contents**

25.1. SQL Dump
:   25.1.1. Restoring the Dump

    25.1.2. Using pg\_dumpall

    25.1.3. Handling Large Databases

25.2. File System Level Backup

25.3. Continuous Archiving and Point-in-Time Recovery (PITR)
:   25.3.1. Setting Up WAL Archiving

    25.3.2. Making a Base Backup

    25.3.3. Making an Incremental Backup

    25.3.4. Making a Base Backup Using the Low Level API

    25.3.5. Recovering Using a Continuous Archive Backup

    25.3.6. Timelines

    25.3.7. Tips and Examples

    25.3.8. Caveats

As with everything that contains valuable data, PostgreSQL databases should be backed up regularly. While the procedure is essentially simple, it is important to have a clear understanding of the underlying techniques and assumptions.

There are three fundamentally different approaches to backing up PostgreSQL data:

* SQL dump
* File system level backup
* Continuous archiving

Each has its own strengths and weaknesses; each is discussed in turn in the following sections.

---
