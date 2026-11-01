package cn.aquora.insights;
import cn.aquora.auth.Actor;
import cn.aquora.common.*;
import cn.aquora.events.EventService;
import cn.aquora.care.CareService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class VisionDraftTest {
  @Test void pairIsForwardedWithoutPersistingAndMissingAfterIsRejected() {
    Data data=mock(Data.class); EventService events=mock(EventService.class);
    IntelligenceClient ai=mock(IntelligenceClient.class);
    when(ai.call(eq("/capabilities"),any(),eq(5))).thenReturn(Map.of("services",Map.of("vision",Map.of("available",true))));
    when(ai.call(eq("/vision"),any(),eq(50))).thenReturn(Map.of("available",true,"candidate",Map.of("kind","drink","amountMl",300)));
    var controller=new InsightsController(data,events,mock(CareService.class),ai);
    var request=new MockHttpServletRequest();request.setAttribute("actor",new Actor(UUID.randomUUID(),"qa","qa","user",true));
    String photo="data:image/png;base64,"+Base64.getEncoder().encodeToString(new byte[100]);
    var body=Map.<String,Object>of("beforeImage",photo,"afterImage",photo,"kind","drink","context","500ml");
    var result=(Map<?,?>)controller.vision(body,request);
    assertEquals(true,result.get("available"));verify(ai).call("/vision",body,50);verifyNoInteractions(events,data);
    assertThrows(ApiException.class,()->controller.vision(Map.of("beforeImage",photo,"kind","drink"),request));
    verify(ai,times(1)).call(eq("/vision"),any(),eq(50));
  }
}
