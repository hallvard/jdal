# jdal constraints

1. No GDAL, GeoTools or other geospatial bindings, no JNI/FFM into libgdal, no shelling out to GDAL tools (enforced by `maven-enforcer-plugin`).
2. Allowed libraries: JTS (topology), proj4j (CRS), sqlite-jdbc, pgjdbc, aesh (CLI), JUnit (tests). JSON is our own minimal `jdal-json` (tolerant parser, streaming reader, strict writer); no Jackson. Others require a note here.
3. Java 26, records/sealed types/immutability. Java numbers (`int`, `long`, `double`), `java.time` for temporal fields.
4. Modularity over JPMS: small Maven modules with one-way dependencies; drivers are discovered with `ServiceLoader`.
5. Every ported GDAL test carries `@Port("autotest/...::test_name")`. A test is never weakened to pass; unsupported ones go to `docs/known-gaps.md`.
