package cn.aquora.insights;

import cn.aquora.auth.Actor;
import cn.aquora.care.CareService;
import cn.aquora.common.*;
import cn.aquora.events.EventService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class InsightsController {
  private final Data d;
  private final EventService events;
  private final CareService care;
  private final IntelligenceClient ai;
  private final Map<String, long[]> aiBuckets = new HashMap<>();

  public InsightsController(Data d, EventService e, CareService c, IntelligenceClient ai) {
    this.d = d;
    events = e;
    care = c;
    this.ai = ai;
  }

  private Map<String, Object> profile(UUID u) {
    return d.object(
        d.db.queryForObject("SELECT data::text FROM profiles WHERE user_id=?", String.class, u));
  }

  private synchronized void limitAi(UUID user, String action, int perUser, int global) {
    long minute = Data.now() / 60000;
    long[] personal = aiBuckets.computeIfAbsent(action + ":" + user, k -> new long[] {minute, 0});
    long[] shared = aiBuckets.computeIfAbsent(action + ":all", k -> new long[] {minute, 0});
    if (personal[0] != minute) { personal[0] = minute; personal[1] = 0; }
    if (shared[0] != minute) { shared[0] = minute; shared[1] = 0; }
    if (personal[1] >= perUser || shared[1] >= global)
      throw new ApiException(429, "智能服务使用较频繁，请稍后再试");
    personal[1]++;
    shared[1]++;
    if (aiBuckets.size() > 2000)
      aiBuckets.entrySet().removeIf(e -> e.getValue()[0] < minute - 2);
  }

  @GetMapping("/dashboard")
  Object dashboard(HttpServletRequest r) {
    UUID u = Actor.from(r).id();
    long start =
        LocalDate.now(CareService.ZONE).atStartOfDay(CareService.ZONE).toInstant().toEpochMilli();
    var today = events.list(u, start, Data.now());
    int intake = 0, output = 0, voids = 0, unknown = 0;
    for (var e : today) {
      String kind = e.get("kind").toString();
      if (Set.of("drink", "food").contains(kind))
        intake += ((Number) e.get("amount_ml")).intValue();
      if (kind.equals("voiding")) {
        voids++;
        if (e.get("amount_ml") == null) unknown++;
        else output += ((Number) e.get("amount_ml")).intValue();
      }
    }
    return Map.of(
        "user",
        Actor.from(r),
        "intake",
        intake,
        "output",
        output,
        "voids",
        voids,
        "unmeasured",
        unknown,
        "events",
        today,
        "tasks",
        care.tasks(u),
        "plan",
        care.plan(u),
        "profile",
        profile(u),
        "updatedAt",
        Data.now());
  }

  @GetMapping("/state")
  Object state(HttpServletRequest r) {
    UUID u = Actor.from(r).id();
    var p = profile(u);
    if (!Boolean.TRUE.equals(p.get("modelEnabled")))
      return Map.of("available", false, "message", "研究性估算尚未启用。可在个人档案中了解并开启；日常记录与计划不受影响。");
    return ai.call(
        "/state",
        Map.of(
            "events",
            events.list(u, Data.now() - 7L * 86400000, Data.now()),
            "profile",
            p,
            "now",
            Data.now()),
        8);
  }

  @GetMapping("/reports")
  Object report(@RequestParam(defaultValue = "7") int days, HttpServletRequest r) {
    if (days < 1 || days > 90) throw new ApiException(400, "请选择1–90天");
    UUID u = Actor.from(r).id();
    LocalDate first = LocalDate.now(CareService.ZONE).minusDays(days - 1);
    var rows = new ArrayList<Map<String, Object>>();
    for (int n = 0; n < days; n++) {
      LocalDate date = first.plusDays(n);
      long start = date.atStartOfDay(CareService.ZONE).toInstant().toEpochMilli();
      var day = events.list(u, start, start + 86400000 - 1);
      int in = 0, out = 0, voids = 0, leaks = 0, unknown = 0;
      for (var e : day) {
        String k = e.get("kind").toString();
        if (Set.of("drink", "food").contains(k)) in += ((Number) e.get("amount_ml")).intValue();
        if (k.equals("voiding")) {
          voids++;
          if (e.get("amount_ml") == null) unknown++;
          else out += ((Number) e.get("amount_ml")).intValue();
        }
        if (k.equals("leakage")) leaks++;
      }
      rows.add(
          Map.of(
              "date",
              date.toString(),
              "intake",
              in,
              "output",
              out,
              "voids",
              voids,
              "leakage",
              leaks,
              "unmeasured",
              unknown,
              "records",
              day.size()));
    }
    long from = first.atStartOfDay(CareService.ZONE).toInstant().toEpochMilli();
    var adherence =
        d.db.queryForMap(
            "SELECT count(*) FILTER(WHERE status<>'cancelled') AS due,count(*) FILTER(WHERE status='done') AS done,count(*) FILTER(WHERE status='skipped') AS skipped FROM tasks WHERE user_id=? AND due_at>=? AND due_at<=?",
            u,
            from,
            Data.now());
    return Map.of(
        "days",
        rows,
        "adherence",
        adherence,
        "generatedAt",
        Data.now(),
        "notice",
        "仅汇总已记录数据。没有记录不等于没有摄入或排出；未测量的排出量单独统计。");
  }

  @GetMapping("/knowledge")
  Object knowledge() {
    return d.db.queryForList(
        "SELECT id,title,content,source,category,version FROM knowledge WHERE status='published' ORDER BY updated_at DESC");
  }

  private Map<String, Object> capabilities() {
    return ai.call("/capabilities", Map.of(), 5);
  }

  private boolean enabled(Map<String, Object> capabilities, String service) {
    Object services = capabilities.get("services");
    if (!(services instanceof Map<?, ?> all)) return false;
    Object detail = all.get(service);
    return detail instanceof Map<?, ?> data && Boolean.TRUE.equals(data.get("available"));
  }

  @GetMapping("/vision/capability")
  Object visionCapability(HttpServletRequest r) {
    Actor.from(r);
    return Map.of("available", enabled(capabilities(), "vision"));
  }

  @GetMapping("/ai/capabilities")
  Object aiCapabilities(HttpServletRequest r) {
    Actor.from(r);
    return capabilities();
  }

  @PostMapping("/vision")
  Object vision(@RequestBody Map<String, Object> body, HttpServletRequest r) {
    UUID user = Actor.from(r).id();
    if (!enabled(capabilities(), "vision"))
      return Map.of("available", false, "message", "图片识别待接入，请手动填写记录");
    Map<String, Object> request = new HashMap<>();
    if (body.containsKey("beforeImage") || body.containsKey("afterImage")) {
      String kind = Data.text(body, "kind", "");
      if (!Set.of("drink", "food").contains(kind) || body.containsKey("image"))
        throw new ApiException(400, "请选择饮水或食物，分别上传前后照片");
      String before = Data.text(body, "beforeImage", "");
      String after = Data.text(body, "afterImage", "");
      validatePhoto(before);
      validatePhoto(after);
      String context = Data.text(body, "context", "").trim();
      if (context.length() > 500) throw new ApiException(400, "补充信息请控制在500字以内");
      request.put("beforeImage", before);
      request.put("afterImage", after);
      request.put("kind", kind);
      request.put("context", context);
    } else {
      String image = Data.text(body, "image", "");
      validatePhoto(image);
      request.put("image", image);
    }
    limitAi(user, "vision", 6, 60);
    return ai.call("/vision", request, 50);
  }

  private void validatePhoto(String image) {
    int comma = image.indexOf(',');
    if (comma < 0 || !Set.of(
        "data:image/jpeg;base64", "data:image/png;base64", "data:image/webp;base64")
        .contains(image.substring(0, comma)))
      throw new ApiException(400, "请分别选择前后两张 JPG、PNG 或 WebP 照片");
    if (image.length() > 4_000_000) throw new ApiException(413, "照片过大，请重新拍摄");
    try {
      byte[] bytes = Base64.getDecoder().decode(image.substring(comma + 1));
      if (bytes.length < 16 || bytes.length > 2_500_000)
        throw new ApiException(413, "每张照片大小需在 2.5 MB 以内");
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "照片格式无法识别");
    }
  }

  @PostMapping("/ai/transcribe")
  Object transcribe(@RequestBody Map<String, Object> body, HttpServletRequest r) {
    UUID user = Actor.from(r).id();
    String audio = Data.text(body, "audio", "");
    if (!audio.startsWith("data:audio/") || audio.length() < 100 || audio.length() > 8_000_000)
      throw new ApiException(400, "请上传有效的音频，大小不超过4 MB");
    limitAi(user, "asr", 8, 80);
    return ai.call("/asr", Map.of("audio", audio), 50);
  }

  @PostMapping("/ai/speak")
  Object speak(@RequestBody Map<String, Object> body, HttpServletRequest r) {
    UUID user = Actor.from(r).id();
    String text = Data.text(body, "text", "").strip();
    if (text.isBlank() || text.length() > 4000)
      throw new ApiException(400, "朗读文字需在4000字以内");
    limitAi(user, "tts", 10, 100);
    return ai.call("/tts", Map.of("text", text, "summarize", Boolean.TRUE.equals(body.get("summarize"))), 50);
  }

  @GetMapping("/chat")
  Object history(HttpServletRequest r) {
    return d.db.queryForList(
        "SELECT * FROM (SELECT role,content,created_at FROM chat_messages WHERE user_id=? ORDER BY created_at DESC LIMIT 30) t ORDER BY created_at",
        Actor.from(r).id());
  }

  @DeleteMapping("/chat")
  Object clear(HttpServletRequest r) {
    d.db.update("DELETE FROM chat_messages WHERE user_id=?", Actor.from(r).id());
    return Map.of("ok", true);
  }

  @PostMapping("/chat")
  Object chat(@RequestBody Map<String, Object> b, HttpServletRequest r) {
    UUID u = Actor.from(r).id();
    String text = Data.text(b, "message", "").strip();
    if (text.isBlank() || text.length() > 2000) throw new ApiException(400, "请输入2000字以内的内容");
    Integer recent =
        d.db.queryForObject(
            "SELECT count(*) FROM chat_messages WHERE user_id=? AND role='user' AND created_at>?",
            Integer.class,
            u,
            Data.now() - 60000);
    if (recent >= 12) throw new ApiException(429, "消息发送较快，请稍后再试");
    limitAi(u, "chat", 12, 120);
    var response =
        ai.call(
            "/chat",
            Map.of(
                "message",
                text,
                "events",
                events.list(u, Data.now() - 86400000, Data.now()),
                "plan",
                care.plan(u),
                "knowledge",
                knowledge()),
            40);
    String reply = Data.text(response, "reply", Data.text(response, "message", "暂时无法回答"));
    d.db.update(
        "INSERT INTO chat_messages VALUES (?,?,?,?,?)",
        UUID.randomUUID(),
        u,
        "user",
        text,
        Data.now());
    d.db.update(
        "INSERT INTO chat_messages VALUES (?,?,?,?,?)",
        UUID.randomUUID(),
        u,
        "assistant",
        reply,
        Data.now() + 1);
    return response;
  }
}
