(ns com.wsscode.pathom.book.interactive-parser
  (:require [clojure.reader :refer [read-string]]
            [cljs.pprint]
            [cljs.spec.alpha :as s]
            [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.book.app-types :as app-types]
            [com.wsscode.pathom.book.async.intro]
            [com.wsscode.pathom.book.async.error-propagation]
            [com.wsscode.pathom.book.connect.getting-started]
            [com.wsscode.pathom.book.connect.getting-started2]
            [com.wsscode.pathom.book.ui.codemirror :as cm]
            [com.wsscode.pathom.fulcro.network :as network]
            [com.wsscode.pathom.specs.query :as s.query]
            [fulcro.client :as fulcro]
            [fulcro.client.alpha.localized-dom :as dom]
            [fulcro.client.data-fetch :as df]
            [fulcro.client.mutations :as fm]
            [fulcro.client.primitives :as fp]
            [clojure.string :as str]))

(s/def ::parser ::p/parser)
(s/def ::env ::p/env)

(def parsers
  {"async.intro"              {::parser com.wsscode.pathom.book.async.intro/parser}
   "async.error-propagation"  {::parser com.wsscode.pathom.book.async.error-propagation/parser}
   "connect.getting-started"  {::parser com.wsscode.pathom.book.connect.getting-started/parser
                               ::ns     "com.wsscode.pathom.book.connect.getting-started"}
   "connect.getting-started2" {::parser com.wsscode.pathom.book.connect.getting-started2/parser
                               ::ns     "com.wsscode.pathom.book.connect.getting-started2"}})

(defn safe-read [s]
  (try
    (read-string s)
    (catch :default _ nil)))

(defn load-parser-result [c query]
  (fp/transact! c [(list 'fulcro/load {:target [::id "singleton" ::parser-result]
                                       :marker :loading
                                       :query  [{::parser-result query}]})]))

(defn expand-keywords [s ns]
  (if ns
    (str/replace s #"::(\w+)" (str ":" ns "/$1"))
    s))

(defn compact-keywords [s ns]
  (if ns
    (str/replace s (js/RegExp (str ":" ns "\\/")) "::")
    s))

(fp/defsc InteractiveParser
  [this {:keys [::s.query/query ::parser-result ::ns] :as props}]
  {:initial-state (fn [initial-query]
                    {::s.query/query (or initial-query "[]")
                     ::parser-result {}
                     ::ns            "user"})
   :ident         (fn [] [::id "singleton"])
   :query         [::id ::s.query/query ::parser-result ::ns [df/marker-table :loading]]}
  (let [marker    (get props [df/marker-table :loading])
        query-exp (safe-read (expand-keywords query ns))]
    (dom/div
      (cm/clojure {:value       query
                   :onChange    #(fm/set-value! this ::s.query/query %)
                   ::cm/options {::cm/extraKeys
                                 {"Cmd-Enter"  #(load-parser-result this (-> this fp/props ::s.query/query (expand-keywords ns) safe-read))
                                  "Ctrl-Enter" #(load-parser-result this (-> this fp/props ::s.query/query (expand-keywords ns) safe-read))}}})
      (if (df/loading? marker)
        (dom/button {:disabled "true"} "Running...")
        (dom/button {:disabled (not query-exp)
                     :style    (if query-exp {} {:color "#c00"})
                     :onClick  #(load-parser-result this query-exp)}
          "Parse"))
      (cm/clojure {:value        (or (-> parser-result
                                         (cljs.pprint/pprint)
                                         (with-out-str)
                                         (compact-keywords ns))
                                     "")
                   ::cm/readOnly true}))))

(def reader
  {::parser-result
   (fn [{::keys [parser env] :keys [query]}]
     (parser (or env {}) query))})

(def parser (p/async-parser {}))
(def env {::p/reader reader})

(app-types/register-app "interactive-parser"
  (fn [{:keys [::app-types/node]}]
    (let [parser-name   (.getAttribute node "data-parser")
          initial-query (.-innerText node)]
      (let [iparser (get parsers parser-name)]
        (assert iparser (str "parser " parser-name " not foud"))
        {::app-types/app  (fulcro/new-fulcro-client
                            :initial-state {:ui/root (-> (fp/get-initial-state InteractiveParser initial-query)
                                                         (assoc ::ns (::ns iparser)))}
                            :networking {:remote (network/local-network #(parser (merge env iparser) %2))})
         ::app-types/root (app-types/make-root InteractiveParser (str "interactive-parser-" parser-name))}))))
