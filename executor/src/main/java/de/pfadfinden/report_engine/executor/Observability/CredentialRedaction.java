package de.pfadfinden.report_engine.executor.Observability;

import java.util.regex.Pattern;

/**
 * Strips JDBC credentials out of a message before it's logged or persisted. Both executor
 * deployments connect via a JDBC URL that embeds the source database's username/password (see
 * docker-compose.yml) - some driver failure messages (e.g. an unparsable URL) echo that URL back
 * verbatim, which would otherwise leak the password into logs/execution-status records.
 */
public final class CredentialRedaction {

  // Query-param style, e.g. jdbc:postgresql://host/db?user=x&password=y
  private static final Pattern QUERY_PARAM_CREDENTIALS =
      Pattern.compile("(?i)\\b(user|username|password)=[^&\\s]*");

  // URI userinfo style, e.g. postgresql://user:password@host/db
  private static final Pattern USERINFO_CREDENTIALS =
      Pattern.compile("://[^/?#@\\s]+:[^/?#@\\s]+@");

  private CredentialRedaction() {}

  public static String redactJdbcCredentials(String message) {
    if (message == null) {
      return null;
    }
    String redacted =
        QUERY_PARAM_CREDENTIALS.matcher(message).replaceAll(match -> match.group(1) + "=REDACTED");
    return USERINFO_CREDENTIALS.matcher(redacted).replaceAll("://REDACTED@");
  }
}
