# Design: `jdal-vsi`, the virtual file system

Written after reading `port/cpl_vsi*.{h,cpp}` and `port/cpl_vsil_*.cpp` in GDAL 3.14 and counting how the vector tests use it. Status: steps 1 to 5 of section 4 are done except S3 and the other cloud handlers (see section 5).

## 1. What GDAL does

**One handler per path prefix.** `VSIFileManager` (`cpl_vsi_virtual.h:643`) holds `map<prefix, VSIFilesystemHandler>` plus a default handler for plain paths. `GetHandler(path)` (`cpl_vsil.cpp:4316`) returns the first prefix that matches, then falls back to the default. Details worth copying:
- `/vsimem` matches `/vsimem/`, and `\` is accepted after the prefix (`/vsimem\foo`).
- `RegisterHandlerLoader(prefix, fn)` installs handlers lazily on the first path that uses them. The cloud handlers use this, so a process that never touches S3 pays nothing.
- All built-in handlers are installed in one list in `VSIInstallAllHandlers` (`cpl_vsil.cpp:4256-4289`). A plugin API (`cpl_vsil_plugin.cpp`) exists so that applications can add handlers.

**Two interfaces.**
- `VSIFilesystemHandler` (line 378): `Open`, `Stat`, `Unlink`, `Mkdir`, `Rmdir`, `ReadDirEx`, `Rename`, `SiblingFiles`, `IsLocal`, `IsArchive`, `SupportsRead/SequentialWrite/RandomWrite`, `GetActualURL`, `Sync`, `CopyFile`, plus cloud-only members (multipart upload, signed URLs, metadata).
- `VSIVirtualHandle` (line 47): `Seek`, `Tell`, `Read`, `Write`, `Eof`, `Truncate`, `Flush`, `Close`, and optionals: `ReadMultiRange`, `AdviseRead`, `PRead`, `GetRangeStatus`.

**Handlers compose by path.** A wrapping handler strips its own prefix and asks the registry for the handler of the remainder:
- `/vsigzip/` opens `path + strlen("/vsigzip/")` through `GetHandler` (`cpl_vsil_gzip.cpp:3016`), so `/vsigzip//vsicurl/https://…/x.gz` works without special cases. Write mode wraps the inner write handle in a deflater.
- Archive handlers (`/vsizip/`, `/vsitar/`, 7z, rar) share `VSIArchiveFilesystemHandler`. Its `SplitFilename` (`cpl_vsil_abstract_archive.cpp:301`) must find where the archive path ends and the member path begins. Two syntaxes: the explicit `/vsizip/{archive}/member` with balanced curly braces, and detection by extension (`.zip`, `.kmz`, …, at most 5 attempts to avoid pathological nesting). The archive's central directory is cached per file.
- `/vsisubfile/offset_size,path` exposes a byte range. `/vsicached?`, `/vsicrypt/` and `/vsisparse/` are other wrappers.

**Memory files** (`cpl_vsi_mem.cpp`): an in-process map from path to a growable byte buffer with reference counting, so a file can be deleted while a handle is open. API for applications: `VSIFileFromMemBuffer` (wrap caller memory, optionally take ownership) and `VSIGetMemFileBuffer` (detach the bytes). A `/vsimem/` path accepts options such as a maximum length.

