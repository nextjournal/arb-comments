(ns nextjournal.arb-comments
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [datomic.api :as d]
            [ring.middleware.content-type :as content-type]
            [ring.middleware.resource :as resource])
  (:import (java.util Date)
           (java.io PushbackReader)
           (java.time LocalDateTime ZoneId)
           (java.time.format DateTimeFormatter)))

(def schema
  [{:db/ident :arb.comment/body
    :db/doc "Unit of richtext representing the comment's body"
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :arb.comment/pertains-to
    :db/doc "The entity the comment pertains to, both host application entities as well as other comments"
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident :arb.comment/author
    :db/doc "The author of the comment"
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident :arb.comment/created-at
    :db/doc "Date/Time comment created at"
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}

   ;; links

   {:db/ident :arb.comment/links
    :db/doc "Holds link entities insisting on the comment"
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many}

   {:db/ident :arb.comment.link/target
    :db/doc "Holds the link target"
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident :arb.comment.link/type
    :db/doc "Holds the link type"
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; dynamic vars
(def ^:dynamic *lookup-attribute* :ductile/id)
(def ^:dynamic *conn* nil)
(def ^:dynamic *author* nil)
(def ^:dynamic *link-types* [])

(defn db [] (assert *conn*) (d/db *conn*))
(defn fresh [e] (d/entity (db) (:db/id e)))

(defn ^:dynamic *present-author* [author] {:name (:user/name author) :url "#"})

(defn present-author []
  (assert *present-author*) (assert *author*)
  (*present-author* *author*))

(defn ->lookup-ref [id]
  (when-some [uuid (cond (string? id) (parse-uuid id) (uuid? id) id)]
    [*lookup-attribute* uuid]))

(defn exclusively-owns-link? [comment link]
  (empty? (-> link :arb.comment/_links (disj comment))))

(defn update-comment-links-tx [db {:keys [id lookup-attribute link-ids]}]
  #_ (prn :update/link-ids link-ids)
  (assert lookup-attribute) (assert id)
  (when-some [comment (d/entity db [lookup-attribute id])]
    (let [lookup-ref-fn (juxt (constantly lookup-attribute) parse-uuid)
          existing-links (map (comp str lookup-attribute) (:arb.comment/links comment))
          [add remove] [(set/difference (set link-ids) (set existing-links))
                        (set/difference (set existing-links) (set link-ids))]]
      (cond-> []
        (seq add)
        (into (map (comp (fn [ref] [:db/add (:db/id comment) :arb.comment/links ref])
                         lookup-ref-fn))
              add)
        (seq remove)
        (-> #_tx
         (into (map (comp (fn [r] [:db/retract (:db/id comment) :arb.comment/links r])
                          lookup-ref-fn))
               remove)
         (into (comp (map lookup-ref-fn)
                     (map (partial d/entity db))
                     (filter (partial exclusively-owns-link? comment))
                     (map (fn [l] [:db/retractEntity (:db/id l)])))

               remove))))))

(defn ->entity [id]
  (when-some [lr (->lookup-ref id)]
    (d/entity (db) lr)))

(defn comment-tx [db {:as _attrs :keys [lookup-attribute id author pertains-to body]}]
  #_ (prn :comment-tx _attrs)
  (assert lookup-attribute)
  (assert (uuid? id)) (assert pertains-to) (assert body) (assert author)
  (let [existing-comment (d/entity db (->lookup-ref id))
        comment-target (d/entity db pertains-to)]
    (assert comment-target)
    (cond-> {:db/id "comment"
             ;; *id* must be unique by identity
             lookup-attribute id
             :arb.comment/body body
             :arb.comment/author (:db/id author)
             :arb.comment/pertains-to (:db/id comment-target)}
      (not existing-comment)
      (assoc :arb.comment/created-at (Date.)))))

(defn new-link-tx [db {:keys [lookup-attribute]} {:keys [id ref type]}]
  #_ (prn :new-link-tx/ref ref)
  (assert id) (assert type) (assert ref)
  (assert lookup-attribute)
  (let [link-target (d/entity db ref)]
    (assert link-target)
    [{lookup-attribute (parse-uuid id)
      :db/id id
      :arb.comment.link/target (:db/id link-target)
      :arb.comment.link/type type}
     [:db/add "comment" :arb.comment/links id]]))

(defn upsert-tx [db {:as attrs :keys [link-data]}]
  (vec (cons (comment-tx db attrs)
             (concat (mapcat (partial new-link-tx db attrs) link-data)
                     (update-comment-links-tx db attrs)))))

(defn upsert-comment! [attrs]
  #_ (prn :upsert-comment!/attrs attrs)
  #_ (prn :upsert-comment!tx (upsert-tx (db) attrs))
  (let [{:keys [db-after tempids]} @(d/transact *conn* (upsert-tx (db) attrs))]
    (d/entity db-after (tempids "comment"))))

(defn add-comment!
  ([entity] (add-comment! entity {}))
  ([entity attrs]
   (assert *conn*) (assert *author*)
   (upsert-comment! {:id (random-uuid)
                     :body (:body attrs)
                     :author *author*
                     :lookup-attribute *lookup-attribute*
                     :pertains-to (:db/id entity)})))

(comment
  (add-comment! ductile-demo/service)

  (binding [*author* ductile-demo/author *conn* ductile-demo/conn]
    (present-comment (fresh ductile-demo/service)))

  ;; TODO: move to test
  (binding [*conn* ductile-demo/conn
            *author* ductile-demo/author]
    (def c1 (add-comment! ductile-demo/service {:body "C1"}))
    (def c2 (add-comment! ductile-demo/service {:body "C2"}))
    (def l1 (add-link! c1 {:type :arb.comment.link.type/inline.compound
                           :target-id "0530e959-5c3a-4ca1-9639-08224e17af7e" ;; Lonato
                           :link-id (d/squuid)}))
    (d/transact *conn* [[:db/add (:db/id c2) :arb.comment/links (:db/id l1)]]))

  (binding [*conn* ductile-demo/conn]
    (d/transact *conn* [[:db/retractEntity (:db/id c1)]]))

  (binding [*conn* ductile-demo/conn]
    (update (into {} (d/touch (fresh c2))) :arb.comment/links (partial map d/touch)))

  (binding [*conn* ductile-demo/conn]
    (map d/touch (:arb.comment/_links (fresh l1)))
    (d/touch (fresh l1))
    (d/touch (fresh c2)))

  (binding [*conn* ductile-demo/conn *author* ductile-demo/author]
    (d/touch (upsert-comment! {:id (str (*lookup-attribute* c1))
                               :pertains-to (str (*lookup-attribute* (:arb.comment/pertains-to c1)))
                               :body "newbody no links"
                               :link-ids []})))

  (binding [*conn* ductile-demo/conn]
    (delete-comment! (fresh c1))))

(defn delete-tx [{:as comment :arb.comment/keys [pertains-to links]}]
  ;; ensure it's a comment and has a parent
  (assert pertains-to "Can't delete an entity which is not a comment")
  (-> [[:db/retractEntity (:db/id comment)]]
      ;; lifts pertains-to of children to the parent's one
      (into (map (fn [child]
                   {:db/id (:db/id child)
                    :arb.comment/pertains-to (:db/id pertains-to)}))
            (:arb.comment/_pertains-to comment))
      ;; retract all links which do not have other sources but ourselves
      (into (comp (filter (partial exclusively-owns-link? comment))
                  (map (fn [l] [:db/retractEntity (:db/id l)]))) links)))

(defn delete-comment! [comment]
  @(d/transact *conn* (delete-tx comment))
  true)

(defn format-date [date]
  (.format (LocalDateTime/ofInstant (.toInstant date) (ZoneId/of "UTC+1"))
           (DateTimeFormatter/ofPattern "dd.MM.YY HH:mm:ss")))

(defn update-some [m k f & args] (if-not (get m k) m (apply update m k f args)))

(defn pull-pattern [{:keys [lookup-attribute]}]
  (assert lookup-attribute)
  {[:arb.comment/_pertains-to :as :arb/comments]
   [[lookup-attribute :as :id]
    [:arb.comment/created-at :as :created-at]
    [:arb.comment/body :as :body]
    ;; TODO: allow to customize user attrs
    {[:arb.comment/author :as :author] [lookup-attribute
                                        [:user/name :as :name]
                                        [:user/email :as :email]]}
    {[:arb.comment/_pertains-to :as :comments] '...}]})

(defn present-comment [entity]
  (d/pull (db) [(pull-pattern {:lookup-attribute *lookup-attribute*})] (:db/id entity)))

(comment
  (binding [*author* ductile-demo/author
            *conn* ductile-demo/conn]
    (present-comment (fresh ductile-demo/service))

    (ductile.datomic.api/pull (db)
                              [#_ [:ductile/id :as :id]
                               [:arb.comment/author :as :author]
                               [:arb.comment/created-at :as :created-at]
                               [:arb.comment/body :as :body]
                               {[:arb.comment/_pertains-to :as :arb/comments] '...}]
                              [:ductile/id (:ductile/id ductile-demo/service)])))


;; multimethods for link suggestions and presentation (by type)
(defmulti present-link-target
  "Given a link, produces an EDN/transit serializable map compatible with the UI component of the matching type.

  The returned map should have keys:
  * `:label`, used in link suggestions
  * `:ref` a lookup-ref (or id) to retrive the target entity as per `(d/entity db ref)`
  Further optional keys depend on the specific implementation."
  :arb.comment.link/type)

;; extra hierarchy
(comment
  (derive :comment.link.type/inline.compound :comment.link.type/inline))

;; defmethods should go on the consumer application
(defmethod present-link-target :default [link]
  (throw (IllegalArgumentException. (format "Multimethod `present-link-target` not implemented for type: '%s'"
                                            (:arb.comment.link/type link)))) )

(defmulti link-suggestions
  "Given `request` and `type` returns a collection of candidates (entities) for a link's target."
  (fn [request type] type))

(defmethod link-suggestions :default [_ type]
  (throw (IllegalArgumentException. (format "Multimethod `link-suggestions` not implemented for type: '%s'" type))))

(defn present-link [{:as link :arb.comment.link/keys [type]}]
  (assoc (present-link-target link)
         :type type
         :id (str (*lookup-attribute* link))))

(defn present-link-suggestions [req type]
  #_(prn :present-link-suggestions type)
  (->> (link-suggestions req type)
       (mapv (fn [link-target]
               (assoc (present-link-target {:arb.comment.link/type type
                                            :arb.comment.link/target link-target})
                      :type type)))))

(defn add-link! [comment {:keys [type target-id link-id]}]
  (let [{:keys [db-after tempids]}
        @(d/transact *conn*
           [{:db/id (:db/id comment)
             :arb.comment/links "new-link"}
            {:db/id "new-link"
             :ductile/id link-id
             :arb.comment.link/target (->lookup-ref target-id)
             :arb.comment.link/type type}])]
    (d/entity db-after (tempids "new-link"))))

;; server handlers
(defn edn-response
  ([body] (edn-response {} body))
  ([opts body]
   (merge {:status 200
           :headers {"Content-Type" "application/edn"}
           :body (pr-str body)} opts)))

(defn throwable->edn-response [e]
  (prn e)
  (edn-response {:status 412} {:message (ex-message e)}))

(defn pertains-to-root [e]
  (last (take-while some? (iterate :arb.comment/pertains-to e))))

(defn handle-show [_req comment]
  (edn-response (present-comment comment)))

(defn handle-delete [_req comment]
  (try
    (delete-comment! comment)
    (-> comment pertains-to-root fresh present-comment edn-response)
    (catch Throwable e
      (throwable->edn-response e))))

(defn handle-upsert [{:keys [body]}]
  (try
    (with-open [rdr (PushbackReader. (io/reader body))]
      (let [attrs (edn/read rdr)]
        #_ (prn :handle-upsert/attrs attrs)
        (-> (upsert-comment! (assoc attrs
                                    :lookup-attribute *lookup-attribute*
                                    :author *author*))
            pertains-to-root
            present-comment
            edn-response)))
    (catch Throwable e
      (throwable->edn-response e))))

(defn handle-link-info [_req link]
  #_(prn :link-info (present-link link))
  (edn-response {:headers {"Cache-Control" "max-age=604800"}}
                (present-link link)))

(defn handle-link-suggestions [req type]
  (try
    (edn-response (present-link-suggestions req (keyword "arb.comment.link.type" type)))
    (catch Throwable e
      (throwable->edn-response e))))

(defn ring-handler* [{:as req :keys [request-method uri]}]
  (when-not (str/ends-with? uri ".js.map")
    (prn :arb-comments/req request-method uri))

  (case [request-method uri]
    [:get "/arb-comments/link/types"]
    (edn-response (mapv #(update % :type name) *link-types*))

    [:patch "/arb-comments/tree"]
    (handle-upsert req)

    (let [[_ id] (re-matches #"/arb-comments/tree/([^/]+)$" uri)
          [_ link-id] (re-matches #"/arb-comments/link/([^/]+)$" uri)
          [_ comment-link-id] (re-matches #"/arb-comments/tree/([^/]+)/link$" uri)
          [_ suggestion-type] (re-matches #"/arb-comments/link/suggestions/(.+)$" uri)
          e (when id (d/entity (db) (->lookup-ref id)))
          link (when link-id (d/entity (db) (->lookup-ref link-id)))]
      (cond
        (and e (= :get request-method))
        (handle-show req e)

        (and e (= :delete request-method))
        (handle-delete req e)

        ;; link routes
        (and link (= :get request-method))
        (handle-link-info req link)

        (and suggestion-type (= :get request-method))
        (handle-link-suggestions req suggestion-type)

        :else
        (edn-response {:status 404} {:message "not found"})))))

(def handler+js-resources
  (-> ring-handler*
      (resource/wrap-resource "public")
      (content-type/wrap-content-type {:mime-types {"mjs" "application/javascript"}})))

(comment
  (re-matches #"/(nextjournal/)?arb-comments/(.*)?" "/arb-comments/")
  (re-matches #"/(nextjournal/)?arb-comments/(.*)?" "/arb-comments/tree/")
  (re-matches #"/(nextjournal/)?arb-comments/(.*)?" "/nextjournal/arb-comments/components.mjs"))

(defn ring-handler
  "Takes a map of options, returns a ring handler (currently matching `/arb-comments/*` routes.)

   Options keys:
   * `:id` (required) points at a datomic attribute for identifying resources in the host application which will hold comments. The same id is also used internally to refer to comments and links
   * `:req->conn` (required) a function from request to a datomic connection
   * `:req->author` (required) a function from request to a datomic entity representing the author of the comment
   * `:present-author` (required) a fuction from the author entity to a map with `:name` (String) and `:url` (URL).
   * `:link-types` a seq of maps with keys `:label` (String) and `:type` (Keyword), where `:type` matches dispatch values
      of methods `arb-comments/link-suggestions` and `arb-comments/present-link-target` and needs to be a qualified keyword
      in the `arb.comment.link.type` namespace.
      Informs UI components of available completions for inserting new links, defaults to an empty vector (suggestions disabled)."
  [{:as _opts :keys [lookup-attribute req->conn req->author present-author link-types]}]
  (fn [{:as req :keys [uri]}]
    (assert lookup-attribute "Need to pass an identifier to refer to commentable resources")
    (assert req->conn "Missing a `:req->conn` function")
    (assert req->author "Missing a `:req->author` function")
    (assert present-author "Missing a `:present-author` function")
    ;; TODO: find a better path for resources
    ;; TODO: consider mount instead, or __arb-comments (?)
    (binding [*lookup-attribute* lookup-attribute
              *conn* (req->conn req)
              *author* (req->author req)
              *present-author* present-author
              *link-types* link-types]
      (ring-handler* req))))

(defn wrap-handler
  "Takes a ring app and the same set of options of `nextjournal.arb-comments/handler`, returns a new ring handler which
  dispatches all routes starting with `/arb-comments` to ."
  [app opts]
  (fn [{:as req :keys [uri]}]
    (if (or (str/starts-with? uri "/arb-comments")
            (str/starts-with? uri "/nextjournal/arb_comments"))
      ((ring-handler opts) req)
      (app req))))
