package cn.aquora.insights;

import cn.aquora.auth.Actor;
import cn.aquora.care.CareService;
import cn.aquora.common.*;
import cn.aquora.events.EventService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
  private final Data data;
  private final EventService events;
  private final CareService care;
  private final AgentClient agent;
  private final Map<UUID, long[]> limits = new HashMap<>();
  public AgentController(Data data, EventService events, CareService care, AgentClient agent) {
    this.data = data; this.events = events; this.care = care; this.agent = agent;
  }
  private synchronized void limit(UUID user) {
    long minute = Data.now()/60000;
    limits.entrySet().removeIf(e -> e.getValue()[0] < minute);
    long[] bucket = limits.computeIfAbsent(user, k -> new long[]{minute,0});
    if (bucket[1] >= 8 || limits.values().stream().mapToLong(x -> x[1]).sum() >= 80)
      throw new ApiException(429, "请求较多，请稍后再试");
    bucket[1]++;
  }
  @PostMapping("/run")
  Object run(@RequestBody Map<String,Object> body, HttpServletRequest request) {
    UUID user = Actor.from(request).id();
    // Reject identity, path, history and MCP configuration supplied by the browser.
    if (!Set.of("message", "scenario").containsAll(body.keySet()))
      throw new ApiException(400, "请求包含不支持的字段");
    if (!(body.get("message") instanceof String text) || text.isBlank() || text.length()>2000)
      throw new ApiException(400, "请输入2000字以内的内容");
    String scenario = Data.text(body,"scenario","home");
    if (!Set.of("home","outdoor","knowledge").contains(scenario))
      throw new ApiException(400,"不支持的交互场景");
    limit(user);
    var history = data.db.queryForList(
        "SELECT role,content FROM (SELECT role,content,created_at FROM chat_messages WHERE user_id=? ORDER BY created_at DESC LIMIT 10) t ORDER BY created_at", user);
    var response = agent.run(Map.of("userId",user.toString(),"message",text.strip(),"scenario",scenario,
        "history",history,"events",events.list(user,Data.now()-86400000,Data.now()),"plan",care.plan(user)));
    String reply = Data.text(response,"reply","暂时无法回答");
    data.db.update("INSERT INTO chat_messages VALUES (?,?,?,?,?)", UUID.randomUUID(),user,"user",text.strip(),Data.now());
    data.db.update("INSERT INTO chat_messages VALUES (?,?,?,?,?)", UUID.randomUUID(),user,"assistant",reply,Data.now()+1);
    return response;
  }
}
