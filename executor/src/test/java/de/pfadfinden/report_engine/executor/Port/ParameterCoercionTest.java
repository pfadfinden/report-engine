package de.pfadfinden.report_engine.executor.Port;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Date;
import java.util.HashMap;
import java.util.Map;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.design.JRDesignParameter;
import net.sf.jasperreports.engine.design.JasperDesign;
import org.junit.jupiter.api.Test;

class ParameterCoercionTest {

  private JasperReport reportWithParameters(Map<String, Class<?>> parameters) throws JRException {
    JasperDesign design = new JasperDesign();
    design.setName("test_report");
    design.setPageWidth(595);
    design.setPageHeight(842);
    design.setColumnWidth(555);

    for (Map.Entry<String, Class<?>> entry : parameters.entrySet()) {
      JRDesignParameter parameter = new JRDesignParameter();
      parameter.setName(entry.getKey());
      parameter.setValueClass(entry.getValue());
      design.addParameter(parameter);
    }

    return JasperCompileManager.compileReport(design);
  }

  @Test
  void coercesStringToDeclaredJavaSqlDate() throws JRException {
    JasperReport report = reportWithParameters(Map.of("p_joined_after", Date.class));

    Map<String, Object> coerced =
        ParameterCoercion.coerce(report, Map.of("p_joined_after", "2021-01-01"));

    assertEquals(Date.valueOf("2021-01-01"), coerced.get("p_joined_after"));
  }

  @Test
  void coercesStringToDeclaredInteger() throws JRException {
    JasperReport report = reportWithParameters(Map.of("p_gruppe_id", Integer.class));

    Map<String, Object> coerced = ParameterCoercion.coerce(report, Map.of("p_gruppe_id", "42"));

    assertEquals(42, coerced.get("p_gruppe_id"));
  }

  @Test
  void passesThroughValuesForUndeclaredParameters() throws JRException {
    JasperReport report = reportWithParameters(Map.of());

    Map<String, Object> raw = new HashMap<>();
    raw.put("some_extra_key", "unchanged");
    Map<String, Object> coerced = ParameterCoercion.coerce(report, raw);

    assertEquals("unchanged", coerced.get("some_extra_key"));
  }

  @Test
  void leavesNullValuesAsNull() throws JRException {
    JasperReport report = reportWithParameters(Map.of("p_joined_after", Date.class));

    Map<String, Object> raw = new HashMap<>();
    raw.put("p_joined_after", null);
    Map<String, Object> coerced = ParameterCoercion.coerce(report, raw);

    assertEquals(null, coerced.get("p_joined_after"));
  }
}
