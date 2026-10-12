# jdal — feasibility of a pure-Java port of GDAL's vector ("OGR") side

Based on GDAL 3.14.0 (`~/git/gdal`, commit 5154115825). Target: Java 26, Maven 4.
Line counts are C++ `.cpp/.h` lines, approximate.

## 0. Method: the Vidocq model

This experiment follows https://vidocq.dev/posts/the-story-of-vidocq/ (a Jakarta EE / MicroProfile runtime built with an AI agent, judged by the official TCKs). Their recipe maps onto jdal as follows.

| Vidocq | jdal |
|---|---|
| Hard constraints given up front (zero external deps, no bytecode tricks, strict JPMS) | `CONSTRAINTS.md`, enforced by the build: Maven Enforcer `bannedDependencies` with an allowlist (JTS, proj4j and whatever we approve), no JNI/FFM into libgdal, no shelling out to `ogr2ogr`, one `module-info.java` per module |
| Human-reviewed plan before coding | this document, plus a short design note per module |
| Guided execution, section by section | the module and phase order in sections 3 and 8 |
| TCK as an inflexible referee, with no disabled tests | `autotest` ported test by test, with a public scoreboard (ported / passing / known-gap) and a rule that a test is never weakened to go green |
| Expected curve: ~40% on first run, ~70% after a day, long tail | track the pass-rate curve per module. Budget for the long tail (formatting, edge-case coercions, error text) to cost as much as everything before it |
| Pitfall: the agent cheated on constraints (bytecode manipulation) | the likely analogue is quietly depending on a native library, or special-casing a test's expected value. Counter with the enforcer rules, a periodic audit, and reviewing every test diff |

Differences from the TCK case that matter:

1. **The GDAL tests are not a language-neutral black-box suite.** They are Python written against the `osgeo` bindings (plus gtest C++). The "TCK" therefore has to be ported, and its quality depends on the port. Mitigation: keep a thin `jdal-compat` layer with GDAL-shaped names (`Open`, `GetLayer`, `GetNextFeature`, `ExportToWkt`) so a Python test translates almost line by line. That strengthens the earlier advice to keep names recognisable. The idiomatic API sits underneath and gets its own tests.
2. **The oracle is weaker.** A TCK is exhaustive by design. GDAL's suite is a regression suite, with uneven coverage and many tests that need a live server or a specific build option. Expect to skip a large share and to have to add differential tests against a real GDAL (Docker) where upstream is silent.
3. **Licensing differs.** Vidocq is EUPL/EPL/GPL. GDAL is MIT, so a port must keep the notices. Also see the `AGENTS.md` note about upstream not accepting AI-written contributions.

## 1. Verdict

Feasible, with a deliberately scoped definition of "port":

- **The core model + a handful of file-based drivers + OGR SQL + the `ogr2ogr`/`ogrinfo` logic: realistic.** About 60–80k lines of C++ carry real semantics. The Java equivalent should come out noticeably smaller.
- **The long tail of drivers (about 70 of them): do not port.** Pick by value. Many need database servers, native libraries or proprietary SDKs, and are out of scope for "pure Java" anyway.
- **Four areas are the real risk**, covered in section 5:
  1. Spatial reference (SRS) and coordinate transformation: PROJ is the hard dependency.
  2. Curve and surface geometries, which JTS lacks.
  3. SQLite, which GeoPackage and the SQLite dialect depend on.
  4. The `/vsi*` virtual file system and `CPLError` global state.

The practical route is **core first, with a conformance harness built from GDAL's own test data**. That harness checks behaviour continuously, as you asked.

Size of the source being ported:

| Area | LOC |
|---|---|
| `ogr/` (core: geometry, feature, SRS, SQL, GeoJSON/GML/KML geometry codecs) | ~150k (of which `mitab_bounds.cpp` alone is 20k of tables) |
| `ogr/ogrsf_frmts/generic` (layer/datasource framework, SQL layer, union/warped/editable layers) | ~30k |
| `ogr/ogrsf_frmts/*` drivers (~76 of them) | ~600k |
| `apps/ogr2ogr_lib.cpp`, `ogrinfo_lib.cpp`, `ogrlineref`, `gdalalg_vector_*` | ~35k |
| Tests: `autotest/ogr` (124 py files, 162k lines), `autotest/osr`, `autotest/utilities` | large |

## 2. Main concepts in the feature part

### 2.1 Layered architecture (bottom to top)

1. **CPL (`port/`)** is the C portability layer.
   - `CPLError` is a global/thread-local error stack.
   - `CPLString`/`CSL` string lists and key=value option lists are used everywhere.
   - `CPLGetConfigOption` is the global configuration.
   - `CPLJSON` and XML are small DOMs.
   - **VSI** is a virtual file system with `/vsimem/`, `/vsizip/`, `/vsigzip/`, `/vsicurl/`, `/vsis3/` and so on, plus a plugin mechanism.
   - Also here: threads, mutexes, quad tree, CSV lookup, charset recoding, progress callbacks.
2. **Geometry model (`ogr_geometry.h`)** is one OO hierarchy for the OGC/ISO SQL/MM geometries:
   - Geometry
     - Point
     - Curve → SimpleCurve (LineString, LinearRing, CircularString), CompoundCurve
     - Surface → CurvePolygon → Polygon → Triangle
     - GeometryCollection → MultiPoint, MultiCurve, MultiLineString, MultiSurface, MultiPolygon, PolyhedralSurface → TIN
   - Every type has a 2D, Z, M and ZM flavour (wkb codes 1–17, +1000, +2000, +3000) plus the 2.5D legacy flag.
   - I/O: WKB (ISO and EWKB), WKT, GML, KML, GeoJSON.
   - Operations:
     - Some are native: envelope, length, area, segmentize, linear-to-curve and curve-to-linear conversion, force-to-type, polygon organisation, `OGRPolygonContains` shortcuts.
     - Most topological operations delegate to GEOS: buffer, union, intersection, simplify, make-valid, convex/concave hull, Delaunay, polygonize and others.
