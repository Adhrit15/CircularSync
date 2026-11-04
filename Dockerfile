# CircularSync container: builds from source, then runs the daemon and web
# portal as a non-root user. Works on any Docker host (Render, Railway, Fly.io,
# a VPS). The platform's $PORT is honoured; the admin key comes from the
# CIRCULARSYNC_ADMIN_KEY environment variable (a random one is generated and
# printed in the logs if it is not set).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY tools ./tools
# Downloads PDFBox from Maven Central and verifies the pinned SHA-256 checksums.
RUN java tools/FetchLibs.java lib
COPY src ./src
RUN find src -name '*.java' > sources.txt \
 && javac --release 17 -encoding UTF-8 -cp "lib/*" -d out @sources.txt

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 --home /app circularsync
COPY --from=build /app/out ./out
COPY --from=build /app/lib ./lib
COPY web ./web
COPY demo ./demo
COPY incoming_notices ./incoming_notices
RUN mkdir -p audit_logs && chown -R circularsync:circularsync incoming_notices audit_logs
USER circularsync

ENV PORT=8080
EXPOSE 8080
CMD ["sh", "-c", "exec java -Djava.awt.headless=true -Xmx384m -cp 'out:lib/*' com.circularsync.main.Main web --host 0.0.0.0 --port \"$PORT\" --public --trust-proxy --no-browser"]
