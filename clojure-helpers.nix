# Shared helpers for Clojure library and binary builders
#
# This module provides utilities for preparing Clojure project sources with
# injected dependencies. The key function is mkPreparedClojureSrc, which
# creates a modified source tree where:
#
#   1. deps.edn has local dependencies injected (via cljInject)
#   2. deps.edn has build configuration injected (via cljBuildInject)
#   3. A build.clj script is present (either from the project or the default)
#
# This prepared source can then be passed to clj-nix's mkCljLib or mkCljBin.

{ lib, stdenv, cljInject, cljBuildInject, cljBuildToolsVersion }:

with lib;

{
  # Prepare a Clojure project source with injected dependencies and build configuration.
  #
  # This function performs the dependency injection that allows local Clojure
  # libraries to be used as dependencies without uploading them to Maven.
  #
  # Parameters:
  #   name: Project name (used for derivation naming)
  #   src: Path to source directory containing deps.edn
  #   cljLibs: Map of Maven coordinates to Nix store paths
  #            e.g., { "org.myorg/my-lib" = /nix/store/...-my-lib.jar; }
  #
  # Returns:
  #   A derivation containing the modified source tree, ready for clj-nix
  mkPreparedClojureSrc = { name, src, cljLibs ? { } }:
    let
      # Convert Nix paths to strings for the injection tools
      cljLibsStringified = mapAttrs (_: path: "${path}") cljLibs;

      # Stage 1: Create modified deps.edn with all injections applied
      #
      # This derivation runs both injection tools:
      # - cljInject: Replaces Maven deps with :local/root paths
      # - cljBuildInject: Adds the :build alias with tools.build
      depsFile = stdenv.mkDerivation {
        name = "${name}-deps.edn";
        buildInputs = [
          (cljInject cljLibsStringified)
          (cljBuildInject "build" {
            "io.github.clojure/tools.build" = cljBuildToolsVersion;
          })
        ];
        phases = [ "installPhase" ];
        installPhase = ''
          mkdir -p $out
          # First pass: inject local dependency overrides
          clj-inject ${src}/deps.edn > pre-deps.edn
          # Second pass: inject build alias configuration
          clj-build-inject pre-deps.edn > $out/deps.edn
          # Show the result for debugging
          cat $out/deps.edn
        '';
      };

      # Paths for build script selection
      defaultBuildClj = ./lib/build.clj;
      projectBuildClj = "${src}/build.clj";

    # Stage 2: Assemble the prepared source tree
    #
    # This derivation copies the original source and overlays:
    # - The modified deps.edn (with injections)
    # - A build.clj script (project's own or the default)
    in stdenv.mkDerivation {
      name = "${name}-prepped";
      phases = [ "installPhase" ];
      installPhase = ''
        mkdir $out
        # Copy all original source files
        cp -r ${src}/. $out
        # Replace deps.edn with the injected version
        rm $out/deps.edn
        cp ${depsFile}/deps.edn $out

        # Use project's build.clj if present, otherwise use default
        # This allows projects to customize their build process
        if [ -f "${projectBuildClj}" ]; then
          echo "Using project's build.clj"
        else
          echo "Using default build.clj"
          cp ${defaultBuildClj} $out/build.clj
        fi
      '';
    };
}
