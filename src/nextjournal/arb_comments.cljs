(ns nextjournal.arb-comments
  (:require ["html-react-parser$default" :as parse]
            ["react" :as react]
            ["react-dom/client" :as react-client]
            ["@radix-ui/react-dropdown-menu" :as DropdownMenu]
            ["@radix-ui/react-icons" :as Icons :refer [StrikethroughIcon FontBoldIcon FontItalicIcon Link2Icon CodeIcon QuoteIcon ListBulletIcon CheckboxIcon CheckIcon CheckIcon]]
            ["@radix-ui/react-toggle" :as Toggle]
            ["@radix-ui/react-popover" :as Popover]
            ["@tiptap/core" :refer [Node Extension]]
            ["@tiptap/extension-mention" :refer [Mention]]
            ["@tiptap/pm/state" :refer [PluginKey]]
            ["@tiptap/react" :refer [useEditor EditorContent ReactRenderer]]
            ["@tiptap/starter-kit" :refer [StarterKit]]
            ["@tiptap/suggestion$default" :as Suggestion]
            ["@tiptap/extension-link" :refer [Link]]
            ["@tiptap/extension-task-item" :refer [TaskItem]]
            ["@tiptap/extension-task-list" :refer [TaskList]]
            [applied-science.js-interop :as j]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [nextjournal.hooks :as hooks]
            [reagent.core :as r]))

(r/set-default-compiler! (r/create-compiler {:function-components true}))

(defonce !link-data-store (atom {}))

(declare render-link)

(defn insert-link-command [^js command-props]
  (let [{:keys [^js editor ^js range props]} (j/lookup command-props)
        node-after (.. editor -view -state -selection -$to -nodeAfter)
        override-space (some-> node-after .-text (.startsWith " "))]
    (.. editor chain focus
        (insertContentAt #js {:from (.-from range)
                              :to (cond-> (.-to range) override-space inc)}
                         (j/lit [{:type "ArbLink"
                                  :attrs props}
                                 {:type "text"
                                  :text " "}])) run)))

