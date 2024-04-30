(ns nextjournal.arb-comments.components
  (:refer-clojure :exclude [repeat])
  (:require ["lit" :as lit :refer [repeat ref createRef until nothing]]))

(defonce config {:version "1.0.1"})
(set! (.-arbCommentsConfig globalThis) config)

(defn handle-json-response [r]
  (if (.-ok r)
    (.json r)
    (.. r json (then (fn [e] (throw (ex-info (:message e) {})))))))

(defclass ArbEditor
  (field editor-container)
  (extends lit/LitElement)
  (constructor [this]
    (super)
    (assoc! this :loading-text "loading rich text editor...")
    (set! editor-container (js/document.createElement "div")))

  Object
  (createRenderRoot [this] this)
  (connectedCallback [this]
    (.connectedCallback super)
    (js/setTimeout (fn [] (assoc! this :loading-text "adjusting serifs and ligatures…")) 1800)
    (js/setTimeout (fn [] (assoc! this :loading-text "just one final tweak, almost there…")) 3800)
    (assoc! this :editor-loading
            (.. (js/import "/nextjournal/arb_comments/editor.mjs")
                (then (fn [editor]
                        (assoc-in! this [:host :editor]
                          ;; TODO: find better way to interact with host
                          (.build editor {:container editor-container
                                          :host (:host this)
                                          :doc (-> this :host :body)
                                          :comment-id (-> this :host :id)}))
                        nothing))
                (catch (fn [e] (js/console.error e))))))

  (disconnectedCallback [this]
    (js/console.log :disconneting-editor)
    (.. this -host -editor -view destroy))

  (render [this]
    #html ^lit/html
    [:div
     (until (:editor-loading this)
       #html ^lit/html [:em {:data-arb-editor-loading true} (:loading-text this)])
     editor-container]))

(assoc! ArbEditor :properties {:loading-text {:type "String"}})

(js/window.customElements.define "arb-editor" ArbEditor)

(defn unshift [a x] (let [a' (.slice a)] (.unshift a' x) a'))

(defn button [op label]
  #html ^lit/html
  [:button {"@click" op :data-arb-button true}
   label])

(defclass ArbComment
  (extends lit/LitElement)
  (constructor [this parent data]
    (super)
    (let [{:keys [id body datetime comments author mode]} data]
      (assoc! this
              :parent parent
              :id id :datetime datetime :author author)
      (when body (assoc! this :body body))
      (if mode
        (assoc! this :mode mode)
        (when (.pending? this)
          (assoc! this :mode "edit")))
      #_
      (assoc! this :mode "edit")
      (.set_children_from_data this comments)))

  Object
  (show? [this] (not= "edit" (:mode this)))
  (edit? [this] (= "edit" (:mode this)))

  (pending? [this] (and (:parent this) (empty? (.-body this))))

  ;; TODO: check rather has `:body` property
  (editComment [this]
    #_(js/console.log :editing (:id this))
    (.cancelAll this)
    (assoc! this :mode "edit"))

  (cancel [this]
    #_(js/console.log :cancel (.pending? this))
    (cond
      (.pending? this)
      (.deleteComment this)
      (.edit? this)
      (assoc! this :mode "show")))

  (cancelAll [this]
    (when-some [root (loop [node this up (:parent this)]
                       (if-not up node (recur up (:parent up))))]
      (.cascadeCancel root)))

  (cascadeCancel [this]
    #_(js/console.log :cancel (:id this))
    (.cancel this)
    (doseq [c (:comments this)] (.cascadeCancel c)))

  (get-comment-body [this] (.. this -editor HTMLContent))

  (addComment [this]
    #_(js/console.log :addComment (subs (:id this) 14 18))
    (.cancelAll this)
    (.. (js/fetch "/arb-comments/tree"
          {:method "POST"
           :headers {:content-type "application/json"}
           :body (JSON/stringify {:comment/pertains-to (:id this)})})
        (then handle-json-response)
        (then (fn [comment]
                #_(js/console.log :new-comment (:id comment))
                (.cancelAll this)
                (assoc! this :comments
                        (conj (:comments this)
                              (new ArbComment this comment)))))
        (catch (fn [e] (js/console.error e)))))

  (updateComment [this]
    #_(js/console.log :update/body (.get-comment-body this))
    (when-not (empty? (.get-comment-body this))
      (.. (js/fetch (str "/arb-comments/tree/" (:id this))
            {:method "PATCH"
             :headers {:content-type "application/json"}
             :body (JSON/stringify {:comment/body (.get-comment-body this)})})
          (then handle-json-response)
          (then (fn [{:keys [body]}]
                  (assoc! this :body body :mode "show")))
          (catch (fn [e] (js/console.error e))))))

  (set_children_from_data [this cs]
    (assoc! this :comments (mapv #(new ArbComment this %) cs)))

  (removeComment [this id]
    (assoc! this :comments (filterv #(not= id (:id %)) (:comments this))))

  (deleteComment [this]
    (.. (js/fetch (str "/arb-comments/tree/" (:id this)) {:method "DELETE"})
        (then handle-json-response)
        (then (fn [{:as _parent-comment :keys [id comments]}]
                (assert (and id (= id (.. this -parent -id))) "Parent mismatch!")
                (if (seq (:comments this))
                  (.. this -parent (set-children-from-data comments))
                  (.. this -parent (removeComment (:id this))))))
        (catch (fn [e] (js/console.log :ignore e)))))

  ;; NOTE: this disables Shadow DOM altogether, needed tailwind to work without too much hassle
  ;; https://github.com/tailwindlabs/tailwindcss/discussions/1935
  ;; https://github.com/tailwindlabs/tailwindcss/discussions/7217
  (createRenderRoot [this] this)

  (cleanupPending [this]
    (when (and (:parent this) (:id this) (.pending? this))
      (js/console.log "window unload: cleaning up pending comment:" (:id this))
      (.. (js/fetch (str "/arb-comments/tree/" (:id this)) {:method "DELETE"})
          (then handle-json-response)
          (catch (fn [e] (js/console.log :ignore e))))))

  (preventToggleTodoItem [this e]
    ;; prevent (un)checking todo items in show mode
    ;; TODO: visually hint at readonly
    (when (and (.show? this)
               (.. e -target -classList (contains "blank-todo-item-checkbox")))
      (.preventDefault e)
      (.stopPropagation e)))

  (connectedCallback [this]
    (.connectedCallback super)
    (when (.edit? this) (.scrollIntoView this))
    (.addEventListener this "click" (.-preventToggleTodoItem this))
    (js/window.addEventListener "beforeunload" (.. this -cleanupPending (bind this))))

  (disconnectedCallback [this]
    (js/console.log :comment-disconnected (:id this))
    (js/window.removeEventListener "beforeunload" (.. this -cleanupPending (bind this)))
    (.disconnectedCallback super))

  (render [this]
    #html ^lit/html
    [:div {:data-arb-comment true}
     ;; dbg
     #_[:em {:class "text-xs red"} (subs (:id this) #_#_14 18)]

     (when (:datetime this)
       #html ^lit/html
       [:div {:data-arb-comment-meta true}
        [:a {:data-arb-comment-author true :tabindex "-1" :href (-> this :author :url)} (-> this :author :name)]
        [:span {:data-arb-comment-date true} (:datetime this)]])

     ;; root is never editable
     (if (and (:parent this) (.edit? this))

       #html ^lit/html
       [:div {:data-arb-comment-content true}
        [:div {:data-arb-comment-editor true} [:arb-editor {".host" this}]]
        [:div {:data-arb-comment-actions true}
         (button (.-updateComment this) "post")
         (button (.-cancel this) "cancel")]]

       #html ^lit/html
       [:div {:data-arb-comment-content true}
        [:div {:data-arb-comment-rendered true}
         (when (:body this)
           (doto (js/document.createElement "div") (assoc! :innerHTML (:body this))))]
        [:div {:data-arb-comment-actions true}
         (button (.-addComment this) (if (:parent this) "reply" "New comment"))
         (when (:parent this)
           (button (.-deleteComment this) "delete"))
         (when (:parent this)
           (button (.-editComment this) "edit"))]])

     (when (not-empty (:comments this))
       #html ^lit/html
       [:div {:data-arb-comments true}
        (repeat (:comments this) (fn [c] (:id c)) (fn [c _idx] c))])]))

(assoc! ArbComment :properties
  {:id {:type "String"}
   :body {:type "String"}
   :comments {:type "Array"}
   :mode {:type "String"}})

(js/window.customElements.define "arb-comment" ArbComment)

(defclass ArbCommentTree
  (extends lit/LitElement)
  (constructor [this] (super))

  Object
  (createRenderRoot [this] this)
  (fetch-tree [this]
    (.. (js/fetch (str "arb-comments/tree/" (.getAttribute this "data-commentable-id")))
        (then handle-json-response)
        (catch (fn [e] (js/console.error :failed e)))))

  (connectedCallback [this]
    (assoc! this :root (.fetch-tree this))
    (.connectedCallback super))

  (render [this]
    #html ^lit/html
    [:div {:data-arb-comment-tree true}
     (until (.then (:root this)
                   (fn [{:keys [id comments]}]
                     (new ArbComment nil {:id id :comments comments})))
            #html ^lit/html
            [:div {:data-arb-comment-tree-loading true} "loading…"])]))

(js/window.customElements.define "arb-comment-tree" ArbCommentTree)

(defclass ArbLink
  (extends lit/LitElement)
  (constructor [this] (super))

  Object
  (connectedCallback [this]
    (let [link-id (.getAttribute this "data-link-id")]
      (.connectedCallback super)
      (assoc! this
              :link-id link-id
              :link-body (.. (fetch (str "/arb-comments/link/" link-id))
                             (then handle-json-response)
                             (then (fn [{:keys [type data]}]
                                     (if-some [[_ component-type] (re-matches #"^([^.]+)\..+$" type)]
                                       (try (doto (js/document.createElement (str "arb-link-" component-type))
                                              (assoc! :data data))
                                            (catch js/Error e
                                              (throw (ex-info (str "Component missing: 'arb-link-" component-type "'")
                                                              {:type component-type :data data}
                                                              e))))
                                       (throw (ex-info (str "Invalid type: '" type "'")
                                                       {:type type :data data})))))))))

  (createRenderRoot [this] this)
  (render [this]
    #html ^lit/html
    [:span {:data-arb-link true}
     [:span (until (:link-body this) #html ^lit/html [:span {:data-arb-link-loading true} "loading…"])]
     #_[:em (str "(arb:" (subs (:link-id this) 0 3) ")")]]))

(js/window.customElements.define "arb-link" ArbLink)

;; flavours of link contents
(defclass ArbLinkInline
  (extends lit/LitElement)
  (constructor [this] (super))
  Object
  (createRenderRoot [this] this)
  (render [this]
    (let [{:keys [label info]} (:data this)]
      #_(js/console.log :info info)
      #html ^lit/html
      [:div {:data-arb-link-inline true}
       [:div {:class "relative"}
        [:span {:data-arb-link-inline-label true} label]
        [:div {:data-arb-link-inline-hover true}
         [:div {:data-arb-link-inline-hover-label true} label]
         [:div {:data-arb-link-inline-hover-info true} info]]]])))

(js/window.customElements.define "arb-link-inline" ArbLinkInline)

(defclass ArbLinkMention
  (extends lit/LitElement)
  (constructor [this] (super))
  Object
  (createRenderRoot [this] this)
  (render [this]
    (let [{:keys [label]} (:data this)]
      #html ^lit/html
      [:span {:data-arb-link-mention true} (str "@" label)])))

(js/window.customElements.define "arb-link-mention" ArbLinkMention)