3. **Spatial reference (`ogrspatialreference.cpp`, `ogrct.cpp`)** is a thin OO facade over PROJ.
   - `OGRSpatialReference` handles WKT1/WKT2/PROJ strings/EPSG/ESRI/URN/URL, axis order and data-axis mapping, and authority lookups.
   - `OGRCoordinateTransformation` handles bulk transforms, area-of-use ranking and antimeridian wrapping.
   - Other formats (`ogr_srs_*.cpp`): ESRI, ERM, ISIS, OZI, PCI, USGS, MITAB, Panorama, XML, CF-1.
4. **Feature model (`ogr_feature.h`)**:
   - `OGRFieldDefn` (types: Integer, Integer64, Real, String, Binary, Date, Time, DateTime, plus the list types; subtypes Boolean, Int16, Float32, JSON, UUID; width, precision, default, nullability, unique, alternative name, comment, domain).
   - `OGRGeomFieldDefn`, `OGRFeatureDefn` (schema), `OGRFeature` (FID, field values with a `OGRField` union, N geometries, style string, native data).
   - Field domains (coded/range/glob).
   - `OGRFieldType` conversion rules, `OGRFeature::SetField` coercions, unset/null/set tri-state.
5. **Layer / DataSource / Driver (`ogrsf_frmts/generic`)**:
   - `OGRLayer` is a pull cursor: `ResetReading`/`GetNextFeature`, a spatial filter, an attribute filter (OGR SQL `WHERE`) and random access by FID. It also covers editing, schema alteration, transactions and capabilities (`TestCapability(OLC*)`), plus an Arrow batch stream.
   - `GDALDataset` is shared with raster. For vector it adds `GetLayer`, `CreateLayer`, `ExecuteSQL`, and `GDALOpenEx` with the `GDAL_OF_VECTOR` flag.
   - `GDALDriver` is a registry entry with identify/open/create plus metadata.
   - Layer wrappers (decorator, union, warped, pool, mutexed, editable, "layer with translate feature") are reusable building blocks, and `ogr_gensql` implements the SQL SELECT engine.
6. **OGR SQL (`swq*.cpp`, `swq_parser.y`, `ogr_gensql.cpp`)** is its own SQL dialect: SELECT with WHERE, JOIN, UNION ALL, ORDER BY, GROUP BY, DISTINCT, CAST, a function library and `OGR_GEOM_*` special fields. It is a Bison grammar plus a hand-written lexer and expression tree, about 12k lines in total. Separately, an SQLite dialect (`ExecuteSQL(..., "SQLITE")`) is implemented on top of SQLite virtual tables, and a native "OGRSQL" can use SQLite as a back end.
7. **Applications / library entry points**:
   - `ogr2ogr_lib.cpp` (8.9k lines) is the biggest single piece of logic: field mapping, reprojection, clipping, geometry-type forcing, FID handling, append/update/overwrite, spatial/attribute filters, time zone handling, coordinate precision and so on. The new `gdal vector …` algorithms (about 50) mostly compose layers and delegate to it.
   - `ogrinfo_lib.cpp` is the text/JSON schema and feature dumper.
   - Also `ogrlineref`, `ogrtindex` and `gdalalg_vector_*` (pipeline, buffer, clip, dissolve, and the rest).

### 2.2 Design traits that matter for a port

- Almost everything is **error-code-plus-global-error-stack** (`OGRErr`, `CPLError`). It does not use exceptions.
- **Ownership transfer** is explicit (`...Directly` methods, `unique_ptr`) and is replaced by the GC in Java. The aliasing risks move to mutability: C++ `clone()` discipline becomes a question of immutable versus mutable Java types.
- **Mutable, in-place geometry** is the norm (`addPoint`, `setCoordinateDimension`, `swapXY`, `transform`), and drivers rely on it heavily.
- **Capability probing** (`TestCapability`) and **config options** (`CPLGetConfigOption`, over 100 call sites in `ogr/*.cpp`, shape, geojson and gpkg alone) drive behaviour. Hundreds of `GDAL_*`/`OGR_*` options are part of the observable contract.
- **Lazy, streaming read** is mandatory. Shapefile, GeoJSON and GPKG use spatial indexes (`.qix`, `.sbn`, R*Tree) and incremental parsing so layers do not have to fit in memory.
- **Plugin drivers** are registered at runtime, so `ServiceLoader` is the natural equivalent.

## 3. Proposed module breakdown (Maven multi-module, Java 26)

Groupings are by dependency direction. Each module has its own JUnit 5 test tree, "ported" from the matching `autotest` and `cpp` tests.

