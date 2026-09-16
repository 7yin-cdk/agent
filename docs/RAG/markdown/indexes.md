---
database: "PostgreSQL"
version: "18"
source: "official"
source_url: "https://www.postgresql.org/docs/18/indexes.html"
document: "indexes"
title: "PostgreSQL: Documentation: 18: Chapter 11. Indexes"
chapter: "Chapter 11. Indexes"
---

---

## Chapter 11. Indexes

**Table of Contents**

11.1. Introduction

11.2. Index Types
:   11.2.1. B-Tree

    11.2.2. Hash

    11.2.3. GiST

    11.2.4. SP-GiST

    11.2.5. GIN

    11.2.6. BRIN

11.3. Multicolumn Indexes

11.4. Indexes and ORDER BY

11.5. Combining Multiple Indexes

11.6. Unique Indexes

11.7. Indexes on Expressions

11.8. Partial Indexes

11.9. Index-Only Scans and Covering Indexes

11.10. Operator Classes and Operator Families

11.11. Indexes and Collations

11.12. Examining Index Usage

Indexes are a common way to enhance database performance. An index allows the database server to find and retrieve specific rows much faster than it could do without an index. But indexes also add overhead to the database system as a whole, so they should be used sensibly.

---
