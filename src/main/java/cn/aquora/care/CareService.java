package cn.aquora.care;

import cn.aquora.common.*;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CareService {
  private final Data d;
  public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  public CareService(Data d) {
    this.d = d;
  }

  public Map<String, Object> plan(UUID u) {
    var rows = d.db.queryForList("SELECT * FROM plans WHERE user_id=? AND active=true", u);
    if (rows.isEmpty()) return Map.of("version", 0, "data", Map.of());
    var m = new LinkedHashMap<>(rows.getFirst());
    m.put("data", d.object(m.get("data")));
    return m;
  }

  @Transactional
  public Object savePlan(UUID u, Map<String, Object> b) {
    d.db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", UUID.class, u);
    var old = plan(u);
    int version = Data.integer(b, "version", 0);
    if (version != ((Number) old.get("version")).intValue())
      throw new ApiException(409, "方案已更新，请刷新后重试");
    if (!Boolean.TRUE.equals(b.get("confirmed"))) throw new ApiException(400, "请确认这些时间来自您已有的护理安排");
    Map<String, Object> value = new LinkedHashMap<>();
    Object goal = b.get("waterGoal");
    if (goal != null && !goal.toString().isBlank()) {
      int g = Integer.parseInt(goal.toString());
      if (g < 100 || g > 5000) throw new ApiException(400, "饮水目标需在100–5000毫升之间");
      value.put("waterGoal", g);
    }
    for (String k : List.of("drinkTimes", "voidTimes")) {
      Object raw = b.get(k);
      if (!(raw instanceof List<?> list) || list.size() > 24)
        throw new ApiException(400, "每天最多设置24个提醒时刻");
      var times = new TreeSet<String>();
      for (Object t : list) {
        String s = t.toString();
        if (!s.matches("([01][0-9]|2[0-3]):[0-5][0-9]"))
          throw new ApiException(400, "时间格式应为 HH:mm");
        times.add(s);
      }
      value.put(k, times);
    }
    String source = Data.text(b, "source", "").strip();
    if (source.isBlank() || source.length() > 200) throw new ApiException(400, "请填写方案来源，例如既有护理安排");
    String note = Data.text(b, "note", "");
    if (note.length() > 1000) throw new ApiException(400, "备注最多1000字");
    value.put("source", source);
    value.put("note", note);
    value.put("confirmed", true);
    long now = Data.now();
    d.db.update("UPDATE plans SET active=false WHERE user_id=? AND active=true", u);
    d.db.update(
        "UPDATE tasks SET status='cancelled',note='方案版本更新',updated_at=? WHERE user_id=? AND status='pending' AND due_at>?",
        now,
        u,
        now);
    UUID id = UUID.randomUUID();
    d.db.update(
        "INSERT INTO plans(id,user_id,version,data,created_at) VALUES (?,?,?,?::jsonb,?)",
        id,
        u,
        version + 1,
        d.json(value),
        now);
    generate(u);
    d.audit(u, "plan.publish", id);
    return plan(u);
  }

  public void generate(UUID u) {
    var p = plan(u);
    if (!p.containsKey("id")) return;
    var v = (Map<?, ?>) p.get("data");
    long created = ((Number) p.get("created_at")).longValue();
    LocalDate today = LocalDate.now(ZONE);
    for (int day = 0; day < 3; day++)
      for (String kind : List.of("drink", "voiding")) {
        Object times = v.get(kind.equals("drink") ? "drinkTimes" : "voidTimes");
        if (!(times instanceof Collection<?> list)) continue;
        for (Object t : list) {
          long due =
              today
                  .plusDays(day)
                  .atTime(LocalTime.parse(t.toString()))
                  .atZone(ZONE)
                  .toInstant()
                  .toEpochMilli();
          if (due < created) continue;
          d.db.update(
              "INSERT INTO tasks(id,user_id,plan_id,kind,due_at,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(plan_id,kind,due_at) DO NOTHING",
              UUID.randomUUID(),
              u,
              p.get("id"),
              kind,
              due,
              Data.now());
        }
      }
  }

  public Object tasks(UUID u) {
    generate(u);
    long start = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant().toEpochMilli();
    return d.db.queryForList(
        "SELECT t.*,p.version AS plan_version FROM tasks t LEFT JOIN plans p ON p.id=t.plan_id WHERE t.user_id=? AND t.due_at>=? AND t.due_at<? AND t.status<>'cancelled' ORDER BY due_at",
        u,
        start,
        start + 86400000);
  }

  @Scheduled(fixedDelay = 30000)
  @Transactional
  public void tick() {
    for (UUID u : d.db.queryForList("SELECT user_id FROM plans WHERE active=true", UUID.class))
      generate(u);
    long now = Data.now();
    d.db.update(
        "INSERT INTO notifications(id,user_id,task_id,title,body,created_at) SELECT gen_random_uuid(),user_id,id,'护理安排提醒','您有一项既定护理安排待处理，请查看今日计划。',? FROM tasks WHERE status='pending' AND due_at<=? AND due_at>? ON CONFLICT(task_id) DO NOTHING",
        now,
        now,
        now - 7200000);
    d.db.update("DELETE FROM sessions WHERE expires_at<?", now);
  }
}
