package cn.aquora.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

public record Actor(UUID id, String username, String displayName, String role, boolean demo) {
  public static Actor from(HttpServletRequest r) {
    return (Actor) r.getAttribute("actor");
  }
}
