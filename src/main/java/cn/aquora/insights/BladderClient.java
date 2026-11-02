package cn.aquora.insights;

import cn.aquora.common.Data;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Thin transport to deterministic Python algorithms hosted alongside Agent. */
@Service
public class BladderClient {
  private final Data data;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  public BladderClient(Data data) { this.data = data; }
  public Map<String,Object> estimate(Object body) {
    try {
      String base = System.getenv().getOrDefault("AGENT_BASE_URL", "http://127.0.0.1:8093");
      var request = HttpRequest.newBuilder(URI.create(base + "/algorithms/bladder/state"))
          .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
          .header("X-Internal-Token", System.getenv().getOrDefault("INTERNAL_TOKEN", ""))
          .POST(HttpRequest.BodyPublishers.ofString(data.json(body))).build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw new IllegalStateException();
      return data.object(response.body());
    } catch (Exception error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      return Map.of("available",false,"code","service_unavailable","message","状态暂时无法更新，请稍后再试");
    }
  }
}
