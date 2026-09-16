---
database: "PostgreSQL"
version: "18"
source: "official"
source_url: "https://www.postgresql.org/docs/18/planner-stats-details.html"
document: "planner-stats-details"
title: "PostgreSQL: Documentation: 18: Chapter 69. How the Planner Uses Statistics"
chapter: "Chapter 69. How the Planner Uses Statistics"
---

---

## Chapter 69. How the Planner Uses Statistics

**Table of Contents**

69.1. Row Estimation Examples

69.2. Multivariate Statistics Examples
:   69.2.1. Functional Dependencies

    69.2.2. Multivariate N-Distinct Counts

    69.2.3. MCV Lists

69.3. Planner Statistics and Security

This chapter builds on the material covered in Section 14.1 and Section 14.2 to show some additional details about how the planner uses the system statistics to estimate the number of rows each part of a query might return. This is a significant part of the planning process, providing much of the raw material for cost calculation.

The intent of this chapter is not to document the code in detail, but to present an overview of how it works. This will perhaps ease the learning curve for someone who subsequently wishes to read the code.

---
