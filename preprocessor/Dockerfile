FROM eclipse-temurin:25 AS jre-build

# Create a custom Java runtime
RUN $JAVA_HOME/bin/jlink \
         --add-modules java.base \
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

# Application deployment
RUN mkdir /opt/app /project \
    && groupadd --system app && useradd --system --gid app --home /project app \
    && chown app:app /opt/app /project
COPY ./target/preprocessor.jar /opt/app
USER app
WORKDIR /project
ENTRYPOINT ["java", "-jar", "/opt/app/preprocessor.jar"]
CMD ["--help"]