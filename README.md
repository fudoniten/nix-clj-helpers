# nix-clj-helpers

Nix helpers for building Clojure libraries, CLI applications and tests. These are high-level wrappers around [clj-nix](https://github.com/jlesquembre/clj-nix) with automatic dependency injection, so you can use your own Clojure libraries as dependencies without uploading them to Maven.

These helpers used to live in [fudo-nix-helpers](https://github.com/fudoniten/fudo-nix-helpers), which still re-exports them, so existing flakes keep working. New projects can depend on this flake directly.

## Overview

The standard `tools.deps` workflow assumes network access to Maven Central or Clojars, which doesn't work well in Nix's pure build environment. This flake rewrites `deps.edn` at build time, replacing Maven coordinates with `:local/root` paths pointing to Nix store locations, then builds with clj-nix from your `deps-lock.json`.

## Quick Start

### Using as a Flake Input

Add this flake to your project's `flake.nix`:

```nix
{
  inputs = {
    nixpkgs.url = "nixpkgs/nixos-26.05";
    nix-clj-helpers = {
      url = "github:fudoniten/nix-clj-helpers";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, nix-clj-helpers, ... }:
    let
      system = "x86_64-linux";
      # Builder functions live in legacyPackages; `packages` only holds
      # derivations (flake check rejects functions there).
      helpers = nix-clj-helpers.legacyPackages.${system};
    in {
      packages.${system}.default = helpers.mkClojureBin {
        name = "my-app";
        src = ./.;
        primaryNamespace = "my.app.core";
        version = "1.0.0";
      };
    };
}
```

### Building a Simple Library

```nix
helpers.mkClojureLib {
  name = "my-library";
  src = ./.;
  version = "1.0.0";
}
```

### Building a CLI Application

```nix
helpers.mkClojureBin {
  name = "my-cli";
  src = ./.;
  primaryNamespace = "my.cli.main";  # Namespace with -main function
  version = "1.0.0";
}
```

## Clojure Helpers

### mkClojureLib

Builds a Clojure library JAR file.

#### Parameters

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `name` | Yes | - | Library name (used in JAR filename) |
| `src` | Yes | - | Path to source directory containing `deps.edn` |
| `version` | No | `"0.1"` | Version string |
| `clojure-src-dirs` | No | `["src"]` | List of Clojure source directories |
| `java-src-dirs` | No | `[]` | List of Java source directories (compiled with `--release 21`) |
| `cljLibs` | No | `{}` | Map of local Clojure library dependencies (see below) |
| `buildCommand` | No | auto-generated | Custom build command override |
| `checkPhase` | No | `null` | Custom check/test phase |
| `jdk` | No | `pkgs.jdk25_headless` | JDK used for the build |

#### Example

```nix
helpers.mkClojureLib {
  name = "my-utils";
  src = ./utils;
  version = "2.0.0";
  clojure-src-dirs = [ "src/clj" ];
  java-src-dirs = [ "src/java" ];
}
```

### mkClojureBin

Builds a runnable Clojure CLI application (uberjar with launcher script).

#### Parameters

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `name` | Yes | - | Application name |
| `src` | Yes | - | Path to source directory containing `deps.edn` |
| `primaryNamespace` | Yes | - | Namespace containing `-main` function |
| `version` | No | `"0.1"` | Version string |
| `cljLibs` | No | `{}` | Map of local Clojure library dependencies |
| `buildCommand` | No | `null` | Custom build command override |
| `checkPhase` | No | `null` | Custom check/test phase |
| `jdk` | No | `pkgs.jdk25_headless` | JDK used for the build and baked into the launcher |
| (other) | No | - | Passed through to clj-nix's `mkCljBin` (e.g. `java-opts`) |

#### Example

```nix
helpers.mkClojureBin {
  name = "my-server";
  src = ./server;
  primaryNamespace = "my.server.main";
  version = "1.0.0";
  cljLibs = {
    "org.fudo/my-utils" = myUtilsLib;  # Reference to another mkClojureLib output
  };
}
```

### Using Local Dependencies

The `cljLibs` parameter allows you to specify local Clojure libraries as dependencies. This is the key feature that enables using your own libraries without Maven.

```nix
let
  # Build the library first
  myLib = helpers.mkClojureLib {
    name = "my-lib";
    src = ./lib;
    version = "1.0.0";
  };
in helpers.mkClojureBin {
  name = "my-app";
  src = ./app;
  primaryNamespace = "my.app.main";
  cljLibs = {
    # Key: Maven coordinate as it appears in deps.edn
    # Value: The Nix derivation (JAR file)
    "org.myorg/my-lib" = myLib;
  };
}
```

Your `deps.edn` should reference the dependency normally:

```clojure
{:deps {org.myorg/my-lib {:mvn/version "1.0.0"}}}
```

At build time, the dependency injection tools will replace this with a `:local/root` pointing to the JAR in the Nix store.

### Custom Build Scripts

By default, projects use a shared `build.clj` from this repository. If your project needs custom build logic, create a `build.clj` in your project root and it will be used automatically.

The default build script supports:
- Clojure compilation
- Java compilation (`--release 21`)
- JAR and uberjar creation
- POM file generation

## Dependency Management

### How It Works

The build process uses two injection tools:

1. **injector** (`lib/injector.bb`): Replaces Maven dependencies with `:local/root` paths
2. **build-injector** (`lib/build-injector.bb`): Adds the `:build` alias with `tools.build` configuration

The workflow is:
1. Read your `deps.edn`
2. Run `injector` to replace specified dependencies with local paths
3. Run `build-injector` to add build configuration
4. Pass the modified source to `clj-nix` for building

### Updating Dependencies

After modifying your `deps.edn`, regenerate the lockfile:

```bash
# From your project directory
nix run github:fudoniten/nix-clj-helpers#updateClojureDeps

# Or with a specific deps file
nix run github:fudoniten/nix-clj-helpers#updateClojureDeps -- ./path/to/deps.edn
```

This will:
1. Apply dependency injections
2. Run `deps-lock` to resolve all dependencies
3. Write `deps-lock.json` to your project

**Important**: Your project must have a `deps-lock.json` file for builds to work.

### Synchronizing Git Dependencies

If your project uses Git dependencies (`:git/url` and `:git/sha` in `deps.edn`) that are also declared as Flake inputs, you can automatically synchronize them to use the latest commits:

```bash
# Update all Git dependencies to latest commits and update lock files
nix run github:fudoniten/nix-clj-helpers#update-git-deps

# Preview what would be updated without making changes
nix run github:fudoniten/nix-clj-helpers#update-git-deps -- --dry-run

# Pin a specific library to a specific commit
nix run github:fudoniten/nix-clj-helpers#update-git-deps -- --override owner/repo=abc123...

# Update without automatically regenerating lock files
nix run github:fudoniten/nix-clj-helpers#update-git-deps-no-locks
```

This tool:
1. Finds all `:git/url` and `:git/sha` entries in your `deps.edn`
2. Fetches the latest commit SHA for each repository
3. Updates `deps.edn` with the new SHAs
4. Matches Git dependencies to Flake inputs and updates `flake.nix` to pin the same commits
5. Optionally runs `nix flake lock` and `updateClojureDeps` to update lock files

#### Example Workflow

Your `deps.edn` has a Git dependency:

```clojure
{:deps {com.github.myuser/my-lib {:git/url "https://github.com/myuser/my-lib"
                                  :git/sha "abc123..."}}}
```

Your `flake.nix` declares the same repository as an input:

```nix
{
  inputs = {
    my-lib = {
      url = "github:myuser/my-lib";
      flake = false;
    };
  };
}
```

Running `update-git-deps` will:
- Fetch the latest commit from `github.com/myuser/my-lib`
- Update the `:git/sha` in `deps.edn`
- Update the flake input URL to `github:myuser/my-lib/new-commit-sha`
- Update `flake.lock` and `deps-lock.json` to pin the new version

#### Options

```bash
update-git-deps [OPTIONS]

Options:
  --deps-file FILE       Path to deps.edn (default: deps.edn)
  --flake-file FILE      Path to flake.nix (default: flake.nix)
  --update-locks         Run nix flake lock and updateClojureDeps after updating
  --override LIB=SHA     Pin a specific library to a specific commit
                         Format: github-owner/repo=abc123...
                         Can be specified multiple times
  --dry-run              Show what would be updated without making changes
  --help                 Show help message
```

### Running Injectors Directly

For debugging or manual use:

```bash
# Inject local dependencies
nix run .#cljInject -- --deps-file deps.edn org.myorg/my-lib /nix/store/xxx-my-lib.jar

# Inject build configuration
nix run .#cljBuildInject -- --deps-file deps.edn --build-namespace build io.github.clojure/tools.build 0.10.6
```

## Repository Layout

```
nix-clj-helpers/
├── flake.nix                 # Entry point; mkHelpers builds everything per system
├── clojure-helpers.nix       # Shared Clojure source preparation (injection)
├── clojure-lib.nix           # mkClojureLib
├── clojure-bin.nix           # mkClojureBin
├── clojure-tests.nix         # mkClojureTests
├── dependency-injection.nix  # cljInject / cljBuildInject wrappers
├── dependency-management.nix # updateClojureDeps, update-git-deps
└── lib/
    ├── build.clj             # Default tools.build script
    ├── injector.bb           # Babashka: swap Maven deps for :local/root
    ├── build-injector.bb     # Babashka: add the :build alias
    └── update-git-deps.bb    # Babashka: sync git deps with flake inputs
```

## Troubleshooting

### "Missing deps-lock.json"

Run `updateClojureDeps` to generate the lockfile:

```bash
nix run github:fudoniten/nix-clj-helpers#updateClojureDeps
```

### "Dependency not found" during build

Ensure the Maven coordinate in `cljLibs` exactly matches what's in your `deps.edn`:

```nix
# deps.edn has: org.myorg/my-lib {:mvn/version "1.0.0"}
cljLibs = {
  "org.myorg/my-lib" = myLibDerivation;  # Must match exactly
};
```

### Custom build.clj not being used

Ensure your `build.clj` is in the root of your `src` directory (same level as `deps.edn`).

### Java compilation errors

The default build script compiles Java sources with `--release 21`. If you need a different target, provide a custom `build.clj` with modified javac options.

### Choosing a JDK

Builds default to `jdk25_headless` (the current LTS), or `jdk21_headless` when your flake makes nixpkgs follow a release older than 25.11, which has no JDK 25. For `mkClojureBin` the same JDK is baked into the launcher script, so it is also the runtime. To stay on an older LTS for a particular project, pass `jdk`:

```nix
helpers.mkClojureBin {
  name = "my-app";
  src = ./.;
  primaryNamespace = "my.app.main";
  jdk = pkgs.jdk21_headless;
}
```

