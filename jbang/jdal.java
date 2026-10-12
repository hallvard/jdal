///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 26+
//REPOS mavenlocal,mavencentral
//DEPS dev.jdal:jdal-cli:0.1.0-SNAPSHOT
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED

/** Starts the jdal command line (info, convert, drivers; no arguments: interactive mode). */
public class jdal {
  public static void main(String[] args) {
    dev.jdal.cli.Main.main(args);
  }
}
