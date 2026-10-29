;; Default build script for Clojure projects using nix-clj-helpers
;;
;; This script provides standard build tasks (jar, uberjar, install) that work
;; with the nix-clj-helpers build system. It's automatically copied into projects
;; that don't provide their own build.clj.
;;
;; The script is invoked via `clojure -T:build <task>` where <task> is one of:
;;   - jar     : Build a library JAR file
;;   - uberjar : Build a standalone JAR with all dependencies
;;   - install : Install to local Maven repository
;;
;; Parameters are passed as keyword arguments:
;;   clojure -T:build uberjar :name my-lib :version 1.0.0 :target ./target
;;
;; Required parameters vary by task but typically include:
;;   :name      - Project name (used in JAR filename)
;;   :version   - Version string
;;   :target    - Output directory for build artifacts
;;   :namespace - Maven group ID (e.g., "org.myorg")
;;
;; Optional parameters:
;;   :clj-src  - Comma-separated list of Clojure source directories
;;   :java-src - Comma-separated list of Java source directories
;;   :verbose  - Enable verbose output (default: false)
;;   :metadata - Path to metadata.edn file with additional params

(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.pprint :refer [pprint]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.set :refer [difference]]))

;; Keys that must be present after parameter processing
(def required-keys
  #{:verbose
    :lib
    :version
    :basis
    :class-dir
    :namespace
    :name
    :target})

;;; ----------------------------------------------------------------------------
;;; Parameter Processing Helpers
;;; ----------------------------------------------------------------------------

