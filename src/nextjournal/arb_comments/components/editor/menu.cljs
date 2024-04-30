(ns nextjournal.arb-comments.editor.menu
  (:require ["lit" :as lit :refer [ref createRef]]
            ["prosemirror-commands" :refer [toggleMark]]
            ["prosemirror-menu" :refer [menuBar MenuItem icons undoItem redoItem wrapItem blockTypeItem]]
            ["prosemirror-state" :refer [TextSelection Plugin PluginKey]]
            ["prosemirror-view" :refer [Decoration DecorationSet]]
            [nextjournal.arb-comments.editor.schema :refer [schema]]
            [nextjournal.arb-comments.editor.suggestions :as suggestions]))

(defn wrapMark [mtype opts]
  (let [cmd (toggleMark mtype)]
    (MenuItem.
      (merge {:run cmd
              :enable (fn [s] (cmd s))
              :active (fn [{:as s :keys [selection]}]
                        (let [{:keys [$from from to]} selection]
                          (if (.-empty selection)
                            (some #(= mtype (:type %)) (.marks $from) #_ (.marksAcross $from $from))
                            (.. s -doc (rangeHasMark from to mtype)))))} opts))))

(defonce link-editor-plugin-key (new PluginKey "suggestions"))

(defn current-href [{:keys [selection]}]
  (some (fn [m] (when (= (.. schema -marks -link) (:type m))
                  (not-empty (.. m -attrs -href)))) (.. selection -$from -nodeAfter -marks)))

(defn link-editor-open-command
  ([state] (link-editor-open-command state nil nil nil))
  ([state dispatch] (link-editor-open-command state dispatch nil nil))
  ([state dispatch view] (link-editor-open-command state dispatch view nil))
  ([{:as state :keys [selection]} dispatch view _]
   (let [{:keys [from to empty]} selection]
     (when-not empty
       (when dispatch
         (dispatch (.. state -tr
                       (setMeta link-editor-plugin-key
                         {:action "open" :from from
                          :widget (new ArbLinkEditor
                                    {:href (or (current-href state) (.. state -doc (textBetween from to)))
                                     :onCancel #(do (.focus view)
                                                    (.dispatch view (.. view -state -tr (setMeta link-editor-plugin-key {:action "close"}))))
                                     :onSubmit #(do (.focus view)
                                                    (.dispatch view (as-> (.. view -state -tr) tr
                                                                      (.. tr
                                                                          (setMeta link-editor-plugin-key {:action "close"})
                                                                          (addMark from to (.. state -schema (mark "link" {:href %})))
                                                                          (setSelection (.create TextSelection (:doc tr) to))))))})}))))
       true))))

;; link editor plugin

(defonce link-editor-plugin
  (new Plugin
    {:key link-editor-plugin-key
     :state {:init (fn [_ _] {:open? false})
             :apply (fn [tr {:as state}]
                      (if-some [{:as meta :keys [action]} (.getMeta tr link-editor-plugin-key)]
                        (case action
                          "open" (-> state (merge meta) (assoc :open? true))
                          "close" (-> state (assoc :open? false)))
                        state))}

     :props {:editable
             (fn [state] (not (:open? (.getState link-editor-plugin-key state))))
             :decorations
             (fn [{:as state :keys [doc]}]
               (let [{:keys [open? from widget]} (.getState link-editor-plugin-key state)]
                 (when open?
                   (.create DecorationSet doc
                     [(.widget Decoration from widget)]))))}}))

;; menu plugin

(defonce plugin
  (menuBar {:content [[(MenuItem. {:run suggestions/open-command :label "@"})]
                      [(wrapMark (.. schema -marks -em) {:icon (:em icons)})
                       (wrapMark (.. schema -marks -strong) {:icon (:strong icons)})
                       (wrapMark (.. schema -marks -monospace) {:icon (:code icons)})]
                      [undoItem redoItem]
                      [(wrapItem (.. schema -nodes -blockquote) {:icon (:blockquote icons)})
                       (wrapItem (.. schema -nodes -bullet_list) {:icon (:bulletList icons)})
                       (wrapItem (.. schema -nodes -numbered_list) {:icon (:orderedList icons)})
                       (MenuItem. {:run link-editor-open-command
                                   :icon (:link icons)
                                   :select (fn [s] (link-editor-open-command s))
                                   :active (fn [{:keys [selection doc]}]
                                             ;; FIXME: more accurate range with mark detection
                                             (let [{:keys [from to]} selection]
                                               (.rangeHasMark doc from to (.. schema -marks -link))))})]]}))

#_(js/console.log :icons (js/Object.keys icons))

;; components

(defclass ArbLinkEditor
  (extends lit/LitElement)
  (constructor [this opts]
    (super)
    (let [{:keys [href onSubmit onCancel]} opts]
      (assoc! this :href href :onSubmit onSubmit :onCancel onCancel)))
  Object
  (createRenderRoot [this] this)
  (firstUpdated [this] (doto (.querySelector this "input") .focus .select
                         (.addEventListener "blur" #(.onCancel this))
                         (.addEventListener "keydown" (fn [{:as e :keys [code]}]
                                                        (.stopPropagation e)
                                                        (when (= "Escape" code) (.onCancel this))
                                                        (when (= "Enter" code)
                                                          (.onSubmit this (:href this))
                                                          (.preventDefault e))))))
  (change [this e] (assoc! this :href (.. e -target -value)))
  (render [this] #html ^lit/html
    [:div {:class "relative inline-block"}
     [:div {:class "absolute flex flex-col bg-slate-300 border border-slate-600 rounded-md mt-2 p-2 z-10 w-max"}
      [:span {:class "text-xs border-b border-slate-600"} "Enter a URL and press Enter…"]
      [:input {:type "text" :value (:href this) "@input" (:change this)
               :class "font-light font-mono text-base bg-slate-300 focus:outline-none"}]]]))

(assoc! ArbLinkEditor :properties {:href {:type "String"}})
(js/window.customElements.define "arb-link-editor" ArbLinkEditor)
