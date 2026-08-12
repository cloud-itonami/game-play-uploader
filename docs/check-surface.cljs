#!/usr/bin/env nbb
;; docs/check-surface.cljs — do the hosts this repo declares actually exist?
;;
;;   nbb docs/check-surface.cljs            # from the repo root
;;
;; The README states, as a dated measurement, that every etzhayyim host this
;; repo depends on is NXDOMAIN. A dated measurement rots. This re-takes it.
;;
;; It does not carry a hardcoded host list — it extracts the hosts from the
;; files that declare them (wrangler.jsonc, kotodama.jsonld, the two Worker
;; entrypoints, the landing pages), so adding a host to the config brings it
;; under the check automatically.
;;
;; Exit codes are three-valued on purpose. "Could not measure" must not be
;; reachable from the same exit code as "measured, all fine":
;;
;;   0  every declared host resolves
;;   1  at least one declared host does not resolve   <- the state on 2026-08-12
;;   3  COULD NOT ANSWER — no hosts were extracted, or the control host failed
;;      (i.e. this machine has no working DNS, so an NXDOMAIN here would mean
;;      nothing). Never report a pass in this case.

(ns check-surface
  (:require ["node:dns/promises" :as dns]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:process" :as process]
            [clojure.string :as str]
            [promesa.core :as p]))

;; A host we do not control, used only to prove DNS works at all. If this
;; fails, every NXDOMAIN below is uninterpretable.
(def control-host "registry.npmjs.org")

(def scan-exts #{".jsonc" ".jsonld" ".json" ".ts" ".html" ".edn"})
(def skip-dirs #{"node_modules" ".svelte-kit" ".git" "dist" "build"})

(defn walk
  "Every scannable file under dir, skipping build output and vendored trees."
  [dir]
  (reduce
   (fn [acc entry]
     (let [nm (.-name entry)
           full (path/join dir nm)]
       (cond
         (.isDirectory entry) (if (contains? skip-dirs nm) acc (into acc (walk full)))
         (contains? scan-exts (path/extname nm)) (conj acc full)
         :else acc)))
   []
   (fs/readdirSync dir #js {:withFileTypes true})))

(defn hosts-in
  "Hostnames under the project's own domain that this file names."
  [text]
  (set (map str/lower-case (re-seq #"(?i)(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)*etzhayyim\.com" text))))

(defn collect
  "host -> sorted set of repo-relative files that declare it."
  [root]
  (reduce
   (fn [acc f]
     (let [rel (path/relative root f)
           text (try (str (fs/readFileSync f "utf8")) (catch :default _ ""))]
       (reduce (fn [a h] (update a h (fnil conj (sorted-set)) rel)) acc (hosts-in text))))
   {}
   (walk root)))

(defn resolves?
  "true / false, or :error when lookup failed for a reason that is not NXDOMAIN
   (so we never read a transient resolver fault as a missing host)."
  [h]
  (-> (dns/lookup h)
      (p/then (fn [_] true))
      (p/catch (fn [e]
                 (let [code (.-code e)]
                   (if (contains? #{"ENOTFOUND" "ENODATA"} code) false :error))))))

(defn -main []
  (let [root (process/cwd)
        declared (collect root)
        hosts (sort (keys declared))]
    (p/let [control (resolves? control-host)]
      (cond
        (not (true? control))
        (do (println (str "COULD NOT ANSWER: control host " control-host " did not resolve."))
            (println "DNS is not working here, so an NXDOMAIN below would prove nothing.")
            (println "Refusing to report on the declared hosts.")
            (process/exit 3))

        ;; Evidence floor: an empty scan must not look like a clean scan.
        (empty? hosts)
        (do (println (str "COULD NOT ANSWER: scanned " (count (walk root))
                          " files under " root " and extracted 0 declared hosts."))
            (println "Either this is not the repo root, or the config no longer names any host.")
            (process/exit 3))

        :else
        (p/let [states (p/all (map resolves? hosts))]
          (let [rows (map vector hosts states)
                missing (filter (comp false? second) rows)
                errored (filter (comp #{:error} second) rows)
                w (apply max (map count hosts))]
            (println (str "SCANNED\t" (count (walk root)) " files\tDECLARED-HOSTS\t" (count hosts)))
            (println (str "control\t" control-host "\tresolves"))
            (println)
            (doseq [[h state] rows]
              (println (str (str/join (repeat (- w (count h)) " ")) h "  "
                            (case state
                              true  "resolves"
                              false "NXDOMAIN"
                              "LOOKUP-ERROR")
                            "  <- " (str/join ", " (get declared h)))))
            (println)
            (cond
              (seq errored)
              (do (println (str "COULD NOT ANSWER: " (count errored)
                                " host(s) failed to look up for a reason other than NXDOMAIN."))
                  (process/exit 3))

              (seq missing)
              (do (println (str (count missing) " of " (count hosts)
                                " declared hosts do not exist."))
                  (println "The README's status table should say exactly this. If it does not, update it.")
                  (process/exit 1))

              :else
              (do (println "All declared hosts resolve. The README's \"not live\" status table is stale — update it.")
                  (process/exit 0)))))))))

(-main)
