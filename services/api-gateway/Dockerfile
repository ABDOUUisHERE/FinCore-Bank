FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY libraries ./libraries
COPY services ./services
RUN --mount=type=cache,id=fincore-maven,target=/root/.m2,sharing=locked mvn -B -ntp -pl services/api-gateway -am package -DskipTests
FROM eclipse-temurin:21.0.12.1_1-jre-alpine-3.24
RUN addgroup -S fincore && adduser -S fincore -G fincore
WORKDIR /app
COPY --from=build /build/services/api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar app.jar
USER fincore
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]