| # | Module | Content | Maps to | Size est. | Main deps |
|---|---|---|---|---|---|
| 0 | `jdal-bom` / parent | Maven 4 build, java 26 toolchain, `--enable-preview` policy, Error Prone/NullAway, JPMS | – | – | – |
| 1 | `jdal-cpl` | `Options` (case-insensitive key=value), config registry, `CplJson` (thin wrapper over Jackson or JSON-P), `Progress`, charset recoding, quad tree, CSV lookup, warnings/errors sink | `port/` subset | ~6–10k | jackson-jr or Jackson |
| 2 | `jdal-vsi` | VFS: `java.nio.file.FileSystem` providers or a `Vsi` facade over `SeekableByteChannel`: `/vsimem/`, `/vsizip/`, `/vsigzip/`, `/vsitar/`, `/vsicurl/` (java.net.http), stdin/out | `cpl_vsil*` | ~8k | commons-compress or JDK zip |
| 3 | `jdal-geom` | Geometry types, `GeometryType` enum, envelope, WKB/WKT/GeoJSON/GML/KML codecs, `GeometryFactory` (force-to-*, polygon organise, arcs↔lines), validation helpers | `ogr/ogrgeometry*`, `ogr_wkb`, `ogr2*geometry`, `gml2ogrgeometry` | ~25k | JTS |
| 4 | `jdal-srs` | `SpatialReference`, WKT1/2, PROJ-string, EPSG DB, axis-order logic, `CoordinateTransformation`, ESRI/other legacy importers | `ogrspatialreference`, `ogrct`, `ogr_srs_*` | ~15k + data | proj4j + (see risk 5.1) |
| 5 | `jdal-feature` | `FieldDefn`, `GeomFieldDefn`, `FeatureDefn`, `Feature`, field domains, style strings, `FieldType` conversions | `ogr_feature.h`, `ogrfeature*`, `ogrfielddefn*` | ~15k | 1,3,4 |
| 6 | `jdal-sql` | OGR SQL parser (hand-written recursive descent or ANTLR 4), expression evaluator, function registry, `swq` select planner, `OgrSqlLayer` | `swq*`, `ogr_gensql`, `ogrfeaturequery` | ~12k | ANTLR (optional) |
| 7 | `jdal-core` | `Layer`, `Dataset`, `Driver`, `DriverRegistry` (`ServiceLoader`), `Capabilities`, wrapper layers (union, warped, editable, decorator, pool), attribute index, `OpenOptions`/`CreationOptions`, `open(…)` | `ogrsf_frmts/generic`, vector bits of `gcore` | ~15k | 1–6 |
| 8 | `jdal-driver-memory`, `-csv`, `-geojson` (+`-geojsonseq`, `-esrijson`, `-topojson`, `-jsonfg`), `-gml`, `-shape` (+dbf/qix/sbn), `-kml`/`-gpx`/`-georss`, `-gmt`, `-vrt`, `-pgdump` | Tier-1 drivers | `ogrsf_frmts/*` | ~3–10k each | jackson-core streaming, XML (StAX) |
| 9 | `jdal-driver-gpkg`, `-sqlite` | Tier-2, gated on the SQLite decision (5.3) | `gpkg`, `sqlite` | ~25k | SQLite option |
| 10 | `jdal-driver-flatgeobuf`, `-mvt`, `-pmtiles`, `-openfilegdb`, `-mitab`, `-parquet` | Tier-3, per-driver value-driven | – | – | flatbuffers, protobuf, parquet-java |
| 11 | `jdal-utils` | Library versions of `ogr2ogr`, `ogrinfo`, `ogrlineref`, `ogrtindex`, `gdal vector *` algorithms, with an `Algorithm` framework and a pipeline (`gdalalg_*`) | `apps/` | ~25k | all |
| 12 | `jdal-cli` | picocli front-end | `apps/*_bin.cpp` | small | picocli |
| 13 | `jdal-conformance` | The test harness (section 4): shared fixtures and golden-file runner | `autotest/` | – | JUnit 5, AssertJ |

Not in scope for this phase: raster, GNM, multidimensional, `gdal_alg`, SWIG bindings, the Arrow/Parquet C data interface at ABI level, and the database server drivers (PG, MySQL, MSSQL, Oracle, HANA, Mongo, Elastic, WFS, OAPIF, CARTO, NGW and others).

### Suggested driver tiers

- **Tier 1** (pure data-format, needed to prove the model): Memory, CSV, GeoJSON/GeoJSONSeq, Shapefile, GML (+KML/GPX/GeoRSS), GMT, VRT, PGDump. About 60k C++ lines, a good part of which is shapelib.
- **Tier 2** (high value, SQLite-dependent): GeoPackage, SQLite/SpatiaLite.
- **Tier 3** (binary formats, pure but big): FlatGeobuf, MVT, PMTiles, OpenFileGDB (30k lines), MapInfo TAB/MIF (43k lines, 20k of it a bounds table), Parquet/Arrow, DXF, DGN, AVC, OSM, SOSI, S-57, NTF, TIGER.
- **Skip**: anything needing a server or a proprietary SDK (FileGDB SDK, DWG, Oracle OCI, HANA, ODBC, libkml), plus most remote services.

## 4. Test strategy ("port the tests and keep them green")

There is a large body of existing tests, and **no GDAL installation on this machine** (`ogrinfo`, `ogr2ogr` and the Python `osgeo` module are not installed). That drives the plan.

1. **Reuse the data, do not copy it.** `autotest/ogr/data` is 33 MB with one subfolder per driver. The conformance module takes the data directory as a system property (`-Dgdal.autotest=~/git/gdal/autotest`), or via a git submodule or a Maven-time fetch. That avoids licence and size problems and keeps the data up to date.
2. **Test porting is by test file, mirrored name.** For example, `ogr_geom.py` (122 tests) becomes `GeomTest.java`, `ogr_feature.py` becomes `FeatureTest`, `ogr_sql_rfc28.py` becomes `SqlRfc28Test`, `ogr_shape.py` becomes `ShapeDriverTest`. Each ported test gets a traceability annotation, `@Port("autotest/ogr/ogr_geom.py::test_ogr_geom_area")`, so a report can show per-file and per-test coverage (ported / skipped-with-reason / not-yet).
3. **Ordering by dependency**, which doubles as the milestone plan:
   - `cpp/test_ogr_wkb.cpp`, `ogr_wkbwkt_geom`, `ogr_geom`, `ogr_factory` → geom
   - `ogr_feature`, `ogr_basic_test`, `ogr_rfc41` → feature
   - `ogr_mem`, `ogr_sql_*`, `test_ogr_swq.cpp` → core and sql
   - then drivers: CSV, GeoJSON, Shape, GML, GPKG
   - `test_ogr2ogr*`, `test_ogrinfo*`, `test_gdalalg_vector_*` → utils
   - `osr/*` → srs (parallel track, gated on 5.1)
