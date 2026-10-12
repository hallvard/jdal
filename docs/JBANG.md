# Running jdal with jbang

Two aliases are defined in `jbang-catalog.json` (repository `hallvard/jdal`):

| alias | what runs | when to use |
|---|---|---|
| `jdal` | the self-contained jar `jdal.jar` of the latest GitHub release (about 18 MB, all drivers, nothing else to download) | everywhere: any machine with jbang |
| `jdal-dev` | `jbang/jdal.java`, which depends on `dev.jdal:jdal-cli` in `~/.m2` | on a machine with a checkout, after `mvn install` |

## Using it anywhere

```
jbang trust add https://github.com/hallvard/                  # once; jbang asks before running anything from a new owner
jbang jdal@hallvard/jdal drivers                               # fetches the catalog from GitHub and runs the alias jdal
jbang app install --name jdal jdal@hallvard/jdal               # installs a command: ~/.jbang/bin/jdal
jdal info -s data.gpkg
jdal convert --t_srs EPSG:4326 in.shp out.gpkg
jdal                                                           # interactive mode
```

Without `jbang trust add`, jbang asks "do you trust ...?" on the terminal; scripts and CI have no
terminal and appear to hang. jbang downloads a JDK 26 itself if there is none (`"java": "26+"` in the
catalog), and the jar's manifest enables native access for SQLite, so no options are needed. The
same jar runs without jbang: `java -jar jdal.jar info data.gpkg`.

`jbang jdal@hallvard` (without the repository name) would look for a repository called
`hallvard/jbang-catalog`; that is the place for a catalog of several tools, with entries that refer to
this one: `{"catalogs": {"jdal": {"catalog-ref": "https://github.com/hallvard/jdal/blob/HEAD/jbang-catalog.json"}}}`.

## Making a release (needed for the alias `jdal`)

The alias points at `https://github.com/hallvard/jdal/releases/latest/download/jdal.jar`, so it works
as soon as a release with an asset called `jdal.jar` exists. Either push a tag and let the workflow
`.github/workflows/release.yml` build and attach it:

```
git tag v0.1.0 && git push origin v0.1.0
```

or do it by hand (needs `gh auth login`):

```
./mvnw -q package -DskipTests
cp jdal-cli/target/jdal-cli-*-all.jar jdal.jar
gh release create v0.1.0 jdal.jar --title v0.1.0 --generate-notes
```

The jar is `jdal-cli-VERSION-all.jar` from the `shade` step of `jdal-cli`; the `META-INF/services`
files of all modules are merged so that the drivers are found. `./mvnw` is the Maven wrapper (Maven 4,
as used here), so the workflow and other machines need only a JDK 26.

## From a checkout

```
mvn -q install -DskipTests                       # puts the jars in ~/.m2, where //REPOS mavenlocal finds them
jbang jbang/jdal.java info -s data.gpkg          # or: jbang jdal-dev@. info ...  (alias in the catalog next to you)
bin/jdal info -s data.gpkg                       # no jbang needed
```

If jbang keeps starting an old version, `jbang cache clear` throws away what it has cached.

Careful with catalogs: `jbang catalog add NAME URL` writes into a `jbang-catalog.json` in the
current directory if there is one (it is how this repository's own file was damaged once); use
`--global`.

## What was tried

Locally, in a throw-away `JBANG_DIR`: the alias `jdal-dev`, the catalog lookup `jdal@hallvard/jdal` on
GitHub (it fetches the catalog and starts), `app install`, the interactive mode, and the fat jar both
with `java -jar` and as a jbang alias to a jar served over HTTP. Not tried: the release workflow and the
GitHub download URL itself, because no release exists yet.

## Why not a Maven repository

The other way is to publish the modules to a Maven repository and use `//DEPS dev.jdal:jdal-cli:VERSION`
(the script `jbang/jdal.java` does that with `//REPOS mavenlocal`). That needs a repository that the
machines can reach: an internal Nexus/Artifactory (add `//REPOS id=url`, credentials from
`~/.m2/settings.xml`), GitHub Packages (needs a token even to read), or Maven Central (needs a group id you
own). JitPack, which builds from git tags, was tried: its build image has JDK 17 and Maven 3.6, so jdal
(Java 26, Maven 4) does not build there without a `jitpack.yml` that installs both.
