# Hugging Face Space image for the Jsonweave playground.
#
# The Space repo holds only this file and README.md: the app is built from source at
# github.com/curioworks/jsonweave, so the Space never carries a second copy of the code.
# Rebuild the Space to pick up new commits (or set JSONWEAVE_REF to a tag).

FROM maven:3.9-eclipse-temurin-21 AS build
ARG JSONWEAVE_REF=main
RUN git clone --depth 1 --branch "$JSONWEAVE_REF" \
      https://github.com/curioworks/jsonweave.git /src
WORKDIR /src
# Pick the shaded runnable jar explicitly; a -Prelease build also emits
# -sources/-javadoc jars that a bare wildcard would match.
RUN mvn -B -pl jsonweave-playground -am package -DskipTests \
 && cp "$(find jsonweave-playground/target -maxdepth 1 -name 'jsonweave-playground-*.jar' \
          ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -1)" /playground.jar

FROM eclipse-temurin:21-jre
# Spaces run containers as uid 1000.
RUN useradd -m -u 1000 user
USER user
WORKDIR /home/user/app
COPY --from=build --chown=user:user /playground.jar app.jar

# Public instance: #mvel executes arbitrary Java and cannot be sandboxed, so it stays
# OFF. #js runs under GraalJS with host access disabled and stays on.
# Spaces route to app_port (7860 in README.md); the server reads $PORT.
ENV JSONWEAVE_MVEL=false \
    JSONWEAVE_JS=true \
    PORT=7860 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

EXPOSE 7860
CMD ["java", "-jar", "app.jar"]
