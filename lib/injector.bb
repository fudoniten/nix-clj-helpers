#!/usr/bin/env bb

;; Dependency injector for Clojure projects built with Nix.
;;
;; Replaces Maven dependency entries in deps.edn with :local/root paths,
;; enabling use of pre-built JARs from the Nix store.
;;
;; Usage:
;;   injector --deps-file deps.edn org.myorg/my-lib /nix/store/xxx.jar

(require '[clojure.edn :as edn]
         '[clojure.pprint :refer [pprint]]
         '[clojure.string :as str]
         '[clojure.tools.cli :refer [parse-opts]])

;; -- Core logic --------------------------------------------------------------

(defn- replace-deps
  "Create a function that replaces dependency entries with :local/root paths."
  [inj-deps]
  (fn [deps]
    (reduce (fn [m [k v]] (assoc m k {:local/root v})) deps inj-deps)))

(defn- map-vals
  "Apply f to each [key value] pair in map m, rebuilding with transformed values."
  [f m]
  (into {} (map (fn [[k v]] [k (f [k v])])) m))

(defn- recursive-update-key
  "Recursively walk a nested map, applying f to the value of any key matching `key`."
  [m key f]
  (map-vals (fn [[k v]]
              (cond
                (= k key) (f v)
                (map? v)  (recursive-update-key v key f)
                :else     v))
            m))

(defn inject-dependencies
  "Inject :local/root overrides into a deps.edn structure."
  [deps inj-deps]
  (recursive-update-key deps :deps (replace-deps inj-deps)))

;; -- CLI ---------------------------------------------------------------------

(def cli-opts
  [[nil "--deps-file DEPS_FILE" "Source deps.edn file."]])

(defn- usage [summary errors]
  (->> (concat errors ["usage: injector [opts] [[<library> <jar-file>] ...]"
                       ""
                       "Options:"
                       summary])
       (str/join \newline)))

(let [{:keys [options arguments errors summary]} (parse-opts *command-line-args* cli-opts)
      inj-deps (into {} (for [[k v] (partition 2 arguments)] [(symbol k) v]))]

  (when (seq errors)
    (binding [*out* *err*]
      (println (usage summary errors)))
    (System/exit 1))

  (when (not (contains? options :deps-file))
    (binding [*out* *err*]
      (println (usage summary ["missing required argument: deps-file"])))
    (System/exit 1))

  (let [deps (-> options :deps-file slurp edn/read-string)]
    (pprint (inject-dependencies deps inj-deps)))

  (System/exit 0))
