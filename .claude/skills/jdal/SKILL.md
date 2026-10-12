---
name: jdal
description: How to use jdal, the pure-Java vector geodata library and CLI (a Java port of GDAL/OGR's vector side). Use when someone asks how to read, inspect, convert, reproject, filter, query or write geodata (GeoPackage, PostGIS, GeoJSON, shapefile, CSV, GML, WFS, OGC API Features, /vsi virtual files) with the jdal command line or with the Java API, or when a jdal command or call fails.
---

# Using jdal

jdal reads and writes vector data. The command line (`bin/jdal`) covers the common
ogr2ogr/ogrinfo jobs; everything else is a few lines of Java. When someone describes a problem,
find the closest recipe below, give the exact command or code, and say which part is an
assumption (file names, CRS, layer names). If the CLI lacks an option (see "Not in the CLI"),
say so and give the API version instead of inventing a flag.

Status, gaps and design notes: `docs/FEASIBILITY.md` (section 11, the status table),
`docs/VSI-DESIGN.md`. Examples below are run as tests in
`jdal-cli/src/test/java/dev/jdal/cli/ApiGuideTest.java`; keep the two in step.

## 1. Command line

Build once, then run from the checkout:

```
mvn -q package -DskipTests
bin/jdal drivers            # what is available (rw = can write, ro = read only)
bin/jdal info FILE          # layers; -s summary of each layer; -a every feature
bin/jdal convert [options] SRC DST [LAYER...]
bin/jdal                    # interactive mode: type  info ...  convert ...  drivers  exit  (Tab completes)
```

With jbang (see `docs/JBANG.md`): `jbang jdal@hallvard/jdal info FILE`, or `jdal info FILE` once
`jbang app install --name jdal jdal@hallvard/jdal` has been done (from a checkout: `jdal-dev` instead of `jdal`); everything below works the same with `jdal`
in place of `bin/jdal`.

`bin/jdal` with arguments runs that one command and exits (exit code 2 for bad options, 1 for
failures such as an unreadable file; messages start with `ERROR:`). Without arguments, or with `-i`,
it opens the interactive mode, where commands are typed without the `jdal` prefix.

### convert options (like ogr2ogr)

| option | meaning |
|---|---|
| `-f`, `--format NAME` | output driver (default: guessed from the extension: `.gpkg`, `.geojson`/`.json`, `.csv`, `.shp`/`.dbf`; `PG:` for PostGIS) |
| `--t_srs CRS` | reproject to `EPSG:25833`, a PROJ string or WKT |
| `--s_srs CRS` | use this CRS for the source (when it has none or the wrong one) |
| `--nln NAME` | name of the output layer |
| `--spat minx,miny,maxx,maxy` | keep features whose envelope intersects the box (source CRS) |
| `--spat_wkt WKT` | keep features that intersect this geometry exactly (source CRS), e.g. `"POLYGON((...))"` |
| `--lco KEY=VALUE` | layer creation option, repeatable |
| `--dsco KEY=VALUE` | dataset creation option, repeatable |
| `--oo KEY=VALUE` | open option for the source, repeatable |
| `LAYER...` | after SRC DST: copy only these layers |

An existing destination file is replaced. Unknown option names and invalid values give a
`Warning:` before the copy starts; a malformed `KEY=VALUE`, CRS or box is an error.

### Recipes

```
bin/jdal info -s data.gpkg                                  # what is in this file
bin/jdal info -a data.shp | head -40                         # look at features
bin/jdal convert in.shp out.gpkg                             # format from the extension
bin/jdal convert -f GeoJSON in.gpkg out.json
bin/jdal convert --s_srs EPSG:32633 --t_srs EPSG:4326 in.shp out.geojson
bin/jdal convert in.gpkg out.gpkg roads buildings            # only two layers
bin/jdal convert --nln places in.geojson out.gpkg
bin/jdal convert --spat 500000,6600000,600000,6700000 in.gpkg out.gpkg
bin/jdal convert --spat_wkt "POLYGON((10 59,11 59,11 60,10 60,10 59))" in.geojson out.gpkg
bin/jdal convert --lco GEOMETRY=AS_WKT in.shp out.csv       # geometry as a WKT column
bin/jdal convert --lco GEOMETRY=AS_XY --lco CREATE_CSVT=YES in.shp out.csv
bin/jdal convert --lco ENCODING=UTF-8 in.gpkg out.shp
bin/jdal convert --lco FID=id --lco GEOMETRY_NAME=shape in.shp out.gpkg
bin/jdal convert in.gpkg "PG:host=localhost dbname=gis user=me password=secret" --lco OVERWRITE=YES
bin/jdal info "PG:host=localhost dbname=gis user=me password=secret"
bin/jdal info "WFS:https://server/wfs"                       # layers = feature types
bin/jdal convert "WFS:https://server/wfs" out.gpkg ns:roads  # one feature type
bin/jdal convert --oo PAGING_ALLOWED=YES --oo PAGE_SIZE=500 "WFS:https://server/wfs" out.gpkg ns:roads
bin/jdal info "OAPIF:https://server/ogcapi"                  # layers = collections
bin/jdal convert --oo PAGE_SIZE=200 "OAPIF:https://server/ogcapi/collections/roads" out.gpkg
bin/jdal info CSV:/vsigzip//data/big.csv.gz                  # gzip, zip, http: see section 3
bin/jdal info /vsizip//data/archive.zip
bin/jdal convert /vsizip//data/archive.zip/roads.shp out.gpkg
bin/jdal info /vsicurl/https://example.org/data.geojson
```

### Drivers and their options

| driver | open | create |
|---|---|---|
| `GPKG` (`.gpkg`) | | layer: `FID`, `GEOMETRY_NAME`, `IDENTIFIER` |
| `PostgreSQL` (`PG:...`) | | layer: `OVERWRITE`, `LAUNDER`, `FID`, `GEOMETRY_NAME`, `SPATIAL_INDEX=GIST/NONE` |
| `GeoJSON` | | none |
| `ESRI Shapefile` (file or directory) | `PROMOTE_TO_MULTI` | layer: `SHPT`, `ENCODING`, `OVERWRITE`, `PROMOTE_TO_MULTI` |
| `CSV` (`CSV:path` forces it) | `AUTODETECT_TYPE`, `AUTODETECT_WIDTH`, `AUTODETECT_SIZE_LIMIT`, `HEADERS`, `SEPARATOR`, `MERGE_SEPARATOR`, `QUOTED_FIELDS_AS_STRING`, `KEEP_GEOM_COLUMNS`, `X/Y/Z_POSSIBLE_NAMES`, `GEOM_POSSIBLE_NAMES` | layer: `GEOMETRY` (NONE, AS_WKT, AS_XY, AS_XYZ, AS_YX), `SEPARATOR`, `STRING_QUOTING`, `CREATE_CSVT`, `WRITE_BOM`, `HEADER`, `LINEFORMAT` |
| `GML` (read only) | `EXPOSE_GML_ID`, `EXPOSE_FID`, `USE_SCHEMA` | |
| `WFS` (`WFS:url`, read only) | `VERSION`, `PAGING_ALLOWED`, `PAGE_SIZE` | |
| `OAPIF` (`OAPIF:url`, read only) | `PAGE_SIZE`, `INITIAL_REQUEST_PAGE_SIZE`, `DATETIME` | |

The same lists are available in code: `Jdal.driver("CSV").orElseThrow().layerCreationOptions()`.
CSV without `--oo AUTODETECT_TYPE=YES` reads every column as text.

### Not in the CLI (use the API, section 2)

Attribute filters (`-where`), `-limit`, SQL (`-sql`), selecting or renaming fields, forcing a
geometry type, clipping, append/update of an existing destination, a `sql` command, open options
for `info` (only `convert` has `--oo`; to look at a CSV with `AUTODETECT_TYPE` convert it first or
use the API). The CLI has `--spat`/`--spat_wkt` only for space.

### Settings (`GDAL_HTTP_*` and friends)

Options are looked up in Java system properties (`-D...`), then environment variables, then a
`.env` file in the current directory (Properties format, `KEY=value`, `#` comments, backslashes
doubled). Useful ones: `GDAL_HTTP_BEARER`, `GDAL_HTTP_USERPWD=user:password`,
`GDAL_HTTP_HEADERS` (one `Name: value` per line), `GDAL_HTTP_USERAGENT`, `GDAL_HTTP_TIMEOUT`,
`GDAL_HTTP_MAX_RETRY`/`GDAL_HTTP_RETRY_DELAY`, `CPL_VSIL_CURL_USE_HEAD=NO`,
`CPL_VSIL_CURL_CHUNK_SIZE`, `CPL_ZIP_ENCODING` (names in old zips), `GML_EXPOSE_FID=NO`,
`OGR_SQL_STRICT=TRUE`. Keep secrets out of version control.

## 2. Java API

Modules: `jdal-core` + a driver per format (`jdal-driver-gpkg`, `-postgis`, `-geojson`,
`-shapefile`, `-csv`, `-oapif`, `-wfs`, `jdal-gml`), `jdal-sql` (attribute filters and SQL),
`jdal-ops` (exact spatial tests, JTS), `jdal-utils` (`Translate`, `Info`), `jdal-vsi`,
`jdal-vsi-curl`. Drivers are found on the class path (`ServiceLoader`): add the jar, get the
format. SQLite needs `--enable-native-access=ALL-UNNAMED` on the java command line. Java 26.

### Read

```java
try (Dataset ds = Jdal.open("cities.gpkg")) {           // the driver is recognised from the file
  Layer layer = ds.requireLayer("cities");               // ds.layers() lists them
  for (FieldDefn f : layer.type().fields()) System.out.println(f.name() + " " + f.type());
  try (Stream<Feature> s = layer.features()) {            // always close streams
    s.forEach(f -> System.out.println(f.get("name") + " " + f.geometry().toWkt()));
  }
}
```

`Jdal.open(location, update, options)` takes `true` for update mode and a `Map<String,String>` of
open options (`Map.of("AUTODETECT_TYPE", "YES")`). `Jdal.open("WFS:https://...")` and
`"OAPIF:https://..."` return layers like any other source. A `Feature` has `fid()`, `get("field")`
or `get(index)`, `geometry()`, `with("field", value)` (a changed copy).

### Filter, count, extent

```java
Query q = Query.sql("pop > 100000 AND name LIKE 'B%'")       // OGR SQL WHERE syntax
    .withBbox(new Envelope(0, 55, 15, 65))                   // envelope test
    .withLimit(10);
try (Stream<Feature> s = layer.features(q)) { ... }

Query inside = Query.spatial(SpatialOp.INTERSECTS,           // exact; also WITHIN, CONTAINS, DISJOINT,
    Wkt.read("POLYGON ((0 58, 12 58, 12 62, 0 62, 0 58))")); // TOUCHES, CROSSES, OVERLAPS, EQUALS
long n = layer.count(inside);                                // geometry in the layer's CRS
layer.count();   layer.extent();   layer.feature(fid);
```

Filters are pushed to PostGIS, WFS and OGC API Features where possible and always checked again
here. String comparisons ignore case as in OGR SQL (`LIKE` does not, `ILIKE` does).

### SQL

```java
try (Layer r = ds.executeSql("SELECT name, pop FROM cities WHERE pop > 100000 ORDER BY pop DESC")) { ... }
try (Layer r = ds.executeSql("SELECT count(*) FROM cities", "OGRSQL")) { ... }   // generic engine
```

GeoPackage and PostGIS run the statement in the database (their SQL); pass `"OGRSQL"` for the
OGR dialect (`OGR_GEOM_AREA`, `FID`, `CAST`, `DISTINCT`, aggregates, joins, `UNION ALL`, `ALTER TABLE`).
Other formats always use the OGR dialect. `executeSql(sql, geometry, dialect)` adds a spatial filter.

### Write

```java
FeatureType type = FeatureType.of("cities", GeometryType.POINT, SpatialReference.fromEpsg(4326),
    List.of(FieldDefn.of("name", FieldType.STRING), FieldDefn.of("pop", FieldType.INTEGER)));
try (Dataset ds = Jdal.create("GPKG", "out.gpkg", Map.of())) {     // dataset creation options
  Layer layer = ds.createLayer(type, Map.of());                    // layer creation options
  layer.insert(Feature.builder(layer.type()).set("name", "Oslo").set("pop", 700000)
      .geometry(Point.xy(10.75, 59.91)).build());
}
try (Dataset ds = Jdal.open("out.gpkg", true, Map.of())) {         // update mode
  Layer layer = ds.requireLayer("cities");
  Feature f = layer.features(Query.sql("name = 'Oslo'")).findFirst().orElseThrow();
  layer.update(f.with("pop", 710000));
  layer.delete(f.fid());   layer.addField(FieldDefn.of("note", FieldType.STRING));
}
```

Field types: `INTEGER`, `INTEGER64`, `REAL`, `STRING`, `BINARY`, `DATE`, `TIME`, `DATETIME` and the
list types. Close the dataset to flush. A layer is not thread safe: use one per thread.

### Convert (what `jdal convert` does)

```java
Translate.translate("in.shp", "out.geojson",
    TranslateOptions.defaults()
        .withDriver("GeoJSON")                                  // else from the extension
        .withSourceSrs(SpatialReference.fromEpsg(32633))
        .withTargetSrs(SpatialReference.fromEpsg(4326))
        .withWhere(f -> ((Integer) f.get("pop")) > 100000)      // any Java predicate
        .withLimit(1000)
        .withLayerOptions(Map.of("GEOMETRY", "AS_WKT")));
```

`Translate.translate(Dataset src, Dataset dst, options)` works on open datasets (also `MemoryDataset`).
Other `with...`: `withLayers`, `withNewLayerName`, `withBbox`, `withSpatialFilter`,
`withForceGeometryType`, `withDatasetOptions`, `withOpenOptions`. `Info.describe(ds, summaryOnly, features)`
returns the `jdal info` text.

### Geometry and CRS

```java
Geometry g = Wkt.read("POLYGON ((0 0, 1 0, 1 1, 0 0))");      // sealed records: Point, LineString, Polygon,
g.toWkt();  g.envelope();  Wkb.write(g);  Wkb.read(bytes);      // MultiPoint, MultiLineString, MultiPolygon,
Point.xy(10.75, 59.91);  Point.xyz(1, 2, 3);                    // GeometryCollection (immutable)
var toUtm = CoordinateTransformation.between(SpatialReference.fromEpsg(4326), SpatialReference.fromEpsg(25833));
Geometry utm = toUtm.transform(Point.xy(10.75, 59.91));         // x = east, y = north; x/y are lon/lat for 4326
SpatialReference.fromUserInput("EPSG:25833");  .fromWkt(wkt);   .toWkt();  .isSame(other)
```

CRS support is what proj4j covers (EPSG codes, PROJ strings); no WKT2/PROJJSON. Curved geometries
are not modelled. For buffer/union and other operations use JTS directly (`jdal-ops` has
`Jts.toJts(geometry)`).

### Virtual files, services, settings, warnings

```java
Vsi.global().writeString("/vsimem/cities.csv", "name,pop\nOslo,700000\n");   // in-memory file
Dataset ds = Jdal.open("/vsimem/cities.csv", false, Map.of("AUTODETECT_TYPE", "YES"));
Vsi.global().readAllBytes("/vsizip//data/a.zip/b.txt");   // /vsigzip/, /vsizip/, /vsisubfile/, /vsimem/,
                                                          // /vsicurl/ (jdal-vsi-curl), /vsistdin/, /vsistdout/
List<String> warnings = Diagnostics.capture(() -> Jdal.open(path).close());      // warnings are not exceptions
```

Errors are `JdalException` (a `RuntimeException`) with the GDAL-style message. Warnings go to the
log unless captured. Settings come from `Config.global().get("GDAL_HTTP_BEARER")` (system property,
environment, `.env`).

### Add a format

Implement `dev.jdal.core.Driver` (`name`, `identify(OpenInfo)`, `open`, optionally `create`),
list the class in `META-INF/services/dev.jdal.core.Driver`, and read files through `Vsi.global()`
(never `java.nio.file` directly) so that `/vsimem/`, zips and URLs work. Port tests from GDAL's
`autotest` and tag them `@Port("autotest/ogr/ogr_x.py::test_name")` (see `CONSTRAINTS.md`).

## 3. Locations (the string you pass to `open` or on the command line)

| form | meaning |
|---|---|
| `data/roads.gpkg`, `roads.shp`, `places.geojson`, `cities.csv` | files; a directory opens as a dataset of the shapefiles or CSVs in it |
| `PG:host=... port=... dbname=... user=... password=...` | PostGIS; layers are tables |
| `WFS:https://host/wfs` or a URL with `SERVICE=WFS` | WFS 1.0/1.1/2.0; `WFS:caps.xml` reads a saved capabilities file |
| `OAPIF:https://host/api` or `.../collections/id` (`WFS3:` also works) | OGC API - Features |
| `CSV:path` | force the CSV driver for other extensions |
| `/vsimem/x` | in-memory file (a GeoPackage there is copied to a temp file and written back when the dataset is closed) |
| `/vsigzip/path.gz`, `/vsizip/archive.zip/member`, `/vsizip/{odd.name}/member` | compressed; they nest, `/vsizip//vsicurl/https://h/a.zip/b.shp` |
| `/vsicurl/https://...` | file on a web server (range requests) |
| `/vsisubfile/offset_size,path` | a byte range |

## 4. When something goes wrong

| message or symptom | usual cause and fix |
|---|---|
| `No driver recognises '...'` | wrong path, or the format has no driver: check `bin/jdal drivers`; for text formats with odd extensions use `CSV:path` |
| `Cannot guess output format of ...` | add `-f GPKG` (or another writable driver from `drivers`) |
| `Driver X cannot create datasets` | the format is read only (WFS, OAPIF, GML) |
| `Warning: Driver CSV does not support FOO layer creation option` | misspelt option; compare with the table above |
| `Expected KEY=VALUE but got 'x'` | `--lco`/`--dsco`/`--oo` need `KEY=VALUE` |
| `Cannot transform (...): Latitude is out of range` | the source CRS is wrong for these coordinates: give `--s_srs` |
| `Unknown CRS` | use `EPSG:nnnn`, a PROJ string or WKT; no WKT2 |
| CSV columns are all strings, no geometry | `--oo AUTODETECT_TYPE=YES`; for coordinates `--oo X_POSSIBLE_NAMES=lon --oo Y_POSSIBLE_NAMES=lat` or `GEOM_POSSIBLE_NAMES=wkt` |
| `HTTP error code : 404, ...` / `WFS server error: ...` | the service said no; check the URL, typename, credentials (`GDAL_HTTP_*`) |
| WFS or OGC API shows no features for a filter | filters are sent when the server supports them and always checked again locally; try without the filter to see what the server returns |
| coordinates swapped (lat/lon) from a WFS | GML with `urn:ogc:def:crs:EPSG::4326` is latitude first and is converted to x,y automatically; if the server mislabels data, use `--s_srs` |
| UnsatisfiedLinkError / native access warning for SQLite | run with `--enable-native-access=ALL-UNNAMED` (`bin/jdal` does) |

## 5. How to help

1. Ask (or infer) the source, the destination, the CRS, and whether only some features are wanted.
2. Give one command or one code block; use real option names from this file only.
3. Suggest `bin/jdal info -s` on the result to check it.
4. If it needs something under "Not in the CLI", give the API version, and say that a CLI option
   does not exist yet.
5. For anything about what jdal can or cannot do (formats, GDAL parity), point to the status table in
   `docs/FEASIBILITY.md` instead of guessing.
