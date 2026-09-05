FROM eclipse-temurin:25 AS jre-build

# Fill/export touches AWT (font metrics/layout) and JDBC, unlike the
# preprocessor's compile-only java.base image - verified empirically against
# the modules actually exercised at runtime.
RUN $JAVA_HOME/bin/jlink \
         --add-modules java.base,java.desktop,java.sql,java.naming,java.xml,java.logging,java.management \
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
# curl is here only for the HEALTHCHECK below.
RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core curl \
    && rm -rf /var/lib/apt/lists/*

# Application deployment. Runs as a non-root user rather than root. /output is created here
# (rather than left for the OUTPUT_DIR volume mount to create) and owned by that user, so Docker
# copies that ownership into a *named* volume mounted over it (see docker-compose.yml) - an empty
# named volume is otherwise initialized root-owned, which this non-root user can't write to.
RUN mkdir /opt/app /output \
    && groupadd --system app && useradd --system --gid app --home /opt/app app \
    && chown app:app /opt/app /output
COPY ./target/local-report-executor.jar /opt/app
USER app

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
  CMD curl -f http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["java", "-jar", "/opt/app/local-report-executor.jar"]
