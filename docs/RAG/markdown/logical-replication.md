---
database: "PostgreSQL"
version: "18"
source: "official"
source_url: "https://www.postgresql.org/docs/18/logical-replication.html"
document: "logical-replication"
title: "PostgreSQL: Documentation: 18: Chapter 29. Logical Replication"
chapter: "Chapter 29. Logical Replication"
---

---

## Chapter 29. Logical Replication

**Table of Contents**

29.1. Publication
:   29.1.1. Replica Identity

29.2. Subscription
:   29.2.1. Replication Slot Management

    29.2.2. Examples: Set Up Logical Replication

    29.2.3. Examples: Deferred Replication Slot Creation

29.3. Logical Replication Failover

29.4. Row Filters
:   29.4.1. Row Filter Rules

    29.4.2. Expression Restrictions

    29.4.3. UPDATE Transformations

    29.4.4. Partitioned Tables

    29.4.5. Initial Data Synchronization

    29.4.6. Combining Multiple Row Filters

    29.4.7. Examples

29.5. Column Lists
:   29.5.1. Examples

29.6. Generated Column Replication

29.7. Conflicts

29.8. Restrictions

29.9. Architecture
:   29.9.1. Initial Snapshot

29.10. Monitoring

29.11. Security

29.12. Configuration Settings
:   29.12.1. Publishers

    29.12.2. Subscribers

29.13. Upgrade
:   29.13.1. Prepare for Publisher Upgrades

    29.13.2. Prepare for Subscriber Upgrades

    29.13.3. Upgrading Logical Replication Clusters

29.14. Quick Setup

Logical replication is a method of replicating data objects and their changes, based upon their replication identity (usually a primary key). We use the term logical in contrast to physical replication, which uses exact block addresses and byte-by-byte replication. PostgreSQL supports both mechanisms concurrently, see Chapter 26. Logical replication allows fine-grained control over both data replication and security.

Logical replication uses a *publish* and *subscribe* model with one or more *subscribers* subscribing to one or more *publications* on a *publisher* node. Subscribers pull data from the publications they subscribe to and may subsequently re-publish data to allow cascading replication or more complex configurations.

When logical replication of a table typically starts, PostgreSQL takes a snapshot of the table's data on the publisher database and copies it to the subscriber. Once complete, changes on the publisher since the initial copy are sent continually to the subscriber. The subscriber applies the data in the same order as the publisher so that transactional consistency is guaranteed for publications within a single subscription. This method of data replication is sometimes referred to as transactional replication.

The typical use-cases for logical replication are:

* Sending incremental changes in a single database or a subset of a database to subscribers as they occur.
* Firing triggers for individual changes as they arrive on the subscriber.
* Consolidating multiple databases into a single one (for example for analytical purposes).
* Replicating between different major versions of PostgreSQL.
* Replicating between PostgreSQL instances on different platforms (for example Linux to Windows).
* Giving access to replicated data to different groups of users.
* Sharing a subset of the database between multiple databases.

The subscriber database behaves in the same way as any other PostgreSQL instance and can be used as a publisher for other databases by defining its own publications. When the subscriber is treated as read-only by an application, there will be no conflicts from a single subscription. On the other hand, if there are other writes done either by an application or by other subscribers to the same set of tables, conflicts can arise.

---
