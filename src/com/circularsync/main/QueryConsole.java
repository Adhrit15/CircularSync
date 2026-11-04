package com.circularsync.main;

import com.circularsync.daemon.DirectoryWatcherDaemon;
import com.circularsync.model.ClubNotice;
import com.circularsync.model.ExamNotice;
import com.circularsync.model.Notice;
import com.circularsync.model.NoticeCategory;
import com.circularsync.model.PlacementNotice;
import com.circularsync.service.NoticeRegistry;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Interprets query commands against the {@link NoticeRegistry}. Used both by
 * the interactive shell of daemon mode and by the one-shot {@code query}
 * command, so the two always behave identically.
 */
public final class QueryConsole {

    /** Result of executing one command line. */
    public enum Outcome { CONTINUE, QUIT }

    static final String HELP_TEXT = String.join(System.lineSeparator(),
            "Query commands:",
            "  list                   every active notice, highest priority first",
            "  top <n>                the n highest-priority notices",
            "  category <type>        notices of one type: EXAM, PLACEMENT, CLUB or GENERAL",
            "  search <words...>      notices containing all of the given words",
            "  due <days>             notices whose deadline is within <days> days, soonest first",
            "  show <id>              full detail of one notice (a unique id prefix is enough)",
            "  stats                  registry and pipeline statistics",
            "  help                   this text",
            "  quit                   stop the daemon (interactive mode only)");

    private final NoticeRegistry registry;
    private final DirectoryWatcherDaemon daemon;
    private final PrintStream out;

    public QueryConsole(NoticeRegistry registry, DirectoryWatcherDaemon daemon, PrintStream out) {
        this.registry = registry;
        this.daemon = daemon;
        this.out = out;
    }

    public Outcome execute(String commandLine) {
        String trimmed = commandLine == null ? "" : commandLine.trim();
        if (trimmed.isEmpty()) {
            return Outcome.CONTINUE;
        }
        List<String> tokens = new ArrayList<>(Arrays.asList(trimmed.split("\\s+")));
        String command = tokens.remove(0).toLowerCase(Locale.ROOT);
        try {
            switch (command) {
                case "list":
                    printNotices("All active notices", registry.ranked());
                    break;
                case "top":
                    int n = tokens.isEmpty() ? 5 : parsePositive(tokens.get(0), "top");
                    printNotices("Top " + n + " notices", registry.top(n));
                    break;
                case "category":
                case "cat":
                    requireArgument(tokens, "category <EXAM|PLACEMENT|CLUB|GENERAL>");
                    Optional<NoticeCategory> category = NoticeCategory.fromLabel(tokens.get(0));
                    if (category.isEmpty()) {
                        out.println("Unknown category '" + tokens.get(0) + "'. Use EXAM, PLACEMENT, CLUB or GENERAL.");
                    } else {
                        printNotices(category.get().getDisplayName() + " notices", registry.byCategory(category.get()));
                    }
                    break;
                case "search":
                case "find":
                    requireArgument(tokens, "search <words...>");
                    String query = String.join(" ", tokens);
                    printNotices("Search results for \"" + query + "\"", registry.search(query));
                    break;
                case "due":
                    int days = tokens.isEmpty() ? 7 : parseNonNegative(tokens.get(0), "due");
                    printNotices("Deadlines within " + days + " day(s)", registry.dueWithin(days));
                    break;
                case "show":
                    requireArgument(tokens, "show <id>");
                    Optional<Notice> notice = registry.find(tokens.get(0));
                    if (notice.isPresent()) {
                        printDetail(notice.get());
                    } else {
                        out.println("No unique notice matches id '" + tokens.get(0) + "'.");
                    }
                    break;
                case "stats":
                    printStats();
                    break;
                case "help":
                case "?":
                    out.println(HELP_TEXT);
                    break;
                case "quit":
                case "exit":
                    return Outcome.QUIT;
                default:
                    out.println("Unknown command '" + command + "'. Type 'help' for the list of commands.");
            }
        } catch (IllegalArgumentException e) {
            out.println(e.getMessage());
        }
        return Outcome.CONTINUE;
    }

    /** Renders the ranked board as plain lines (also used for the audit snapshot). */
    public static List<String> renderBoard(List<Notice> notices) {
        List<String> lines = new ArrayList<>();
        int rank = 1;
        for (Notice notice : notices) {
            lines.add(String.format("%2d. %s", rank++, notice.formatSummary()));
        }
        return lines;
    }

    private void printNotices(String heading, List<Notice> notices) {
        out.println(heading + " (" + notices.size() + "):");
        if (notices.isEmpty()) {
            out.println("   (none)");
            return;
        }
        for (String line : renderBoard(notices)) {
            out.println(line);
        }
    }

    private void printDetail(Notice notice) {
        out.println(notice.formatSummary());
        out.println("  Category   : " + notice.getCategory().getDisplayName());
        out.println("  Source file: " + notice.getSourceName());
        out.println("  Issuer     : " + notice.getIssuer());
        out.println("  Issued     : " + notice.getIssueDate());
        out.println("  Deadline   : " + notice.getDeadline() + " (" + notice.getDaysUntilDeadline() + " day(s) left)");
        out.println("  Keywords   : " + String.join(", ", notice.getPriorityKeys()));
        out.println("  Score      : " + notice.getPriorityScore() + " / " + Notice.MAX_SCORE);
        if (notice instanceof ExamNotice) {
            ExamNotice exam = (ExamNotice) notice;
            out.println("  Course     : " + exam.getCourseCode() + " (" + exam.getExamType() + ")");
            out.println("  Exam date  : " + exam.getExamDate() + " at " + exam.getVenue());
        } else if (notice instanceof PlacementNotice) {
            PlacementNotice placement = (PlacementNotice) notice;
            out.println("  Company    : " + placement.getCompany() + ", role " + placement.getRole());
            out.println("  Eligibility: " + placement.getEligibility());
            if (placement.getPackageLpa() > 0) {
                out.println("  Package    : " + placement.getPackageLpa() + " LPA");
            }
        } else if (notice instanceof ClubNotice) {
            ClubNotice club = (ClubNotice) notice;
            out.println("  Club       : " + club.getClubName());
            out.println("  Event      : " + club.getEventDate() + " at " + club.getEventVenue());
        }
        out.println("  ----");
        for (String line : notice.getBody().split("\\R")) {
            out.println("  " + line);
        }
    }

    private void printStats() {
        out.println("Active notices : " + registry.size());
        for (Map.Entry<NoticeCategory, Integer> entry : registry.countsByCategory().entrySet()) {
            out.println(String.format("  %-10s : %d", entry.getKey(), entry.getValue()));
        }
        out.println("Indexed terms  : " + registry.indexedTermCount());
        if (daemon != null) {
            out.println("Worker threads : " + daemon.getWorkerCount());
            out.println("Queue depth    : " + daemon.getQueueDepth());
            out.println("Pipeline       : " + daemon.getStatistics());
        }
    }

    private static void requireArgument(List<String> tokens, String usage) {
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Usage: " + usage);
        }
    }

    private static int parsePositive(String value, String command) {
        int parsed = parseNonNegative(value, command);
        if (parsed == 0) {
            throw new IllegalArgumentException(command + " expects a number greater than zero");
        }
        return parsed;
    }

    private static int parseNonNegative(String value, String command) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(command + " expects a non-negative whole number, got '" + value + "'");
        }
    }
}
