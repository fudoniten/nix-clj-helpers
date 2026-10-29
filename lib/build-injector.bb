#!/usr/bin/env bb

;; Build configuration injector for Clojure projects built with Nix.
;;
;; Adds or updates the :build alias in deps.edn with tools.build configuration,
;; ensuring projects can be built with `clojure -T:build`.
;;
;; Usage:
;;   build-injector --deps-file deps.edn --build-namespace build \
;;                  io.github.clojure/tools.build 0.10.6

(require '[clojure.edn :as edn]
         '[clojure.pprint :refer [pprint]]
         '[clojure.string :as str]
         '[clojure.tools.cli :refer [parse-opts]])

;; -- Core logic --------------------------------------------------------------

(defn inject-build-namespace
  "Set the default namespace for the :build alias."
  [m ns]
  (assoc-in m [:aliases :build :ns-default] ns))

(defn inject-build-dependencies
  "Add dependencies required for the build process to :aliases :build :deps."
  [deps inj-deps]
  (update-in deps [:aliases :build :deps]
             (fn [existing-deps]
               (merge existing-deps
                      (into {}
                            (map (fn [[dep ver]]
                                   {(symbol dep) {:mvn/version ver}}))
                            inj-deps)))))

;; -- CLI ---------------------------------------------------------------------

(def cli-opts
  [[nil "--deps-file DEPS_FILE" "Source deps.edn file."]
   [nil "--build-namespace NAMESPACE" "Namespace in which to find build functions."]])

(defn- usage [summary errors]
  (->> (concat errors ["usage: build-injector [opts] [[<library> <version>] ...]"
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

  (when (not (contains? options :build-namespace))
    (binding [*out* *err*]
      (println (usage summary ["missing required argument: build-namespace"])))
    (System/exit 1))

  (let [deps (-> options :deps-file slurp edn/read-string)]
    (pprint (-> deps
                (inject-build-dependencies inj-deps)
                (inject-build-namespace (:build-namespace options)))))

  (System/exit 0))
