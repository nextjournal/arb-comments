(ns nextjournal.arb-comments.editor.suggestions
  (:refer-clojure :exclude [repeat])
  (:require ["lit" :as lit :refer [repeat live]]
            ["prosemirror-inputrules" :refer [InputRule]]
            ["prosemirror-state" :refer [EditorState Plugin PluginKey]]
            ["prosemirror-view" :refer [EditorView Decoration DecorationSet]]
            [nextjournal.arb-comments.editor.fuzzy :as fuzzy]))

(defn handle-json-response [r]
  (if (.-ok r)
    (.json r)
    (.. r json (then (fn [e] (throw (ex-info (:message e) {})))))))

(defclass SuggestionList
  (extends lit/LitElement)
  (constructor [this opts]
    (super)
    (let [{:keys [onCancel onComplete]} opts]
      (assoc! this :current-selection-idx 0
              :suggestions nil :query "" :type nil
              :onComplete onComplete :onCancel onCancel
              ;; TODO: suggestion spec should be passed from backend in a promise on connected
              :spec [{:label "People"
                      :type "mention.person"}
                     {:label "Compounds"
                      :type "inline.compound"}])))

  Object
  (createRenderRoot [this] this)

  (loadSuggestions [this url]
    (assert (:type this))
    (.. (fetch (str "/arb-comments/link/suggestions/" (:type this)))
        (then handle-json-response)
        (then (fn [suggestions]
                (assoc! this
                        :suggestions suggestions
                        :current-selection-idx 0)))
        (catch (fn [e] (js/console.error e )))))

  (moveSelection [this dir]
    (update! this :current-selection-idx
      #(mod (+ % dir)
            (if (:suggestions this)
              (.. this -suggestions -length)
              (.. this -spec -length)))))

  (selectCurrent [this]
    (cond
      (not (:type this))
      (let [{:keys [type]} (nth (.searchResults this (:spec this))
                                (:current-selection-idx this))]
        (assoc! this :type type)
        (.loadSuggestions this)
        (assoc! this :query ""))
      (:type this)
      (.onComplete this (assoc (nth (.searchResults this (:suggestions this))
                                    (:current-selection-idx this))
                               :type (:type this)))))

  (goBack [this]
    (if (:type this)
      (assoc! this :type nil :suggestions nil :query "")
      (.onCancel this)))

  (change [this e] (assoc! this :query (.. e -target -value) :current-selection-idx 0))

  (searchResults [this coll]
    #_ (js/console.log :query/results (:query this))
    (if (seq (:query this))
      (fuzzy/search coll #(get % :label) (:query this))
      coll))

  (firstUpdated [this] (doto (.querySelector this "input") .focus .select
                         (.addEventListener "blur" #(.onCancel this))
                         (.addEventListener "keydown" (fn [{:as e :keys [code]}]
                                                        (.stopPropagation e)
                                                        (case code
                                                          "Escape" (.onCancel this)
                                                          "ArrowUp" (do (.moveSelection this -1) (.preventDefault e))
                                                          "ArrowDown" (do (.moveSelection this +1) (.preventDefault e))
                                                          "ArrowLeft" (do (.goBack this) (.preventDefault e))
                                                          "Enter" (do (.preventDefault e) (.selectCurrent this)) nil)))))

  (renderItem [this x idx]
    #html ^lit/html
    [:div {:class
           (str "rounded-md p-1 mb-1"
                (if (= idx (:current-selection-idx this))
                  " bg-slate-800 text-slate-300"
                  " border border-slate-600"))} (:label x)])

  (render [this]
    #html ^lit/html
    [:div {:class "inline-block relative"}
     [:div {:class "absolute flex flex-col bg-slate-300 border border-slate-600 rounded-md mt-2 p-2 z-10 overflow-y-auto max-h-96 w-80"}
      [:span {:class "text-xs border-b border-slate-600"} "Search or select by ↑ ↓, press Enter to insert link"]
      [:input {:type "text" ".value" (live (:query this)) "@input" (:change this)
               :class "font-light font-mono text-base bg-slate-300 focus:outline-none mb-1"}]
      (cond
        (not (:type this))
        (repeat (.searchResults this (:spec this)) (fn [x] (:type x)) (.. this -renderItem (bind this)))

        (seq (:suggestions this))
        (when (:suggestions this)
          (repeat (.searchResults this (:suggestions this)) (fn [x] (:id x)) (.. this -renderItem (bind this)))))]]))

(assoc! SuggestionList :properties {:query {:type "String"}
                                    :suggestions {:type "Array"}
                                    :current-selection-idx {:type "Number"}})

(js/window.customElements.define "arb-suggestion-list" SuggestionList)

(defonce plugin-key (new PluginKey "suggestions"))

(defn open-command [state dispatch view]
  (dispatch (.. state -tr (setMeta plugin-key
                            {:action "open"
                             :pos (.. state -selection -from)
                             ;; TODO: have a plugin to keep options in editor state instead of passing view here
                             :view view}))) true)

(defn make-suggestion-widget [_ opts] (new SuggestionList opts))

(defn plugin [{:as opts :keys [comment-id]} {:as config :keys [suggestionsWidgetFn] :or {suggestionsWidgetFn make-suggestion-widget}}]
  (new Plugin
    {:key plugin-key
     :state {:init (fn [_ _] {:open? false})
             :apply (fn [tr {:as state}]
                      (if-some [{:as meta :keys [pos action view]} (.getMeta tr plugin-key)]
                        (case action
                          "close" (-> state (assoc :open? false) (dissoc :type :widget))
                          "open" (assoc state :open? true :pos pos :widget
                                        (suggestionsWidgetFn view
                                          (assoc opts
                                                 :onCancel #(do (.focus view) (.dispatch view (.. view -state -tr (setMeta plugin-key {:action "close"}))))
                                                 :onComplete #(let [{:keys [id type]} %] ;; applied to current selection
                                                                (.focus view)
                                                                (.. (js/fetch (str "/arb-comments/tree/" comment-id "/link")
                                                                      {:method "POST"
                                                                       :headers {:content-type "application/json"}
                                                                       :body (JSON/stringify {:target-id id :type type})})
                                                                    (then handle-json-response)
                                                                    (then (fn [link]
                                                                            (.dispatch view
                                                                              (.. view -state -tr
                                                                                  (setMeta plugin-key {:action "close"})
                                                                                  (replaceSelectionWith (.. view -state -schema (node :arb-link {:link-id (:id link)})))
                                                                                  ;; TODO: fix cursor at newline after arb-link nodes
                                                                                  (insertText " ")))))))))))
                        state))}

     :props {:editable (fn [state] (not (:open? (.getState plugin-key state))))
             :decorations
             (fn [{:as state :keys [doc]}]
               (let [{:keys [open? pos widget]} (.getState plugin-key state)]
                 (when open?
                   (.create DecorationSet doc [(.widget Decoration pos widget)]))))}}))
