#!/usr/bin/env bb
;;
;; update-git-deps.bb - Synchronize Git dependencies across deps.edn, flake.nix, and deps-lock.json
;;
;; This script:
;; 1. Finds all Git dependencies in deps.edn (:git/url and :git/sha entries)
;; 2. Fetches the latest commit SHA for each repository
;; 3. Updates deps.edn with the new SHAs
;; 4. Updates flake.nix inputs to reference the same commits
;; 5. Optionally updates flake.lock and deps-lock.json
;;
;; Usage:
;;   update-git-deps.bb [--deps-file deps.edn] [--flake-file flake.nix] [--update-locks]
;;                      [--override lib-name=commit-sha]
;;
;; Options:
;;   --deps-file FILE      Path to deps.edn (default: deps.edn)
;;   --flake-file FILE     Path to flake.nix (default: flake.nix)
;;   --update-locks        Run nix flake lock and updateClojureDeps after updating
;;   --override LIB=SHA    Pin a specific library to a specific commit
;;                         Format: github-owner/repo=abc123...
;;                         Can be specified multiple times
;;   --dry-run             Show what would be updated without making changes
;;   --help                Show this help message

(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.pprint :as pprint]
         '[clojure.java.shell :refer [sh]]
         '[clojure.tools.cli :refer [parse-opts]])

;; ============================================================================
;; Git Operations
;; ============================================================================

