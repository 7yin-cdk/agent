---
database: "PostgreSQL"
version: "18"
source: "official"
source_url: "https://www.postgresql.org/docs/18/event-log-registration.html"
document: "event-log-registration"
title: "PostgreSQL: Documentation: 18: 18.12. Registering Event Log on Windows"
chapter: "PostgreSQL: Documentation: 18: 18.12. Registering Event Log on Windows"
---

---

## 18.12. Registering Event Log on Windows #

To register a Windows event log library with the operating system, issue this command:

```
regsvr32 pgsql_library_directory/pgevent.dll
```

This creates registry entries used by the event viewer, under the default event source named `PostgreSQL`.

To specify a different event source name (see event\_source), use the `/n` and `/i` options:

```
regsvr32 /n /i:event_source_name pgsql_library_directory/pgevent.dll
```

To unregister the event log library from the operating system, issue this command:

```
regsvr32 /u [/i:event_source_name] pgsql_library_directory/pgevent.dll
```

### Note

To enable event logging in the database server, modify log\_destination to include `eventlog` in `postgresql.conf`.

---
