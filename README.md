# CircularSync

CircularSync is a campus circular parsing daemon and query system. It watches a
directory for notice files (`.txt` or `.md`), parses and validates them on a
worker thread pool, ranks them by urgency and priority, and answers queries from
the command line or a local web portal. Every outcome is written to an audit log.

## Requirements

JDK 17 or newer. No third-party libraries are used.

## Build and test

```
./build.sh          # Linux, macOS, Git Bash
build.bat           # Windows cmd
```

Both scripts compile `src/` and `test/` into `out/` and run the test suite
(`com.circularsync.test.CircularSyncTestSuite`). Pass `--skip-tests` to compile
only. If `java` is not on your PATH, set `JAVA_HOME` first.

## Run

```
java -cp out com.circularsync.main.Main scan
java -cp out com.circularsync.main.Main daemon
java -cp out com.circularsync.main.Main daemon --duration 30
java -cp out com.circularsync.main.Main web
java -cp out com.circularsync.main.Main query top 3
java -cp out com.circularsync.main.Main query search hall-ticket
```

Options: `--dir <path>` (default `incoming_notices`), `--logs <path>` (default
`audit_logs`), `--threads <n>` (default 4), `--duration <seconds>` (daemon
only), `--today <yyyy-MM-dd>` (fix the reference date).

## Web portal

`web` runs the daemon plus a browser portal at http://localhost:8080 (bound to
this computer only) and opens it in your browser. The **Uploads** tab accepts
drag-and-drop uploads and one-click demo notices and shows the live status of
every file the daemon sees. The **Notice Board** tab shows the ranked output
with search, category and deadline filters, notice details and a live pipeline
activity feed. Uploads are written into `incoming_notices` and go through the
same WatchService and worker pool as any other file.

Portal options: `--port <n>` (default 8080), `--webroot <path>` (default
`web`), `--no-browser`. On Windows: `run.bat web --today 2026-11-04`.

Query commands, available in the daemon shell and through `query`: `list`,
`top <n>`, `category <EXAM|PLACEMENT|CLUB>`, `search <words...>`, `due <days>`,
`show <id>`, `stats`, `help`, `quit`.

## Notice file format

```
TYPE: EXAM
TITLE: End Semester Examination Schedule - CS301
DATE: 2026-11-02
DEADLINE: 2026-11-10
ISSUER: Office of the Controller of Examinations
PRIORITY_KEYS: mandatory, hall-ticket
COURSE_CODE: CS301
EXAM_DATE: 2026-11-16
VENUE: Examination Hall A
---
Body text of the circular.
```

Every notice needs `TYPE`, `TITLE`, `DATE`, `DEADLINE` and `PRIORITY_KEYS`.
Each type also needs its own headers:

- `EXAM`: `COURSE_CODE`, `EXAM_DATE`, `VENUE` (optional `EXAM_TYPE`)
- `PLACEMENT`: `COMPANY`, `ROLE`, `ELIGIBILITY` (optional `PACKAGE_LPA`, `MIN_CGPA`, `DRIVE_DATE`)
- `CLUB`: `CLUB_NAME`, `EVENT_DATE`, `EVENT_VENUE` (optional `SEATS`)

Markdown files can decorate headers as `- **TITLE:** value`. Dates use ISO
format. A notice whose deadline has passed is rejected and audited instead of
being indexed.

## Layout

```
src/com/circularsync/
  model/       Notice (abstract), ExamNotice, PlacementNotice, ClubNotice, NoticeCategory
  interfaces/  Processable
  exceptions/  CircularSyncException, InvalidNoticeFormatException, ExpiredDeadlineException
  service/     NoticeParserService, NoticeRegistry, AuditLogger
  daemon/      DirectoryWatcherDaemon, ProcessingEvent, ProcessingListener
  web/         WebPortalServer, FileActivityTracker, JsonWriter
  main/        Main, QueryConsole
test/com/circularsync/test/CircularSyncTestSuite.java
web/index.html      the portal page
demo/               notices to drop in during a demo
incoming_notices/   sample notices
audit_logs/         audit.log and processed_notices.txt (generated at runtime)
```

See `DEMO.md` for a step-by-step live demo, and `CircularSync_Project_Report.txt`
for the full design report.
