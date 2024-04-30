(ns nextjournal.arb-comments.ui
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [hiccup.page :as page]
            [hiccup2.core :as h]))

(def import-map
  [:script {:type "importmap"}
   (h/raw
     (json/write-str
       {"imports"
        {"squint-cljs/core.js" "https://cdn.jsdelivr.net/npm/squint-cljs@0.6.95/src/squint/core.js"
         "squint-cljs/src/squint/string.js" "https://cdn.jsdelivr.net/npm/squint-cljs@0.6.95/src/squint/string.js"

         ;; local dev
         ;;"@nextjournal/blank" "./js/blank.mjs"
         "@nextjournal/blank" "https://www.unpkg.com/@nextjournal/blank@0.1.7-alpha/src/nextjournal/blank.mjs"

         ;; prosemirror menu
         ;;"prosemirror-menu" "https://cdn.skypack.dev/pin/prosemirror-menu@v1.2.4-yhlxrV1AzPmNMhljsCqt/mode=imports,min/optimized/prosemirror-menu.js"
         "prosemirror-menu" "https://cdn.skypack.dev/pin/prosemirror-menu@v1.2.4-yhlxrV1AzPmNMhljsCqt/mode=imports/optimized/prosemirror-menu.js"

         ;; lit (web components)
         "lit" "https://cdn.jsdelivr.net/gh/lit/dist@3.1.2/all/lit-all.min.js"

         ;; blank / bb import-map
         "@codemirror/autocomplete" "https://esm.sh/prosemirror-autocomplete@0.4.3"
         "@codemirror/commands" "https://ga.jspm.io/npm:@codemirror/commands@6.3.3/dist/index.js"
         "@codemirror/language" "https://ga.jspm.io/npm:@codemirror/language@6.10.1/dist/index.js"
         "@codemirror/search" "https://ga.jspm.io/npm:@codemirror/search@6.5.6/dist/index.js"
         "@codemirror/state" "https://ga.jspm.io/npm:@codemirror/state@6.4.1/dist/index.js"
         "@codemirror/view" "https://ga.jspm.io/npm:@codemirror/view@6.25.0/dist/index.js"
         "@lezer/common" "https://ga.jspm.io/npm:@lezer/common@1.2.1/dist/index.js"
         "@lezer/highlight" "https://ga.jspm.io/npm:@lezer/highlight@1.2.0/dist/index.js"
         "@lezer/lr" "https://ga.jspm.io/npm:@lezer/lr@1.4.0/dist/index.js"
         "@nextjournal/lang-clojure" "https://ga.jspm.io/npm:@nextjournal/lang-clojure@1.0.0/dist/index.js"
         "@nextjournal/lezer-clojure" "https://ga.jspm.io/npm:@nextjournal/lezer-clojure@1.0.0/dist/index.es.js"
         "codemirror" "https://ga.jspm.io/npm:codemirror@6.0.1/dist/index.js"
         "katex" "https://ga.jspm.io/npm:katex@0.16.9/dist/katex.mjs"
         "orderedmap" "https://ga.jspm.io/npm:orderedmap@2.1.1/dist/index.js"
         "prosemirror-commands" "https://ga.jspm.io/npm:prosemirror-commands@1.5.2/dist/index.js"
         "prosemirror-gapcursor" "https://ga.jspm.io/npm:prosemirror-gapcursor@1.3.2/dist/index.js"
         "prosemirror-history" "https://ga.jspm.io/npm:prosemirror-history@1.3.2/dist/index.js"
         "prosemirror-inputrules" "https://ga.jspm.io/npm:prosemirror-inputrules@1.4.0/dist/index.js"
         "prosemirror-keymap" "https://ga.jspm.io/npm:prosemirror-keymap@1.2.2/dist/index.js"
         "prosemirror-model" "https://ga.jspm.io/npm:prosemirror-model@1.19.4/dist/index.js"
         "prosemirror-schema-basic" "https://ga.jspm.io/npm:prosemirror-schema-basic@1.2.2/dist/index.js"
         "prosemirror-schema-list" "https://ga.jspm.io/npm:prosemirror-schema-list@1.3.0/dist/index.js"
         "prosemirror-state" "https://ga.jspm.io/npm:prosemirror-state@1.4.3/dist/index.js"
         "prosemirror-transform" "https://ga.jspm.io/npm:prosemirror-transform@1.8.0/dist/index.js"
         "prosemirror-view" "https://ga.jspm.io/npm:prosemirror-view@1.33.1/dist/index.js"
         "rope-sequence" "https://ga.jspm.io/npm:rope-sequence@1.3.4/dist/index.js"
         "style-mod" "https://ga.jspm.io/npm:style-mod@4.1.2/src/style-mod.js"
         "w3c-keyname" "https://ga.jspm.io/npm:w3c-keyname@2.2.8/index.js"}}))])

(defn include-modules+css []
  (list import-map
        [:script {:type "module" :src "/nextjournal/arb_comments/components.mjs"}]
        ;; the following is loaded dynamically from the first, but we preload it here for caching
        [:script {:type "module" :src "/nextjournal/arb_comments/editor.mjs" :async true}]
        [:link {:rel "stylesheet" :href "/nextjournal/arb_comments.css"}]))
