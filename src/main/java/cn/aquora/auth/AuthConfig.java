package cn.aquora.auth;

import cn.aquora.common.ApiException;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class AuthConfig implements WebMvcConfigurer {
  private final AuthService auth;

  public AuthConfig(AuthService auth) {
    this.auth = auth;
  }

  public void addInterceptors(InterceptorRegistry r) {
    r.addInterceptor(
            new HandlerInterceptor() {
              public boolean preHandle(HttpServletRequest q, HttpServletResponse s, Object h) {
                String p = q.getRequestURI();
                boolean preview = q.getServerName().equals("39.107.15.165");
                if (preview
                    && java.util.Set.of("/api/auth/register", "/api/auth/login").contains(p))
                  throw new ApiException(403, "IP入口仅供独立体验，正式账号请使用HTTPS域名入口");
                if (java.util.Set.of(
                        "/api/health",
                        "/api/auth/config",
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/auth/demo")
                    .contains(p)) return true;
                String token = q.getHeader("Authorization");
                if (token == null || !token.startsWith("Bearer "))
                  throw new ApiException(401, "请先登录");
                Actor a = auth.resolve(token.substring(7));
                if (preview && !a.demo()) throw new ApiException(403, "正式账号请使用HTTPS域名入口");
                q.setAttribute("actor", a);
                if (p.startsWith("/api/admin/") && !a.role().equals("admin"))
                  throw new ApiException(403, "需要管理员权限");
                return true;
              }
            })
        .addPathPatterns("/api/**");
  }
}