(defn lib-name
  "Construct a Maven-style library name symbol from namespace and name.
   Example: (lib-name \"org.myorg\" \"my-lib\") => 'org.myorg/my-lib"
  [ns name]
  (symbol (str ns "/" name)))

(defn add-lib-name
  "Add the :lib key by combining :namespace and :name."
  [{:keys [namespace name] :as params}]
  (assoc params :lib (lib-name namespace name)))

(defn- add-class-dir
  "Set :class-dir to {target}/classes for compiled output."
  [{:keys [target] :as params}]
  (assoc params :class-dir
         (format "%s/classes" target)))

(defn- jar-file
  "Compute the output path for a library JAR: {target}/{name}-{version}.jar"
  [{:keys [target version name]}]
  (format "%s/%s-%s.jar" target name version))

(defn- uberjar-file
  "Compute the output path for an uberjar: {target}/{name}-uber-{version}.jar"
  [{:keys [target version name]}]
  (format "%s/%s-uber-%s.jar" target name version))

;; Default values for optional parameters
(def default-params
  {:verbose   false
   :version   "DEV"
   :namespace "fudo.org"
   :src-dirs  []})

;;; ----------------------------------------------------------------------------
;;; Build Steps
;;; ----------------------------------------------------------------------------

(defn clean
  "Remove previously generated build artifacts from the target directory."
  [{:keys [target] :as params}]
  (b/delete {:path (str target)})
  params)

(defn compile-java
  "Compile Java source files to bytecode.

  If :java-src is provided (comma-separated directory list), compiles all
  .java files to the :class-dir, targeting Java 21 bytecode (--release 21).

  Adds the Java source directories to :src-dirs for inclusion in the JAR."
  [{:keys [verbose java-src class-dir basis] :as params}]
  (if java-src
    (let [java-src (str/split (str java-src) #",")]
      (when verbose (println (format "compiling java files in %s..." java-src)))
      (b/javac {:src-dirs   java-src
                :class-dir  class-dir
                :basis      basis
                ;; Java 21 is the oldest LTS we expect to run on; --release
                ;; also checks API usage against it, unlike -source/-target
                :javac-opts ["--release" "21"]})
      (update params :src-dirs concat java-src))
    (do (when verbose (println "skipping java compile, no java-src specified..."))
        params)))

(defn compile-clj
  "Compile Clojure source files to bytecode.

  If :clj-src is provided (comma-separated directory list), AOT compiles all
  Clojure namespaces to the :class-dir.

  Adds the Clojure source directories to :src-dirs for inclusion in the JAR."
  [{:keys [verbose clj-src class-dir basis] :as params}]
  (if clj-src
    (let [clj-src (str/split (str clj-src) #",")]
      (when verbose (println (format "compiling clj files in %s..." clj-src)))
      (b/compile-clj {:basis     basis
                      :src-dirs  clj-src
                      :class-dir class-dir})
      (update params :src-dirs concat clj-src))
    (do (when verbose (println "skipping clj compile, no clj-src specified..."))
        params)))

(defn- read-metadata-from-file
  "Read additional build parameters from an EDN metadata file.

  This allows projects to store build configuration in a separate file
  (default: metadata.edn) rather than passing everything on the command line."
  [filename]
  (if (.exists (io/file filename))
    (do (println (format "reading metadata from %s..." filename))
        (-> filename
            (slurp)
            (edn/read-string)))
    (println (format "skipping nonexistent metadata file %s..." filename))))

(defn ensure-keys
  "Validate that all required keys are present in the params map.
   Throws RuntimeException if any are missing."
  [m ks]
  (let [missing-keys (->> m (keys) (set) (difference ks))]
    (when (seq missing-keys)
      (throw (RuntimeException. (format "Missing required keys: %s"
                                        (str/join ", " (map name missing-keys)))))))
  m)

(defn- add-basis
  "Create and add the tools.build basis to params."
  [params]
  (assoc params :basis (b/create-basis {:project "deps.edn"})))

(defn- process-params
  "Process and validate build parameters.

  Merges defaults, command-line params, and metadata file params (in that order
  of precedence). Computes derived values like :lib and :class-dir, and ensures
  all required keys are present."
  [base-params]
  (-> default-params
      (merge base-params)
      ;; Metadata file can override defaults but not CLI params
      (merge (read-metadata-from-file
              (or (:metadata base-params)
                  "metadata.edn")))
      (add-basis)
      (add-lib-name)
      (add-class-dir)
      (ensure-keys required-keys)
      ;; Normalize types for consistency
      (update :target str)
      (update :version str)
      (update :name str)
      (update :namespace str)))

(defn write-pom
  "Generate a pom.xml file in the class directory.

  The POM is required for Maven compatibility and contains project metadata
  like group ID, artifact ID, version, and dependencies."
  [{:keys [lib version basis src-dirs verbose class-dir] :as params}]
  (when verbose (println (format "writing POM file to %s..." class-dir)))
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     basis
                :src-dirs  src-dirs})
  params)

(defn- copy-src-to-target
  "Copy source files to the class directory for inclusion in the JAR.

  This ensures source code is available in the JAR for debugging and
  for Clojure's runtime compilation if needed."
  [{:keys [verbose src-dirs class-dir] :as params}]
  (when verbose (println (format "copying source files from %s to %s..."
                                 src-dirs class-dir)))
  (b/copy-dir {:src-dirs src-dirs :target-dir class-dir})
  params)

(defn- finalize
  "Print completion message."
  [params]
  (println "done!")
  params)

(defn- write-jar
  "Create a JAR file from the compiled classes and sources."
  [{:keys [class-dir verbose] :as params}]
  (let [jarfile (jar-file params)]
    (when verbose (println (format "writing jar file to %s..." jarfile)))
    (b/jar {:class-dir class-dir :jar-file jarfile})
    params))

(defn- write-uberjar
  "Create an uberjar containing all dependencies.

  An uberjar bundles all transitive dependencies into a single JAR file,
  making it self-contained and runnable without external dependencies."
  [{:keys [verbose class-dir basis] :as params}]
  (let [uberjar (uberjar-file params)]
    (when verbose (println (format "writing uberjar file to %s..." uberjar)))
    (b/uber {:class-dir class-dir :uber-file uberjar :basis basis})
    params))

(defn- install-to-local
  "Install the JAR to the local Maven repository (~/.m2/repository).

  This makes the library available as a dependency for other local projects."
  [{:keys [verbose basis lib version jar class-dir] :as params}]
  (when verbose (println (format "installing %s..." jar)))
  (b/install {:basis     basis
              :lib       lib
              :version   version
              :jar-file  jar
              :class-dir class-dir})
  params)

;;; ----------------------------------------------------------------------------
;;; Public Build Tasks
;;; ----------------------------------------------------------------------------

(defn jar
  "Build a library JAR file.

  This is the main entry point for building a Clojure library. It:
  1. Processes and validates parameters
  2. Compiles Java sources (if any)
  3. Compiles Clojure sources (if any)
  4. Generates a POM file
  5. Copies source files to the output
  6. Creates the JAR file

  Usage: clojure -T:build jar :name my-lib :version 1.0.0 :target ./target"
  [params]
  (-> params
      (process-params)
      (compile-java)
      (compile-clj)
      (write-pom)
      (copy-src-to-target)
      (write-jar)
      (finalize)))

(defn uberjar
  "Build a standalone uberjar with all dependencies.

  Similar to `jar` but produces a self-contained JAR that includes all
  transitive dependencies. This is typically used for CLI applications
  that need to run without external dependencies.

  Usage: clojure -T:build uberjar :name my-app :version 1.0.0 :target ./target"
  [params]
  (-> params
      (process-params)
      (compile-java)
      (compile-clj)
      (write-pom)
      (copy-src-to-target)
      (write-uberjar)
      (finalize)))

(defn install
  "Install the library JAR to the local Maven repository.

  Builds the JAR and installs it to ~/.m2/repository, making it available
  as a dependency for other local projects.

  Usage: clojure -T:build install :name my-lib :version 1.0.0 :target ./target"
  [params]
  (-> params
      (process-params)
      (write-jar)
      (install-to-local)))
