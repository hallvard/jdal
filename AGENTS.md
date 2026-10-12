# AGENTS.md

jdal is a pure-Java port of GDAL/OGR's vector side (Java 26, Maven 4, no native code). GDAL's own
`autotest` is the referee: behaviour and messages follow GDAL, and tests are ported from it.

## Build and test

```
mvn -q install -DskipTests                   # all modules; installs to ~/.m2 (needed by jbang)
mvn -q -pl jdal-gml -am verify               # one module: ALWAYS with -am, otherwise sibling modules come
                                             # from ~/.m2 and may be stale
mvn verify                                   # everything (~3 minutes); run it in the background
JDAL_TEST_PG="PG:host=localhost port=55432 dbname=jdal user=postgres password=postgres" mvn verify
                                             # also runs the PostGIS integration test (Docker container jdal-pg)
bin/jdal drivers                             # run the CLI from a checkout (jbang: docs/JBANG.md)
./mvnw ...                                   # Maven wrapper (Maven 4), used by the release workflow
```

GDAL's sources and test data are expected in `~/git/gdal` (override with `-Dgdal.autotest=...`);
tests that need them are skipped when they are missing. Surefire output is quiet: read
`*/target/surefire-reports/*.txt` for the real counts.

## Rules (see `CONSTRAINTS.md`)

- No GDAL/GeoTools bindings, no FFM into GDAL, no shelling out to GDAL tools (enforced by `maven-enforcer-plugin`).
- Allowed libraries: JTS, proj4j, sqlite-jdbc, pgjdbc, aesh, JUnit. JSON is our own `jdal-json`; HTTP is
  `java.net.http` through `jdal-http`; XML is the JDK's StAX/DOM. Do not add a dependency without
  considering alternatives and a note in `CONSTRAINTS.md`.
- Immutable records and sealed types, Java numbers, `java.time`. Small Maven modules with one-way
  dependencies; formats are plugged in with `ServiceLoader` (`META-INF/services`).
- Never weaken a ported test to make it pass. Tag ported tests `@Port("autotest/ogr/ogr_x.py::test_name")`.
  Gaps go into the status table in `docs/FEASIBILITY.md` (section 11), not into silence.

## Conventions

- Drivers read and write through `Vsi.global()` (`jdal-vsi`), never `java.nio.file` directly, so that
  `/vsimem/`, zips, gzip and URLs work. Tests use `Autotest.vsimem()` for scratch files.
- Settings (`GDAL_HTTP_*`, `CPL_*`, ...) come from `Config.global()` (system property, environment,
  `.env`), not from `System.getenv`.
- Errors are `JdalException` with GDAL's wording; warnings go through `Diagnostics.warn` (tests capture
  them with `Diagnostics.capture`).
- A layer filters with `Query` (bbox, attribute filter in OGR SQL syntax, spatial predicate, limit). A driver
  that pushes part of a query to a server or database must still be correct if the server ignores it:
  apply the whole query again locally, and fall back when the server refuses.
- Web drivers are tested against an in-process `com.sun.net.httpserver` with canned responses, not the network.
- Code is formatted with google-java-format (not enforced; checkstyle runs in `verify` and reports warnings).
- A new module goes in the root `pom.xml` (`<modules>` and `dependencyManagement`) and in the status table
  and README module list.

## Where things are

- `README.md`: modules and how to build. `docs/FEASIBILITY.md`: concepts, hard problems, and the status
  table of what is done and not done. `docs/VSI-DESIGN.md`: virtual file system. `docs/JBANG.md`: jbang.
- **Using jdal** (CLI recipes, API examples, error messages): read `.claude/skills/jdal/SKILL.md`. It is
  plain markdown for any agent; its examples are tested in `jdal-cli/src/test/java/dev/jdal/cli/ApiGuideTest.java`
  and must be kept in step with the CLI and API.

## Careful with

- `jbang catalog add` without `--global` writes into `jbang-catalog.json` of the current directory.
- Build output (`target/`, `.mvn/target/`) is ignored; never commit it.
- `sed -i` on macOS needs `sed -i ''` and has no `\b`; use perl or python for edits.
- The Docker container `jdal-pg` (PostGIS, port 55432) is for tests; do not leave tables behind in it.
