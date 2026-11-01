package cn.aquora.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Data {
  public final JdbcTemplate db;
  public final ObjectMapper mapper;

  public Data(JdbcTemplate db, ObjectMapper mapper) {
    this.db = db;
    this.mapper = mapper;
  }

  public String json(Object o) {
    try {
      return mapper.writeValueAsString(o);
    } catch (Exception e) {
      throw new IllegalArgumentException();
    }
  }

  public Map<String, Object> object(Object s) {
    try {
      return mapper.readValue(s.toString(), new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      return new HashMap<>();
    }
  }

  public void audit(UUID actor, String action, Object id) {
    db.update(
        "INSERT INTO audit(actor_id,action,resource_id,created_at) VALUES (?,?,?,?)",
        actor,
        action,
        String.valueOf(id),
        System.currentTimeMillis());
  }

  public static long now() {
    return System.currentTimeMillis();
  }

  public static String text(Map<String, Object> m, String key, String fallback) {
    Object v = m.get(key);
    return v == null ? fallback : v.toString();
  }

  public static int integer(Map<String, Object> m, String key, int fallback) {
    Object v = m.get(key);
    return v == null ? fallback : Integer.parseInt(v.toString());
  }
}
