# CircularSync

CircularSync is a campus circular parsing daemon and query system. It watches a
directory for circulars (PDF, Word `.docx`, `.txt` or `.md`), extracts and
validates them on a worker thread pool, ranks them by urgency and priority, and
serves them through a web portal and a command-line query shell. Every outcome
is written to an audit log.

## Requirements

JDK 17 or newer. The only third-party dependency is Apache PDFBox (for PDF
text), which the build downloads from Maven Central into `lib/` and verifies
against pinned SHA-256 checksums (`tools/FetchLibs.java`).

## Build and test

```
./build.sh          # Linux, macOS, Git Bash
build.bat           # Windows cmd
```

Both scripts fetch `lib/`, compile `src/` and `test/` into `out/`, and run the
test suite (`com.circularsync.test.CircularSyncTestSuite`). Pass `--skip-tests`
to compile only. If `java` is not on your PATH, set `JAVA_HOME` first.

## Run

On Windows, `run.bat <command> [options]` sets the classpath for you:

```
run.bat web
run.bat scan
run.bat daemon
run.bat query top 3
```

Elsewhere: `java -cp "out:lib/*" com.circularsync.main.Main <command> [options]`.

General options: `--dir <path>` (default `incoming_notices`), `--logs <path>`
(default `audit_logs`), `--threads <n>` (default 4), `--duration <seconds>`
(daemon only), `--today <yyyy-MM-dd>` (fix the reference date).

Query commands, available in the daemon shell and through `query`: `list`,
`top <n>`, `category <EXAM|PLACEMENT|CLUB|GENERAL>`, `search <words...>`,
`due <days>`, `show <id>`, `stats`, `help`, `quit`.

## Web portal

`web` runs the daemon plus a browser portal (default http://localhost:8080).
The **Uploads** tab accepts drag-and-drop uploads and one-click demo notices
and shows the live status of every file the daemon sees. The **Notice Board**
tab shows the ranked output with search, category and deadline filters, notice
details, a link to the original document, and a live pipeline activity feed.
Uploads are written into `incoming_notices` and go through the same
WatchService and worker pool as any other file.

Portal options:

| Option | Meaning |
| --- | --- |
| `--port <n>` | port to listen on (default 8080, or `$PORT`) |
| `--host <addr>` | interface to bind (default `127.0.0.1`; `0.0.0.0` for all) |
| `--public` | the portal is reachable from the internet; requires an admin key |
| `--admin-key <key>` | admin key (default `$CIRCULARSYNC_ADMIN_KEY`; generated and printed if public and unset) |
| `--trust-proxy` | take client IPs from `X-Forwarded-For` / `CF-Connecting-IP` (behind a proxy or tunnel) |
| `--webroot <path>` | folder with `index.html` (default `web`) |
| `--no-browser` | do not open the portal in a browser |

## Publishing on the internet

When the portal is public, everyone can view the board, but uploading,
deleting and demo drops need the admin key ("Admin sign-in" in the portal).
Wrong keys lock a client out after 10 attempts per 10 minutes, uploads are rate
limited, file types are checked by content, and responses carry security
headers.

**Option 1: cloud host (stays online without your PC).** The `Dockerfile`
builds and runs the portal on any Docker host. For Render's free tier: push
this repository to GitHub, open https://dashboard.render.com, choose
**New > Blueprint**, select the repository, and apply. `render.yaml` creates the
web service and a random `CIRCULARSYNC_ADMIN_KEY` (shown under the service's
**Environment** tab). Free instances sleep when idle, and uploaded files are
not kept across redeploys.

**Option 2: tunnel from this PC (instant, while the PC is on).**

```
run.bat web --public --trust-proxy
cloudflared tunnel --url http://localhost:8080
```

The first command prints the admin key, the second a public
`https://<random>.trycloudflare.com` address. Set `CIRCULARSYNC_ADMIN_KEY`
before starting to keep the same key across restarts.

## Circular formats

**PDF and Word documents.** Text is extracted (PDFBox for PDF, the JDK's ZIP
and XML APIs for `.docx`; at most 15 MB). Scanned image-only PDFs have no text
and are rejected with an explanation, and old `.doc` files must be saved as
`.docx`.

**Free-form circulars** (any format) are read like a person would read a
letter: the category from its vocabulary (exam, placement, club, otherwise
general), the title from a "Subject:" line, the issue date from a "Date:" line,
the deadline from phrases such as "last date", "on or before" or "register by",
the issuer from the signature, plus course codes, venues, companies, packages,
club names and seat limits. Day-first dates (`15/11/2026`, `15.11.26`) and
written dates (`15th November 2026`) are understood. The portal marks such
notices as auto-detected and lists which fields were inferred.

**Structured notices** (`.txt` / `.md`) can state everything exactly:

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

Every structured notice needs `TYPE`, `TITLE`, `DATE`, `DEADLINE` and
`PRIORITY_KEYS`. Each type also needs its own headers:

- `EXAM`: `COURSE_CODE`, `EXAM_DATE`, `VENUE` (optional `EXAM_TYPE`)
- `PLACEMENT`: `COMPANY`, `ROLE`, `ELIGIBILITY` (optional `PACKAGE_LPA`, `MIN_CGPA`, `DRIVE_DATE`)
- `CLUB`: `CLUB_NAME`, `EVENT_DATE`, `EVENT_VENUE` (optional `SEATS`)
- `GENERAL`: nothing extra

Markdown files can decorate headers as `- **TITLE:** value`. A file that
starts like a structured notice is validated strictly, so mistakes are reported
rather than guessed. A notice whose deadline has passed is rejected and audited
instead of being indexed.

## Layout

```
src/com/circularsync/
  model/       Notice (abstract), ExamNotice, PlacementNotice, ClubNotice, GeneralNotice, NoticeCategory
  interfaces/  Processable
  exceptions/  CircularSyncException, InvalidNoticeFormatException, ExpiredDeadlineException
  service/     NoticeParserService, DocumentTextExtractor, CircularStructurer, NoticeRegistry, AuditLogger
  daemon/      DirectoryWatcherDaemon, ProcessingEvent, ProcessingListener
  web/         WebPortalServer, PortalSettings, FileActivityTracker, RateLimiter, JsonWriter
  main/        Main, QueryConsole
test/com/circularsync/test/CircularSyncTestSuite.java
tools/              FetchLibs (verified dependency download), MakeDemoDocs (demo PDF/DOCX)
web/index.html      the portal page
demo/               notices to drop in during a demo
incoming_notices/   sample notices
Dockerfile, render.yaml   cloud deployment
audit_logs/         audit.log and processed_notices.txt (generated at runtime)
```

See `DEMO.md` for a step-by-step live demo, and `CircularSync_Project_Report.txt`
for the full design report.
