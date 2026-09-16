---
database: "PostgreSQL"
version: "18"
source: "official"
source_url: "https://www.postgresql.org/docs/18/mvcc.html"
document: "mvcc"
title: "PostgreSQL: Documentation: 18: Chapter 13. Concurrency Control"
chapter: "Chapter 13. Concurrency Control"
---

---

## Chapter 13. Concurrency Control

**Table of Contents**

13.1. Introduction

13.2. Transaction Isolation
:   13.2.1. Read Committed Isolation Level

    13.2.2. Repeatable Read Isolation Level

    13.2.3. Serializable Isolation Level

13.3. Explicit Locking
:   13.3.1. Table-Level Locks

    13.3.2. Row-Level Locks

    13.3.3. Page-Level Locks

    13.3.4. Deadlocks

    13.3.5. Advisory Locks

13.4. Data Consistency Checks at the Application Level
:   13.4.1. Enforcing Consistency with Serializable Transactions

    13.4.2. Enforcing Consistency with Explicit Blocking Locks

13.5. Serialization Failure Handling

13.6. Caveats

13.7. Locking and Indexes

This chapter describes the behavior of the PostgreSQL database system when two or more sessions try to access the same data at the same time. The goals in that situation are to allow efficient access for all sessions while maintaining strict data integrity. Every developer of database applications should be familiar with the topics covered in this chapter.

---
