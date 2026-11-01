package cn.aquora.insights;

import cn.aquora.common.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class IntelligenceClient {
  private final Data d;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public IntelligenceClient(Data d) {
    this.d = d;
  }

  public Map<String, Object> call(String path, Object body, int seconds) {
    try {
      String base = System.getenv().getOrDefault("AI_BASE_URL", "http://127.0.0.1:8091");
      var req =
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(Duration.ofSeconds(seconds))
              .header("Content-Type", "application/json")
              .header("X-Internal-Token", System.getenv().getOrDefault("INTERNAL_TOKEN", ""))
              .POST(HttpRequest.BodyPublishers.ofString(d.json(body)))
              .build();
      var res = client.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != 200) throw new RuntimeException("upstream " + res.statusCode());
      return d.object(res.body());
    } catch (Exception e) {
      return Map.of("available", false, "message", "智能服务暂不可用，记录和护理计划仍可正常使用");
    }
  }
}
