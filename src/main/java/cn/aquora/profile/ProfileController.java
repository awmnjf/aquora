package cn.aquora.profile;

import cn.aquora.auth.Actor;
import cn.aquora.common.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {
  private final Data d;

  public ProfileController(Data d) {
    this.d = d;
  }

  @GetMapping
  Object get(HttpServletRequest r) {
    var row =
        d.db.queryForMap("SELECT data,version FROM profiles WHERE user_id=?", Actor.from(r).id());
    return Map.of("data", d.object(row.get("data")), "version", row.get("version"));
  }

  @PutMapping
  @Transactional
  Object save(@RequestBody Map<String, Object> b, HttpServletRequest r) {
    Actor a = Actor.from(r);
    int version = Data.integer(b, "version", 0);
    Map<String, Object> data = new LinkedHashMap<>();
    String name = Data.text(b, "displayName", a.displayName()).strip();
    if (name.isBlank() || name.length() > 40) throw new ApiException(400, "请填写40字以内的昵称");
    data.put("displayName", name);
    String method = Data.text(b, "method", "catheter");
    if (!Set.of("catheter", "voiding", "drainage").contains(method))
      throw new ApiException(400, "请选择管理方式");
    data.put("method", method);
    for (String key : List.of("age", "weight", "baselineRate", "conversionRatio", "residual")) {
      Object value = b.get(key);
      if (value != null && !value.toString().isBlank()) {
        double v = Double.parseDouble(value.toString());
        double min = key.equals("age") ? 18 : key.equals("weight") ? 20 : 0;
        double max =
            switch (key) {
              case "age" -> 120;
              case "weight" -> 300;
              case "baselineRate" -> 180;
              case "conversionRatio" -> 1;
              default -> 1000;
            };
        if (!Double.isFinite(v) || v < min || v > max)
          throw new ApiException(400, "参数超出允许范围：" + key);
        data.put(key, v);
      }
    }
    data.put("modelEnabled", Boolean.TRUE.equals(b.get("modelEnabled")));
    data.put("modelStatus", "experimental");
    if (d.db.update(
            "UPDATE profiles SET data=?::jsonb,version=version+1 WHERE user_id=? AND version=?",
            d.json(data),
            a.id(),
            version)
        == 0) throw new ApiException(409, "档案已在其他页面更新，请刷新后重试");
    d.db.update("UPDATE users SET display_name=? WHERE id=?", name, a.id());
    d.audit(a.id(), "profile.update", a.id());
    return get(r);
  }
}
