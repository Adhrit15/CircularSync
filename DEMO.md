# CircularSync Live Demo Guide

A 5-minute live demonstration of CircularSync. You need two windows side by
side: a **terminal** running the daemon, and **File Explorer** showing the
project folder.

## 0. One-time setup (before the audience arrives)

Java must be available. On this machine it ships with Android Studio, so in
PowerShell, from the project folder (`D:\JAVA`), run:

```
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\build.bat
```

You should see `26 passed, 0 failed`. That is your first talking point: the
project has an automated test suite and it passes.

Always pass `--today 2026-11-04` in the commands below. The sample notices
have November 2026 deadlines; fixing the date keeps the scores and rankings
exactly as shown here, whatever day you present.

## Option A: web portal demo (recommended)

```
.\run.bat web --today 2026-11-04
```

Your browser opens http://localhost:8080. Keep the terminal visible beside it:
every notice the portal shows is also printed there, which proves both views
come from the same daemon.

1. **Uploads tab.** The three sample notices already show as *Found in folder*
   and *Indexed*. Say: "The daemon scanned the folder at start-up with four
   worker threads."
2. **Click "Drop in" next to `1_hackathon.md`.** A row appears as
   *Processing*, flips to *Indexed* with score 48, and a toast confirms it.
   (You can also drag any `.txt` or `.md` file onto the drop zone.)
3. **Drop `2_urgent_reexam.txt`**, then open the **Notice Board** tab. The
   re-exam sits at **#1 with score 100**, marked *Due tomorrow*.
4. **Back on Uploads, drop `3_broken_notice.txt` and `4_expired_notice.txt`.**
   They show *Invalid format* and *Expired* with the exact reason. The Rejected
   counter rises and nothing reaches the board.
5. **Notice Board:** type `hall-ticket` or `robotics` in search, click the
   Exam / Placement / Club filters, pick *Due in 2 days*, and click any card to
   open its full details. The *Pipeline activity* feed on the right shows each
   event as it happens.
6. **Real-world formats: drop `5_scholarship_circular.pdf` and
   `6_placement_drive.docx`.** These are free-form letters with no special
   headers, like real circulars. The PDF becomes a *General* notice (deadline
   read from "on or before 18th November 2026"); the Word file becomes a
   *Placement* notice with company, role, 12 LPA package, deadline and drive
   date all detected (score 85). On the board they carry a *PDF* / *DOCX* chip
   and an *auto-detected* marker; the detail panel lists which fields were
   inferred and has an **Open original PDF** link. Say: "Most circulars arrive
   as PDF or Word letters, so the system reads them the way a person would."
7. **Uploads: click Delete** on `1_hackathon.md`. It becomes *Withdrawn* and
   leaves the board.

To stop, type `quit` in the terminal (or press Ctrl+C and answer `Y`). To
reset, delete the demo rows with the Delete buttons before quitting, or remove
the demo files from `incoming_notices` afterwards.

## Option A2: show it on the public internet

To let the audience open the portal on their own phones:

```
.\run.bat web --public --trust-proxy
```

and in a second terminal:

```
& "$env:LOCALAPPDATA\cloudflared\cloudflared.exe" tunnel --url http://localhost:8080
```

The second window prints a `https://<random>.trycloudflare.com` address; share
it (or show it as a QR code). Anyone can browse the board, but only someone
signed in with the admin password (top-right *Admin sign-in*) can upload or
delete. Set your own password first with
`$env:CIRCULARSYNC_ADMIN_PASSWORD = 'your password'`, or use the one the first
window generates and prints. The address changes every time the
tunnel restarts. For a permanent address, deploy to Render as described in
README.md.

## Option B: terminal-only demo

## 1. Start the daemon

```
.\run.bat daemon --today 2026-11-04
```

**What happens:** the three sample notices already in `incoming_notices` are
parsed in parallel by worker threads and printed as `>> New notice indexed`.
Then the `circularsync>` prompt appears.

**What to say:** "The NIO WatchService is now monitoring `incoming_notices`.
Four worker threads from an ExecutorService are waiting on a BlockingQueue."

Type:

```
list
```

The board is ranked by priority score: exam (86), placement (77), club (29).

## 2. Drop a new notice in, live

In File Explorer, open the `demo` folder and **copy `1_hackathon.md` into
`incoming_notices`**.

**What happens:** within a second the terminal prints
`>> New notice indexed: [CLUB ...] 24 Hour Campus Hackathon ... score 48`.

**What to say:** "Nobody pressed anything. The WatchService fired an event, a
worker thread parsed the Markdown file, and the registry inserted it into a
sorted TreeSet."

## 3. Drop an urgent notice: it jumps to the top

Copy `demo\2_urgent_reexam.txt` into `incoming_notices`, then type:

```
top 3
```

The re-exam (deadline tomorrow, keywords `mandatory, re-exam, backlog`) is now
**#1 with score 100**, above the end-semester exam.

**What to say:** "Each subclass overrides `calculatePriorityScore()`. Exams get
a higher base score than club events, and deadline urgency adds more points."

## 4. Show error handling

Copy **both** `demo\3_broken_notice.txt` and `demo\4_expired_notice.txt` into
`incoming_notices`. Nothing new appears on the board. Now type:

```
stats
```

You will see `rejectedFormat=1 rejectedExpired=1`. Then open
`audit_logs\audit.log` in Notepad and scroll to the bottom:

- `REJECTED_FORMAT ... missing required header(s) [COMPANY, DEADLINE, ELIGIBILITY, PRIORITY_KEYS, ROLE]`
- `REJECTED_EXPIRED ... expired on 2026-10-30`

**What to say:** "These are our custom checked exceptions,
`InvalidNoticeFormatException` and `ExpiredDeadlineException`. The system
doesn't crash; it explains exactly what is wrong. The log is written by a
separate thread, so logging never slows down parsing."

## 5. Queries

```
due 2
search hall-ticket
category exam
show EXM-d3
```

- `due 2`: deadlines within 2 days, soonest first (uses a PriorityQueue).
- `search hall-ticket`: keyword search over a ConcurrentHashMap index.
- `show EXM-d3`: full detail of the re-exam. Any unique start of an id works;
  `EXM-` alone is ambiguous once there are two exams.

## 6. Delete a file: the notice disappears

Delete `incoming_notices\1_hackathon.md` in File Explorer, then type `list`.
The hackathon is gone, and `audit.log` shows `REMOVED ... withdrawn because
1_hackathon.md was deleted`.

## 7. Stop

```
quit
```

Then show `audit_logs\processed_notices.txt`, the final ranked board written
to disk.

## 8. Reset for the next run

Delete the four demo files you copied into `incoming_notices`, keeping only
the three `sample_*.txt` files. The originals stay in the `demo` folder.

## Quick reference

| Command | Shows |
| --- | --- |
| `list` | whole board, by priority |
| `top 3` | three most important notices |
| `due 2` | deadlines in the next 2 days |
| `search <words>` | keyword search |
| `category exam` | one category (exam / placement / club) |
| `show <id>` | full detail of one notice |
| `stats` | counts, threads, rejections |
| `quit` | stop the daemon |

One-shot alternatives without the interactive prompt:

```
.\run.bat scan --today 2026-11-04
.\run.bat query top 3 --today 2026-11-04
```

## If something goes wrong

- **`'java' is not recognized`**: set `JAVA_HOME` as in step 0, in the same
  window.
- **`CircularSync is not built yet`**: run `.\build.bat` first.
- **A notice you expected is rejected as expired**: you forgot
  `--today 2026-11-04`.
