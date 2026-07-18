# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Resolve dependencies in their own layer so source edits don't refetch the world.
COPY pom.xml .
COPY jsonweave-core/pom.xml       jsonweave-core/
COPY jsonweave-mvel/pom.xml       jsonweave-mvel/
COPY jsonweave-js/pom.xml         jsonweave-js/
COPY jsonweave-cli/pom.xml        jsonweave-cli/
COPY jsonweave-playground/pom.xml jsonweave-playground/
RUN mvn -B -pl jsonweave-playground -am dependency:go-offline -DskipTests

COPY jsonweave-core/src       jsonweave-core/src
COPY jsonweave-mvel/src       jsonweave-mvel/src
COPY jsonweave-js/src         jsonweave-js/src
COPY jsonweave-playground/src jsonweave-playground/src
# Pick the shaded runnable jar explicitly: a -Prelease build also drops -sources
# and -javadoc jars here, and a bare wildcard would match all three.
RUN mvn -B -pl jsonweave-playground -am package -DskipTests \
 && cp "$(find jsonweave-playground/target -maxdepth 1 -name 'jsonweave-playground-*.jar' \
          ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -1)" /playground.jar

# ---- run ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /playground.jar app.jar

# Public deployment: #mvel executes arbitrary Java and is unsandboxable, so it stays
# OFF here. #js (GraalJS) runs sandboxed and is left on. Override only for local use.
ENV JSONWEAVE_MVEL=false \
    JSONWEAVE_JS=true \
    PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

# Run as a non-root user.
RUN useradd --system --uid 10001 weave && chown -R weave /app
USER weave

EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
