(ns nextjournal.arb-comments.editor.schema
  (:require ["@nextjournal/blank" :as blank]
            ["prosemirror-model" :refer [Schema]]))

(def schema
  (new Schema
    {:nodes (.. blank/schema -spec -nodes
                (append {:arb-link {:atom true
                                    :marks ""
                                    :group "inline"
                                    :toDebugString (fn [node] (str "<arbl" (subs (get (.-attrs node) :link-id) 0 4) ">"))
                                    :inline true
                                    :selectable false
                                    :attrs {:link-id {:default nil}}
                                    :toDOM (fn [node]
                                             [:arb-link {"data-link-id" (get (.-attrs node) :link-id)}])
                                    :parseDOM [{:tag "arb-link"
                                                :getAttrs (fn [dom] {:link-id (.getAttribute dom "data-link-id")})}]}}))
     :marks (.. blank/schema -spec -marks)}))
