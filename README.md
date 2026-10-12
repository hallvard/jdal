# jdal

A pure-Java (Java 26, Maven 4) port of GDAL's vector side. See [docs/FEASIBILITY.md](docs/FEASIBILITY.md) for the
assessment and [CONSTRAINTS.md](CONSTRAINTS.md) for the rules.

## Modules

| Module | Content |
|---|---|
| `jdal-geom` | immutable geometry records (Point ... GeometryCollection), WKT/WKB with GDAL-exact number formatting |
| `jdal-srs` | `SpatialReference`, `CoordinateTransformation` (proj4j) |
| `jdal-feature` | `FieldDefn`, `FeatureType`, immutable `Feature`, value coercion |
| `jdal-core` | `Layer`/`Dataset`/`Driver`, `Jdal.open/create` (ServiceLoader), `Query`, memory datasets |
| `jdal-config` | options (`GDAL_HTTP_*`, `CPL_ZIP_ENCODING`, ...) from system properties, environment variables and a `.env` file in the current directory |
| `jdal-json` | minimal JSON: tolerant parser, streaming `JsonReader`, strict writer; no dependencies |
| `jdal-vsi` | virtual file system behind a handler SPI (`ServiceLoader`): local files, `/vsimem/`, `/vsigzip/`, `/vsizip/`, `/vsisubfile/`, `/vsistdin/`, `/vsistdout/`; see docs/VSI-DESIGN.md |
| `jdal-http` | HTTP client setup shared by the web based modules (GDAL_HTTP_* options, retries, authentication headers) |
| `jdal-gml` | GML geometries, features and application schemas (StAX), and the GML file driver |
| `jdal-ops` | exact spatial predicates on JTS for spatial filters (service `GeometryOps`) |
| `jdal-filter` | OGR SQL WHERE clauses as CQL2 text and OGC filter encoding, for pushing filters to web services |
| `jdal-driver-oapif` | OGC API - Features client |
| `jdal-driver-wfs` | WFS 1.0/1.1/2.0 client |
| `jdal-vsi-curl` | `/vsicurl/`: files on HTTP(S) servers read with range requests |
| `jdal-driver-geojson` | GeoJSON read (streaming, two pass) and write |
| `jdal-driver-csv` | CSV: separator detection, .csvt, type autodetection, WKT/XY geometry, append |
| `jdal-driver-shapefile` | ESRI Shapefile (.shp/.shx/.dbf/.prj/.cpg): read, write, update, delete, add field |
| `jdal-driver-gpkg` | GeoPackage (sqlite-jdbc) |
| `jdal-driver-postgis` | PostgreSQL/PostGIS (pgjdbc) |
| `jdal-utils` | `Translate` (ogr2ogr core), `Info` (ogrinfo format) |
| `jdal-cli` | `jdal info|convert|drivers` (aesh) |
| `jdal-testkit` | `@Port` annotation and access to GDAL's `autotest` data |

## Build and test

    mvn install                      # needs ~/git/gdal/autotest, or -Dgdal.autotest=/path/to/autotest
    JDAL_TEST_PG="PG:host=localhost dbname=jdal user=postgres password=postgres" mvn -pl jdal-driver-postgis test

Tests ported from GDAL carry `@Port("autotest/...::test_name")`.

## For coding agents

`AGENTS.md` describes how to build, test and extend the project for coding agents (Claude Code,
GitHub Copilot and others read it).

## Using jdal

`bin/jdal` runs the command line from a checkout (`mvn -q package -DskipTests` first):
`bin/jdal info FILE`, `bin/jdal convert [options] SRC DST`, `bin/jdal drivers`, or `bin/jdal` alone
for the interactive mode. With jbang: `jbang jdal@hallvard/jdal info FILE` (a self-contained jar from the latest release) or
`jbang app install --name jdal jdal@hallvard/jdal` for a `jdal` command; see `docs/JBANG.md`.
A usage guide for the command line and the Java API, written as a Claude
Code skill, is in `.claude/skills/jdal/SKILL.md`; ask Claude how to do something with jdal and it
uses that file. Its code examples are tested in `jdal-cli/.../ApiGuideTest.java`.

## Status

See "Status" in docs/FEASIBILITY.md section 11.
