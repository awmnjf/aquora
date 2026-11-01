package cn.aquora.events;

import cn.aquora.common.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {
  private final Data d;

  public EventService(Data d) {
    this.d = d;
  }

  public record Input(
      String kind,
      Integer amountMl,
      long occurredAt,
      String drinkType,
      String emptying,
      String note,
      String source,
      String idempotencyKey,
      Integer version,
      String reason,
      UUID taskId) {}

  public List<Map<String, Object>> list(UUID u, long from, long to) {
    return d.db.queryForList(
        "SELECT * FROM events WHERE user_id=? AND deleted=false AND occurred_at>=? AND occurred_at<=? ORDER BY occurred_at DESC LIMIT 2000",
        u,
        from,
        to);
  }

  void validate(Input b) {
    if (!Set.of("drink", "food", "voiding", "leakage", "symptom").contains(b.kind()))
      throw new ApiException(400, "未知记录类型");
    if (b.occurredAt() > Data.now() + 60000 || b.occurredAt() < Data.now() - 366L * 86400000)
      throw new ApiException(400, "记录时间需在过去一年内，且不能晚于现在");
    if (b.amountMl() != null && (b.amountMl() < 0 || b.amountMl() > 3000))
      throw new ApiException(400, "记录量应在0到3000毫升之间");
    if (Set.of("drink", "food").contains(b.kind()) && (b.amountMl() == null || b.amountMl() == 0))
      throw new ApiException(400, "请填写摄入量");
    if (b.note() != null && b.note().length() > 1000) throw new ApiException(400, "备注最多1000字");
    if (b.emptying() != null && !Set.of("complete", "incomplete", "unknown").contains(b.emptying()))
      throw new ApiException(400, "排空状态不正确");
    if (b.source() != null && !Set.of("manual", "assistant").contains(b.source()))
      throw new ApiException(400, "当前不支持该记录来源");
  }

  @Transactional
  public Object add(UUID u, Input b) {
    validate(b);
    if (b.idempotencyKey() == null || !b.idempotencyKey().matches("[A-Za-z0-9_-]{8,100}"))
      throw new ApiException(400, "记录请求缺少有效的幂等标识");
    var old =
        d.db.queryForList(
            "SELECT * FROM events WHERE user_id=? AND idempotency_key=?", u, b.idempotencyKey());
    if (!old.isEmpty()) {
      var o = old.getFirst();
      if (!Objects.equals(o.get("kind"), b.kind())
          || !Objects.equals(o.get("amount_ml"), b.amountMl())
          || ((Number) o.get("occurred_at")).longValue() != b.occurredAt())
        throw new ApiException(409, "该请求标识已被其他记录使用");
      return o;
    }
    UUID id = UUID.randomUUID();
    long now = Data.now();
    d.db.update(
        "INSERT INTO events(id,user_id,actor_id,kind,amount_ml,occurred_at,drink_type,emptying,note,source,idempotency_key,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        u,
        u,
        b.kind(),
        b.amountMl(),
        b.occurredAt(),
        b.drinkType() == null ? "water" : b.drinkType(),
        b.emptying() == null ? "unknown" : b.emptying(),
        b.note() == null ? "" : b.note(),
        b.source() == null ? "manual" : b.source(),
        b.idempotencyKey(),
        now,
        now);
    if (b.taskId() != null) {
      String kind = Set.of("drink", "food").contains(b.kind()) ? "drink" : b.kind();
      int n =
          d.db.update(
              "UPDATE tasks SET status='done',event_id=?,updated_at=? WHERE id=? AND user_id=? AND kind=? AND status='pending'",
              id,
              now,
              b.taskId(),
              u,
              kind);
      if (n == 0) throw new ApiException(409, "关联任务已变化，请刷新后重新记录");
    }
    d.audit(u, "event.create", id);
    return get(u, id);
  }

  public Map<String, Object> get(UUID u, UUID id) {
    var rows = d.db.queryForList("SELECT * FROM events WHERE id=? AND user_id=?", id, u);
    if (rows.isEmpty()) throw new ApiException(404, "未找到记录");
    return rows.getFirst();
  }

  @Transactional
  public Object edit(UUID u, UUID id, Input b, boolean remove) {
    var before = get(u, id);
    if ((boolean) before.get("deleted")) throw new ApiException(409, "记录已经撤销");
    if (b.reason() == null || b.reason().isBlank() || b.reason().length() > 300)
      throw new ApiException(400, "请填写修订原因（300字以内）");
    if (!remove) validate(b);
    int n;
    if (remove)
      n =
          d.db.update(
              "UPDATE events SET deleted=true,version=version+1,updated_at=? WHERE id=? AND user_id=? AND version=?",
              Data.now(),
              id,
              u,
              b.version());
    else
      n =
          d.db.update(
              "UPDATE events SET kind=?,amount_ml=?,occurred_at=?,drink_type=?,emptying=?,note=?,version=version+1,updated_at=? WHERE id=? AND user_id=? AND version=?",
              b.kind(),
              b.amountMl(),
              b.occurredAt(),
              b.drinkType(),
              b.emptying(),
              b.note() == null ? "" : b.note(),
              Data.now(),
              id,
              u,
              b.version());
    if (n == 0) throw new ApiException(409, "记录已更新，请刷新后重试");
    d.db.update(
        "INSERT INTO event_revisions(event_id,actor_id,version,before_data,reason,created_at) VALUES (?,?,?,?::jsonb,?,?)",
        id,
        u,
        before.get("version"),
        d.json(before),
        b.reason(),
        Data.now());
    if (remove || !Objects.equals(before.get("kind"), b.kind()))
      d.db.update(
          "UPDATE tasks SET status='pending',event_id=null,updated_at=? WHERE event_id=? AND user_id=?",
          Data.now(),
          id,
          u);
    d.audit(u, remove ? "event.revoke" : "event.revise", id);
    return get(u, id);
  }
}