4. **Differential testing.** Because the Python tests are mostly "assert expected output", they port well. But a large part of GDAL's behaviour is only captured as reference files (`autotest/utilities/ref_data`, `.json` info dumps). Where the tests compare against hard-coded text (for example `ogrinfo` output), port the expected text verbatim. For the rest, a **golden-file generator** can be built later, but that needs a real GDAL, for example via Docker `ghcr.io/osgeo/gdal`. Treat that as optional, since the data files and assertions already in the repo are the contract.
5. **Test tags and an expected-failure ledger**: a `known-gaps.yaml` lists each unported or skipped test with a reason, so "green" always means "all tests we claim to support pass".
6. **Fuzz-type regression**: `fuzzers/` and `autotest/ogr/data/*/` contain many malformed-input cases. Port them as "must throw a typed exception, must not hang" tests (a JUnit `assertTimeout`).
7. **Test porting is mechanical but not free**: about 160k lines of Python for `ogr` alone. Most of it covers drivers that will not be ported; the core files (`ogr_geom`, `ogr_feature`, `ogr_basic_test`, `ogr_mem`, `ogr_sql_*`, `ogr_csv`, `ogr_geojson`, `ogr_shape`, `ogr_gml*`, `ogr_gpkg`, `osr_*`) total roughly 60k lines.

## 5. Things that are not easily translated, and the proposal for each

### 5.1 Coordinate reference systems (highest risk)
GDAL's SRS layer is almost entirely a facade over **PROJ**, which provides the EPSG database (SQLite `proj.db`, about 9 MB), WKT2 parsing, PROJJSON, datum-ensemble handling, authority lookups, grid-shift support (GeoTIFF/NTv2 grids, `/proj_grids`), area-of-use ranking and operation selection. **proj4j** is a much smaller PROJ.4-style library. It does not provide WKT2, the EPSG database, operation selection, PROJJSON or most of `OGRSpatialReference`'s API surface (`FindMatches`, `AutoIdentifyEPSG`, `GetAuthorityCode`, `StripVertical`, `SetTOWGS84` and others). It also has no WKT parsing for CRSs beyond limited support (check the exact version).

Recommendation: split the SRS module into
- an **interface** (`SpatialReference`, `CoordinateTransformation`) that drivers depend on, and
- an **implementation** that starts with proj4j (EPSG codes, `+proj` strings, a WKT1 parser written in-house on top of a tiny WKT tree), and lets us grow towards WKT2 and PROJJSON as test demand appears.

The port of `ogr_srsnode.cpp` (WKT tree) is straightforward and valuable. Measure early: of the `osr/*.py` tests, most need PROJ's database, so expect that a large fraction stays "known gap" for a long time. Good questions to answer in the first week are "which proj4j version and which CRSs do we need" and "can the EPSG registry be shipped as a resource (licensing: the EPSG dataset has terms of use)".

### 5.2 Curved and non-simple geometries versus JTS
JTS models Point, LineString, LinearRing, Polygon, Multi\* and GeometryCollection. It has **no** CircularString, CompoundCurve, CurvePolygon, MultiCurve, MultiSurface, PolyhedralSurface, TIN or Triangle. (As far as I know JTS core has no curve types. Verify against the release we pin.) OGR does **not** run GEOS on curves: it linearises first (`getLinearGeometry` / `getCurveGeometry` with `OGRGeometryFactory::curveFromLineString`), runs the GEOS operation on the linear version, and then optionally re-curves. Many drivers (GPKG, GML, DXF, MITAB, OpenFileGDB) read and write curves natively.

Recommendation: **own geometry model**, not JTS types as the public API. Use sealed interfaces and records, with JTS as a backend only for topological operations:

```java
sealed interface Geometry permits Point, Curve, Surface, GeometryCollection, PolyhedralSurface {}
sealed interface Curve extends Geometry permits LineString, LinearRing, CircularString, CompoundCurve {}
```

JTS is then used through an adapter (`toJts()`/`fromJts()`) for buffer, union, intersection, validity, simplify, hull, Delaunay and polygonize. That mirrors exactly what OGR does with GEOS, so the behaviour gap stays small. The cost is a conversion on each operation. For the common types the copy is cheap, and it can be optimised later if JTS's `CoordinateSequence` is made the backing store.

A second, related point: **M values.** JTS carries `CoordinateXYM`/`XYZM` but most JTS algorithms ignore M. OGR does the same (GEOS drops M), so the semantic gap is small.

### 5.3 SQLite (GeoPackage, SQLite/SpatiaLite, and `ExecuteSQL(…, "SQLITE")`)
There is no pure-Java SQLite. Options:
1. `sqlite-jdbc` (xerial). Not pure in the strict sense (it bundles per-platform native libraries), but it is the de facto choice and only needs a Maven dependency.
2. Java 26's FFM API (`java.lang.foreign`) binding to the system `libsqlite3`. Zero extra jars, but native.
3. A pure-Java SQLite file-format reader/writer or a SQL engine over the same on-disk format. These exist only as small experiments, and none would pass GPKG's R*Tree/trigger/SQL-function demands.

GeoPackage needs more than file storage: R-Tree virtual tables, triggers, the `ST_*` SQL functions (registered by GDAL as SQL functions), and transactions. 1,437 `sqlite3_*` call sites in gpkg alone.

Recommendation: be explicit that the GPKG/SQLite drivers are **"pure Java except the SQLite engine"**, put them in an optional module, and defer them until after Tier 1. Ask the user for the policy decision (see section 7).

### 5.4 The SQL dialects
The OGR SQL parser is a Bison grammar (`swq_parser.y`, about 1.2k lines). There is no Bison in Java. Port it as a **hand-written recursive-descent / Pratt parser** (the grammar is small and testable, and is documented in `doc/source/user/ogr_sql_dialect.rst`) or with ANTLR 4. The evaluator (`swq_op_general.cpp`, `swq_expr_node.cpp`) maps well to sealed-interface AST plus pattern-matching `switch`. The SQLite dialect depends on 5.3.

