# Dependency injection tools for Clojure projects
#
# This module provides tools to inject local Clojure dependencies into deps.edn
# files, replacing Maven coordinates with :local/root paths. This enables using
# local libraries without publishing them to Maven Central.
#
# Both tools are implemented as Babashka scripts, avoiding the need for a
# full JVM build (and the chicken-and-egg dependency problem that entails).

{ pkgs }:

with pkgs.lib;

rec {
  # --------------------------------------------------------------------
  # Dependency Injection Tools
  # --------------------------------------------------------------------

  # The injector binary - replaces Maven deps with :local/root paths
  cljInjectBin = pkgs.writeShellApplication {
    name = "injector";
    runtimeInputs = [ pkgs.babashka ];
    text = ''exec bb ${./lib/injector.bb} "$@"'';
  };

  # Wrapper that invokes injector with a map of dependencies to inject.
  # deps: { "org.myorg/my-lib" = /nix/store/...-my-lib.jar; }
  cljInject = deps:
    pkgs.writeShellApplication {
      name = "clj-inject";
      runtimeInputs = [ cljInjectBin ];
      text = let
        # Build CLI arguments: 'lib-coord' 'jar-path' pairs
        # Single quotes protect against special characters in paths
        injectionString = concatStringsSep " "
          (mapAttrsToList (lib: jar: "'${lib}' '${jar}'") deps);
      in ''injector --deps-file="$1" ${injectionString}'';
    };

  # The build-injector binary - adds :build alias with tools.build
  cljBuildInjectBin = pkgs.writeShellApplication {
    name = "build-injector";
    runtimeInputs = [ pkgs.babashka ];
    text = ''exec bb ${./lib/build-injector.bb} "$@"'';
  };

  # Wrapper that invokes build-injector with namespace and dependencies.
  # ns: Build namespace (typically "build")
  # deps: { "io.github.clojure/tools.build" = "0.10.6"; }
  cljBuildInject = ns: deps:
    pkgs.writeShellApplication {
      name = "clj-build-inject";
      runtimeInputs = [ cljBuildInjectBin ];
      text = let
        # Build CLI arguments: 'lib-coord' 'version' pairs
        injectionString = concatStringsSep " "
          (mapAttrsToList (lib: ver: "'${lib}' '${ver}'") deps);
      in ''
        build-injector --deps-file="$1" --build-namespace='${ns}' ${injectionString}'';
    };
}