(defn parse-git-url
  "Parse a Git URL to extract owner and repo.
   Handles: https://github.com/owner/repo, git@github.com:owner/repo, github:owner/repo"
  [url]
  (cond
    ;; GitHub HTTPS: https://github.com/owner/repo or https://github.com/owner/repo.git
    (re-matches #"https://github\.com/([^/]+)/([^/\.]+)(\.git)?" url)
    (let [[_ owner repo] (re-matches #"https://github\.com/([^/]+)/([^/\.]+)" url)]
      {:owner owner :repo repo :github? true})

    ;; GitHub SSH: git@github.com:owner/repo or git@github.com:owner/repo.git
    (re-matches #"git@github\.com:([^/]+)/([^/\.]+)(\.git)?" url)
    (let [[_ owner repo] (re-matches #"git@github\.com:([^/]+)/([^/\.]+)" url)]
      {:owner owner :repo repo :github? true})

    ;; GitHub shorthand with ref: github:owner/repo/ref
    (re-matches #"github:([^/]+)/([^/]+)/.+" url)
    (let [[_ owner repo] (re-matches #"github:([^/]+)/([^/]+)/.+" url)]
      {:owner owner :repo repo :github? true})

    ;; GitHub shorthand: github:owner/repo
    (re-matches #"github:([^/]+)/([^/]+)" url)
    (let [[_ owner repo] (re-matches #"github:([^/]+)/([^/]+)" url)]
      {:owner owner :repo repo :github? true})

    :else
    {:url url :github? false}))

(defn get-latest-commit
  "Fetch the latest commit SHA for a Git repository using git ls-remote.
   Returns the full SHA (40 characters) or nil if failed."
  [git-url]
  (try
    (let [result (sh "git" "ls-remote" git-url "HEAD")]
      (if (zero? (:exit result))
        (-> (:out result)
            str/trim
            (str/split #"\s+")
            first)
        (do
          (println "ERROR: Failed to fetch commits from" git-url)
          (println "       " (:err result))
          nil)))
    (catch Exception e
      (println "ERROR: Exception fetching commits from" git-url)
      (println "       " (.getMessage e))
      nil)))

(defn normalize-github-url
  "Convert various GitHub URL formats to canonical https://github.com/owner/repo format"
  [url]
  (let [parsed (parse-git-url url)]
    (if (:github? parsed)
      (str "https://github.com/" (:owner parsed) "/" (:repo parsed))
      url)))

;; ============================================================================
;; deps.edn Parsing and Updating
;; ============================================================================

(defn find-git-deps
  "Walk through a deps.edn map and find all Git dependencies.
   Returns a vector of maps with :lib-name, :git/url, :git/sha, and :path (for updating)"
  [deps-map]
  (let [results (atom [])]
    (letfn [(walk-deps [path m]
              (doseq [[k v] m]
                (when (map? v)
                  (if (and (:git/url v) (:git/sha v))
                    ;; Found a git dependency
                    (swap! results conj
                           {:lib-name k
                            :git/url (:git/url v)
                            :git/sha (:git/sha v)
                            :path path})
                    ;; Recurse into nested maps
                    (walk-deps (conj path k) v)))))]
      ;; Walk :deps at root level
      (when-let [deps (:deps deps-map)]
        (walk-deps [:deps] deps))

      ;; Walk :extra-deps in all aliases
      (when-let [aliases (:aliases deps-map)]
        (doseq [[alias-name alias-config] aliases]
          (when-let [extra-deps (:extra-deps alias-config)]
            (walk-deps [:aliases alias-name :extra-deps] extra-deps)))))
    @results))

(defn update-git-sha
  "Update the :git/sha value in a nested map at the given path.
   path is a vector like [:deps lib-name] or [:aliases :test :extra-deps lib-name]"
  [deps-map path lib-name new-sha]
  (let [dep-path (conj path lib-name :git/sha)]
    (assoc-in deps-map dep-path new-sha)))

(defn update-deps-edn
  "Update all Git dependencies in deps.edn with new SHAs.
   sha-updates is a map of {lib-name new-sha}"
  [deps-map sha-updates]
  (reduce
   (fn [acc [lib-name new-sha]]
     (let [git-deps (find-git-deps acc)
           matching-dep (first (filter #(= (:lib-name %) lib-name) git-deps))]
       (if matching-dep
         (update-git-sha acc (:path matching-dep) lib-name new-sha)
         acc)))
   deps-map
   sha-updates))

;; ============================================================================
;; flake.nix Parsing and Updating
;; ============================================================================

(defn parse-flake-inputs
  "Parse flake.nix content to extract input declarations.
   Returns a vector of maps with :name, :url, :line-number"
  [flake-content]
  (let [lines (str/split-lines flake-content)
        results (atom [])]
    (doseq [[idx line] (map-indexed vector lines)]
      ;; Match patterns like: input-name.url = "github:owner/repo/ref";
      ;; or: input-name = { url = "github:owner/repo"; ... };
      (when-let [[_ input-name url] (re-matches #"\s*([a-zA-Z0-9_-]+)\.url\s*=\s*\"([^\"]+)\".*" line)]
        (swap! results conj {:name input-name
                             :url url
                             :line-number (inc idx)
                             :line line}))

      ;; Also match inline url = "..." inside input blocks
      (when-let [[_ url] (re-matches #"\s*url\s*=\s*\"([^\"]+)\".*" line)]
        ;; Need to find the input name by looking backwards
        ;; This is a simplified approach - looks for "name = {" pattern
        (let [prev-lines (take idx lines)
              input-name (some (fn [prev-line]
                                 (when-let [[_ name] (re-matches #"\s*([a-zA-Z0-9_-]+)\s*=\s*\{.*" prev-line)]
                                   name))
                               (reverse prev-lines))]
          (when input-name
            (swap! results conj {:name input-name
                                 :url url
                                 :line-number (inc idx)
                                 :line line})))))
    @results))

(defn github-url-to-flake-url
  "Convert a GitHub URL to flake URL format with commit pinned.
   e.g., https://github.com/owner/repo + sha -> github:owner/repo/sha"
  [git-url commit-sha]
  (let [parsed (parse-git-url git-url)]
    (if (:github? parsed)
      (str "github:" (:owner parsed) "/" (:repo parsed) "/" commit-sha)
      ;; For non-GitHub URLs, use git+https format
      (str "git+" git-url "?rev=" commit-sha))))

(defn match-git-dep-to-input
  "Try to match a Git dependency to a flake input by URL or name.
   Returns the matching input or nil."
  [git-dep flake-inputs]
  (let [normalized-url (normalize-github-url (:git/url git-dep))
        lib-name-str (name (:lib-name git-dep))
        ;; Extract potential input name from lib-name
        ;; e.g., com.github.user/repo -> repo, or just use the whole name
        potential-names [(second (str/split lib-name-str #"/"))
                         lib-name-str
                         (str/replace lib-name-str #"\." "-")]]
    ;; Try to find matching input by URL first
    (or
     ;; Match by URL (normalize both to compare)
     (first (filter (fn [input]
                      (let [input-parsed (parse-git-url (:url input))]
                        (when (:github? input-parsed)
                          (let [input-normalized (str "https://github.com/"
                                                      (:owner input-parsed) "/"
                                                      (:repo input-parsed))]
                            (= normalized-url input-normalized)))))
                    flake-inputs))
     ;; Match by name (heuristic)
     (first (filter (fn [input]
                      (some #(= (:name input) %) potential-names))
                    flake-inputs)))))

(defn update-flake-url-in-line
  "Update a single line in flake.nix to use the new commit-pinned URL"
  [line new-url]
  (str/replace line #"\"[^\"]+\"" (str "\"" new-url "\"")))

(defn update-flake-nix
  "Update flake.nix content with new commit-pinned URLs.
   updates is a map of {input-name {:url new-url}}"
  [flake-content updates]
  (let [lines (str/split-lines flake-content)
        flake-inputs (parse-flake-inputs flake-content)
        ;; Build a map of line-number -> new-url
        line-updates (into {}
                           (for [[input-name {:keys [url]}] updates
                                 :let [matching-input (first (filter #(= (:name %) input-name) flake-inputs))]
                                 :when matching-input]
                             [(:line-number matching-input) url]))]
    (->> lines
         (map-indexed (fn [idx line]
                        (if-let [new-url (get line-updates (inc idx))]
                          (update-flake-url-in-line line new-url)
                          line)))
         (str/join "\n")
         (#(str % "\n"))))) ;; Add final newline

;; ============================================================================
;; Main Logic
;; ============================================================================

(defn process-git-deps
  "Main processing logic: find deps, fetch latest commits, prepare updates.
   Returns {:sha-updates {lib-name sha}, :flake-updates {input-name {:url url}}}"
  [deps-map flake-content overrides dry-run?]
  (let [git-deps (find-git-deps deps-map)
        flake-inputs (parse-flake-inputs flake-content)]

    (println "\n========================================")
    (println "Found" (count git-deps) "Git dependencies in deps.edn")
    (println "========================================\n")

    (if (empty? git-deps)
      (do
        (println "No Git dependencies found. Nothing to update.")
        {:sha-updates {} :flake-updates {}})

      (let [sha-updates (atom {})
            flake-updates (atom {})]

        (doseq [{:keys [lib-name git/url git/sha]} git-deps]
          (println "Processing:" lib-name)
          (println "  Current URL:" url)
          (println "  Current SHA:" sha)

          ;; Check for override
          (let [override-key (let [parsed (parse-git-url url)]
                              (if (:github? parsed)
                                (str (:owner parsed) "/" (:repo parsed))
                                url))
                override-sha (get overrides override-key)
                new-sha (or override-sha
                           (do
                             (println "  Fetching latest commit...")
                             (get-latest-commit url)))]

            (if new-sha
              (if (= new-sha sha)
                (println "  ✓ Already up to date")
                (do
                  (if override-sha
                    (println "  → Override SHA:" new-sha)
                    (println "  → Latest SHA:  " new-sha))
                  (swap! sha-updates assoc lib-name new-sha)

                  ;; Try to find matching flake input
                  (when-let [matching-input (match-git-dep-to-input
                                             {:lib-name lib-name :git/url url}
                                             flake-inputs)]
                    (println "  → Matched to flake input:" (:name matching-input))
                    (let [new-flake-url (github-url-to-flake-url (:url matching-input) new-sha)]
                      (swap! flake-updates assoc (:name matching-input) {:url new-flake-url})))))
              (println "  ✗ Failed to fetch latest commit")))

          (println))

        {:sha-updates @sha-updates
         :flake-updates @flake-updates}))))

(defn write-file
  "Write content to a file"
  [file-path content]
  (spit file-path content))

(defn pretty-print-edn
  "Pretty-print an EDN data structure to a string"
  [data]
  (with-out-str (pprint/pprint data)))

;; ============================================================================
;; CLI
;; ============================================================================

(def cli-options
  [[nil "--deps-file FILE" "Path to deps.edn"
    :default "deps.edn"]
   [nil "--flake-file FILE" "Path to flake.nix"
    :default "flake.nix"]
   [nil "--update-locks" "Run nix flake lock and updateClojureDeps after updating"]
   [nil "--override OVERRIDE" "Override a specific library commit (format: owner/repo=sha)"
    :default []
    :assoc-fn (fn [m k v]
                (update m k conj v))]
   [nil "--dry-run" "Show what would be updated without making changes"]
   ["-h" "--help" "Show this help message"]])

(defn parse-overrides
  "Parse override arguments into a map of {lib-key sha}"
  [override-args]
  (into {}
        (for [arg override-args
              :let [[lib-key sha] (str/split arg #"=" 2)]
              :when (and lib-key sha)]
          [lib-key sha])))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]

    (cond
      (:help options)
      (do
        (println "update-git-deps.bb - Synchronize Git dependencies")
        (println)
        (println summary)
        (System/exit 0))

      errors
      (do
        (println "ERROR: Invalid arguments")
        (doseq [error errors]
          (println "  " error))
        (println)
        (println summary)
        (System/exit 1))

      :else
      (let [{:keys [deps-file flake-file update-locks override dry-run]} options
            overrides (parse-overrides override)]

        ;; Validate files exist
        (when-not (.exists (java.io.File. deps-file))
          (println "ERROR: deps.edn file not found:" deps-file)
          (System/exit 1))

        (when-not (.exists (java.io.File. flake-file))
          (println "ERROR: flake.nix file not found:" flake-file)
          (System/exit 1))

        ;; Read files
        (println "Reading" deps-file "and" flake-file "...")
        (let [deps-map (edn/read-string (slurp deps-file))
              flake-content (slurp flake-file)

              ;; Process
              {:keys [sha-updates flake-updates]} (process-git-deps deps-map flake-content overrides dry-run)]

          (if (and (empty? sha-updates) (empty? flake-updates))
            (println "\n✓ All dependencies are up to date!")

            (do
              (println "\n========================================")
              (println "Summary of Updates")
              (println "========================================\n")

              (when (seq sha-updates)
                (println "deps.edn updates:" (count sha-updates) "dependencies")
                (doseq [[lib-name sha] sha-updates]
                  (println "  -" lib-name "→" (subs sha 0 8) "...")))

              (when (seq flake-updates)
                (println "\nflake.nix updates:" (count flake-updates) "inputs")
                (doseq [[input-name {:keys [url]}] flake-updates]
                  (println "  -" input-name "→" url)))

              (if dry-run
                (println "\n[DRY RUN] No files were modified.")

                (do
                  (println "\nWriting updates...")

                  ;; Update deps.edn
                  (when (seq sha-updates)
                    (let [updated-deps (update-deps-edn deps-map sha-updates)]
                      (write-file deps-file (pretty-print-edn updated-deps))
                      (println "  ✓ Updated" deps-file)))

                  ;; Update flake.nix
                  (when (seq flake-updates)
                    (let [updated-flake (update-flake-nix flake-content flake-updates)]
                      (write-file flake-file updated-flake)
                      (println "  ✓ Updated" flake-file)))

                  ;; Optionally update lock files
                  (when update-locks
                    (println "\nUpdating lock files...")

                    ;; Update flake.lock
                    (println "  Running: nix flake lock")
                    (let [result (sh "nix" "flake" "lock")]
                      (if (zero? (:exit result))
                        (println "  ✓ Updated flake.lock")
                        (do
                          (println "  ✗ Failed to update flake.lock")
                          (println (:err result)))))

                    ;; Update deps-lock.json
                    (println "  Running: nix run .#update-clojure-deps --" deps-file)
                    (let [result (sh "nix" "run" ".#update-clojure-deps" "--" deps-file)]
                      (if (zero? (:exit result))
                        (println "  ✓ Updated deps-lock.json")
                        (do
                          (println "  ✗ Failed to update deps-lock.json")
                          (println (:err result))))))

                  (println "\n✓ Done!")))))

          (System/exit 0))))))

;; Run main if executed as script
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
