(ns nextjournal.hooks
  (:require ["react" :as react]))

(deftype WrappedState [st]
  IIndexed
  (-nth [_coll i] (aget st i))
  (-nth [_coll i nf] (or (aget st i) nf))
  IDeref
  (-deref [^js _this] (aget st 0))
  IReset
  (-reset! [^js _this new-value]
    ;; `constantly` here ensures that if we reset state to a fn,
    ;; it is stored as-is and not applied to prev value.
    ((aget st 1) (constantly new-value))
    new-value)
  ISwap
  (-swap! [_this f] ((aget st 1) f))
  (-swap! [_this f a] ((aget st 1) #(f % a)))
  (-swap! [_this f a b] ((aget st 1) #(f % a b)))
  (-swap! [_this f a b xs] ((aget st 1) #(apply f % a b xs))))

(defn- as-array [x] (cond-> x (not (array? x)) to-array))

(defn use-memo
  "React hook: useMemo. Defaults to an empty `deps` array."
  ([f] (react/useMemo f #js[]))
  ([f deps] (react/useMemo f (as-array deps))))

(defn use-callback
  "React hook: useCallback. Defaults to an empty `deps` array."
  ([x] (use-callback x #js []))
  ([x deps] (react/useCallback x (to-array deps))))

(defn- wrap-effect
  ;; utility for wrapping function to return `js/undefined` for non-functions
  [f] #(let [v (f)] (if (fn? v) v js/undefined)))

(defn use-effect
  "React hook: useEffect. Defaults to an empty `deps` array.
   Wraps `f` to return js/undefined for any non-function value."
  ([f] (react/useEffect (wrap-effect f) #js[]))
  ([f deps] (react/useEffect (wrap-effect f) (as-array deps))))

(defn use-layout-effect
  "React hook: useLayoutEffect. Defaults to an empty `deps` array.
   Wraps `f` to return js/undefined for any non-function value."
  ([f] (react/useLayoutEffect (wrap-effect f) #js[]))
  ([f deps] (react/useLayoutEffect (wrap-effect f) (as-array deps))))

(defn use-state
  "React hook: useState. Can be used like react/useState but also behaves like an atom."
  [init]
  (WrappedState. (react/useState init)))
