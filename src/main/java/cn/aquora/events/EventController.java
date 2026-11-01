package cn.aquora.events;

import cn.aquora.auth.Actor;
import cn.aquora.common.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
public class EventController {
  private final EventService service;
  private final Data d;

  public EventController(EventService s, Data d) {
    service = s;
    this.d = d;
  }

  @GetMapping
  Object list(
      @RequestParam(defaultValue = "0") long from,
      @RequestParam(defaultValue = "9223372036854775807") long to,
      HttpServletRequest r) {
    return service.list(Actor.from(r).id(), from, to);
  }

  @PostMapping
  Object add(@RequestBody EventService.Input b, HttpServletRequest r) {
    return service.add(Actor.from(r).id(), b);
  }

  @PutMapping("/{id}")
  Object edit(@PathVariable UUID id, @RequestBody EventService.Input b, HttpServletRequest r) {
    return service.edit(Actor.from(r).id(), id, b, false);
  }

  @PostMapping("/{id}/revoke")
  Object remove(@PathVariable UUID id, @RequestBody EventService.Input b, HttpServletRequest r) {
    return service.edit(Actor.from(r).id(), id, b, true);
  }

  @GetMapping("/{id}/history")
  Object history(@PathVariable UUID id, HttpServletRequest r) {
    service.get(Actor.from(r).id(), id);
    return d.db.queryForList(
        "SELECT version,reason,created_at FROM event_revisions WHERE event_id=? ORDER BY version DESC",
        id);
  }
}