### 5.5 Numeric and bit-level semantics
- **Unsigned and widths:** OGR uses `GIntBig` (long), `GUInt16`, `GByte` and unions such as `OGRField` (a C union reused by type). Java has no unsigned types or unions. Use `long`/`int` with `Integer.toUnsignedLong`/`Short.toUnsignedInt`, and a `FieldValue` sealed interface rather than a union (see section 6).
- **Byte order / binary parsing:** `ByteBuffer` with explicit `ByteOrder` and `MemorySegment` (FFM, final in Java 22) are a good fit for WKB, shapefile, DBF, FlatGeobuf. This is one area where Java is as good as C++.
- **Float semantics:** NaN/Inf handling, `strtod`-style locale-independent parsing and the **exact text formatting of doubles** (`%.15g`, `CPLSPrintf`, GeoJSON coordinate precision, WKT precision) are surprisingly tested. Java's `Double.toString` and `String.format("%g")` differ from C. Plan a small, well-tested `NumberFormat` utility early (the `ogr_geom`/`ogr_wkbwkt_geom` tests will fail on formatting otherwise). `float16` appears in newer code.
- **Date/time:** `OGRField` stores Date/Time/DateTime as separate year/month/day/hour/minute/second(float)/TZFlag; TZFlag has 15-minute granularity with special values for "unknown". Map to `java.time` (`LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`) but keep a "timezone unknown" notion (`LocalDateTime` versus `OffsetDateTime` is a natural fit), and keep fractional seconds as float seconds where tests need it.

### 5.6 Global state, error handling and configuration
- `CPLError` / `CPLErrorReset` / `CPLGetLastErrorMsg` and `CPLPushErrorHandler`. Tests assert on warnings and error text. Java idiom: checked or unchecked exceptions for failures (`GdalException` hierarchy). Warnings need a **pluggable `DiagnosticSink`** (default: `System.Logger`) so tests can capture them. Do not try to preserve the global error stack, but keep a `ScopedValue`-bound sink for "error handler push/pop" semantics (ScopedValue is final in Java 25/26 and fits this well).
- `CPLSetConfigOption` / thread-local options. Use a `Config` object resolved via `ScopedValue` or explicit parameters, with env/system property fallback. Do not copy the string-keyed lookups wholesale: model the important ones as typed options records and keep a string escape hatch for compatibility.
- `OGRErr` return codes become exceptions, **but the semantics of "partial failure" (return code with partially modified output)** need case-by-case thought.

### 5.7 Memory model and mutability
OGR geometries are mutable; the idiom the user asked for (modern Java) points to immutable value types (records, defensive copies). Both are needed:
- **Immutable** `Geometry`/`CoordinateSequence` for the public API (thread safety, safe sharing between features and layers), plus
- **Builders** (`LineString.builder()`) for the driver read paths, to avoid O(n²) copying.

The cost: ogr2ogr-style in-place edits (`swapXY`, `transform`, `setCoordinateDimension`, `segmentize`) become map-style operations returning new objects. This is the biggest idiom change and should be decided up front. `OGRFeature` can be mutable (it is a record-like row buffer in GDAL), with geometry fields immutable.

Large-data performance: GDAL relies on contiguous `double*` arrays. Use `double[]` interleaved or separate `x[]`, `y[]`, `z[]`, `m[]` as the `CoordinateSequence` implementation; avoid per-point objects. The Vector API is still incubating, so do not depend on it.

### 5.8 I/O abstractions (VSI) and seeking
Drivers read through `VSIFile` handles: seekable, with `/vsimem` for in-memory test fixtures. `SeekableByteChannel` plus a `FileSystem` provider covers local, zip and memory (`jimfs` or an in-house one for tests). The `/vsicurl` family (range requests, caching, auth for S3/GCS/Azure) is a project of its own; keep only HTTP range reads with a cache initially. Drivers also rely on `GDALOpenInfo`-style **header sniffing** (identify without fully opening), which is easy to preserve.

### 5.9 Threading
Most OGR objects are explicitly not thread-safe. Java 26 virtual threads, `StructuredTaskScope` and `ScopedValue` make a better fit for the "mutexed layer" and "layer pool" wrappers than their C++ equivalents. Mostly a simplification, but test the pooling semantics.

### 5.10 Text encoding
Shapefile/DBF code pages (`.cpg`), CSV encodings, and `CPLRecode` between UTF-8, ISO-8859-1, CP1252, and so on. `java.nio.charset` covers it, with extra care for malformed input handling (GDAL replaces with `?` or `_` in some paths, and tests check it).

### 5.11 Generated or table-heavy code
- `mitab_bounds.cpp` (20k lines) is a data table; load from a resource file.
- `ogr_srs_esri_names.h`, `data/*.csv` (EPSG/ESRI/USGS/OZI datum tables) become resources.
- `swq_parser.cpp` (3k lines) is generated and must not be ported line-by-line.
- `gml2ogrgeometry` carries about 3.9k lines of GML 2/3/3.2 geometry reading with xlink and srsName handling. It ports well to StAX or DOM but is long.

### 5.12 Things GDAL does that Java makes easy
Maps, sealed types and exhaustive `switch` for the 17×4 geometry-type matrix (a `GeometryType` enum with Z/M flags, replacing `wkbFlatten`/`OGR_GT_*` integer arithmetic). `Optional` or sentinel records for "unset/null" field values. Streams or `Iterable<Feature>` for layer iteration (`try (var cursor = layer.features())`, auto-closing). Text blocks for the large inline expected strings in tests.

## 6. Modern-Java idioms to adopt

- `sealed interface` plus `record` plus pattern-matching `switch` for the geometry hierarchy, field values (`sealed interface FieldValue permits IntegerValue, Integer64Value, RealValue, StringValue, BinaryValue, DateValue, ..., IntegerList, ..., Unset, Null`), SQL AST nodes and filter expressions.
- `enum` for `FieldType`/`FieldSubType`/`GeometryType`, with capability flags as an `EnumSet<Capability>` (instead of `TestCapability("...")` strings; keep a string adapter for compatibility tests).
- `AutoCloseable` datasets and layers, `Stream`/`Iterator` cursors, `ServiceLoader` for drivers, JPMS modules per Maven module.
- Option bags as records with builders; `Optional`/`@Nullable` (JSpecify and NullAway) instead of null returns without contract.
- `ScopedValue` for ambient config and error sinks; virtual threads where concurrency is wanted.
- `MemorySegment`/`ByteBuffer` for binary I/O; `java.time` for temporal types; `java.text`/`HexFormat`/`Base64` for text helpers.
- Keep **names recognisable** (`Layer.getNextFeature()` as `layer.features()` plus `next()` is fine) so that ported tests stay readable and diffs against upstream remain easy to follow.