**Stat flags** (`cpl_vsi.h:238`): `EXISTS`, `NATURE`, `SIZE`, `SET_ERROR`, `CACHE_ONLY`. They exist so remote handlers can skip the expensive HEAD or listing request when the caller needs less. `SiblingFiles` lets a handler tell drivers which sidecars exist (shapefile, GDAL's `OpenInfo`) without probing each one, which matters on object stores.

## 2. How much the vector tests depend on it

Counting `/vsi…/` strings in `autotest/ogr/*.py`:

| Prefix | Uses | Files that use it |
|---|---|---|
| `/vsimem/` | 941 | 33 test files |
| `/vsizip/` | 44 | |
| `/vsipmtiles/` | 36 | (PMTiles driver, out of scope) |
| `/vsicurl/` | 10 | |
| `/vsigzip/` | 9 | |
| `/vsistdout/` | 7 | |
| `/vsistdin/`, `/vsitar/`, `/vsisparse/` | 3, 2, 2 | |

The VSI layer has its own tests: `gcore/vsifile.py` (73 tests) and `gcore/vsizip.py` (29). So `/vsimem/` is the one that matters for porting tests: as long as the Java tests write fixtures to temp directories instead, every port diverges from the original and some assertions (error text with the path, file listings) do not carry over.

## 3. Proposal

### 3.1 Modules

- `jdal-vsi`: the API (`Vsi`, `VsiFile`, `VsiHandler`, `VsiStat`), the registry, and handlers that need only the JDK: local files (default), `/vsimem/`, `/vsigzip/`, `/vsizip/` (JDK `ZipFile` or `zipfs`, read, and write for the simple case), `/vsisubfile/`, `/vsistdin/`, `/vsistdout/`.
- `jdal-vsi-curl`: `/vsicurl/` and `/vsicurl_streaming/` on `java.net.http` (range requests, a block cache, `ReadMultiRange` as parallel requests).
- `jdal-vsi-s3`, `-azure`, `-gcs`: later, each behind its own credential lookup. Not needed for the first release of anything.
- `/vsitar/`: needs a tar reader. `commons-compress` is the usual choice, but the format is simple enough to read with about 150 lines (ustar and pax headers, no compression handling since `/vsigzip/` composes). Decide when it is needed.
- 7z, rar, crypt, sparse, hdfs, swift, oss, adls: not planned.

### 3.2 SPI

Same pattern as `Driver`: handlers are found with `ServiceLoader`, so the classpath decides what exists.

```java
public interface VsiHandler {
  /** Prefixes this handler serves, e.g. "/vsimem/" or "/vsizip/". Matching is case-sensitive, longest wins. */
  List<String> prefixes();

  /** Opens a path (the full path, prefix included). */
  VsiFile open(Vsi vsi, String path, VsiMode mode, Map<String, String> options) throws IOException;

  VsiStat stat(Vsi vsi, String path, int flags) throws IOException;   // Optional.empty() or a record
  default List<String> list(Vsi vsi, String dir, int max) throws IOException { throw unsupported(); }
  default void delete(Vsi vsi, String path) throws IOException { throw unsupported(); }
  default void mkdir(Vsi vsi, String dir) throws IOException { throw unsupported(); }
  default void rename(Vsi vsi, String from, String to) throws IOException { throw unsupported(); }
  default List<String> siblings(Vsi vsi, String path) { return null; }   // null = unknown, as in GDAL
  default boolean isLocal() { return false; }
  default Capabilities capabilities() {...}                               // read / sequential write / random write
}
```

- `VsiFile` extends `SeekableByteChannel` (read, write, position, size, truncate) and adds `read(ByteBuffer, long position)` for positional reads (GDAL's `PRead`) and optionally `readRanges(long[] offsets, int[] lengths)`.
- Every method receives the `Vsi` registry, so a wrapper handler (gzip, zip, subfile) opens the inner path as `vsi.open(rest, …)`. That is how composition works, and it avoids a global.
- `Vsi` has `Vsi.global()` (loaded from the classpath, lazily on the first matching prefix, like GDAL's handler loaders) and `Vsi.of(handler…)` for tests that need an isolated registry or fakes.
- Matching: the longest matching prefix wins (GDAL: first match in sorted map order, which is the same for non-nested prefixes). `/vsimem` without the slash matches. A duplicate prefix in two providers is an error, not a silent pick.
- Plain paths go to the default local handler (`java.nio.file`). `file:` URIs are mapped to plain paths before lookup.
- Exceptions: `IOException` from handlers; the façade maps them to `JdalException` with GDAL-style text where tests compare messages.

Optional adapter: a `NioHandler` that serves a prefix by delegating to any installed `java.nio.file.spi.FileSystemProvider` scheme (for example mounting a `jimfs` or a zip filesystem). It is convenient but not a replacement for our own SPI, because paths such as `/vsizip/a.zip/b` are not URIs and remote handlers need ranged reads and caching that `FileSystemProvider` does not model.

### 3.3 Memory files

`MemHandler`: `ConcurrentHashMap<String, MemFile>`; a `MemFile` holds a `byte[]` plus length, grown by doubling, with a read/write lock and a reference count so that delete-while-open works as in GDAL. Extra static API on `Vsi`: `putBytes(path, byte[])`, `bytes(path)` (a copy), `takeBytes(path)` (detach and remove). These are the Java forms of `VSIFileFromMemBuffer` and `VSIGetMemFileBuffer` and what the ported tests use to set up and read back fixtures.

### 3.4 Integration with drivers

Drivers see locations only through `Vsi`:
- `OpenInfo.path()` (a `Path`, null for non-local locations) becomes `OpenInfo.location()` plus `vsi()`. `header()`, `isFile()`, `isDirectory()` and `extension()` already live in `OpenInfo`, so only their implementation changes.
- CSV, GeoJSON: read through `VsiFile` (wrap as `InputStream`/`Reader`); write through an output channel. Easy.
- Shapefile: `BlockFile` already works on a channel, so it takes a `VsiFile`. Sidecar discovery (`.shx`, `.dbf`, `.prj`, `.cpg`, `.qix`) uses `siblings()` first and falls back to `stat()`, exactly as GDAL's `OpenInfo` does. Directory datasets use `list()`.
- GPKG and SQLite: **hard.** sqlite-jdbc wants a real file. Options, in order of preference: (a) for non-local paths copy to a temp file on open and, for update mode, write back on close; (b) use sqlite-jdbc's in-memory `serialize`/`deserialize` for `/vsimem/`; (c) register a SQLite VFS through the native API (not possible from pure Java without FFM bindings, and we do not want that). Document the limitation, start with (a).
- PostGIS: unaffected.
- CLI: `jdal info /vsizip/data.zip/poly.shp` works once the shapefile driver goes through `Vsi`.

### 3.5 Things to decide

1. **Error semantics.** GDAL returns `NULL` and sets an error that `gdal.VSIGetLastErrorMsg()` reads. Java throws `IOException`; ported tests that check for `None` become `assertThrows` or `Optional`.
2. **Thread safety.** GDAL handles are not thread-safe but handlers are. Same here: handlers must be, handles need not be.
3. **Configuration options.** GDAL reads `CPL_VSIL_CURL_*`, `AWS_*` and so on from environment/config. Proposal: a `VsiConfig` passed to handlers (default: environment variables and system properties), not globals.
4. **Text encoding of paths.** GDAL paths are UTF-8 `char*`. Java `String` has none of that trouble, but `Path` on some platforms does not round-trip every string. Use `String` in the API, convert at the local handler.
5. **Caching** (`/vsicached?`, the block cache inside curl): defer. Only curl needs it.
6. **Archive path splitting.** Port `SplitFilename` as is (curly-brace form plus extension detection) so that path strings in GDAL tests are accepted unchanged.

## 4. Order of work

1. `jdal-vsi` with the API, registry, local and `/vsimem/` handlers, and the `Vsi.putBytes/bytes/takeBytes` helpers. Port the relevant parts of `gcore/vsifile.py` (open/read/seek/write/truncate/stat/list/rename/unlink on files and on `/vsimem/`).
2. Move CSV, GeoJSON and Shapefile to `Vsi`; switch the already ported tests that use temp files for in-memory fixtures to `/vsimem/` where the original did. This is the step where the 941 usages start to port literally.
3. `/vsigzip/` and `/vsizip/` (read, then write), `/vsisubfile/`, `/vsistdin/`, `/vsistdout/`; port `gcore/vsizip.py`.
4. GPKG via temp copy.
5. `jdal-vsi-curl` with range requests, then S3 as the first cloud handler.

Steps 1 and 2 give most of the value for the vector port; everything after that is independent and can be done in any order.

## 5. Status

Done (steps 1 and 2):
- `jdal-vsi`: `Vsi` (registry and operations), `VsiHandler` SPI, `VsiFile`, `VsiMode`, `VsiStat`, `VsiPath`, `LocalHandler` (default) and `MemHandler` (`/vsimem/`, registered with `META-INF/services`). Duplicate prefixes fail at start-up; the longest prefix wins; `/vsimem` and `/vsimem\x` are accepted.
- Differences from the proposal: `VsiFile` also has `write(ByteBuffer, long)` and `setLength(long)` (GDAL's `VSIFTruncateL` extends and leaves the position alone, `SeekableByteChannel.truncate` does neither). There is no special API for memory buffers; `Vsi.writeBytes`, `readAllBytes` and `delete` cover `VSIFileFromMemBuffer` and `VSIGetMemFileBuffer`. EOF and error flags are not modelled.
- `OpenInfo` has `isPath()` instead of `path()`; locations are strings everywhere. CSV, GeoJSON and Shapefile contain no `java.nio.file` calls any more. The shapefile sidecar lookup uses `exists`, not `siblings()` yet.
- Tests: `VsifileTest` (20 cases, from 17 tests in `gcore/vsifile.py`, run on `/vsimem/` and on a temp directory where the original does both). The CSV, GeoJSON and Shapefile tests that wrote temp files now use `Autotest.vsimem()`, which is GDAL's `tmp_vsimem` fixture.
- Not ported on purpose: tests on Windows names, `/vsicache/`, multipart upload, sync and `OpenDir`, which need handlers or APIs we do not have.

Done (steps 3 to 5):
- **Shared building blocks:** `ReadOnlyVsiFile` (public, for handlers in other modules), `RangeFile` (a window on another file, also writable), `InflatingFile` (decompressing stream with forward skip and restart on backward seek; bytes decoded before an error are delivered, the error comes on the next read) and `PositionalInputStream` (reads by absolute position, so several streams can share one `VsiFile`).
- **`/vsigzip/`:** reads gzip (plain files pass through, as in GDAL), size from the trailer, write-only sequential gzip. The path after the prefix goes through `Vsi`, so `/vsigzip//vsimem/x.gz` works.
- **`/vsizip/`:** own zip reader and writer (no `ZipFile`, so that the archive can be any `VsiFile`, also inside another zip or on HTTP). Path splitting as in GDAL: `{archive}` syntax with balanced braces, extension detection (`.zip .kmz .dwf .ods .xlsx .xlsm`, at most 5 candidates), natural chaining (`/vsizip/vsimem/x.zip`). Names: UTF-8 flag, Info-ZIP Unicode Path field, else `CPL_ZIP_ENCODING` (default CP437). Reads stored and deflated entries, zip64 sizes and offsets. Writes: new archives and appending to existing ones (new entries replace the old central directory, which is rewritten at the end), directory entries, one open entry at a time, no reading of an archive that is being written; the index is cached per archive and checked against size and modification time. Not done: zip64 when writing, encryption, deflate64, multi-threaded compression, `/vsitar/`, 7z, rar, SOZip.
- **`/vsisubfile/offset_size,path`**, **`/vsistdin/`** (what was read is kept so the file can be read again) and **`/vsistdout/`** (the streams can be injected for tests).
- **GeoPackage:** non-local locations are staged in a temporary file; in update or create mode it is copied back when the dataset is closed. Read-only access does not write back. The whole file is copied both ways, so this is for small and medium files.
- **`jdal-vsi-curl`:** `/vsicurl/` and `/vsicurl_streaming/` (the same implementation) on `java.net.http`. Size from HEAD, with fallback to a ranged GET when HEAD is refused or has no size, Content-Range parsing, servers that ignore Range, 404 remembered until `clearCache()`, redirects, chunked reads (`CPL_VSIL_CURL_CHUNK_SIZE`) with an LRU cache (`CPL_VSIL_CURL_CACHE_SIZE`), `CPL_VSIL_CURL_USE_HEAD`, `GDAL_HTTP_HEADERS`, `GDAL_HTTP_USERAGENT`, `GDAL_HTTP_TIMEOUT`, Apache style directory listings. Not done: retries, authentication helpers, `ReadMultiRange` in parallel, nginx style listings, `/vsicurl?` option syntax, S3, Azure, GCS (separate modules when needed).
- **Tests:** `ArchiveTest` (zip 1-7, 10-14 and seek test from `vsizip.py`; gzip and the corrupted file from `vsifile.py`; subfile, stdin, stdout, prefix without slash), `CurlTest` (13 cases against an in-process HTTP server; `vsicurl.py` itself needs GDAL's scripted mock server), a GeoPackage on `/vsimem/`, shapefiles read from a zip, CSV read from a gzip.
