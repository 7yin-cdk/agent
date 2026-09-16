---
database: "PostgreSQL"
version: "18"
source: "official"
source_url: "https://www.postgresql.org/docs/18/sql.html"
document: "sql"
title: "Part II. The SQL Language"
chapter: "Part II. The SQL Language"
---

---

# Part II. The SQL Language

This part describes the use of the SQL language in PostgreSQL. We start with describing the general syntax of SQL, then how to create tables, how to populate the database, and how to query it. The middle part lists the available data types and functions for use in SQL commands. Lastly, we address several aspects of importance for tuning a database.

The information is arranged so that a novice user can follow it from start to end and gain a full understanding of the topics without having to refer forward too many times. The chapters are intended to be self-contained, so that advanced users can read the chapters individually as they choose. The information is presented in narrative form with topical units. Readers looking for a complete description of a particular command are encouraged to review the Part VI.

Readers should know how to connect to a PostgreSQL database and issue SQL commands. Readers that are unfamiliar with these issues are encouraged to read Part I first. SQL commands are typically entered using the PostgreSQL interactive terminal psql, but other programs that have similar functionality can be used as well.

**Table of Contents**

4. SQL Syntax
:   4.1. Lexical Structure

    4.2. Value Expressions

    4.3. Calling Functions

5. Data Definition
:   5.1. Table Basics

    5.2. Default Values

    5.3. Identity Columns

    5.4. Generated Columns

    5.5. Constraints

    5.6. System Columns

    5.7. Modifying Tables

    5.8. Privileges

    5.9. Row Security Policies

    5.10. Schemas

    5.11. Inheritance

    5.12. Table Partitioning

    5.13. Foreign Data

    5.14. Other Database Objects

    5.15. Dependency Tracking

6. Data Manipulation
:   6.1. Inserting Data

    6.2. Updating Data

    6.3. Deleting Data

    6.4. Returning Data from Modified Rows

7. Queries
:   7.1. Overview

    7.2. Table Expressions

    7.3. Select Lists

    7.4. Combining Queries ( UNION , INTERSECT , EXCEPT )

    7.5. Sorting Rows ( ORDER BY )

    7.6. LIMIT and OFFSET

    7.7. VALUES Lists

    7.8. WITH Queries (Common Table Expressions)

8. Data Types
:   8.1. Numeric Types

    8.2. Monetary Types

    8.3. Character Types

    8.4. Binary Data Types

    8.5. Date/Time Types

    8.6. Boolean Type

    8.7. Enumerated Types

    8.8. Geometric Types

    8.9. Network Address Types

    8.10. Bit String Types

    8.11. Text Search Types

    8.12. UUID Type

    8.13. XML Type

    8.14. JSON Types

    8.15. Arrays

    8.16. Composite Types

    8.17. Range Types

    8.18. Domain Types

    8.19. Object Identifier Types

    8.20. pg\_lsn Type

    8.21. Pseudo-Types

9. Functions and Operators
:   9.1. Logical Operators

    9.2. Comparison Functions and Operators

    9.3. Mathematical Functions and Operators

    9.4. String Functions and Operators

    9.5. Binary String Functions and Operators

    9.6. Bit String Functions and Operators

    9.7. Pattern Matching

    9.8. Data Type Formatting Functions

    9.9. Date/Time Functions and Operators

    9.10. Enum Support Functions

    9.11. Geometric Functions and Operators

    9.12. Network Address Functions and Operators

    9.13. Text Search Functions and Operators

    9.14. UUID Functions

    9.15. XML Functions

    9.16. JSON Functions and Operators

    9.17. Sequence Manipulation Functions

    9.18. Conditional Expressions

    9.19. Array Functions and Operators

    9.20. Range/Multirange Functions and Operators

    9.21. Aggregate Functions

    9.22. Window Functions

    9.23. Merge Support Functions

    9.24. Subquery Expressions

    9.25. Row and Array Comparisons

    9.26. Set Returning Functions

    9.27. System Information Functions and Operators

    9.28. System Administration Functions

    9.29. Trigger Functions

    9.30. Event Trigger Functions

    9.31. Statistics Information Functions

10. Type Conversion
:   10.1. Overview

    10.2. Operators

    10.3. Functions

    10.4. Value Storage

    10.5. UNION , CASE , and Related Constructs

    10.6. SELECT Output Columns

11. Indexes
:   11.1. Introduction

    11.2. Index Types

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

12. Full Text Search
:   12.1. Introduction

    12.2. Tables and Indexes

    12.3. Controlling Text Search

    12.4. Additional Features

    12.5. Parsers

    12.6. Dictionaries

    12.7. Configuration Example

    12.8. Testing and Debugging Text Search

    12.9. Preferred Index Types for Text Search

    12.10. psql Support

    12.11. Limitations

13. Concurrency Control
:   13.1. Introduction

    13.2. Transaction Isolation

    13.3. Explicit Locking

    13.4. Data Consistency Checks at the Application Level

    13.5. Serialization Failure Handling

    13.6. Caveats

    13.7. Locking and Indexes

14. Performance Tips
:   14.1. Using EXPLAIN

    14.2. Statistics Used by the Planner

    14.3. Controlling the Planner with Explicit JOIN Clauses

    14.4. Populating a Database

    14.5. Non-Durable Settings

15. Parallel Query
:   15.1. How Parallel Query Works

    15.2. When Can Parallel Query Be Used?

    15.3. Parallel Plans

    15.4. Parallel Safety

---