(defn ArbLink [opts !state]
  (.create Node
           (j/lit {:name "ArbLink"
                   :marks ""
                   :defining true
                   :inline true
                   :group "inline"
                   :selectable false
                   :parseHTML (fn []
                                (j/lit [{:tag "[data-arb-link-id]" :priority 60}]))
                   :addAttributes (fn []
                                    (j/lit {:id {:default "none"
                                                 :parseHTML (fn [e] (.getAttribute e "data-arb-link-id"))
                                                 :renderHTML (fn [attrs] (j/lit {:data-arb-link-id (.-id attrs)}))}}))
                   :addNodeView (fn []
                                  (fn [^js node-opts]
                                    (j/lit {:dom (let [el (js/document.createElement "span")]
                                                   (.render (react-client/createRoot el)
                                                            (r/as-element [render-link opts {:id (.. node-opts -node -attrs -id)}]))
                                                   el)})))
                   :renderHTML (fn [opts]
                                 (let [{:keys [HTMLAttributes]} (j/lookup opts)]
                                   (j/lit ["span" HTMLAttributes])))

                   :addKeyboardShortcuts (fn []
                                           #js {"Mod-j" (fn [^js opts]
                                                          (let [editor (.-editor opts)
                                                                selection (.. editor -state -selection)
                                                                current-pos (.-from selection)]
                                                            (swap! !state assoc
                                                                   :open? true
                                                                   :props #js {:clientRect #(.. opts -editor -view (coordsAtPos current-pos))
                                                                               :command (fn [cmd-props]
                                                                                          (insert-link-command #js {:range selection
                                                                                                                    :editor editor
                                                                                                                    :props cmd-props}))})))})
                   :addProseMirrorPlugins (fn []
                                            (this-as this
                                              #js [(Suggestion (clj->js {:editor (.-editor this)
                                                                         :pluginKey (PluginKey. "ArbLink")
                                                                         :command insert-link-command
                                                                         :char "@"
                                                                         :render (fn []
                                                                                   (clj->js {:onStart #(swap! !state assoc :props % :open? true)}))}))]))})))

(defn on-link-select [{:keys [props]} completion-data]
  (let [link-id (str (random-uuid))]
    #_ (js/console.log :complete-with-link link-id completion-data)
    (swap! !link-data-store assoc link-id (assoc completion-data :id link-id))
    (.command props #js {:id link-id})))

(defn arb-link-dropdown-menu [{:keys [fetch-link-types fetch-link-suggestions]} editor !state]
  (let [{:as state :keys [props types type suggestions open?]} @!state
        rect (.clientRect props)]
    (hooks/use-effect
     (fn []
       (when-not (or types type)
         (.. (fetch-link-types)
             (then (fn [ts]
                     (cond
                       (= 1 (count ts)) (swap! !state assoc :type (:type (first ts)))
                       (seq ts) (swap! !state assoc :types ts)
                       :else (js/console.warn "Link types not implemented, suggestions disabled."))))
             (catch (fn [e] (js/console.warn (str "Fetching link types failed (" e ")"))))))) []) ;; run only once
    (hooks/use-effect
     (fn []
       (when type
         (.. (fetch-link-suggestions type)
             (then (fn [suggestions] (swap! !state assoc :suggestions suggestions)))
             (catch (fn [e] (js/console.error e)))))) [type])
    (when (and rect (or types suggestions))
      [:> DropdownMenu/Root {:default-open true
                             :open open?
                             :on-open-change (fn [open?]
                                               (when-not open?
                                                 (reset! !state {})
                                                 (.. editor -commands focus)))}
       [:> DropdownMenu/Trigger {:as-child true}
        [:div {:style {:position "fixed"
                       :top (.-top rect)
                       :left (.-left rect)
                       :width (.-width rect)
                       :height (.-height rect)}}]]
       [:> DropdownMenu/Portal
        [:> DropdownMenu/Content {:data-dropdown-menu-content "DropdownMenuContent"}
         (if suggestions
           (into [:<>]
                 (map (fn [{:as completion-data :keys [label]}]
                        [:> DropdownMenu/Item {:data-dropdown-menu-item "DropdownMenuItem"
                                               :on-select #(on-link-select state completion-data)} label]))
                 suggestions)
           (into [:<>]
                 (map (fn [{:keys [label type]}]
                        [:> DropdownMenu/Item {:data-dropdown-menu-item "DropdownMenuItem"
                                               :on-select #(do
                                                             (.preventDefault %)
                                                             (swap! !state assoc :type type))} label]))
                 types))
         [:> DropdownMenu/Arrow {:data-dropdown-menu-arrow true}]]]])))

(defn link-editor [{:keys [editor !menu-state button]}]
  (let [focus-editor! #(js/setTimeout (fn [] (.. editor -commands focus))) ;; need to be deferred in event queue to avoid race with popover focus
        close-popover! #(do (swap! !menu-state assoc :link-editor-open? false)
                            (focus-editor!))
        set-href! #(do (if-some [href (not-empty (:href @!menu-state))]
                         (.. editor chain (extendMarkRange "link") (setLink #js {:href href}) run)
                         (.. editor chain (extendMarkRange "link") unsetLink run))
                       (close-popover!))]
    ;; FIXME: Function components cannot be given refs. Attempts to access this ref will fail. Did you mean to use React.forwardRef()?
    [:> Popover/Content {:data-arb-editor-menu-link-href-editor true
                         :on-key-up #(when (= "Enter" (.-key %)) (set-href!))
                         :on-interact-outside #(focus-editor!)
                         :on-escape-key-down #(focus-editor!)}
     [:> Popover/Arrow {:data-arb-editor-menu-link-href-editor-arrow true}]
     [:div {:data-arb-editor-menu-link-href-editor-inner true}
      [:input {:type "text"
               :default-value (not-empty (.. editor (getAttributes "link") -href))
               :on-change #(swap! !menu-state assoc :href (.. ^js % -target -value))}]
      [button {:variant :white
               :data-arb-editor-menu-link-href-cancel true
               :on-click #(do (.. editor chain (extendMarkRange "link") unsetLink run)
                              (close-popover!))} "Unlink"]
      [button {:variant :primary
               :data-arb-editor-menu-link-href-confirm true
               :on-click #(set-href!)}
       [:> CheckIcon] "OK"]]]))

(defn menu-item-toggle [{:keys [name cmd icon editor is-active?]}]
  [:> Toggle/Root {:data-arb-editor-menu-item true
                   :pressed (is-active? name)
                   :onPressedChange (or cmd #(.. editor chain focus (toggleMark name) run))}
   [:> icon]])

(defn editor-menu [{:as opts :keys [^js editor is-active? !state !arb-link-dropdown-state]}]
  [:div {:data-arb-editor-menu true}
   (into [:div {:type "multiple" :data-arb-editor-menu-group true}]
         (map (fn [item] [menu-item-toggle (merge opts item)]))
         [{:name "bold" :icon FontBoldIcon}
          {:name "italic" :icon FontItalicIcon}
          {:name "strike" :icon StrikethroughIcon}
          {:name "code" :icon CodeIcon}])
   [:div {:data-arb-editor-menu-separator true}]
   [:div {:type "single" :data-arb-editor-menu-group true}
    [:> Popover/Root {:open (:link-editor-open? @!state)
                      :on-open-change #(swap! !state update :link-editor-open? not)}
     [:> Popover/Trigger {:as-child true}
      [:> Toggle/Root {:data-arb-editor-menu-item true
                       :data-state (if (is-active? "link") "on" "off")}
       [:> Link2Icon]]]
     [:> Popover/Portal
      [link-editor opts]]]
    [:button {:data-arb-editor-menu-item true
              :on-click #(let [selection (.. editor -state -selection)
                               current-pos (.-from selection)]
                           (.. editor -commands focus)
                           (swap! !arb-link-dropdown-state assoc
                                  :open? true
                                  :props #js {:clientRect (fn [] (.. editor -view (coordsAtPos current-pos)))
                                              :command (fn [cmd-props]
                                                         (insert-link-command #js {:range selection
                                                                                   :editor editor
                                                                                   :props cmd-props}))}))} "@"]]
   [:div {:data-arb-editor-menu-separator true}]
   (into [:div {:type "multiple" :data-arb-editor-menu-group true}]
         (map (fn [item] [menu-item-toggle (merge opts item)]))
         [{:name "blockquote" :icon QuoteIcon :cmd #(.. editor chain focus toggleBlockquote run)}
          {:name "bulletList" :icon ListBulletIcon :cmd #(.. editor chain focus toggleBulletList run)}
          {:name "todoList" :icon CheckboxIcon :cmd #(.. editor chain focus toggleTaskList run)}])])

(defn get-link-ids [^js editor]
  (let [ids (atom #{})]
    (.. editor -state -doc
        (descendants (fn [node]
                       (when (= "ArbLink" (.. node -type -name))
                         (swap! ids conj (.. node -attrs -id)))
                       true)))
    @ids))

(defn save-comment-body+links-command! [{:keys [on-post !state !parent-state]} {:keys [id pertains-to]} ^js editor]
  (if (not-empty (.. editor -state -doc -textContent))
    (let [link-ids (get-link-ids editor)
          link-data (keep #(get @!link-data-store %) link-ids)]
      (if-not on-post
        (js/console.warn "No `:on-post` callback passed to render-tree" )
        (on-post {:id id
                  :pertains-to pertains-to
                  :body (.getHTML editor)
                  :link-ids (vec (set/difference link-ids (set (map :id link-data))))
                  :link-data link-data}))
      (swap! !state assoc :editing? false)
      (when !parent-state
        (swap! !parent-state dissoc :reply))
      true)
    false))

(defn key-bindings [{:keys [!menu-state opts comment]}]
  (.create Extension
           #js {:addKeyboardShortcuts
                (fn []
                  #js {"Mod-k" (fn [_] (swap! !menu-state update :link-editor-open? not))
                       "Mod-Enter" (fn [cmd-opts]
                                     (save-comment-body+links-command! opts comment (.-editor cmd-opts)))})}))

(defn render-editor [{:as opts :keys [!state !parent-state button]} {:as c :keys [id body]}]
  (let [!arb-link-dropdown-state (hooks/use-state {})
        !menu-state (hooks/use-state {:link-editor-open? false})
        editor (useEditor (clj->js {:content body
                                    :extensions [StarterKit
                                                 (ArbLink opts !arb-link-dropdown-state)
                                                 (.configure Link #js {:openOnClick false :autolink true})
                                                 TaskList TaskItem
                                                 (key-bindings {:!menu-state !menu-state
                                                                :comment c
                                                                :opts opts})]}))]
    (hooks/use-effect #(when (and editor (or (:body c) !parent-state)) ;; reply or edit
                         (.. editor -commands focus)) [editor])
    (when editor
      [:div {:data-arb-editor-container true}
       [:div {:data-arb-editor-wrapper true}
        [editor-menu (merge opts
                            {:editor editor
                             :!menu-state !menu-state
                             :!arb-link-dropdown-state !arb-link-dropdown-state
                             :is-active? (fn [what] (.isActive editor what))})] ;; WHY: do we really need this clojure for the active state to be reactive?
        [:div {:data-arb-editor-content true}
         [:> EditorContent {:editor editor}]]
        (when (:open? @!arb-link-dropdown-state)
          [arb-link-dropdown-menu opts editor !arb-link-dropdown-state])]
       [:div {:data-arb-new-comment-actions true}
        (let [can-save? (not-empty (.. editor -state -doc -textContent))]
          [button {:variant (if can-save? :primary :white)
                   :disabled? (not can-save?)
                   :on-click #(save-comment-body+links-command! opts c editor)}
           [:> CheckIcon]
           "Save"])
        (when (or (:body c) !parent-state)
          [button {:variant :white
                   :on-click #(if (:body c)
                                (swap! !state assoc :editing? false)
                                (swap! !parent-state dissoc :reply))}
           "Cancel"])]])))

(defmulti render-arb-link :type)

(defmethod render-arb-link :default [{:keys [type data]}]
  [:span {:data-arb-link-inline true}
   [:span
    {:data-arb-link-inline-label true
     :data-arb-link-unsupported true}
    "Unsupported link type: " type]
   [:span
    {:data-arb-link-inline-hover true}
    (pr-str data)]])

(defn render-link [{:keys [fetch-link]} {:keys [id]}]
  (let [!link (hooks/use-state nil)]
    (hooks/use-effect
     (fn []
       (.. (or (when-some [data (get @!link-data-store id)] (js/Promise.resolve data))
               (fetch-link id))
           (then (fn [data] (reset! !link data))))) [id])
    (if @!link
      [render-arb-link @!link]
      [:span {:data-arb-link-inline-label true} "Loading…"])))

(defn new-comment [{:keys [author]}]
  {:id (random-uuid)
   :editing? true
   :author author})

(defn render-comment [{:as opts :keys [format-datetime on-delete lookup-attribute link can-add? can-edit? can-delete?]
                       :or {can-edit? (constantly true)
                            can-delete? (constantly true)
                            can-add? true}}
                      {:as c :keys [author comments created-at editing? body id]}]
  (assert lookup-attribute)
  (let [!state (hooks/use-state {:editing? editing?})
        {:keys [editing? reply]} @!state]
    [:div {:data-arb-comment true}
     [:div {:data-arb-comment-main true}
      [:div.flex.justify-between {:data-arb-comment-meta true}
       [:div
        (when created-at
          [:span {:data-arb-comment-date true}
           (cond-> created-at format-datetime format-datetime)
           " • "])
        [:span {:data-arb-comment-author true}
         [:a {:href (:url author)} (:name author)]]]

       (when-not editing?
         [:div.flex.gap-4
          {:data-arb-comment-actions true
           :class "text-[13px] opacity-70"}
          (when (can-delete? c)
            [link {:variant :destructive-hover
                   :on-click #(on-delete c)}
             "Delete"])
          (when (can-edit? c)
            [link {:variant :secondary
                   :on-click #(swap! !state update :editing? not)}
             "Edit"])
          (when can-add?
            [link {:variant :primary-hover
                   :on-click #(swap! !state assoc :reply (new-comment opts))}
             "Reply"])])]
      (if editing?
        [render-editor (assoc opts :!state !state) c]
        [:<>
         [:div {:data-arb-comment-content true}
          (when body
            (parse body #js {:replace
                             (fn [^js dom-node]
                               (or
                                (when-let [id (j/get-in dom-node [:attribs "data-arb-link-id"])]
                                  (r/as-element [render-link opts {:id id}]))
                                (when (= "checkbox" (j/get-in dom-node [:attribs :type]))
                                  (j/assoc-in! dom-node [:attribs :disabled] true))))}))]])]
     (into [:<>]
           (map (fn [child-comment]
                  ^{:key (str (:id child-comment) "-" (:editing? child-comment))}
                  [render-comment
                   (assoc opts :!parent-state !state)
                   (assoc child-comment :pertains-to [lookup-attribute id])]))
           (cond-> comments
             reply (conj reply)))]))

(defn render-tree [{:as opts :keys [pertains-to can-add?]} comments]
  (let [opts (merge {:button (partial vector :button)
                     :link (partial vector :button)}
                    opts)]
    [:div {:data-arb-comments true}
     (into [:<>]
           (map (fn [child-comment]
                  ^{:key (:id child-comment)}
                  [render-comment opts (assoc child-comment :pertains-to pertains-to)]))
           ;; auto open editable comment on render (make customizable)
           (conj comments (when can-add? (new-comment opts))))]))
