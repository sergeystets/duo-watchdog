package duowatchdog.core;

import duowatchdog.core.CreditsReport.DailyUsage;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One account's usage over months, as {@code creditsUsage} gives it for a date range: the credits billed each day,
 * the same for each feature, and what GitLab did not bill. A group member may read this much of their own usage;
 * per project, per model or per session takes the group's Owner.
 *
 * @param start       the first day GitLab has, which it moves to the subscription's start
 * @param end         the last day, at most today
 * @param daily       billed credits per day
 * @param flows       billed credits per day of each feature used in the range
 * @param nonBillable credits per day GitLab did not bill (promotional, beta); empty when it did not say
 * @param flowsError  why the per-feature figures are missing, else null
 */
public record History(LocalDate start, LocalDate end, List<DailyUsage> daily, List<FlowHistory> flows,
                      List<DailyUsage> nonBillable, String flowsError) {

    /** How many months the Statistics view asks for: GitLab answers at most a year at once. */
    public static final int MONTHS = 12;

    public record FlowHistory(String id, String title, List<DailyUsage> daily) {

        public double total() {
            return daily.stream().mapToDouble(DailyUsage::credits).sum();
        }
    }

    /**
     * One calendar month.
     *
     * @param byFlow billed credits per feature id, in {@link #flowsByCredits()} order
     */
    public record Month(YearMonth month, double credits, Map<String, Double> byFlow, double nonBillable) {
    }

    /** How long after a month ends GitLab's figures for it may still change: they lag a few hours behind use. */
    public static final int SETTLE_DAYS = 2;

    /** The ranges the Statistics view offers, in months; the first is its default. */
    public static final List<Integer> RANGES = List.of(3, 6, 12);

    /** The months to show for a range: this month and the {@code count} - 1 before it, newest first. */
    public static List<YearMonth> monthsFor(int count, LocalDate today) {
        List<YearMonth> months = new ArrayList<>();
        for (int i = 0; i < Math.max(count, 1); i++) {
            months.add(YearMonth.from(today).minusMonths(i));
        }
        return months;
    }

    /** Whether a month's figures no longer change, so they can be kept rather than asked for again. */
    public static boolean settled(YearMonth month, LocalDate today) {
        return today.isAfter(month.atEndOfMonth().plusDays(SETTLE_DAYS - 1));
    }

    /** An empty month: one GitLab had nothing for, before the subscription. */
    public static History none(YearMonth month) {
        return new History(month.atDay(1), month.atEndOfMonth(), List.of(), List.of(), List.of(), null);
    }

    /** The first day of the month {@link #MONTHS} - 1 months before today's: what the Statistics view asks from. */
    public static LocalDate from(LocalDate today) {
        return today.withDayOfMonth(1).minusMonths(MONTHS - 1);
    }

    /**
     * Several accounts' histories as one: their days added up, and their features merged by name, since each GitLab
     * gives its own ids.
     */
    public static History merge(List<History> histories) {
        LocalDate start = histories.stream().map(History::start).min(LocalDate::compareTo).orElse(LocalDate.now());
        LocalDate end = histories.stream().map(History::end).max(LocalDate::compareTo).orElse(LocalDate.now());
        List<DailyUsage> daily = new ArrayList<>();
        List<DailyUsage> nonBillable = new ArrayList<>();
        Map<String, List<DailyUsage>> byTitle = new LinkedHashMap<>();
        String flowsError = null;
        for (History h : histories) {
            daily.addAll(h.daily());
            nonBillable.addAll(h.nonBillable());
            for (FlowHistory flow : h.flows()) {
                byTitle.computeIfAbsent(flow.title(), title -> new ArrayList<>()).addAll(flow.daily());
            }
            if (flowsError == null) {
                flowsError = h.flowsError();
            }
        }
        List<FlowHistory> flows = new ArrayList<>();
        byTitle.forEach((title, days) -> flows.add(new FlowHistory(title, title, days)));
        return new History(start, end, daily, flows, nonBillable, flowsError);
    }

    /** Every calendar month from the start's to the end's, oldest first; months without use are zero. */
    public List<Month> months() {
        List<FlowHistory> order = flowsByCredits();
        List<Month> months = new ArrayList<>();
        for (YearMonth m = YearMonth.from(start); !m.isAfter(YearMonth.from(end)); m = m.plusMonths(1)) {
            YearMonth month = m;
            Map<String, Double> byFlow = new LinkedHashMap<>();
            for (FlowHistory flow : order) {
                byFlow.put(flow.id(), sum(flow.daily(), month));
            }
            months.add(new Month(month, sum(daily, month), byFlow, sum(nonBillable, month)));
        }
        return months;
    }

    /** Features by their credits over the whole range, most first. */
    public List<FlowHistory> flowsByCredits() {
        List<FlowHistory> sorted = new ArrayList<>(flows);
        sorted.sort(Comparator.comparingDouble(FlowHistory::total).reversed().thenComparing(FlowHistory::title));
        return sorted;
    }

    public double total() {
        return daily.stream().mapToDouble(DailyUsage::credits).sum();
    }

    /**
     * The credits of an average month: over the months that are over, from the first with any use, so months before
     * the account used Duo do not pull it down; this month so far while there are none.
     */
    public double monthlyAverage(YearMonth current) {
        List<Month> all = months();
        List<Month> counted = new ArrayList<>();
        for (Month m : all) {
            if (m.month().isBefore(current) && (!counted.isEmpty() || m.credits() > 0)) {
                counted.add(m);
            }
        }
        if (counted.isEmpty()) {
            return all.stream().filter(m -> m.month().equals(current)).mapToDouble(Month::credits).sum();
        }
        return counted.stream().mapToDouble(Month::credits).sum() / counted.size();
    }

    public double nonBillableTotal() {
        return nonBillable.stream().mapToDouble(DailyUsage::credits).sum();
    }

    private static double sum(List<DailyUsage> days, YearMonth month) {
        return days.stream().filter(d -> YearMonth.from(d.date()).equals(month)).mapToDouble(DailyUsage::credits).sum();
    }
}
