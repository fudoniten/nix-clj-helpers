# Build and run Clojure tests
#
# This module creates a derivation that runs Clojure tests using eftest.
# The key challenge is that test dependencies must be locked and available in the sandbox.
#
# The workflow:
#   1. Prepare source with injected deps.edn (via clojureHelpers)
#   2. Use clj-nix's mkCljLib to build classpath with all deps from lockfile
#   3. Override buildCommand to run tests instead of building
#
# Example usage:
#   mkClojureTests {
#     name = "my-lib";
#     src = ./.;
#     testAlias = "test";
#     cljLibs = {
#       "org.myorg/other-lib" = otherLibDerivation;
#     };
#   }
#
# Requirements:
#   - deps.edn must have a :test alias with eftest
#   - deps-lock.json must include test dependencies (use update-clojure-deps-with-tests)

{ lib, mkCljLib, defaultJdk, clojureHelpers }:

with lib;

# Parameters:
#   name: Test derivation name
#   src: Path to source directory containing deps.edn and deps-lock.json
#   testAlias: Alias in deps.edn for test configuration (default: "test")
#   cljLibs: Map of Maven coordinates to local JAR derivations (default: {})
#   jdk: JDK used to run the tests (default: JDK 25 headless)
{ name, src, testAlias ? "test", cljLibs ? { }, jdk ? defaultJdk, ... }:

let
  # Prepare the source tree with injected dependencies
  preppedSrc =
    clojureHelpers.mkPreparedClojureSrc { inherit name src cljLibs; };

  # Use mkCljLib but override the buildCommand to run tests
  # This leverages all of clj-nix's dependency resolution machinery
in mkCljLib {
  jdkRunner = jdk;
  name = "${name}-tests";
  projectSrc = preppedSrc;
  lockfile = "${preppedSrc}/deps-lock.json";
  version = "test";

  # Override the build command to run tests instead of building a JAR
  buildCommand = ''
    # Run tests with eftest using the test alias
    # clj-nix has already resolved all dependencies from deps-lock.json
    clojure -M:${testAlias}

    # Create a marker file to satisfy mkCljLib's expectation of a JAR output
    # Extract the Maven group path from the name (e.g., "org.fudo/klaxon" -> "org/fudo")
    group_path=$(echo "${name}" | sed 's|/.*||' | tr '.' '/')
    artifact=$(echo "${name}" | sed 's|.*/||')
    mkdir -p "target/$group_path"
    touch "target/$group_path/$artifact-tests.jar"
  '';
}
