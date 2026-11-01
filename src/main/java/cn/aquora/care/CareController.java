package cn.aquora.care;

import cn.aquora.auth.Actor;
import cn.aquora.common.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CareController {
  private final Data d;
  private final CareService care;

  public CareController(Data d, CareService c) {
    this.d = d;
    care = c;
  }

  @GetMapping("/plan")
  Object plan(HttpServletRequest r) {
    return care.plan(Actor.from(r).id());
  }

  @PutMapping("/plan")
  Object save(@RequestBody Map<String, Object> b, HttpServletRequest r) {
    return care.savePlan(Actor.from(r).id(), b);
  }

  @GetMapping("/plan/history")
  Object history(HttpServletRequest r) {
    return d.db.queryForList(
        "SELECT version,active,created_at,data->>'source' AS source FROM plans WHERE user_id=? ORDER BY version DESC",
        Actor.from(r).id());
  }

  @GetMapping("/tasks")
  Object tasks(HttpServletRequest r) {
    return care.tasks(Actor.from(r).id());
  }

  @PostMapping("/tasks/{id}/skip")
  Object skip(@PathVariable UUID id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    UUID u = Actor.from(r).id();
    String reason = Data.text(b, "reason", "").strip();
    if (reason.isBlank() || reason.length() > 300) throw new ApiException(400, "请填写未执行原因");
    if (d.db.update(
            "UPDATE tasks SET status='skipped',note=?,updated_at=? WHERE user_id=? AND id=? AND status='pending'",
            reason,
            Data.now(),
            u,
            id)
        == 0) throw new ApiException(409, "任务已变化或不存在");
    d.audit(u, "task.skip", id);
    return Map.of("ok", true);
  }

  @GetMapping("/notifications")
  Object notifications(HttpServletRequest r) {
    return d.db.queryForList(
        "SELECT * FROM notifications WHERE user_id=? ORDER BY created_at DESC LIMIT 50",
        Actor.from(r).id());
  }

  @PostMapping("/notifications/{id}/read")
  Object read(@PathVariable UUID id, HttpServletRequest r) {
    d.db.update(
        "UPDATE notifications SET seen=true WHERE id=? AND user_id=?", id, Actor.from(r).id());
    return Map.of("ok", true);
  }

  @GetMapping("/outings")
  Object outings(HttpServletRequest r) {
    return d.db.queryForList(
        "SELECT * FROM outings WHERE user_id=? AND end_at>? ORDER BY start_at",
        Actor.from(r).id(),
        Data.now() - 86400000);
  }

  @PostMapping("/outings")
  Object outing(@RequestBody Map<String, Object> b, HttpServletRequest r) {
    UUID u = Actor.from(r).id();
    long start = Long.parseLong(b.get("startAt").toString()),
        end = Long.parseLong(b.get("endAt").toString());
    String title = Data.text(b, "title", "").strip();
    if (title.isBlank()
        || title.length() > 100
        || end <= start
        || end - start > 86400000
        || start < Data.now() - 60000
        || end > Data.now() + 30L * 86400000) throw new ApiException(400, "请填写未来30天内、一天以内的有效行程");
    care.generate(u);
    var conflicts =
        d.db.queryForList(
            "SELECT id,kind,due_at FROM tasks WHERE user_id=? AND status='pending' AND due_at>=? AND due_at<=? ORDER BY due_at",
            u,
            start,
            end);
    UUID id = UUID.randomUUID();
    d.db.update("INSERT INTO outings VALUES (?,?,?,?,?,?)", id, u, title, start, end, Data.now());
    d.audit(u, "outing.create", id);
    return Map.of(
        "id",
        id,
        "conflicts",
        conflicts,
        "message",
        conflicts.isEmpty() ? "行程已保存" : "行程与护理安排重叠，原提醒时间保持不变，请提前安排可用地点或调整行程");
  }

  @DeleteMapping("/outings/{id}")
  Object remove(@PathVariable UUID id, HttpServletRequest r) {
    d.db.update("DELETE FROM outings WHERE id=? AND user_id=?", id, Actor.from(r).id());
    return Map.of("ok", true);
  }

  @GetMapping("/devices")
  Object devices(HttpServletRequest r) {
    return Map.of(
        "items",
        d.db.queryForList("SELECT * FROM devices WHERE user_id=?", Actor.from(r).id()),
        "supported",
        List.of(),
        "message",
        "设备接入接口已预留，具体型号验证完成后开放绑定。手动记录始终可用。");
  }
}