## 7. Dependencies (permitted / proposed)

| Need | Proposed | Notes |
|---|---|---|
| Topology | **JTS** (permitted) | buffer, union, validity, hull, Delaunay, polygonize, simplify, coverage ops (check which of `CoverageCleaner`, `CoverageSimplifier`, `CoverageValidator` exist in the version used) |
| CRS | **proj4j** (permitted) | see 5.1 |
| JSON | Jackson core (streaming `JsonParser`) | GeoJSON needs streaming for large files; GDAL's `ogrjsoncollectionstreamingparser` is its own streaming parser |
| XML | JDK StAX (`javax.xml.stream`) | GML, KML, GPX, GeoRSS; no extra dependency |
| Compression | JDK `java.util.zip`; commons-compress (tar, 7z, optional) | `/vsizip`, `/vsigzip`; use JDK-only first |
| CLI | picocli | optional |
| Binary formats | flatbuffers-java (FlatGeobuf), protobuf-java (MVT/PMTiles), parquet-java + Arrow (Parquet) | Tier 3 only |
| SQLite | xerial `sqlite-jdbc` or FFM | policy decision, see 5.3 |
| Tests | JUnit 5, AssertJ, `jimfs` (in-memory FS) | |
| Quality | Error Prone, NullAway / JSpecify | |

## 8. Phased plan

1. **Phase 0 (about 1 week):** Maven 4 multi-module skeleton, conformance harness, `@Port` traceability report, decisions on the open questions below, a proj4j capability probe.
2. **Phase 1 (core model):** `cpl`, `geom` (including WKT/WKB, curves, number formatting), `feature`; ported tests `ogr_geom`, `ogr_wkbwkt_geom`, `ogr_feature`, `ogr_basic_test`, `ogr_factory`, `test_ogr_wkb`.
3. **Phase 2 (framework):** `core` and `Memory` driver; `ogr_mem`; `sql` (RFC28) with `ogr_sql_rfc28`/`ogr_sql_test`; wrapper layers; `srs` interface and a minimum proj4j implementation.
4. **Phase 3 (Tier-1 drivers):** CSV, GeoJSON family, Shapefile (+index), GML, KML/GPX/GeoRSS, VRT.
5. **Phase 4 (utilities):** `ogrinfo`, `ogr2ogr` library with their tests; selected `gdal vector` algorithms.
6. **Phase 5 (optional):** GPKG/SQLite (policy dependent), FlatGeobuf, MVT, PMTiles, OpenFileGDB, MapInfo.

## 9. Open questions for you

1. **SQLite policy:** is `sqlite-jdbc` (bundled native) acceptable for GPKG, or must everything be strictly pure Java (in which case GPKG is out or needs a custom engine)?
2. **proj4j scope:** is the intent to accept a reduced CRS capability (EPSG/PROJ strings, no WKT2/PROJJSON/datum grids), or should the SRS module grow towards PROJ parity?
3. **API compatibility:** a new idiomatic API with GDAL names kept recognisable (recommended), or a mechanical mirror of `OGR*` classes for ease of syncing with upstream?
4. **Mutability:** immutable geometry plus builders (recommended) versus GDAL-style mutable geometry?
5. **Test data:** reference `~/git/gdal/autotest` in place, or vendor a subset into jdal?
6. **Licensing/provenance:** GDAL is MIT-style, so a port is fine if the licence notices are kept (see `LICENSE.TXT`, and note that the per-file headers need to be carried over). Upstream's `AGENTS.md` says the project does not accept AI-generated contributions, so this port should be a separate project and not aimed at upstreaming.
7. **Java 26 and preview features:** note that the installed JDK is 27 and Maven is 4.0.0-rc-7. Decide whether to build with `--release 26` and whether preview features are allowed.

## 10. Upcoming JDK features: JSON API and Valhalla value classes

Status as found on 2026-10-10 (secondary sources, verify before relying on it).

