package cn.aquora.auth;

import cn.aquora.common.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
  private final AuthService auth;

  public AuthController(AuthService auth) {
    this.auth = auth;
  }

  public record Register(
      @NotBlank @Size(max = 40) String username,
      @NotBlank @Size(max = 72) String password,
      @NotBlank @Size(max = 40) String displayName,
      boolean terms) {}

  public record Login(
      @NotBlank @Size(max = 40) String username, @NotBlank @Size(max = 72) String password) {}

  @GetMapping("/health")
  Object health() {
    return Map.of("status", "ok", "version", "1.0.0");
  }

  @GetMapping("/auth/config")
  Object config() {
    return Map.of(
        "password",
        true,
        "wechat",
        false,
        "alipay",
        false,
        "sms",
        false,
        "reason",
        "第三方应用尚未开通，当前可使用账号密码登录");
  }

  @PostMapping("/auth/register")
  Object register(@Valid @RequestBody Register b, HttpServletRequest r) {
    auth.limit(r.getRemoteAddr());
    return auth.register(b.username(), b.password(), b.displayName(), b.terms());
  }

  @PostMapping("/auth/login")
  Object login(@Valid @RequestBody Login b, HttpServletRequest r) {
    auth.limit(r.getRemoteAddr());
    return auth.login(b.username(), b.password());
  }

  @PostMapping("/auth/demo")
  Object demo(HttpServletRequest r) {
    auth.limit(r.getRemoteAddr());
    return auth.demo();
  }

  @GetMapping("/auth/me")
  Object me(HttpServletRequest r) {
    return Actor.from(r);
  }

  @PostMapping("/auth/logout")
  Object logout(HttpServletRequest r) {
    auth.logout(r.getHeader("Authorization").substring(7));
    return Map.of("ok", true);
  }

  public record Password(
      @NotBlank @Size(max = 72) String currentPassword,
      @NotBlank @Size(min = 10, max = 72) String newPassword) {}

  @PostMapping("/auth/password")
  Object password(@Valid @RequestBody Password b, HttpServletRequest r) {
    auth.limit(r.getRemoteAddr());
    auth.changePassword(
        Actor.from(r).id(),
        b.currentPassword(),
        b.newPassword(),
        r.getHeader("Authorization").substring(7));
    return Map.of("ok", true);
  }
}
