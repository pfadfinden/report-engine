package de.pfadfinden.reports_engine.executor.Port;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperReport;

/**
 * Coerces the raw, JSON-decoded parameter values coming from an HTTP request body into the types a
 * report's parameters actually declare (JRParameter#getValueClass()). JasperReports does not coerce
 * parameter values itself - a Jackson-decoded Integer handed to a report expecting a
 * java.lang.Long, for example, fails fast inside the fill rather than being silently widened.
 *
 * <p>Shared by every HTTP-facing executor (local-report-executor, azure-report-executor) so both
 * apply the same coercion rules.
 *
 * <p>Only the common scalar cases (numbers, strings, booleans) are handled; anything else is passed
 * through unchanged and left to fail fill-time with a clear JasperReports error if it's genuinely
 * incompatible.
 */
public final class ParameterCoercion {

  private ParameterCoercion() {}

  public static Map<String, Object> coerce(JasperReport report, Map<String, Object> rawParameters) {
    Map<String, Class<?>> declaredTypes = new HashMap<>();
    for (JRParameter parameter : report.getParameters()) {
      declaredTypes.put(parameter.getName(), parameter.getValueClass());
    }

    Map<String, Object> coerced = new HashMap<>();
    for (Map.Entry<String, Object> entry : rawParameters.entrySet()) {
      Class<?> targetType = declaredTypes.get(entry.getKey());
      coerced.put(entry.getKey(), coerceValue(entry.getValue(), targetType));
    }
    return coerced;
  }

  private static Object coerceValue(Object value, Class<?> targetType) {
    if (value == null || targetType == null || targetType.isInstance(value)) {
      return value;
    }

    if (Number.class.isAssignableFrom(targetType) && value instanceof Number number) {
      if (targetType == Long.class) return number.longValue();
      if (targetType == Integer.class) return number.intValue();
      if (targetType == Double.class) return number.doubleValue();
      if (targetType == Float.class) return number.floatValue();
      if (targetType == Short.class) return number.shortValue();
      if (targetType == BigDecimal.class) return new BigDecimal(number.toString());
    }

    // HTTP request bodies hand every parameter over as a String (form
    // fields, or JSON strings) regardless of what the report declares -
    // parse it into the numeric type the report actually expects.
    if (Number.class.isAssignableFrom(targetType) && value instanceof String s) {
      if (targetType == Long.class) return Long.valueOf(s);
      if (targetType == Integer.class) return Integer.valueOf(s);
      if (targetType == Double.class) return Double.valueOf(s);
      if (targetType == Float.class) return Float.valueOf(s);
      if (targetType == Short.class) return Short.valueOf(s);
      if (targetType == BigDecimal.class) return new BigDecimal(s);
    }

    if (targetType == String.class) {
      return value.toString();
    }

    if (targetType == Boolean.class && value instanceof String s) {
      return Boolean.parseBoolean(s);
    }

    return value;
  }
}
