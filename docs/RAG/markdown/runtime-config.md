---
database: "PostgreSQL"
version: "18"
source: "official"
source_url: "https://www.postgresql.org/docs/18/runtime-config.html"
document: "runtime-config"
title: "PostgreSQL: Documentation: 18: Chapter 19. Server Configuration"
chapter: "Chapter 19. Server Configuration"
---

---

## Chapter 19. Server Configuration

**Table of Contents**

19.1. Setting Parameters
:   19.1.1. Parameter Names and Values

    19.1.2. Parameter Interaction via the Configuration File

    19.1.3. Parameter Interaction via SQL

    19.1.4. Parameter Interaction via the Shell

    19.1.5. Managing Configuration File Contents

19.2. File Locations

19.3. Connections and Authentication
:   19.3.1. Connection Settings

    19.3.2. TCP Settings

    19.3.3. Authentication

    19.3.4. SSL

19.4. Resource Consumption
:   19.4.1. Memory

    19.4.2. Disk

    19.4.3. Kernel Resource Usage

    19.4.4. Background Writer

    19.4.5. I/O

    19.4.6. Worker Processes

19.5. Write Ahead Log
:   19.5.1. Settings

    19.5.2. Checkpoints

    19.5.3. Archiving

    19.5.4. Recovery

    19.5.5. Archive Recovery

    19.5.6. Recovery Target

    19.5.7. WAL Summarization

19.6. Replication
:   19.6.1. Sending Servers

    19.6.2. Primary Server

    19.6.3. Standby Servers

    19.6.4. Subscribers

19.7. Query Planning
:   19.7.1. Planner Method Configuration

    19.7.2. Planner Cost Constants

    19.7.3. Genetic Query Optimizer

    19.7.4. Other Planner Options

19.8. Error Reporting and Logging
:   19.8.1. Where to Log

    19.8.2. When to Log

    19.8.3. What to Log

    19.8.4. Using CSV-Format Log Output

    19.8.5. Using JSON-Format Log Output

    19.8.6. Process Title

19.9. Run-time Statistics
:   19.9.1. Cumulative Query and Index Statistics

    19.9.2. Statistics Monitoring

19.10. Vacuuming
:   19.10.1. Automatic Vacuuming

    19.10.2. Cost-based Vacuum Delay

    19.10.3. Default Behavior

    19.10.4. Freezing

19.11. Client Connection Defaults
:   19.11.1. Statement Behavior

    19.11.2. Locale and Formatting

    19.11.3. Shared Library Preloading

    19.11.4. Other Defaults

19.12. Lock Management

19.13. Version and Platform Compatibility
:   19.13.1. Previous PostgreSQL Versions

    19.13.2. Platform and Client Compatibility

19.14. Error Handling

19.15. Preset Options

19.16. Customized Options

19.17. Developer Options

19.18. Short Options

There are many configuration parameters that affect the behavior of the database system. In the first section of this chapter we describe how to interact with configuration parameters. The subsequent sections discuss each parameter in detail.

---
