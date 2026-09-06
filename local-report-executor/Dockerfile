FROM eclipse-temurin:25 AS jre-build

# Fill/export touches AWT (font metrics/layout) and JDBC, unlike the
# preprocessor's compile-only java.base image - verified empirically against
# the modules actually exercised at runtime. java.instrument is for -javaagent (the OpenTelemetry
# agent, added below) - without it the JVM fails at startup with "Could not find agent library
# instrument on the library path ... Module java.instrument may be missing from runtime image."
# jdk.jfr/jdk.management.jfr let the agent's runtime-telemetry module pick its fuller JFR-based
# JVM metrics collector instead of silently falling back to a JMX-only one that's missing several
# metrics a generic "JVM Overview" dashboard expects - CPU utilization (jvm.cpu.recent_utilization)
# in particular, verified empirically absent without these two modules.
RUN $JAVA_HOME/bin/jlink \
         --add-modules java.base,java.desktop,java.sql,java.naming,java.xml,java.logging,java.management,java.instrument,jdk.jfr,jdk.management.jfr \
         --strip-debug \
         --no-man-pages \
         --no-header-files \
         --compress=2 \
         --output /javaruntime

# Runtime base image - bookworm-slim (Debian 12), not buster (Debian 10, EOL since mid-2024)
FROM debian:bookworm-slim
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jre-build /javaruntime $JAVA_HOME

# Without fontconfig (+ at least one real font), java.desktop's font manager
# fails to initialize the first time a fill touches AWT font metrics, with
# "Fontconfig head is null, check your fonts or fonts configuration". That
# first failure is caught and reported as a normal FAILED execution, but it
# also poisons the font manager's static state for the life of the process:
# every fill after it throws NoClassDefFoundError instead - an Error, not an
# Exception, which escapes ReportExecutionRunner's catch (Exception e) and is
# silently dropped by the background executor, leaving the execution stuck at
# PENDING forever with nothing logged.
# curl is here for the HEALTHCHECK below and to fetch the OTel agent jar further down;
# ca-certificates is needed for the latter specifically - the base image has no CA bundle at all,
# so an https:// curl fails with "error setting certificate file" without it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# OpenTelemetry Java instrumentation agent - auto-instruments Jetty (Javalin's underlying server)
# for http.server.* request metrics/spans per the stable HTTP semantic conventions, without this
# app having to hand-roll that itself. Pinned to the exact release the root pom's
# opentelemetry-instrumentation-bom-alpha (2.31.1-alpha) already targets, so the agent and this
# app's library instrumentation versions can't drift apart. See Telemetry.java for how this app's
# own manual instrumentation defers to the agent (when present) instead of fighting it for
# GlobalOpenTelemetry.
ARG OTEL_JAVAAGENT_VERSION=2.31.1
RUN curl -fsSL -o /opt/opentelemetry-javaagent.jar \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVAAGENT_VERSION}/opentelemetry-javaagent.jar"

# Application deployment. Runs as a non-root user rather than root. /output is created here
# (rather than left for the OUTPUT_DIR volume mount to create) and owned by that user, so Docker
# copies that ownership into a *named* volume mounted over it (see docker-compose.yml) - an empty
# named volume is otherwise initialized root-owned, which this non-root user can't write to.
RUN mkdir /opt/app /output \
    && groupadd --system app && useradd --system --gid app --home /opt/app app \
    && chown app:app /opt/app /output /opt/opentelemetry-javaagent.jar
COPY ./target/local-report-executor.jar /opt/app
# otel-extension.jar (built by the sibling otel-extension module, staged here by
# local-report-executor's own pom.xml - see its maven-dependency-plugin config) is loaded
# directly by the agent below as an extension, into the agent's own isolated classloader that
# can't see local-report-executor.jar's copy - see otel-extension's class-level docs.
COPY ./target/otel-extension.jar /opt/app
USER app

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
  CMD curl -f http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["java", \
    "-javaagent:/opt/opentelemetry-javaagent.jar", \
    "-Dotel.javaagent.extensions=/opt/app/otel-extension.jar", \
    "-jar", "/opt/app/local-report-executor.jar"]
