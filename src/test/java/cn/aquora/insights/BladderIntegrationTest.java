package cn.aquora.insights;
import cn.aquora.auth.Actor;
import cn.aquora.care.CareService;
import cn.aquora.common.Data;
import cn.aquora.events.EventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class BladderIntegrationTest {
 @Test void fetchesOnlyAuthenticatedUserAndCallsPythonAlgorithm() {
  var db=mock(JdbcTemplate.class);var ai=mock(IntelligenceClient.class);var bladder=mock(BladderClient.class);
  var controller=new InsightsController(new Data(db,new ObjectMapper()),mock(EventService.class),mock(CareService.class),ai,bladder);
  UUID u=UUID.randomUUID();var r=new MockHttpServletRequest();r.setAttribute("actor",new Actor(u,"test","test","user",true));
  when(db.queryForObject(anyString(),eq(String.class),eq(u))).thenReturn("{\"modelEnabled\":true}");
  when(db.queryForList(anyString(),eq(u),anyLong(),anyLong())).thenReturn(List.of(Map.of("id","one","kind","drink","amount_ml",100,"occurred_at",1L)));
  when(bladder.estimate(any())).thenReturn(Map.of("available",false));
  controller.state(r);
  var sql=ArgumentCaptor.forClass(String.class);verify(db).queryForList(sql.capture(),eq(u),anyLong(),anyLong());
  assertTrue(sql.getValue().contains("user_id=? AND deleted=false"));assertTrue(sql.getValue().contains("LIMIT 10001"));
  var body=ArgumentCaptor.forClass(Object.class);verify(bladder).estimate(body.capture());
  assertEquals(1,((List<?>)((Map<?,?>)body.getValue()).get("events")).size());verifyNoInteractions(ai);
 }
 @Test void refusesSilentTruncation() {
  var db=mock(JdbcTemplate.class);var bladder=mock(BladderClient.class);
  var controller=new InsightsController(new Data(db,new ObjectMapper()),mock(EventService.class),mock(CareService.class),mock(IntelligenceClient.class),bladder);
  UUID u=UUID.randomUUID();var r=new MockHttpServletRequest();r.setAttribute("actor",new Actor(u,"test","test","user",true));
  when(db.queryForObject(anyString(),eq(String.class),eq(u))).thenReturn("{\"modelEnabled\":true}");
  when(db.queryForList(anyString(),eq(u),anyLong(),anyLong())).thenReturn(Collections.nCopies(10001,Map.of()));
  var result=(Map<?,?>)controller.state(r);assertEquals("truncated",result.get("code"));verifyNoInteractions(bladder);
 }
}
