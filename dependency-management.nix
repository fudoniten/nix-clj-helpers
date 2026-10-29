# Dependency management tools for Clojure projects
#
# This module provides tools for updating and managing Clojure dependencies,
# particularly for regenerating deps-lock.json files with injected dependencies.

{ pkgs, system, cljInject, cljBuildInject, cljBuildToolsVersion, deps-lock }:

rec {
  # --------------------------------------------------------------------
  # Dependency Management
  # --------------------------------------------------------------------

  # Helper function to create a script that regenerates deps-lock.json with
  # all injections applied.
  # Parameters:
  #   deps: Attribute set of local Clojure dependencies to inject
  #   aliases: List of alias names to include when locking (default: [])
  # Returns: A derivation that can be used in buildInputs or run directly
  # Usage in consumer flake: (updateClojureDeps {})
  # Usage via nix run: nix run .#updateClojureDeps
  #                    nix run .#updateClojureDeps -- path/to/deps.edn
  # For tests: (updateClojureDeps { aliases = ["test"]; })
  updateClojureDeps = { deps ? {}, aliases ? [] }:
    let
      # Build the alias flags for deps-lock
      aliasFlags = if aliases == [] then ""
                   else "--alias-include ${pkgs.lib.concatStringsSep "," aliases}";
    in
    pkgs.writeShellApplication {
      name = "update-deps.sh";
      runtimeInputs = [
        (cljInject deps)
        (cljBuildInject "build" {
          "io.github.clojure/tools.build" = cljBuildToolsVersion;
        })
        deps-lock
      ];
      text = ''
        if [ $# -eq 0 ]; then
          DEPS="$(pwd)/deps.edn"
        elif [ $# -eq 1 ]; then
          DEPS="$1"
        else
          echo "usage: $0 [deps-file]"
          exit 1
        fi
        SRC=$(pwd)
        TMP=$(mktemp -d)
        # First pass: inject local dependency overrides
        clj-inject "$DEPS" > "$TMP/deps-prebuild.edn"
        # Second pass: inject build alias configuration
        clj-build-inject "$TMP/deps-prebuild.edn" > "$TMP/deps.edn"
        echo "DEPS.EDN:"
        cat "$TMP/deps.edn"
        cd "$TMP"
        # Generate the lockfile
        # Include specified aliases to lock their dependencies too
        deps-lock ${aliasFlags}
        mv "$TMP/deps-lock.json" "$SRC/deps-lock.json"
      '';
    };

  # --------------------------------------------------------------------
  # Git Dependency Synchronization
  # --------------------------------------------------------------------

  # Binary wrapper for the Git dependency updater Babashka script
  updateGitDepsBin = pkgs.writeShellApplication {
    name = "update-git-deps";
    runtimeInputs = [ pkgs.babashka pkgs.git ];
    text = ''exec bb ${./lib/update-git-deps.bb} "$@"'';
  };

  # Pre-configured update-git-deps with common defaults
  # This can be run via: nix run .#update-git-deps
  # or used as a build input
  update-git-deps = pkgs.writeShellApplication {
    name = "update-git-deps";
    runtimeInputs = [ updateGitDepsBin pkgs.nix ];
    text = ''
      # Run the git deps updater with lock file updates enabled by default
      exec update-git-deps --update-locks "$@"
    '';
  };

  # Variant without automatic lock updates (for manual control)
  update-git-deps-no-locks = pkgs.writeShellApplication {
    name = "update-git-deps-no-locks";
    runtimeInputs = [ updateGitDepsBin ];
    text = ''
      exec update-git-deps "$@"
    '';
  };
}
