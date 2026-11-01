package cn.aquora.insights;

import cn.aquora.common.Data;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AgentClient {
  private final Data data;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  public AgentClient(Data data) { this.data = data; }
  public Map<String,Object> run(Object body) {
    try {
      String base = System.getenv().getOrDefault("AGENT_BASE_URL", "http://127.0.0.1:8093");
      var request = HttpRequest.newBuilder(URI.create(base + "/run"))
          .timeout(Duration.ofSeconds(55)).header("Content-Type", "application/json")
          .header("X-Internal-Token", System.getenv().getOrDefault("INTERNAL_TOKEN", ""))
          .POST(HttpRequest.BodyPublishers.ofString(data.json(body))).build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 429)
        return Map.of("reply", "小澜正在处理请求，请稍等片刻再说。", "mode", "busy");
      if (response.statusCode() != 200) throw new IllegalStateException();
      return data.object(response.body());
    } catch (Exception error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      return Map.of("reply", "小澜暂时不可用，请稍后重试。记录和护理计划仍可正常使用。", "mode", "unavailable");
    }
  }
}
