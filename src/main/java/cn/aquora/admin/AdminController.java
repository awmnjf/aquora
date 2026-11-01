package cn.aquora.admin;

import cn.aquora.auth.Actor;
import cn.aquora.common.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
  private final Data d;

  public AdminController(Data d) {
    this.d = d;
  }

  @GetMapping("/overview")
  Object overview() {
    return Map.of(
        "users",
        d.db.queryForObject("SELECT count(*) FROM users WHERE demo=false", Integer.class),
        "demos",
        d.db.queryForObject("SELECT count(*) FROM users WHERE demo=true", Integer.class),
        "events",
        d.db.queryForObject("SELECT count(*) FROM events WHERE deleted=false", Integer.class),
        "pending",
        d.db.queryForObject(
            "SELECT count(*) FROM tasks WHERE status='pending' AND due_at<=?",
            Integer.class,
            Data.now()),
        "notifications",
        d.db.queryForObject("SELECT count(*) FROM notifications", Integer.class),
        "knowledge",
        d.db.queryForObject(
            "SELECT count(*) FROM knowledge WHERE status='published'", Integer.class));
  }

  @GetMapping("/users")
  Object users() {
    return d.db.queryForList(
        "SELECT id,username,display_name,role,status,demo,created_at FROM users ORDER BY created_at DESC LIMIT 200");
  }

  @PostMapping("/users/{id}/status")
  @Transactional
  Object status(@PathVariable UUID id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    Actor a = Actor.from(r);
    String status = Data.text(b, "status", "");
    if (!Set.of("active", "disabled").contains(status) || id.equals(a.id()))
      throw new ApiException(400, "不能停用当前账号，或状态不正确");
    d.db.update("UPDATE users SET status=? WHERE id=? AND role<>'admin'", status, id);
    if (status.equals("disabled")) d.db.update("DELETE FROM sessions WHERE user_id=?", id);
    d.audit(a.id(), "account." + status, id);
    return Map.of("ok", true);
  }

  @GetMapping("/audit")
  Object audit() {
    return d.db.queryForList(
        "SELECT a.id,a.action,a.resource_id,a.created_at,u.username FROM audit a LEFT JOIN users u ON u.id=a.actor_id ORDER BY a.id DESC LIMIT 100");
  }

  @GetMapping("/knowledge")
  Object knowledge() {
    return d.db.queryForList(
        "SELECT id,title,content,source,category,status,version,updated_at FROM knowledge ORDER BY updated_at DESC");
  }

  @PostMapping("/knowledge")
  Object publish(@RequestBody Map<String, Object> b, HttpServletRequest r) {
    String title = Data.text(b, "title", "").strip(),
        content = Data.text(b, "content", "").strip(),
        source = Data.text(b, "source", "").strip(),
        status = Data.text(b, "status", "draft");
    if (title.isEmpty()
        || title.length() > 150
        || content.isEmpty()
        || content.length() > 20000
        || source.isEmpty()
        || source.length() > 300
        || !Set.of("draft", "published", "archived").contains(status))
      throw new ApiException(400, "请填写标题、正文、来源及有效发布状态");
    UUID id = b.get("id") == null ? UUID.randomUUID() : UUID.fromString(b.get("id").toString());
    d.db.update(
        "INSERT INTO knowledge(id,title,content,source,category,status,updated_at) VALUES (?,?,?,?,'guide',?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title,content=excluded.content,source=excluded.source,status=excluded.status,version=knowledge.version+1,updated_at=excluded.updated_at",
        id,
        title,
        content,
        source,
        status,
        Data.now());
    d.audit(Actor.from(r).id(), "knowledge." + status, id);
    return Map.of("id", id);
  }

  @GetMapping("/integrations")
  Object integrations() {
    return Map.of(
        "model",
        !System.getenv().getOrDefault("LLM_API_KEY", "").isBlank(),
        "wechat",
        false,
        "alipay",
        false,
        "sms",
        false,
        "notificationChannel",
        "站内通知；浏览器通知需要用户授权且页面保持打开",
        "storage",
        "PostgreSQL",
        "architecture",
        "Spring Boot / FastAPI / React");
  }
}
