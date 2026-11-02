package cn.aquora.insights;

import cn.aquora.auth.Actor;
import cn.aquora.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PersonalReportTest {
  @Test void rejectsInvalidAndFuturePeriods() {
    assertThrows(ApiException.class, () -> PersonalReportController.range("bad", "2026-01-01"));
    assertThrows(ApiException.class, () -> PersonalReportController.range("2025-01-01", "2025-05-01"));
    assertThrows(ApiException.class, () -> PersonalReportController.range("2025-01-02", "2025-01-01"));
    assertThrows(ApiException.class, () -> PersonalReportController.range("2999-01-01", "2999-01-02"));
    var range = PersonalReportController.range("2025-01-01", "2025-01-02");
    assertEquals(Instant.parse("2024-12-31T16:00:00Z").toEpochMilli(), range.start());
    assertEquals(2 * 86400000L - 1, range.end() - range.start());
  }
  @Test void sumsAllRowsAndPreservesMissingAndUnmeasuredCoverage() {
    var db = mock(JdbcTemplate.class); var ai = mock(IntelligenceClient.class);
    var controller = new PersonalReportController(new Data(db, new ObjectMapper()), ai);
    UUID user = UUID.randomUUID();
    when(db.queryForList(anyString(), eq(user), anyLong(), anyLong())).thenReturn(List.of(
      Map.of("date", "2025-01-01", "intake", 200, "drinks", 1, "voids", 1, "unmeasured", 1, "records", 2),
      Map.of("date", "2025-01-03", "output", 0, "voids", 1, "measured", 1, "records", 1)));
    when(db.queryForMap(anyString(), eq(user), anyLong(), anyLong())).thenReturn(Map.of("due", 3, "done", 1));
    var result = controller.snapshot(user, PersonalReportController.range("2025-01-01", "2025-01-03"));
    var s = (Map<?,?>) result.get("summary");
    assertEquals(200L,s.get("intake")); assertEquals(0L,s.get("output")); assertEquals(2L,s.get("coveredDays"));
    assertEquals(1L,s.get("intakeDays")); assertEquals(1L,s.get("outputDays")); assertEquals(1L,s.get("unmeasured"));
    assertEquals(0L, ((Map<?,?>)((List<?>)result.get("days")).get(1)).get("records"));
    var sql = ArgumentCaptor.forClass(String.class);
    verify(db).queryForList(sql.capture(), eq(user), anyLong(), anyLong());
    assertFalse(sql.getValue().contains("LIMIT")); assertTrue(sql.getValue().contains("user_id=? AND deleted=false"));
  }
  @Test void interpretationUsesAuthenticatedUserAndServerSnapshot() {
    var db = mock(JdbcTemplate.class); var ai = mock(IntelligenceClient.class);
    var controller = new PersonalReportController(new Data(db, new ObjectMapper()), ai);
    UUID user = UUID.randomUUID(); var req = new MockHttpServletRequest();
    req.setAttribute("actor", new Actor(user,"qa","qa","user",true));
    when(db.queryForList(anyString(), eq(user), anyLong(), anyLong())).thenReturn(List.of());
    when(db.queryForMap(anyString(), eq(user), anyLong(), anyLong())).thenReturn(Map.of("due",0,"done",0));
    when(ai.call(eq("/report-interpretation"), any(), eq(45))).thenReturn(Map.of("available",true));
    controller.interpret(new PersonalReportController.RangeInput("2025-01-01","2025-01-02"), req);
    var payload = ArgumentCaptor.forClass(Object.class);
    verify(ai).call(eq("/report-interpretation"),payload.capture(),eq(45));
    var report = (Map<?,?>)((Map<?,?>)payload.getValue()).get("report");
    assertEquals("2025-01-01",report.get("from"));
    assertEquals(0L,((Map<?,?>)report.get("summary")).get("records"));
    assertFalse(report.containsKey("userId")); assertFalse(report.containsKey("events"));
  }
}
