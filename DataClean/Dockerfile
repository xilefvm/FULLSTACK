FROM node:24-bookworm-slim AS frontend
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:27-jdk AS build
ARG MAVEN_VERSION=3.9.12
ADD https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz /tmp/maven.tar.gz
RUN tar -xzf /tmp/maven.tar.gz -C /opt
WORKDIR /build
COPY pom.xml .
COPY src src
COPY --from=frontend /web/dist/dataclean/browser/ src/main/resources/static/
RUN /opt/apache-maven-${MAVEN_VERSION}/bin/mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:27-jre
WORKDIR /app
COPY --from=build /build/target/dataclean-1.0.0.jar /app/dataclean.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/dataclean.jar"]
