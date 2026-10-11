FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B clean package
RUN mvn -B dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=/app/browser-deps
FROM eclipse-temurin:21-jre-noble
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/playwright
RUN --mount=type=bind,from=build,source=/app/browser-deps,target=/tmp/browser-deps,readonly \
    java -cp '/tmp/browser-deps/*' com.microsoft.playwright.CLI install --with-deps chromium \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home app
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
WORKDIR /app
COPY --from=build /app/target/video-parser-1.0.0.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["sh","-c","if [ \"$(id -u)\" = 0 ]; then exec runuser -u app -- java -jar /app/app.jar; else exec java -jar /app/app.jar; fi"]
