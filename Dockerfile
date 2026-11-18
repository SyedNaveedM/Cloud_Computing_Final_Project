FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE=controller
WORKDIR /build
COPY pom.xml ./
COPY common ./common
COPY controller ./controller
COPY worker ./worker
RUN mvn -B -pl ${MODULE} -am -DskipTests package

FROM eclipse-temurin:21-jre-jammy
ARG MODULE=controller
WORKDIR /app
RUN groupadd --gid 10001 objectstore && useradd --uid 10001 --gid objectstore --no-create-home objectstore \
    && mkdir -p /data && chown objectstore:objectstore /data
COPY --from=build /build/${MODULE}/target/${MODULE}-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
