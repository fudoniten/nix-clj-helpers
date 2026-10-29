# Examples

## Synchronizing Git Dependencies

This example shows how to use the `update-git-deps` tool to keep Git dependencies synchronized between `deps.edn` and `flake.nix`.

### Scenario

You have a Clojure project with dependencies on your own libraries that aren't published to Clojars or Maven. You use Nix flakes to manage these dependencies in your development environment, but you also need to specify them as Git dependencies in `deps.edn` for tools like `clj` to work.

### Setup

**deps.edn:**
```clojure
{:paths ["src"]
 :deps {org.clojure/clojure {:mvn/version "1.12.0"}

        ;; Your own libraries as Git dependencies
        com.github.myuser/auth-lib {:git/url "https://github.com/myuser/auth-lib"
                                     :git/sha "abc123def456..."}

        com.github.myuser/db-lib {:git/url "https://github.com/myuser/db-lib"
                                   :git/sha "789xyz012..."}}}
```

**flake.nix:**
```nix
{
  inputs = {
    nixpkgs.url = "nixpkgs/nixos-26.05";
    nix-clj-helpers.url = "github:fudoniten/nix-clj-helpers";

    # Your library dependencies
    auth-lib = {
      url = "github:myuser/auth-lib";
      flake = false;
    };

    db-lib = {
      url = "github:myuser/db-lib";
      flake = false;
    };
  };

  outputs = { self, nixpkgs, nix-clj-helpers, auth-lib, db-lib, ... }:
    let
      system = "x86_64-linux";
      helpers = nix-clj-helpers.legacyPackages.${system};

      # Build your libraries from the flake inputs
      authLib = helpers.mkClojureLib {
        name = "auth-lib";
        src = auth-lib;
        version = "1.0.0";
      };

      dbLib = helpers.mkClojureLib {
        name = "db-lib";
        src = db-lib;
        version = "1.0.0";
      };
    in {
      packages.${system}.default = helpers.mkClojureBin {
        name = "my-app";
        src = ./.;
        primaryNamespace = "my.app.core";
        version = "1.0.0";

        # Inject the built libraries
        cljLibs = {
          "com.github.myuser/auth-lib" = authLib;
          "com.github.myuser/db-lib" = dbLib;
        };
      };
    };
}
```

### Problem

When you update one of your libraries (auth-lib or db-lib), you need to:
1. Find the new commit SHA
2. Update `deps.edn` with the new `:git/sha`
3. Update `flake.nix` to pin the same commit
4. Run `nix flake lock` to update `flake.lock`
5. Run `updateClojureDeps` to update `deps-lock.json`

This is tedious and error-prone, especially when you have multiple dependencies.

### Solution

Use the `update-git-deps` tool to automate this:

```bash
# Update all Git dependencies to their latest commits
nix run github:fudoniten/nix-clj-helpers#update-git-deps
```

This will:
1. Find the Git dependencies in your `deps.edn`
2. Fetch the latest commit SHA from each repository
3. Update `deps.edn` with the new SHAs
4. Match the dependencies to your flake inputs (by URL)
5. Update `flake.nix` to reference the specific commits
6. Run `nix flake lock` to update the lock file
7. Run `updateClojureDeps` to update `deps-lock.json`

### Result

**Updated deps.edn:**
```clojure
{:paths ["src"]
 :deps {org.clojure/clojure {:mvn/version "1.12.0"}

        com.github.myuser/auth-lib {:git/url "https://github.com/myuser/auth-lib"
                                     :git/sha "new-commit-sha-1..."}

        com.github.myuser/db-lib {:git/url "https://github.com/myuser/db-lib"
                                   :git/sha "new-commit-sha-2..."}}}
```

**Updated flake.nix:**
```nix
{
  inputs = {
    nixpkgs.url = "nixpkgs/nixos-26.05";
    nix-clj-helpers.url = "github:fudoniten/nix-clj-helpers";

    auth-lib = {
      url = "github:myuser/auth-lib/new-commit-sha-1...";
      flake = false;
    };

    db-lib = {
      url = "github:myuser/db-lib/new-commit-sha-2...";
      flake = false;
    };
  };
  # ... rest of the file
}
```

Both files now reference the exact same commits, ensuring your development environment and deployments are in sync.

### Advanced Usage

#### Preview Changes Without Applying

```bash
nix run github:fudoniten/nix-clj-helpers#update-git-deps -- --dry-run
```

Output:
```
========================================
Found 2 Git dependencies in deps.edn
========================================

Processing: com.github.myuser/auth-lib
  Current URL: https://github.com/myuser/auth-lib
  Current SHA: abc123def456...
  Fetching latest commit...
  → Latest SHA:   def789abc123...
  → Matched to flake input: auth-lib

Processing: com.github.myuser/db-lib
  Current URL: https://github.com/myuser/db-lib
  Current SHA: 789xyz012...
  → Already up to date

========================================
Summary of Updates
========================================

deps.edn updates: 1 dependencies
  - com.github.myuser/auth-lib → def789abc123...

flake.nix updates: 1 inputs
  - auth-lib → github:myuser/auth-lib/def789abc123...

[DRY RUN] No files were modified.
```

#### Pin a Specific Version

If you need to pin one library to a specific commit (e.g., a known-good version while debugging):

```bash
nix run github:fudoniten/nix-clj-helpers#update-git-deps -- \
  --override myuser/auth-lib=abc123def456...
```

This will update db-lib to the latest commit but keep auth-lib at the specified SHA.

#### Update Without Lock Files

If you want to update just the source files without regenerating lock files:

```bash
nix run github:fudoniten/nix-clj-helpers#update-git-deps-no-locks
```

Then manually run lock updates later:
```bash
nix flake lock
nix run .#update-clojure-deps
```

### Naming Conventions

The tool matches Git dependencies to flake inputs by:
1. **URL matching** (primary): It compares the GitHub URLs
2. **Name matching** (fallback): It tries to match the library name to the input name

For best results, keep your flake input names aligned with your library names:
- `deps.edn`: `com.github.myuser/my-lib`
- `flake.nix` input: `my-lib` (the part after the `/`)

If the automated matching doesn't work, the tool will skip that dependency with a warning. You can then manually update those entries.

### Workflow Integration

Add this to your project's README or development documentation:

```markdown
## Updating Dependencies

To update to the latest versions of our internal libraries:

1. Run the update tool:
   ```bash
   nix run github:fudoniten/nix-clj-helpers#update-git-deps
   ```

2. Review the changes:
   ```bash
   git diff deps.edn flake.nix flake.lock deps-lock.json
   ```

3. Test the build:
   ```bash
   nix build
   ```

4. Commit the updates:
   ```bash
   git add deps.edn flake.nix flake.lock deps-lock.json
   git commit -m "Update Git dependencies to latest versions"
   ```
```

### Troubleshooting

#### "Failed to fetch commits from ..."

This usually means:
- The repository URL is incorrect
- You don't have access to the repository (private repo without credentials)
- Network connectivity issues

Check your Git credentials and repository access.

#### "No matching flake input found"

The tool couldn't automatically match a Git dependency to a flake input. This can happen if:
- The input name doesn't match the library name
- The URLs are in different formats (e.g., HTTPS vs SSH)

You can either:
1. Rename your flake input to match the library name
2. Manually update that particular dependency

#### Changes not reflected in build

Make sure to run `nix flake lock` and rebuild after updating:
```bash
nix flake lock
nix build
```

The flake lock file might be caching old references.
