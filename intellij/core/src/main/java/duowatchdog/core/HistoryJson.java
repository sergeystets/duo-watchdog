package duowatchdog.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import duowatchdog.core.CreditsReport.DailyUsage;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A month's {@link History} as JSON, for the cache of settled months: dates as yyyy-MM-dd, credits as numbers, the
 * features with their days. Nothing in it is secret.
 */
public final class HistoryJson {

    private HistoryJson() {
    }

    public static String write(History h) {
        JsonObject o = new JsonObject();
        o.addProperty("start", h.start().toString());
        o.addProperty("end", h.end().toString());
        o.add("daily", days(h.daily()));
        JsonArray flows = new JsonArray();
        for (History.FlowHistory f : h.flows()) {
            JsonObject flow = new JsonObject();
            flow.addProperty("id", f.id());
            flow.addProperty("title", f.title());
            flow.add("daily", days(f.daily()));
            flows.add(flow);
        }
        o.add("flows", flows);
        o.add("nonBillable", days(h.nonBillable()));
        if (h.flowsError() != null) {
            o.addProperty("flowsError", h.flowsError());
        }
        return o.toString();
    }

    /** The month written by {@link #write}, or null when the text is not one. */
    public static History read(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            List<History.FlowHistory> flows = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("flows")) {
                JsonObject f = e.getAsJsonObject();
                flows.add(new History.FlowHistory(f.get("id").getAsString(), f.get("title").getAsString(),
                        days(f.getAsJsonArray("daily"))));
            }
            return new History(LocalDate.parse(o.get("start").getAsString()), LocalDate.parse(o.get("end").getAsString()),
                    days(o.getAsJsonArray("daily")), flows, days(o.getAsJsonArray("nonBillable")),
                    o.has("flowsError") ? o.get("flowsError").getAsString() : null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonArray days(List<DailyUsage> days) {
        JsonArray array = new JsonArray();
        for (DailyUsage d : days) {
            JsonArray day = new JsonArray();
            day.add(d.date().toString());
            day.add(d.credits());
            array.add(day);
        }
        return array;
    }

    private static List<DailyUsage> days(JsonArray array) {
        List<DailyUsage> days = new ArrayList<>();
        for (JsonElement e : array) {
            JsonArray day = e.getAsJsonArray();
            days.add(new DailyUsage(LocalDate.parse(day.get(0).getAsString()), day.get(1).getAsDouble()));
        }
        return days;
    }
}
