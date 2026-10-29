# Build a runnable Clojure CLI application
#
# This module wraps clj-nix's mkCljBin with automatic dependency injection,
# allowing you to use local Clojure libraries as dependencies without
# uploading them to Maven.
#
# The result is an executable that runs the specified namespace's -main function.
#
# Example usage:
#   mkClojureBin {
#     name = "my-cli";
#     src = ./.;
#     primaryNamespace = "my.cli.main";
#     version = "1.0.0";
#     cljLibs = {
#       "org.myorg/my-lib" = myLibDerivation;
#     };
#   }

{ lib, mkCljBin, defaultJdk, clojureHelpers }:

with lib;

# Parameters:
#   name: Application name (used for the executable)
#   src: Path to source directory containing deps.edn and deps-lock.json
#   primaryNamespace: Namespace containing the -main function to run
#   version: Version string (default: "0.1")
#   cljLibs: Map of Maven coordinates to local JAR derivations (default: {})
#   buildCommand: Custom build command override (default: null, uses clj-nix default)
#   checkPhase: Custom test/check phase (default: null)
#   jdk: JDK used to build and run the application (default: JDK 25 headless)
#   Any other attributes (e.g. java-opts) are passed through to mkCljBin.
{ name, primaryNamespace, src, version ? "0.1", checkPhase ? null
, buildCommand ? null, cljLibs ? { }, jdk ? defaultJdk, ... }@args:

let
  # Prepare the source tree with injected dependencies and build script
  preppedSrc = clojureHelpers.mkPreparedClojureSrc {
    inherit name src cljLibs;
  };

# Build the application using clj-nix
# This produces an uberjar with a launcher script
in mkCljBin ((removeAttrs args [
  "primaryNamespace"
  "src"
  "cljLibs"
  "jdk"
  "checkPhase"
  "buildCommand"
]) // {
  inherit name version;
  jdkRunner = jdk;
  projectSrc = preppedSrc;
  # The namespace with -main function to execute
  main-ns = primaryNamespace;
  checkPhase = optionalString (checkPhase != null) checkPhase;
  # Use the lockfile from the prepared source (which came from original src)
  lockfile = "${preppedSrc}/deps-lock.json";
}
# Use custom build command if provided
// (optionalAttrs (buildCommand != null) { inherit buildCommand; }))
