package cn.aquora.insights;

import cn.aquora.auth.Actor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/ai")
public class AiAdminController {
  private final IntelligenceClient ai;

  public AiAdminController(IntelligenceClient ai) {
    this.ai = ai;
  }

  @GetMapping("/providers")
  Object providers(HttpServletRequest request) {
    Actor.from(request);
    return ai.call("/providers/status", Map.of(), 5);
  }

  @PostMapping("/reload")
  Object reload(HttpServletRequest request) {
    Actor.from(request);
    return ai.call("/providers/reload", Map.of(), 5);
  }
}
