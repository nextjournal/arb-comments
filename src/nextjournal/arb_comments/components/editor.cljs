(ns nextjournal.arb-comments.components.editor
  (:require ["@nextjournal/blank" :as blank :refer [blockwise-selection todo-item-plugin]]
            ["lit" :as lit :refer [ref createRef]]
            ["prosemirror-commands" :refer [toggleMark]]
            ["prosemirror-history" :refer [history]]
            ["prosemirror-inputrules" :refer [inputRules]]
            ["prosemirror-keymap" :refer [keymap]]
            ["prosemirror-menu" :refer [menuBar MenuItem icons undoItem redoItem wrapItem blockTypeItem]]
            ["prosemirror-model" :refer [DOMSerializer DOMParser Node]]
            ["prosemirror-state" :refer [EditorState Plugin PluginKey]]
            ["prosemirror-view" :refer [EditorView Decoration DecorationSet]]
            [nextjournal.arb-comments.components :refer [config]]
            [nextjournal.arb-comments.editor.suggestions :as suggestions]
            [nextjournal.arb-comments.editor.menu :as menu]
            [nextjournal.arb-comments.editor.schema :refer [schema]]))

;; TODO:
;; * re-add a basic code input-rule & node-view
;; * placeholder https://gist.github.com/amk221/1f9657e92e003a3725aaa4cf86a07cc0
;; * prosemirror-menu

(defonce history-plugin (history))

(defn debug-state [s]
  #_(js/console.log :debug-state (.. s -doc toString)))

(defn key-bindings [{:keys [host]}]
  (keymap {"Mod-k" menu/link-editor-open-command
           "Mod-Enter" #(do (.updateComment host) true)
           "Mod-j" suggestions/open-command}))

(defclass Editor
  (constructor [this opts]
    (let [{:keys [container doc]} opts]
      #_(when doc (js/console.log :doc doc))
      (set! (.-view this)
        (doto (new EditorView container
                {:state (doto (.create EditorState
                                (cond-> {:schema schema
                                         :plugins [(key-bindings opts)
                                                   (suggestions/plugin opts config)
                                                   ;; TODO: assign key!!!
                                                   #_blockwise-selection
                                                   todo-item-plugin
                                                   (keymap blank/keymap)
                                                   (inputRules {:rules (blank/build-input-rules schema)})
                                                   history-plugin
                                                   menu/link-editor-plugin
                                                   menu/plugin]}
                                  doc
                                  (assoc :doc
                                         (.. DOMParser (fromSchema schema)
                                             (parse (doto (js/document.createElement "div")
                                                      (assoc! :innerHTML doc))))))) debug-state)
                 ;; TODO: fix formula views html
                 #_#_:nodeViews {:formula (blank/formula-view)
                                 :inline_formula (blank/formula-view)}}) .focus))))

  Object
  (HTMLContent [this]
    (when-some [doc-content (.docContent this)]
      (.-innerHTML
        (.serializeFragment (DOMSerializer/fromSchema (.. this -view -state -schema))
          doc-content {} (js/document.createElement "div")))))

  (docContent [this]
    (when-not (empty? (.. this -view -state -doc -textContent))
      (.. this -view -state -doc -content))))

(defn build [container body]
  (new Editor container body))
