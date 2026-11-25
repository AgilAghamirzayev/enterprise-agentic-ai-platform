FROM eclipse-temurin:21-jre-jammy
ARG MODULE
WORKDIR /opt/platform
RUN apt-get update && apt-get install -y --no-install-recommends curl
COPY ${MODULE}/target/${MODULE}-0.1.0.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