### 10.1 Simple JSON API ([JEP 540](https://openjdk.org/jeps/540), incubator)
- A small immutable DOM: `Json.parse`, a sealed `JsonValue` hierarchy, access errors as `JsonValueException`. Reported as proposed to target JDK 28 ([InfoQ](https://www.infoq.com/news/2026/08/java-native-json-api/)). It is not usable on the JDK 27 we build with.
- **Fit:** the GeoJSON driver is currently tree based, and `GeoJsonGeometry.read/write` is a natural pattern-matching `switch` over sealed `JsonValue`. It would remove the Jackson dependency for small and medium files, and the Jackson 3 `JsonNode` API we use is close in shape.
- **Limits:** it is DOM only. GDAL's GeoJSON driver streams large files (`ogrjsoncollectionstreamingparser`), which a DOM cannot do. We also need leniency GDAL has (trailing commas, seen in `ogr_geojson.py::test_ogr_geojson_37`), whereas the prototype deliberately rejects things like duplicate member names ([core-libs-dev](https://mail.openjdk.org/pipermail/core-libs-dev/2025-May/145905.html)).
- **Plan:** put JSON behind a small internal facade in the GeoJSON driver (read tree, write tree, optional streaming). Keep Jackson for now; reconsider once the API is final or at least out of incubation. Streaming reads stay on Jackson (or our own parser) regardless.

### 10.2 Value classes ([JEP 401](https://openjdk.org/jeps/401), preview)
- Targeted at JDK 28 (March 2027) as a *preview* feature; early-access builds exist ([jdk.java.net/valhalla](https://jdk.java.net/valhalla/)). With preview enabled several JDK classes (wrappers, `LocalDate`, ...) become value classes.
- **Candidates in jdal:** `Point` (a record of five doubles), `Envelope` (four doubles), `SpatialReference`-like small records, `Dimension`/`GeometryType` are enums and unaffected. These could become `value record`s: no identity, flattened in fields and, later, arrays.
- **What does not benefit:** `CoordinateSequence` is deliberately one interleaved `double[]`. Arrays keep identity and flattening of arrays of value objects is not part of JEP 401, so the interleaved layout stays the right design with or without Valhalla. `Feature`, `Polygon` etc. hold lists and are identity objects.
- **Risk:** preview class files require `--enable-preview` for every consumer, which is viral for a library. So: do not adopt before it is final.
- **Preparation that costs nothing now:** keep `Point`/`Envelope` free of identity use (no `==`, no `synchronized`, no identity maps; tests should use `assertEquals`, not `assertSame`), keep them immutable and `equals`-based. Revisit with a benchmark (reading a 1M-point layer) when JDK 28 EA builds are available.

## 11. Status

Decisions taken: sqlite-jdbc is fine for SQLite/GeoPackage; CRS scope is what proj4j covers; geometry and features are immutable records with plain Java numbers; modularity through Maven modules (JPMS not required); CLI with aesh; driver priority SQLite/GeoPackage, PostGIS, GeoJSON, then Shapefile and CSV. JSON is our own minimal module (no Jackson), see CONSTRAINTS.md.

| Area | State |
|---|---|
| geom: WKT/WKB (ISO, EWKB, legacy), GDAL-exact number formatting, polygon ring organiser | done. Ported `ogr_wkbwkt_geom.py` incl. all 214 broken-WKT cases and the WKB/WKT data pairs. Known gap: `POINT (nan nan)` is read as EMPTY |
| json | tolerant parser (BOM, comments, trailing commas, single quotes, NaN/Infinity, `.5`, duplicates), pull `JsonReader` for streaming, strict writer (rejects NaN/Infinity and lone surrogates). Shaped like JEP 540 so it can be swapped later |
| vsi | `jdal-vsi`: path-prefix handlers found with `ServiceLoader` (design in `VSI-DESIGN.md`), local disk and `/vsimem/`, `Vsi.global()` plus isolated registries for tests. CSV, GeoJSON and Shapefile read and write through it, and their tests now run on `/vsimem/`. Ported 20 `gcore/vsifile.py` tests and most of `vsizip.py`. Also `/vsigzip/`, `/vsizip/` (read and write), `/vsisubfile/`, `/vsistdin/`, `/vsistdout/`, and `jdal-vsi-curl` (`/vsicurl/` with range requests and a chunk cache); GeoPackage on virtual paths is staged in a temp file. Not done: `/vsitar/`, 7z, cloud handlers (S3, Azure, GCS), zip64 writing |
| srs | EPSG/PROJ-string/WKT-with-authority, proj4j transforms, identification of well-known WKT by name, WKT1 generation (OGC and ESRI flavour) for ~35 common EPSG codes (WGS84/NAD83/ETRS89 geographic and UTM, BNG, 3857). Not done: general WKT parsing/generation, WKT2, PROJJSON, datum grids |
| feature/core | schema, features, layers, memory driver, ServiceLoader registry, `Options`, `Diagnostics` (warnings, capturable in tests) |
| GeoJSON | streaming read (schema pass + feature pass, constant memory), strict write, CRS member, inline text. Ported `ogr_geojson.py` tests 2-8, 11 (layer CRS only), 37. Not done: GeoJSONSeq, ESRIJSON/TopoJSON, update mode, coordinate precision options |
| GeoPackage | read/write/update/delete/add field, SQL pass-through, GDAL-written samples read; SRS definitions written as WKT for known codes. Not done: R-tree, gpkg_extensions, tiles/attributes tables, field domains, curves |
| PostGIS | read/write/update/delete/add field, bbox pushdown, GiST index, SQL pass-through, layer options. Verified against PostGIS 3.5 (`imresamu/postgis:17-3.5`) with `PgIntegrationTest`. Not done: several geometry columns, geography, several schemas |
| CSV | read: separator autodetection with warning, RFC 4180 + GDAL tolerance, header detection, BOM, .csvt, `AUTODETECT_TYPE/WIDTH/SIZE_LIMIT`, `QUOTED_FIELDS_AS_STRING`, boolean words, WKT/WKB/X-Y-Z geometry options, .prj; write: `GEOMETRY`, `STRING_QUOTING`, `CREATE_CSVT`, `WRITE_BOM`, `SEPARATOR`, `LINEFORMAT`; append; directory datasets. Ported `ogr_csv.py` tests 2, 11, 17, 19-24, 32 (types/widths/limits), 33 and the separator, quoting and header tests. Not done: schema override, GeoJSON geometry column, GEOM_POSSIBLE_NAMES with several columns, `KEEP_SOURCE_COLUMNS`, VSI paths |
| Shapefile | read (.shp/.shx/.dbf/.prj/.cpg/LDID, PROMOTE_TO_MULTI, ring organisation, Z/M with M-usage detection, deleted records, bbox pushdown), write (all point/multipoint/line/polygon types with Z/M, orientation fixing, field name laundering, `ENCODING`, `SHPT`), update, delete, add field, append, directories. Ported `ogr_shape.py` tests 2, 3, 4, 10, 11, 12, 13, 15 and many type/attribute cases. Not done: .qix/.sbn spatial indexes, MultiPatch, REPACK/RESIZE, SQL (tests 5-9 need OGR SQL and geometry filters) |
| utils/cli | `Translate` (SRS, bbox, where predicate, layer selection, rename, layer/dataset/open options), `Info`; `jdal info|convert|drivers` with `--lco/--dsco/--oo` |
| OGR SQL | `jdal-sql`, mirrors swq: lexer/parser, binder (type checks, constant promotion), three-valued evaluator, SELECT (columns, CAST, special fields `FID`/`OGR_GEOMETRY`/`OGR_GEOM_WKT`/`OGR_GEOM_AREA`, ORDER BY, LIMIT/OFFSET, DISTINCT, aggregates with Kahan-Babuska sums, `EXCEPT`/`EXCLUDE`), JOIN, UNION ALL, DDL (`ALTER TABLE`, `DROP TABLE`, `DELLAYER:`), attribute filters via `Query.where` in all drivers, character (not byte) semantics. Dialect rule as GDAL: OGR SQL by default, native SQL for GPKG/PostGIS unless `OGRSQL` is requested. Ported all of `ogr_sql_rfc28.py` and most of `ogr_sql_test.py`. Gaps: `OGR_STYLE` fields, `SetNextByIndex`, `SQLITE` dialect, `CREATE INDEX` (throws), native WHERE pushdown (evaluated in Java), geometry spatial filter uses the envelope only |
| config | `jdal-config`: options are read from Java system properties, then environment variables, then a `.env` file in the current directory (`Properties` format, `export ` and one pair of quotes removed, backslashes doubled; read once at first use). Used by `jdal-http` (`GDAL_HTTP_*`), `CPL_ZIP_ENCODING`, `GML_EXPOSE_*`, `OGR_SQL_*`. Keep secrets such as `GDAL_HTTP_BEARER` out of version control |
| http | `jdal-http`: one HTTP client set up like GDAL's (`GDAL_HTTP_HEADERS`, `_USERAGENT`, `_TIMEOUT`, `_USERPWD`, `_BEARER`, retries with `_MAX_RETRY`, `_RETRY_DELAY`, `_RETRY_CODES`), used by `/vsicurl/` and the web drivers. Redirects are followed by the JDK client, which also keeps manually set headers on redirects to other hosts: do not put secrets in `GDAL_HTTP_HEADERS` for services that redirect elsewhere |
| GML | `jdal-gml`: StAX reader without DTD or external entities (billion laughs rejected), geometries of GML 2, 3.1 and 3.2 (Point, LineString, Curve with line segments, Polygon, Surface/PolygonPatch, Multi*, Envelope/Box, `pos`/`posList`/`coordinates`/`coord`, `srsDimension` also from the top envelope), axis order from `srsName` (URN and URL forms are north first for geographic CRS and a few projected ones, `EPSG:n` is x,y), feature ids as `gml_id` or `fid`, schema from an application schema (`.xsd` next to the file or in `xsi:schemaLocation`, includes, restrictions, choice of geometry kinds promoted to multi) or inferred from the data, and the GML file driver (layer per feature type, read only). Ported 10 `ogr_gml.py` tests on GDAL's own files. Not done: writing, `.gfs` files, xlink resolving, topology (`TopoCurve`), arcs and circles, nested properties kept as structure (they are flattened with `_`), the sub-feature and registry features, `gml:null` values |
| OGC API Features | `jdal-driver-oapif`: `OAPIF:` and `WFS3:` URLs, collections (with paging), a single collection URL, user query parameters kept, schema from the first page, features page by page following `next` links (relative links resolved), bbox, datetime, limit and page size sent to the server, count from `numberMatched`, extent from the collection, attribute filters sent as CQL2 text when the service lists the filter and cql2-text conformance classes (only properties the service names as queryable, strings compared with `CASEI()` as OGR SQL ignores case; a service that refuses the request is asked again without the filter; the filter is always applied here as well, so a service that ignores it still gives the right result; a limit is only sent with a complete filter; counts use `numberMatched`). Ported 8 of the 22 tests of `ogr_oapif.py` against an in-process server. Not done: CRS parameters (storage CRS, `bbox-crs`), OpenAPI probing, queryables/schema documents, STAC, writing |
| WFS | `jdal-driver-wfs`: WFS 1.0, 1.1 and 2.0 read only. Capabilities (also from a file), layers per feature type, schema from DescribeFeatureType (falling back to one feature), GetFeature streamed as GML, bbox in the axis order of the CRS, `MAXFEATURES`/`COUNT`, paging with `STARTINDEX`, `RESULTTYPE=hits` for counts, exception reports as errors, `gml_id`/`fid` field. Attribute filters are sent as OGC filter encoding (Filter 1.0/1.1 and FES 2.0 by WFS version; strings with `matchCase="false"`, so not for WFS 1.0; no BBOX together with FILTER, the spatial filter wins) with the same safeguards as for OGC API Features. Not done: GeoJSON/other output formats, transactions, joins, SORTBY, the XML description files |
| ops / spatial filters | `jdal-ops` (JTS 1.20, found through the `GeometryOps` service so the core stays free of it): exact `intersects`, `within`, `contains`, `disjoint`, `touches`, `crosses`, `overlaps`, `equals` against a prepared filter geometry. `Query.spatial` carries such a relation to a polygon (in the layer's CRS); `Query.candidateBox()` gives drivers the envelope to preselect with. Used by the default layer scan, Shapefile, GeoPackage (envelope prefilter, then exact), `executeSql` spatial filters, `jdal convert --spat_wkt`, and pushed down: PostGIS (`ST_Intersects`, `ST_Within`, ... with the geometry as WKB), OGC API Features (`S_INTERSECTS(...)` etc. with WKT, geometry property from the queryables, skipped when the request would be too long), WFS (OGC filter with GML geometry, in the CRS axis order; needs the application schema for the property name; no BBOX together with a filter). Everything is also tested here after the server answered, and a refused filter is retried as a plain bbox. Without `jdal-ops` only `INTERSECTS` works, on envelopes. Not done: buffer, union and other operations; transforming the filter geometry to the layer CRS (it must be given in it); spatial pushdown to GeoPackage (no spatial functions or R-tree) and to the file formats |
| Not started | curves, KML/GPX, GML writing, FlatGeobuf/MVT/OpenFileGDB |

Checkstyle (google_checks, warnings only, as in httpfile) runs in `verify`. Filter translation lives in `jdal-filter` (on `jdal-sql`'s public `WhereFilter`); it has not been tried against real servers, only against canned ones. Next steps in suggested order: more operations in `jdal-ops` (buffer, union, clip, make-valid for `jdal convert`), a GeoPackage R-tree for geometry-based spatial filters; then GPKG R-tree; then GML/GPX/KML.
