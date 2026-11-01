package cn.aquora.insights;
import cn.aquora.auth.Actor;
import cn.aquora.common.*;
import cn.aquora.events.EventService;
import cn.aquora.care.CareService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AgentScopeTest {
 @Test void rejectsClientIdentityAndUnknownScenarioBeforeAnyToolCall() {
  var data=mock(Data.class);var events=mock(EventService.class);var care=mock(CareService.class);var agent=mock(AgentClient.class);
  var controller=new AgentController(data,events,care,agent);
  var request=new MockHttpServletRequest();request.setAttribute("actor",new Actor(UUID.randomUUID(),"qa","qa","user",true));
  assertThrows(ApiException.class,()->controller.run(Map.of("message","hello","userId",UUID.randomUUID().toString()),request));
  assertThrows(ApiException.class,()->controller.run(Map.of("message","hello","scenario","shell"),request));
  assertThrows(ApiException.class,()->controller.run(Map.of("message","hello","tools",List.of("shell")),request));
  verifyNoInteractions(agent,events,care,data);
 }
}
