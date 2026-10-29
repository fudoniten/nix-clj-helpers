# Build a Clojure library JAR file
#
# This module wraps clj-nix's mkCljLib with automatic dependency injection,
# allowing you to use local Clojure libraries as dependencies without
# uploading them to Maven.
#
# The build process:
#   1. Prepare source with injected deps.edn (via clojureHelpers)
#   2. Build the library using clj-nix's mkCljLib
#   3. Extract the JAR file from the build output
#
# Example usage:
#   mkClojureLib {
#     name = "my-lib";
#     src = ./.;
#     version = "1.0.0";
#     cljLibs = {
#       "org.myorg/other-lib" = otherLibDerivation;
#     };
#   }

{ mkCljLib, defaultJdk, clojureHelpers, stdenv, lib }:

with lib;

# Parameters:
#   name: Library name (used in JAR filename)
#   src: Path to source directory containing deps.edn and deps-lock.json
#   version: Version string (default: "0.1")
#   clojure-src-dirs: List of Clojure source directories (default: ["src"])
#   java-src-dirs: List of Java source directories (default: [])
#   cljLibs: Map of Maven coordinates to local JAR derivations (default: {})
#   buildCommand: Custom build command override (default: auto-generated)
#   checkPhase: Custom test/check phase (default: null)
#   jdk: JDK used to build the library (default: JDK 25 headless)
{ name, src, version ? "0.1", clojure-src-dirs ? [ "src" ], java-src-dirs ? [ ]
, buildCommand ? null, checkPhase ? null, cljLibs ? { }, jdk ? defaultJdk, ...
}:

let
  # Prepare the source tree with injected dependencies and build script
  preppedSrc =
    clojureHelpers.mkPreparedClojureSrc { inherit name src cljLibs; };

  # Build the library using clj-nix
  # This produces a directory containing the JAR and other build artifacts
  stageBuild = mkCljLib ({
    inherit version;
    jdkRunner = jdk;
    name = "${name}-staging";
    projectSrc = preppedSrc;
    checkPhase = optionalString (checkPhase != null) checkPhase;
    # Use the lockfile from the prepared source (which came from original src)
    lockfile = "${preppedSrc}/deps-lock.json";
  }
  # Use custom build command if provided
    // (optionalAttrs (buildCommand != null) { inherit buildCommand; })
    # Otherwise, generate the default build command
    // (optionalAttrs (buildCommand == null) {
      buildCommand = concatStringsSep " " ([
        "clojure -T:build"
        "uberjar"
        ":name"
        name
        ":target ./target"
        ":verbose true"
        ":version"
        version
        ":clj-src"
        (concatStringsSep "," clojure-src-dirs)
      ]
      # Add Java source dirs if specified
        ++ (optionals (java-src-dirs != [ ]) [
          ":java-src"
          (concatStringsSep "," java-src-dirs)
        ]));
    }));

  # Final derivation: extract just the JAR file
in stdenv.mkDerivation {
  name = "${name}-${version}.jar";
  inherit version;
  phases = [ "installPhase" ];
  src = stageBuild;
  # Copy the JAR to $out (the JAR becomes the derivation output itself)
  installPhase = "cp $src/*.jar $out";

  # Expose the prepped source for use in dependency injection
  # This allows downstream projects to use this library's deps.edn when
  # updating their own deps-lock.json files
  passthru = { preppedSrc = preppedSrc; };
}
