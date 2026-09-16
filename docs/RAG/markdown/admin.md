---
database: "PostgreSQL"
version: "18"
source: "official"
source_url: "https://www.postgresql.org/docs/18/admin.html"
document: "admin"
title: "Part III. Server Administration"
chapter: "Part III. Server Administration"
---

---

# Part III. Server Administration

This part covers topics that are of interest to a PostgreSQL administrator. This includes installation, configuration of the server, management of users and databases, and maintenance tasks. Anyone running PostgreSQL server, even for personal use, but especially in production, should be familiar with these topics.

The information attempts to be in the order in which a new user should read it. The chapters are self-contained and can be read individually as desired. The information is presented in a narrative form in topical units. Readers looking for a complete description of a command are encouraged to review the Part VI.

The first few chapters are written so they can be understood without prerequisite knowledge, so new users who need to set up their own server can begin their exploration. The rest of this part is about tuning and management; that material assumes that the reader is familiar with the general use of the PostgreSQL database system. Readers are encouraged review the Part I and Part II parts for additional information.

**Table of Contents**

16. Installation from Binaries

17. Installation from Source Code
:   17.1. Requirements

    17.2. Getting the Source

    17.3. Building and Installation with Autoconf and Make

    17.4. Building and Installation with Meson

    17.5. Post-Installation Setup

    17.6. Supported Platforms

    17.7. Platform-Specific Notes

18. Server Setup and Operation
:   18.1. The PostgreSQL User Account

    18.2. Creating a Database Cluster

    18.3. Starting the Database Server

    18.4. Managing Kernel Resources

    18.5. Shutting Down the Server

    18.6. Upgrading a PostgreSQL Cluster

    18.7. Preventing Server Spoofing

    18.8. Encryption Options

    18.9. Secure TCP/IP Connections with SSL

    18.10. Secure TCP/IP Connections with GSSAPI Encryption

    18.11. Secure TCP/IP Connections with SSH Tunnels

    18.12. Registering Event Log on Windows

19. Server Configuration
:   19.1. Setting Parameters

    19.2. File Locations

    19.3. Connections and Authentication

    19.4. Resource Consumption

    19.5. Write Ahead Log

    19.6. Replication

    19.7. Query Planning

    19.8. Error Reporting and Logging

    19.9. Run-time Statistics

    19.10. Vacuuming

    19.11. Client Connection Defaults

    19.12. Lock Management

    19.13. Version and Platform Compatibility

    19.14. Error Handling

    19.15. Preset Options

    19.16. Customized Options

    19.17. Developer Options

    19.18. Short Options

20. Client Authentication
:   20.1. The pg\_hba.conf File

    20.2. User Name Maps

    20.3. Authentication Methods

    20.4. Trust Authentication

    20.5. Password Authentication

    20.6. GSSAPI Authentication

    20.7. SSPI Authentication

    20.8. Ident Authentication

    20.9. Peer Authentication

    20.10. LDAP Authentication

    20.11. RADIUS Authentication

    20.12. Certificate Authentication

    20.13. PAM Authentication

    20.14. BSD Authentication

    20.15. OAuth Authorization/Authentication

    20.16. Authentication Problems

21. Database Roles
:   21.1. Database Roles

    21.2. Role Attributes

    21.3. Role Membership

    21.4. Dropping Roles

    21.5. Predefined Roles

    21.6. Function Security

22. Managing Databases
:   22.1. Overview

    22.2. Creating a Database

    22.3. Template Databases

    22.4. Database Configuration

    22.5. Destroying a Database

    22.6. Tablespaces

23. Localization
:   23.1. Locale Support

    23.2. Collation Support

    23.3. Character Set Support

24. Routine Database Maintenance Tasks
:   24.1. Routine Vacuuming

    24.2. Routine Reindexing

    24.3. Log File Maintenance

25. Backup and Restore
:   25.1. SQL Dump

    25.2. File System Level Backup

    25.3. Continuous Archiving and Point-in-Time Recovery (PITR)

26. High Availability, Load Balancing, and Replication
:   26.1. Comparison of Different Solutions

    26.2. Log-Shipping Standby Servers

    26.3. Failover

    26.4. Hot Standby

27. Monitoring Database Activity
:   27.1. Standard Unix Tools

    27.2. The Cumulative Statistics System

    27.3. Viewing Locks

    27.4. Progress Reporting

    27.5. Dynamic Tracing

    27.6. Monitoring Disk Usage

28. Reliability and the Write-Ahead Log
:   28.1. Reliability

    28.2. Data Checksums

    28.3. Write-Ahead Logging ( WAL )

    28.4. Asynchronous Commit

    28.5. WAL Configuration

    28.6. WAL Internals

29. Logical Replication
:   29.1. Publication

    29.2. Subscription

    29.3. Logical Replication Failover

    29.4. Row Filters

    29.5. Column Lists

    29.6. Generated Column Replication

    29.7. Conflicts

    29.8. Restrictions

    29.9. Architecture

    29.10. Monitoring

    29.11. Security

    29.12. Configuration Settings

    29.13. Upgrade

    29.14. Quick Setup

30. Just-in-Time Compilation ( JIT )
:   30.1. What Is JIT compilation?

    30.2. When to JIT ?

    30.3. Configuration

    30.4. Extensibility

31. Regression Tests
:   31.1. Running the Tests

    31.2. Test Evaluation

    31.3. Variant Comparison Files

    31.4. TAP Tests

    31.5. Test Coverage Examination

---
