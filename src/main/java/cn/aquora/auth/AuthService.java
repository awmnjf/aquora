package cn.aquora.auth;

import cn.aquora.common.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private final Data d;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
  private final Map<String, long[]> limits = new ConcurrentHashMap<>();

  public AuthService(Data d) {
    this.d = d;
  }

  public void limit(String address) {
    long now = Data.now();
    if (limits.size() > 20000) limits.entrySet().removeIf(e -> now - e.getValue()[0] > 600000);
    long[] v = limits.computeIfAbsent(address, k -> new long[] {now, 0});
    synchronized (v) {
      if (now - v[0] > 600000) {
        v[0] = now;
        v[1] = 0;
      }
      if (++v[1] > 30) throw new ApiException(429, "尝试过于频繁，请十分钟后再试");
    }
  }

  public static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String random() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public Actor resolve(String token) {
    var rows =
        d.db.queryForList(
            "SELECT u.* FROM users u JOIN sessions s ON s.user_id=u.id WHERE s.token_hash=? AND s.expires_at>? AND u.status='active'",
            hash(token),
            Data.now());
    if (rows.isEmpty()) throw new ApiException(401, "登录已过期，请重新登录");
    return actor(rows.getFirst());
  }

  private Actor actor(Map<String, Object> u) {
    return new Actor(
        (UUID) u.get("id"),
        (String) u.get("username"),
        (String) u.get("display_name"),
        (String) u.get("role"),
        (boolean) u.get("demo"));
  }

  private Map<String, Object> session(Actor a) {
    String t = random();
    d.db.update(
        "INSERT INTO sessions VALUES (?,?,?,?)",
        hash(t),
        a.id(),
        Data.now() + (a.demo() ? 172800000L : 604800000L),
        Data.now());
    return Map.of("token", t, "user", a);
  }

  @Transactional
  public Map<String, Object> register(
      String username, String password, String name, boolean terms) {
    if (!terms) throw new ApiException(400, "请阅读并同意用户协议和隐私说明");
    if (!username.matches("[A-Za-z][A-Za-z0-9_]{3,31}")
        || password.length() < 10
        || password.length() > 72
        || name.isBlank()
        || name.length() > 40) throw new ApiException(400, "账号需为4–32位字母、数字或下划线，密码需10–72位，昵称不能为空");
    username = username.toLowerCase(Locale.ROOT);
    if (d.db.queryForObject("SELECT count(*) FROM users WHERE username=?", Integer.class, username)
        > 0) throw new ApiException(409, "该账号已被使用");
    UUID id = UUID.randomUUID();
    d.db.update(
        "INSERT INTO users(id,username,password_hash,display_name,created_at) VALUES (?,?,?,?,?)",
        id,
        username,
        passwords.encode(password),
        name,
        Data.now());
    d.db.update("INSERT INTO profiles(user_id) VALUES (?)", id);
    d.audit(id, "account.register", id);
    return session(new Actor(id, username, name, "user", false));
  }

  public Map<String, Object> login(String username, String password) {
    var rows =
        d.db.queryForList(
            "SELECT * FROM users WHERE username=? AND status='active'",
            username.toLowerCase(Locale.ROOT));
    if (rows.isEmpty()
        || !passwords.matches(password, (String) rows.getFirst().get("password_hash")))
      throw new ApiException(401, "账号或密码不正确");
    return session(actor(rows.getFirst()));
  }

  @Transactional
  public Map<String, Object> demo() {
    UUID id = UUID.randomUUID();
    String name = "体验访客";
    String uname = "demo_" + id.toString().substring(0, 12);
    long now = Data.now();
    d.db.update(
        "INSERT INTO users(id,username,password_hash,display_name,demo,created_at) VALUES (?,?,?,?,true,?)",
        id,
        uname,
        passwords.encode(random()),
        name,
        now);
    d.db.update(
        "INSERT INTO profiles(user_id,data) VALUES (?,?::jsonb)",
        id,
        d.json(
            Map.of(
                "displayName",
                name,
                "weight",
                65,
                "age",
                42,
                "method",
                "catheter",
                "modelEnabled",
                true,
                "baselineRate",
                45,
                "conversionRatio",
                0.55,
                "residual",
                20)));
    var zone = ZoneId.of("Asia/Shanghai");
    var day = LocalDate.now(zone);
    long morning = now - 5400000;
    if (morning > now) morning = now - 10800000;
    String[] kinds = {"voiding", "drink", "drink"};
    int[] amounts = {280, 200, 150};
    long[] times = {morning, morning + 1200000, Math.min(now - 120000, morning + 3600000)};
    for (int i = 0; i < 3; i++)
      d.db.update(
          "INSERT INTO events(id,user_id,actor_id,kind,amount_ml,occurred_at,drink_type,emptying,source,idempotency_key,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'complete','manual',?,?,?)",
          UUID.randomUUID(),
          id,
          id,
          kinds[i],
          amounts[i],
          times[i],
          "water",
          "demo-" + i,
          now,
          now);
    Map<String, Object> plan = new LinkedHashMap<>();
    plan.put("waterGoal", 1800);
    plan.put(
        "drinkTimes",
        List.of(
            "08:00",
            "12:00",
            "16:00",
            Instant.ofEpochMilli(now + 1800000)
                .atZone(zone)
                .toLocalTime()
                .withSecond(0)
                .withNano(0)
                .toString()));
    plan.put(
        "voidTimes",
        List.of(
            "08:30",
            "12:30",
            "16:30",
            Instant.ofEpochMilli(now + 3600000)
                .atZone(zone)
                .toLocalTime()
                .withSecond(0)
                .withNano(0)
                .toString()));
    plan.put("confirmed", true);
    plan.put("source", "体验方案，仅用于功能演示");
    plan.put("note", "正式用户请转录自己的既有护理安排");
    d.db.update(
        "INSERT INTO plans(id,user_id,version,data,created_at) VALUES (?,?,1,?::jsonb,?)",
        UUID.randomUUID(),
        id,
        d.json(plan),
        now);
    return session(new Actor(id, uname, name, "user", true));
  }

  public void logout(String t) {
    d.db.update("DELETE FROM sessions WHERE token_hash=?", hash(t));
  }

  @Transactional
  public void changePassword(UUID user, String old, String next, String currentToken) {
    if (next == null || next.length() < 10 || next.length() > 72)
      throw new ApiException(400, "新密码需10–72位");
    String stored =
        d.db.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, user);
    if (!passwords.matches(old, stored)) throw new ApiException(400, "当前密码不正确");
    d.db.update("UPDATE users SET password_hash=? WHERE id=?", passwords.encode(next), user);
    d.db.update("DELETE FROM sessions WHERE user_id=? AND token_hash<>?", user, hash(currentToken));
    d.audit(user, "account.password_change", user);
  }
}
