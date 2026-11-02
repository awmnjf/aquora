package cn.aquora.insights;

import cn.aquora.auth.Actor;
import cn.aquora.care.CareService;
import cn.aquora.common.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/personal-report")
public class PersonalReportController {
  private final Data data;
  private final IntelligenceClient ai;
  private final Map<UUID, long[]> buckets = new HashMap<>();
  private long globalMinute, globalCount;
  static final List<String> METRICS = List.of("intake", "output", "drinks", "foods", "voids", "measured", "unmeasured", "leakage", "symptoms", "incomplete", "records");

  public PersonalReportController(Data data, IntelligenceClient ai) { this.data = data; this.ai = ai; }
  public record RangeInput(String from, String to) {}
  record Range(LocalDate from, LocalDate to) {
    long start() { return from.atStartOfDay(CareService.ZONE).toInstant().toEpochMilli(); }
    long end() { return Math.min(to.plusDays(1).atStartOfDay(CareService.ZONE).toInstant().toEpochMilli() - 1, Data.now()); }
  }
  static Range range(String from, String to) {
    try {
      var first = LocalDate.parse(from); var last = LocalDate.parse(to);
      long days = ChronoUnit.DAYS.between(first, last) + 1;
      if (days < 1 || days > 90 || last.isAfter(LocalDate.now(CareService.ZONE))) throw new IllegalArgumentException();
      return new Range(first, last);
    } catch (Exception e) { throw new ApiException(400, "请选择不晚于今天的日期，单次最多90天"); }
  }
  private synchronized void limit(UUID user) {
    long minute = Data.now() / 60000;
    buckets.entrySet().removeIf(e -> e.getValue()[0] != minute);
    if (globalMinute != minute) { globalMinute = minute; globalCount = 0; }
    var bucket = buckets.computeIfAbsent(user, k -> new long[]{minute, 0});
    if (bucket[1] >= 3 || globalCount >= 30) throw new ApiException(429, "解读请求较频繁，请稍后重试");
    bucket[1]++; globalCount++;
  }
  @GetMapping
  Object get(@RequestParam String from, @RequestParam String to, HttpServletRequest request) {
    return snapshot(Actor.from(request).id(), range(from, to));
  }
  Map<String, Object> snapshot(UUID user, Range range) {
    // Aggregate in the database so a busy period cannot be silently truncated by the event list limit.
    var grouped = data.db.queryForList("""
      SELECT (to_timestamp(occurred_at / 1000.0) AT TIME ZONE 'Asia/Shanghai')::date::text AS date,
        coalesce(sum(amount_ml) FILTER (WHERE kind IN ('drink','food')),0) AS intake,
        coalesce(sum(amount_ml) FILTER (WHERE kind='voiding'),0) AS output,
        count(*) FILTER (WHERE kind='drink') AS drinks, count(*) FILTER (WHERE kind='food') AS foods,
        count(*) FILTER (WHERE kind='voiding') AS voids,
        count(*) FILTER (WHERE kind='voiding' AND amount_ml IS NOT NULL) AS measured,
        count(*) FILTER (WHERE kind='voiding' AND amount_ml IS NULL) AS unmeasured,
        count(*) FILTER (WHERE kind='leakage') AS leakage, count(*) FILTER (WHERE kind='symptom') AS symptoms,
        count(*) FILTER (WHERE kind='voiding' AND emptying='incomplete') AS incomplete, count(*) AS records
      FROM events WHERE user_id=? AND deleted=false AND occurred_at>=? AND occurred_at<=? GROUP BY 1 ORDER BY 1
      """, user, range.start(), range.end());
    var byDate = new HashMap<String, Map<String, Object>>();
    grouped.forEach(row -> byDate.put(row.get("date").toString(), row));
    var rows = new ArrayList<Map<String, Object>>();
    var total = new LinkedHashMap<String, Object>();
    METRICS.forEach(k -> total.put(k, 0L));
    long covered = 0, intakeDays = 0, outputDays = 0;
    for (LocalDate date = range.from(); !date.isAfter(range.to()); date = date.plusDays(1)) {
      var source = byDate.getOrDefault(date.toString(), Map.of());
      Map<String, Object> day = new LinkedHashMap<>(); day.put("date", date.toString());
      for (String key : METRICS) {
        long value = ((Number) source.getOrDefault(key, 0)).longValue();
        day.put(key, value); total.put(key, ((Number) total.get(key)).longValue() + value);
      }
      if (((Number) day.get("records")).longValue() > 0) covered++;
      if (((Number) day.get("drinks")).longValue() + ((Number) day.get("foods")).longValue() > 0) intakeDays++;
      if (((Number) day.get("measured")).longValue() > 0) outputDays++;
      rows.add(day);
    }
    total.put("coveredDays", covered); total.put("intakeDays", intakeDays); total.put("outputDays", outputDays);
    var tasks = data.db.queryForMap("SELECT count(*) FILTER (WHERE status<>'cancelled') AS due, count(*) FILTER (WHERE status='done') AS done FROM tasks WHERE user_id=? AND due_at>=? AND due_at<=?", user, range.start(), range.end());
    return Map.of("from", range.from().toString(), "to", range.to().toString(), "days", rows, "summary", total, "tasks", tasks, "generatedAt", Data.now());
  }
  @GetMapping("/events")
  Object history(@RequestParam String date, @RequestParam(defaultValue="all") String kind,
      @RequestParam(defaultValue="0") int offset, HttpServletRequest request) {
    UUID user = Actor.from(request).id(); var day = range(date, date);
    if (offset < 0 || offset > 100000 || !Set.of("all", "drink", "food", "voiding", "other").contains(kind)) throw new ApiException(400, "记录筛选无效");
    String filter = kind.equals("all") ? "" : kind.equals("other") ? " AND kind IN ('leakage','symptom')" : " AND kind=?";
    var args = new ArrayList<Object>(List.of(user, day.start(), day.end()));
    if (!kind.equals("all") && !kind.equals("other")) args.add(kind);
    String where = " FROM events WHERE user_id=? AND deleted=false AND occurred_at>=? AND occurred_at<=?" + filter;
    Long total = data.db.queryForObject("SELECT count(*)" + where, Long.class, args.toArray());
    args.add(offset);
    var rows = data.db.queryForList("SELECT id,kind,amount_ml,occurred_at,drink_type,emptying,note,source,version" + where + " ORDER BY occurred_at DESC,id DESC LIMIT 20 OFFSET ?", args.toArray());
    return Map.of("events", rows, "total", total, "offset", offset, "hasMore", offset + rows.size() < total);
  }
  @PostMapping("/interpret")
  Object interpret(@RequestBody RangeInput input, HttpServletRequest request) {
    UUID user = Actor.from(request).id(); var period = range(input.from(), input.to()); limit(user);
    // Dates are the only client input. All figures are rebuilt from the authenticated user's data.
    return ai.call("/report-interpretation", Map.of("report", snapshot(user, period)), 45);
  }
}
