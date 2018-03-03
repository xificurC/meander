(ns meander.core
  (:refer-clojure
   :exclude [bound?
             extend
             while
             repeat
             replace
             resolve])
  (:require
   [clojure.core :as clj]
   [clojure.core.specs.alpha :as core.specs]
   [clojure.set :as set]
   [clojure.spec.alpha :as spec]
   [clojure.walk :as walk]
   [meander.protocols :as protocols]
   [meander.util :as util]))


;; ---------------------------------------------------------------------
;; Internal utilities


(defmacro undefined
  {:private true}
  ([]
   `(throw (ex-info "undefined" ~(meta &form)))))


(spec/fdef fmap
  :args (spec/cat :f ifn?
                  :x any?)
  :ret any?
  :fn (fn [{:keys [args ret]}]
        (let [[_ x] args]
          (if (satisfies? protocols/IFmap x)
            (instance? (class x) ret)
            true))))

(defn fmap
  ([f x]
   (if (satisfies? protocols/IFmap x)
     (protocols/-fmap x f)
     (f x))))


(defn walk
  ([inner-f outer-f x]
   (if (satisfies? protocols/IWalk x)
     (protocols/-walk x inner-f outer-f)
     (outer-f x))))


(defn postwalk
  ([f x]
   (walk (partial postwalk f) f x)))


(defn prewalk
  ([f x]
   (walk (partial prewalk f) identity (f x))))


(defn splicing-form?
  "True if `x` is an unquote splicing form i.e. `~@xs`."
  {:private true}
  ([x]
   (and (seq? x)
        (= (first x)
           'clojure.core/unquote-splicing))))


(defn lconj
  "Return a goal which is the logicical conjunction 0, 1, or 2 goals."
  {:arglists '([] [goal] [goal-1 goal-2])
   :private true}
  ([]
   (fn [smap]
     (list smap)))
  ([g]
   (fn [smap]
     (g smap)))
  ([g1 g2]
   (fn [smap]
     (mapcat g2 (g1 smap)))))


(defn lconj*
  "Return a goal which is the logical conjunction of `gaols`."
  {:private true}
  ([goals]
   {:pre [(sequential? goals)]}
   (reduce lconj goals)))


;; ---------------------------------------------------------------------
;; Term API


(defn form
  "Return the term form of `x`."
  ([x]
   (if (satisfies? protocols/IForm x)
     (protocols/-form x)
     x)))


(defn variable?
  "true if `x` is a variable, false otherwise."
  ([x]
   (satisfies? protocols/IVariable x)))


(defn variables
  "Return the set of term variables in `x`."
  ([x]
   {:post [(set? %)]}
   (if (satisfies? protocols/ITermVariables x)
     (protocols/-term-variables x)
     #{})))


(defn ground?
  "true if the term `x` contains no variables."
  ([x]
   (empty? (variables x))))


(defn positions*
  {:private true}
  ([term level]
   (cond
     (variable? term)
     #{""}

     (coll? term)
     (reduce into
             #{""}
             (map-indexed
              (fn [i s-i]
                (map str (clj/repeat i) (positions* s-i (inc level))))
              term))

     :else
     #{""})))


(defn positions
  "Return a set of positions in `term`."
  ([term]
   {:pre [(or (variable? term)
              (coll? term))]}
   (positions* term 0)))


(defn size
  "The cardinality of the set of positions with respect to the term
  `term`."
  ([term]
   (count (positions term))))


(defn compare-term
  ([term-a term-b]
   (compare (size term-a) (size term-b))))


(defn rename-variables [term prefix]
  {:pre [(string? prefix)]}
  (let [state (volatile! {})]
    (postwalk
     (fn [x]
       (if (variable? x)
         (if-some [[_ renamed-variable] (find (deref state) (name x))]
           renamed-variable
           (let [new-name (format "%s_%d" prefix (count (deref state)))
                 renamed-variable (fmap (constantly new-name) x)]
             (vswap! state assoc (name x) renamed-variable)
             renamed-variable))
         x))
     term)))


;; variant?
(defn isomorphic?
  "true if two terms, `term-a` and `term-b`, are structurally
  equivalent e.g. they have the same \"shape\".

    (def a (make-variable 'a))
    (def b (make-variable 'b))
    (def c (make-variable 'c))
    (def d (make-variable 'd))

    (isomorphic? [a a] [b b]) ;; => true
    (isomorphic? [a a] [b c]) ;; => false
    (isomorphic? [a b] [c d]) ;; => true
    (isomorphic? [a b] [b a]) ;; => true
    (isomorphic? [a b] [c a]) ;; => true

  Semantically equivalent to Prolog's `=@=`.
  "
  [term-a term-b]
  (= (rename-variables term-a "v")
     (rename-variables term-b "v")))


;; ---------------------------------------------------------------------
;; Unification API


(spec/def ::substitution-map
  (spec/map-of string? any?))


(defn substitution-map? [x]
  (spec/valid? ::substitution-map x))


(spec/def ::maybe-substitution-map
  (spec/nilable ::substitution-map))


(defn substitute
  {:arglists '([u substitution-map])}
  ([x smap]
   (if (satisfies? protocols/ISubstitute x)
     (protocols/-substitute x smap)
     x)))


(spec/fdef unify
  :args (spec/cat :a any?
                  :b any?
                  :substitution-map ::substitution-map)
  :ret ::maybe-substitution-map)


(defn unify
  {:arglists '([u v]
               [u v substitution-map])}
  ([u v]
   (unify u v {}))
  ([u v smap]
   {:pre [(substitution-map? smap)]}
   ;; Orient: move variables to the left-hand side.
   (cond
     (and (not (variable? u))
          (variable? v))
     (recur v u smap)

     ;; Eliminate: solve for a particular variable (or unify in some
     ;; custom way).
     (satisfies? protocols/IUnify u)
     (protocols/-unify u v smap)

     (satisfies? protocols/IUnify* u)
     (first (protocols/-unify* u v smap))

     :else
     (when (= u v)
       smap))))


(defmacro if-unifies
  {:arglists '([[binding u v substitution-map] then else?])
   :style/indent 1}
  ([[smap* u v smap] then]
   `(if-unifies ~[smap* u v smap] ~then nil))
  ([[smap* u v smap] then else]
   `(if-some [smap*# (unify ~u ~v ~smap)]
      (let [~smap* smap*#]
        ~then)
      ~else)))


(defn unify*
  "Return all possible substitutions for u and v."
  {:arglists '([u v]
               [u v substitution-map])}
  ([u v]
   (unify* u v {}))
  ([u v smap]
   {:pre [(map? smap)]}
   (if (satisfies? protocols/IUnify* u)
     (protocols/-unify* u v smap)
     (if-unifies [smap* u v smap]
       (list smap*)
       ()))))


(defn bound?
  {:arglists '([variable substitution-map])}
  ([var smap]
   {:pre [(variable? var)
          (substitution-map? smap)]}
   (contains? smap var)))


(defn resolve
  "Semantically equivalent to μKaren's walk."
  {:arglists '([term substitution-map])}
  ([t smap]
   {:pre [(substitution-map? smap)]}
   (if (variable? t)
     (if-some [[_ x] (find smap (name t))]
       (resolve x smap)
       t)
     t)))


(defmacro if-resolve
  {:style/indent 1}
  ([[binding u smap] then]
   `(if-resolve [~binding ~u ~smap]
      ~then
      nil))
  ([[binding u smap] then else]
   `(let [u# ~u
          v# (resolve u# ~smap)]
      (if (identical? v# u#)
        ~else
        (let [~binding v#]
          ~then)))))


(defn resolve-all
  {:arglists '([terms substitution-map])}
  ([ts smap]
   {:pre [(substitution-map? smap)]}
   (map resolve ts (clj/repeat smap))))


(defn extend
  "Bind (`assoc`) `variable` to a `term` in the `substitution-map`
  provided the variable does not occur in `term`. Returns `nil` if
  the occurence check succeeds and the `substitution-map` if it does
  not."
  {:arglists '([substitution-map variable term])}
  ([smap v t]
   {:pre [(substitution-map? smap)
          (instance? clojure.lang.Named v)]}
   (if (contains? (variables t) v)
     nil
     (assoc smap (name v) t))))


(defmacro if-extend
  {:arglists '([[substitution-map* u v substitution-map] if-true if-false])
   :private true
   :style/indent 1}
  ([[smap* u v smap] if-t if-f]
   `(if (contains? (variables ~v) ~u)
      ~if-f
      (let [~smap* (assoc ~smap ~u ~v)]
        ~if-t))))


(defn extend-no-check
  "Bind (`assoc`) `variable` to a `term` in the `substitution-map`
  without checking if `variable` does not occur in `term`."
  {:arglists '([substitution-map variable term])}
  ([smap v t]
   {:pre [(substitution-map? smap)
          (instance? clojure.lang.Named v)]}
   (assoc smap (name v) t)))


;; ---------------------------------------------------------------------
;; Core Types


(deftype Variable [name meta]
  clojure.lang.IMeta
  (meta [this]
    (.-meta this))

  clojure.lang.IObj
  (withMeta [this m]
    (Variable. name m))

  clojure.lang.Named
  (getName [_]
    (clojure.core/name name))

  protocols/IForm
  (-form [this]
    this)

  protocols/IFmap
  (-fmap [this f]
    (Variable. (f name) (.-meta this)))

  protocols/IVariable

  protocols/ISubstitute
  (-substitute [this smap]
    (resolve this smap))

  protocols/ITermVariables
  (-term-variables [this]
    #{this})

  protocols/IUnify
  (-unify [this that smap]
    (if (= this that)
      smap
      (if-resolve [x this smap]
        (let [y (resolve that smap)]
          (if (= x y)
            smap
            nil))
        (extend smap this that))))

  Object
  (equals [_ that]
    (and (instance? Variable that)
         (= name (.name that))))

  (hashCode [this]
    (.hashCode (.name this))))


(defmethod print-method meander.core.Variable [v ^java.io.Writer w]
  (.write w (str "~" (name v))))


(defn make-variable
  ([]
   (Variable. (name (gensym)) {}))
  ([name]
   {:pre [(or (instance? clojure.lang.Named name)
              (string? name))]}
   (Variable. (clj/name name) {}))
  ([name meta]
   {:pre [(or (instance? clojure.lang.Named name)
              (string? name))
          (map? meta)]}
   (Variable. (clj/name name) meta)))


(deftype SplicingVariable [name meta]
  clojure.lang.IMeta
  (meta [this]
    (.-meta this))

  clojure.lang.IObj
  (withMeta [this m]
    (SplicingVariable. name m))

  clojure.lang.Named
  (getName [_]
    (clojure.core/name name))

  protocols/IForm
  (-form [this]
    this)

  protocols/IFmap
  (-fmap [this f]
    (SplicingVariable. (f name) (.-meta this)))

  protocols/IVariable

  protocols/ISubstitute
  (-substitute [this smap]
    (resolve this smap))

  protocols/ITermVariables
  (-term-variables [this]
    #{this})

  protocols/IUnify
  (-unify [this that smap]
    (if (= this that)
      smap
      (if-resolve [x this smap]
        (when (coll? x)
          (let [y (resolve that smap)]
            (when (= x y)
              smap)))
        (when (coll? that)
          (extend smap this that)))))

  Object
  (equals [_ that]
    (and (instance? SplicingVariable that)
         (= name (.name that))))

  (hashCode [this]
    (.hashCode (.name this))))


(defmethod print-method meander.core.SplicingVariable [v ^java.io.Writer w]
  (.write w (str "~@" (name v))))


(defn make-splicing-variable
  ([]
   (SplicingVariable. (gensym) {}))
  ([name]
   (SplicingVariable. name {}))
  ([name meta]
   (SplicingVariable. name meta)))


(defn splicing-variable?
  ([x]
   (instance? SplicingVariable x)))


(defn not-splicing-variable?
  ([x]
   (not (splicing-variable? x))))


;; ---------------------------------------------------------------------
;; Sequential terms with splicing variables


(defn unify-splicing-variables*
  "Return a sequence of substitutions satisfying the unification of
  the splicing-variables `s-vars` and the collection `u-coll`.

    (let [s-vars (parse-form '[~@xs ~@ys])
          u-coll [1 2 3]]
      (unify-splicing-variables* s-vars u-coll {}))
    ;; =>
    ({\"xs\" [], \"ys\" [1 2 3]}
     {\"xs\" [1], \"ys\" [2 3]}
     {\"xs\" [1 2], \"ys\" [3]}
     {\"xs\" [1 2 3], \"ys\" []})

    (let [s-vars (parse-form '[~@xs ~@ys])
          u-coll [1 2 3]]
      (unify-splicing-variables* s-vars u-coll {\"xs\" [1 2 ]}))
    ;; =>
    ({\"xs\" [1 2], \"ys\" [3]})
  "
  {:private true}
  ([s-vars u-coll smap]
   (let [n (count s-vars)]
     (mapcat
      (fn [pairs]
        (loop [smap smap
               pairs pairs]
          (if-some [[[s-var coll] & pairs*] (seq pairs)]
            (if-unifies [smap* s-var coll smap]
              (recur smap*
                     pairs*))
            (list smap))))
      (map (partial partition 2)
           (map interleave
                (clj/repeat s-vars)
                (util/partitions n u-coll)))))))


;; ---------------------------------------------------------------------
;; Vector terms

(defn unify-splicing-vector*
  "Return a sequence of all possible substutitions satisfying `u-vec`
  and `v-vec` where `u-vec` contains splicing variables."
  {:private true}
  ([u-vec v-vec smap]
   (let [;; Split the pattern at the boundary of the first splicing
         ;; var.  For example, if the pattern was `[1 ~x 3 ~@xs 6 7 ~@ys]`
         ;; the result will be `[[1 ~x 3] [~@xs 6 7 ~@ys]]`.
         [u-left u-right] (map vec (split-with not-splicing-variable? u-vec))
         ;; Split the vector at the boundary index derived by
         ;; counting the number of values in `u-left`. Continuing
         ;; with the example above with `v-vec` as `[1 2 3 4 5 6 7 8]`
         ;; the result will be `[[1 2 3] [4 5 6 7 8]]`.
         [v-left v-right] (util/vsplit-at (count u-left) v-vec)]
     (->>
      ;; Unify `u-left` and `v-left` to get a sequence of solutions
      ;; for the non-variable porition of the vector. With our
      ;; example `u-left` and `v-left` we'd get back the sequence
      ;; `({"x" 2})`.
      (unify* u-left v-left smap)
      ;; Now the interesting part: finding all of the solutions for
      ;; `u-right` and `v-right`
      (mapcat
       (fn [smap]
         (let [;; Once again we need to break the pattern down into
               ;; pieces we can easily reason about. This time we
               ;; want to partition our pattern in a way that
               ;; isolates runs of splicing variables. Applying this
               ;; to `u-right` we get `[[~@xs] [6 7] [~@ys]]`.
               u-parts (mapv vec (partition-by splicing-variable? u-right))
               ;; Next we'll need to build a search space from
               ;; `v-right` that has parity with `u-parts`.
               ;;
               ;;   (,,,
               ;;    [[4] [5 6 7] [8 9]]
               ;;    [[4 5] [6 7] [8 9]]
               ;;    [[4 5 6] [7] [8 9]]
               ;;    ,,,)
               ;;
               v-space (util/partitions (count u-parts) v-right)]
           ;; With our search space in hand we can begin to look
           ;; for possible solutions.
           ;;
           ;; First we'll need to pair up each n-tuple in `v-space`
           ;; with each element `u-parts`.
           ;;
           ;; (,,,
           ;;  (([~@xs] [4]), ([6 7] [5 6 7]), ([~@ys] [8 9]))
           ;;  (([~@xs] [4 5]), ([6 7] [6 7]), ([~@ys] [8 9]))
           ;;  (([~@xs] [4 5 6]), ([6 7] [7]), ([~@ys] [8 9]))
           ;;  ,,,)
           ;;
           ;; Each pair in each row is then unified from left to
           ;; right using a logical conjunction goal. Notice second
           ;; row above will successfully unify `~@xs` with `[4 5]`,
           ;; `[6 7]` with `[6 7]`, and `~@ys` with `[8 9]`. This
           ;; happens to be the only solution in this case, however,
           ;; if we insert the sequence `6 7` anywhere after `3` and
           ;; not in between `6` and `7`, we would find an
           ;; additional solution.
           (->> (map (comp (partial partition 2)
                           (partial interleave u-parts))
                     v-space)
                (mapcat
                 (fn [row]
                   ((lconj*
                     (map
                      (fn [f [u-part v-part]]
                        (fn [smap]
                          (f u-part v-part smap)))
                      ;; We use a special function to find solutions
                      ;; for splicing variables.
                      (cycle [unify-splicing-variables*
                              unify*])
                      row))
                    smap)))))))))))



(defn unify-vector*
  "Return a sequence of all possible substutitions satisfying `u-vec`
  and `v-vec`."
  ([u-vec v-vec smap]
   {:pre [(vector? u-vec)]}
   (when (vector? v-vec)
     (if (some splicing-variable? u-vec)
       (unify-splicing-vector* u-vec v-vec smap)
       (when (= (count u-vec)
                (count v-vec))
         ((lconj*
           (map
            (fn [[u v]]
              (fn [smap]
                (unify* u v smap)))
            (partition 2 (interleave u-vec v-vec))))
          smap))))))


(defn unify-vector
  "Find the first substitution, if any, satisifying the unification of
  `u-vec` and `v-vec`."
  ([u-vec v-vec smap]
   {:pre [(vector? u-vec)]}
   (first (unify-vector* u-vec v-vec smap))))


(extend-type clojure.lang.IPersistentVector
  protocols/IFmap
  (-fmap [this f]
    (mapv f this))


  protocols/ITermVariables
  (-term-variables [this]
    (reduce set/union #{} (map variables this)))


  protocols/ISubstitute
  (-substitute [this smap]
    (reduce
     (fn [v x]
       (if (splicing-variable? x)
         (let [ys (resolve x smap)]
           (cond
             (identical? ys x)
             (let [ys* (resolve (make-variable (name x)) smap)]
               (cond
                 (identical? ys* x)
                 (throw
                  (ex-info "Missing substitution for splice variable."
                           {:variable x
                            :smap smap}))

                 (or (coll? ys*) (nil? ys*))
                 (into v ys*)

                 :else
                 (throw
                  (ex-info "Splicing variable not bound to a collection."
                           {:value ys*
                            :smap smap}))))

             (or (coll? ys) (nil? ys))
             (into v ys)

             :else
             (throw
              (ex-info "Splicing variable not bound to a collection."
                       {:value ys
                        :smap smap}))))
         (conj v (substitute x smap))))
     []
     this))


  protocols/IUnify*
  (-unify* [this that smap]
    (unify-vector* this that smap))


  protocols/IUnify
  (-unify [this that smap]
    (first (protocols/-unify* this that smap)))


  protocols/IWalk
  (-walk [this inner-f outer-f]
    (outer-f (protocols/-fmap this inner-f))))


;; ---------------------------------------------------------------------
;; SeqTerm


(defn unify-splicing-seq*
  ([u-seq v-seq smap]
   (when (seq? v-seq)
     (let [[u-left u-right] (split-with not-splicing-variable? u-seq)
           [v-left v-right] (split-at (count u-left) v-seq)]
       (mapcat
        (fn [smap]
          (let [u-parts (partition-by splicing-variable? u-right)
                v-space (util/partitions (count u-parts) v-right)]
            (mapcat
             (fn [row]
               ((lconj*
                 (map
                  (fn [f [u-part v-part]]
                    (fn [smap]
                      (f u-part v-part smap)))
                  (cycle [unify-splicing-variables*
                          unify*])
                  row))
                smap))
             (map (comp (partial partition 2)
                        (partial interleave u-parts))
                  v-space))))
        (unify* u-left v-left smap))))))


(defn unify-seq*
  ([u-seq v-seq smap]
   {:pre [(seq? u-seq)]}
   (when (seq? v-seq)
     (if (some splicing-variable? u-seq)
       (unify-splicing-seq* u-seq v-seq smap)
       (when (= (count u-seq)
                (count v-seq))
         ((lconj*
           (map
            (fn [[u v]]
              (fn [smap]
                (unify* u v smap)))
            (partition 2 (interleave u-seq v-seq))))
          smap))))))


(defn unify-seq
  ([u-seq v-seq smap]
   {:pre [(seq? u-seq)]}
   (first (unify-seq* u-seq v-seq))))


(extend-type clojure.lang.ISeq
  protocols/IFmap
  (-fmap [this f]
    (map f this))


  protocols/IForm
  (-form [_]
    (map form seq))


  protocols/ISubstitute
  (-substitute [this smap]
    (mapcat
     (fn [x]
       (if (splicing-variable? x)
         (let [ys (resolve x smap)]
           (cond
             (identical? ys x)
             (throw
              (ex-info "Missing substitution for splice variable"
                       {:variable x}))

             (or (coll? ys) (nil? ys))
             ys

             :else
             (throw
              (ex-info "Splice variable not bound to a collection"
                       {:value ys}))))
         (list (substitute x smap))))
     this))


  protocols/ITermVariables
  (-term-variables [this]
    (reduce set/union #{} (map variables this)))


  protocols/IWalk
  (-walk [this inner-f outer-f]
    (outer-f (protocols/-fmap this inner-f)))


  protocols/IUnify*
  (-unify* [this that smap]
    (unify-seq* this that smap))


  protocols/IUnify
  (-unify [this that smap]
    (first (protocols/-unify* this that smap))))


;; ---------------------------------------------------------------------
;; Map term


(defn unify-entry*
  {:private true}
  ([e-a e-b smap]
   (let [[k-a v-a] e-a
         [k-b v-b] e-b
         smap* (unify* k-a k-b smap)]
     (mapcat
      (fn [smap]
        (unify* v-a v-b smap))
      smap*))))


(defn unify-entries*
  {:private true}
  ([pairs smap]
   (if-some [[[e-a e-b] & pairs*] (seq pairs)]
     (mapcat
      (fn [smap]
        (unify-entries* (rest pairs) smap))
      (unify-entry* e-a e-b smap))
     (list smap))))


(defn unify-map*
  "Return a lazy seq of all possible substitutions for `map-a` and
  `map-b`."
  ([map-a map-b smap]
   {:pre [(map? map-a)]}
   (when (map? map-b)
     (when (= (count map-a) (count map-b))
       (if (not= map-a map-b)
         (mapcat
          (fn [!map-a]
            (let [entries (partition 2 (interleave !map-a map-b))]
              (when-some [smap* (unify-entries* entries smap)]
                smap*)))
          (util/permutations map-a))
         (list smap))))))


(defn unify-map
  ([map-a map-b smap]
   (first (unify-map* map-a map-b smap))))


(extend-type clojure.lang.IPersistentMap 
  protocols/IFmap
  (-fmap [this f]
    (into {} (map f this)))


  protocols/IForm
  (-form [this]
    (reduce-kv
     (fn [m k v]
       (assoc m (form k) (form v)))
     {}
     this))


  protocols/ISubstitute
  (-substitute [this smap]
    (reduce-kv
     (fn [m k v]
       (assoc m (substitute k smap) (substitute v smap)))
     {}
     this))


  protocols/ITermVariables
  (-term-variables [this]
    (reduce set/union #{} (clojure.core/map variables (mapcat identity this))))


  protocols/IUnify
  (-unify [this that smap]
    (first (protocols/-unify* this that smap)))


  protocols/IUnify*
  (-unify* [this that smap]
    (unify-map* this that smap))


  protocols/IWalk
  (-walk [this inner-f outer-f]
    (outer-f (into {} (map inner-f this)))))


;; ---------------------------------------------------------------------
;; Set term


(defn unify-set*
  "Return a lazy sequence of all possible substitutions satisfying the
  unification of two sets `set-u` and `set-v`."
  {:arglists '([set-u set-v substitution-map])}
  ([set-u set-v smap]
   {:pre [(set? set-u)]}
   (when (set? set-v)
     (when (= (count set-u) (count set-v))
       (if (not= set-u set-v)
         (mapcat
          (fn [!set-u]
            (let [pairs (partition 2 (interleave !set-u set-v))
                  goals (map (fn [[a b]]
                               (fn [smap]
                                 (unify* a b smap)))
                             pairs)]
              ((lconj* goals) smap)))
          (util/permutations set-u))
         (list smap))))))


(extend-type clojure.lang.IPersistentSet
  protocols/IFmap
  (-fmap [this f]
    (set (map f this)))


  protocols/IForm
  (-form [this]
    (set (map form this)))


  protocols/ISubstitute
  (-substitute [this smap]
    (protocols/-fmap this (fn [x] (substitute x smap))))


  protocols/ITermVariables
  (-term-variables [this]
    (reduce set/union #{} (map variables this)))


  protocols/IUnify
  (-unify [this that smap]
    (first (protocols/-unify* this that smap)))


  protocols/IUnify*
  (-unify* [this that smap]
    (unify-set* this that smap))


  protocols/IWalk
  (-walk [this inner-f outer-f]
    (outer-f (set (map inner-f this)))))


(defn parse-form*
  ([x]
   (parse-form* x {}))
  ([x env]
   (if-some [[_ val] (find env x)]
     [val env]
     (cond
       (seq? x)
       (cond
         (= (first x) 'quote)
         [x env]

         (= (first x) `unquote)
         (let [var (make-variable (second x))]
           [var (assoc env x var)])

         (= (first x) `unquote-splicing)
         (let [var (make-splicing-variable (second x))]
           [var (assoc env x var)])

         :else
         (reduce
          (fn [[s env*] y]
            (let [[y* env**] (parse-form* y env*)]
              [(concat s (list y*)) env**]))
          [() env]
          x))

       (vector? x)
       (reduce
        (fn [[v env*] y]
          (let [[y* env**] (parse-form* y env*)]
            [(conj v y*) env**]))
        [[] env]
        x)

       (map? x)
       (reduce
        (fn [[m env*] e]
          (let [[e* env**] (parse-form* e env*)]
            [(conj m e*) env**]))
        [{} env]
        x)

       (set? x)
       (reduce
        (fn [[s env*] y]
          (let [[y* env**] (parse-form* y env*)]
            [(conj s y*) env**]))
        [#{} env]
        x)

       :else
       [x env]))))


(defn parse-form
  [x]
  (first (parse-form* x {})))


(defn unparse-form [form]
  (postwalk
   (fn [x]
     (cond
       (splicing-variable? x)
       (list 'clojure.core/unquote-splicing (symbol (name x)))

       (variable? x)
       (list 'clojure.core/unquote (symbol (name x)))

       :else
       x))
   form))


;; ---------------------------------------------------------------------
;; Pattern compilation


(defn type-check?
  {:private true}
  [pattern]
  (get (meta pattern) ::type-check? true))


(defn no-type-check
  {:private true}
  [pattern]
  (vary-meta pattern assoc ::type-check? false))


(defn derive-env
  {:private true}
  [form]
  (fmap name (variables form)))


(defn compile-smap
  {:private true}
  [env]
  `(hash-map ~@(mapcat (juxt identity symbol) env)))


(defn compile-pattern
  {:private true}
  [pattern target inner-form env]
  (if (satisfies? protocols/ICompilePattern pattern)
    (protocols/-compile-pattern pattern target inner-form env)
    `(if (= ~target ~pattern)
       ~inner-form)))


(defn compile-unify*
  [pattern target env]
  (let [smap-in (gensym "smap_in__")
        smap-out (gensym "smap_out__")]
    `(reify
       protocols/IUnify*
       (protocols/-unify* [~'_ ~target ~smap-in]
         ~(compile-pattern
           pattern
           target
           `(mapcat
             (fn [~smap-out]
               (if (and ~@(map
                           (fn [var-name]
                             `(or (not (contains? ~smap-in ~var-name))
                                  (= (get ~smap-out ~var-name)
                                     (get ~smap-in ~var-name))))
                           env))
                 (list ~smap-out)))
             (list ~(compile-smap env)))
           env)))))


(defn compile-seq-pattern
  {:private true}
  [pattern target inner-form env]
  (let [;; Divorce the non-variable and variable length portions of the
        ;; collection.
        [non-var-side var-side] (split-with not-splicing-variable? pattern)]
    (case [(empty? non-var-side) (empty? var-side)]
      ;; If the right side is an empty seq we can execute inner-form.
      [true true]
      `(if ~(if (type-check? pattern)
              `(and (seq? ~target)
                    (not (seq ~target)))
              `(not (seq ~target)))
         ~inner-form)

      ;; The sequence has a non-variable length.
      [false true]
      (let [ ;; Because we're compiling from left to right, each term in
            ;; the sequence needs to be compiled with the union of it's
            ;; left siblings environments. The first term in the
            ;; sequence uses the current environment. So if we had a
            ;; current environment of #{} and the sequence
            ;;
            ;;   (~x ~y ~z)
            ;;
            ;; we'd have an environment sequence of
            ;;
            ;;   (#{} #{"x"} #{"x" "y"} #{"x" "y" "z"})
            ;;
            envs (reductions set/union (cons env (map derive-env pattern)))
            ;; Now we want to compile something which looks similar to
            ;;
            ;;   (if (and (seq? TARGET)
            ;;            (= M (count (take M TARGET))))
            ;;     (let [NEW_TARGET (nth TARGET INDEX)]
            ;;       INNER-FORM*))
            ;;
            ;; where
            ;;
            ;;    TARGET is our current target,
            ;;    M is the size of the pattern,
            ;;    INDEX is the element index, and
            ;;    INNER-FORM* is the current inner form.
            ;;
            ;; To do this we build a triple of index, pattern element,
            ;; environment,
            ;;
            ;;    ([0 ~x #{}], [1 ~y #{"x"}], [2 ~z #{"x" "y"}])
            ;;
            ;; and compile each inner form from right to left. We go
            ;; right to left because the right side compiles the inner
            ;; most form. Note, the final element of the environment
            ;; sequence is not needed in this case, however, it is
            ;; needed when compiling variable length sequences.
            m (count pattern)
            inner-form* (reduce
                         (fn [inner-form* [index term env]]
                           (let [target* (gensym (str "nth_" index "__"))]
                             `(let [~target* (nth ~target ~index)]
                                ~(compile-pattern term target* inner-form* env))))
                         inner-form
                         (reverse (map vector (range) pattern envs)))]
        `(if ~(if (type-check? pattern)
                `(and (seq? ~target)
                      (= ~m (count (take ~m ~target)))
                      (not (seq (drop ~m ~target))))
                `(and (= ~m (count (take ~m ~target)))
                      (not (seq (drop ~m ~target)))))
           ~inner-form*))

      ;; The sequence has a variable length.
      [true false]
      (let [envs (reductions set/union (cons env (map derive-env pattern)))
            pattern* (map
                      (fn [term env ret-env]
                        (cond
                          (splicing-variable? term)
                          (if (contains? env (name term))
                            (symbol (name term))
                            `(list (make-splicing-variable ~(name term))))

                          (variable? term)
                          (if (contains? env (name term))
                            `(list ~(symbol (name term)))
                            `(list (make-variable ~(name term))))

                          (ground? term)
                          `(list ~term)

                          :else
                          (let [target (gensym "nth__")]
                            `(list
                              (reify
                                protocols/IUnify*
                                (protocols/-unify* [~'_ ~target smap#]
                                  ~(compile-pattern
                                    term
                                    target
                                    `(list ~(compile-smap ret-env))
                                    env)))))))
                      pattern
                      envs
                      (rest envs))
            inner-form* `(unify* (concat ~@pattern*) ~target ~(compile-smap env))]
        (if (type-check? pattern)
          `(if (and (seq? ~target)
                    (seq target))
             ~inner-form*)
          inner-form*))

      [false false]
      (let [subseq-1 (gensym "seq__")
            subseq-2 (gensym "seq__")
            inner-form* `(let [~subseq-2 (drop ~(count non-var-side) ~target)]
                           ~(compile-seq-pattern (no-type-check var-side)
                                                 subseq-2
                                                 inner-form
                                                 (set/union env (derive-env non-var-side))))
            inner-form* `(let [~subseq-1 (take ~(count non-var-side) ~target)]
                           ~(compile-seq-pattern (no-type-check non-var-side)
                                                 subseq-1
                                                 inner-form*
                                                 env))]
        (if (type-check? pattern)
          `(if (seq? ~target)
             ~inner-form*)
          inner-form*)))))


(defn compile-vector-pattern
  {:private true}
  [pattern target inner-form env]
  (let [[non-var-side var-side] (split-with not-splicing-variable? pattern)]
    (case [(empty? non-var-side) (empty? var-side)]
      [true true]
      `(if ~(if (type-check? pattern)
              `(and (vector? ~target)
                    (not (= ~target [])))
              `(not (= ~target [])))
         ~inner-form)

      [false true]
      (let [envs (reductions set/union (cons env (map derive-env pattern)))
            m (count pattern)
            inner-form* (reduce
                         (fn [inner-form* [index term env]]
                           (let [target* (gensym (str "nth_" index "__"))]
                             `(let [~target* (nth ~target ~index)]
                                ~(compile-pattern term target* inner-form* env))))
                         inner-form
                         (reverse (map vector (range) pattern envs)))]
        `(if ~(if (type-check? pattern)
                `(and (vector? ~target)
                      (= ~m (count ~target)))
                `(= ~m (count ~target)))
           ~inner-form*))

      ;; The sequence has a variable length.
      [true false]
      (let [;; Build vector pattern.
            pattern* (reduce
                      (fn [vec-form term]
                        (cond
                          (splicing-variable? term)
                          (if (contains? env (name term))
                            `(into ~vec-form ~(symbol (name term)))
                            `(conj ~vec-form (make-splicing-variable ~(name term))))

                          (variable? term)
                          ~(if (contains? env (name term))
                             `(conj ~vec-form ~(symbol (name term)))
                             `(conj ~vec-form (make-variable ~(name term))))

                          (ground? term)
                          `(conj ~vec-form ~term)

                          :else
                          `(conj ~vec-form
                                 ~(compile-unify* term (gensym "nth__") env))))
                      []
                      pattern)
            smap (gensym "smap__")
            ret-env (derive-env pattern)
            inner-form* `(mapcat
                          (fn [~smap]
                            (let [{:strs ~(mapv symbol ret-env)} ~smap]
                              ~inner-form))
                          (unify* ~pattern* ~target ~(compile-smap env)))]
        (if (type-check? pattern)
          `(if (and (seq? ~target)
                    (seq ~target))
             ~inner-form*)
          inner-form*))

      [false false]
      (let [subvec-1 (gensym "vec__")
            subvec-2 (gensym "vec__")
            inner-form*
            `(let [~subvec-2 (subvec ~target ~(count non-var-side))]
               ~(compile-vector-pattern (no-type-check var-side)
                                        subvec-2
                                        inner-form
                                        (set/union env (derive-env non-var-side))))
            inner-form*
            `(if (contains? ~target ~(dec (count non-var-side))) ;; Bounds check.
               (let [~subvec-1 (subvec ~target 0 ~(count non-var-side))]
                 ~(compile-vector-pattern (no-type-check non-var-side)
                                          subvec-1
                                          inner-form*
                                          env)))]
        (if (type-check? pattern)
          `(if (vector? ~target)
             ~inner-form*)
          inner-form*)))))


(defn compile-set-pattern
  {:private true}
  [pattern target inner-form env]
  (let [pattern* (fmap
                  (fn [x]
                    (cond
                      ;; Currently not supported.
                      (splicing-variable? x)
                      (if (contains? env (name x))
                        (symbol (name x))
                        `(make-splicing-variable ~(name x)))

                      (variable? x)
                      (if (contains? env (name x))
                        (symbol (name x))
                        `(make-variable ~(name x)))

                      (ground? x)
                      x

                      :else
                      (let [target (gensym "target__")
                            smap-in (gensym "smap_in__")
                            smap-out (gensym "smap_out__")
                            ret-env (set/union env (derive-env x))]
                        `(reify
                           protocols/IUnify*
                           (protocols/-unify* [~'_ ~target ~smap-in]
                             ~(compile-pattern
                               x
                               target
                               `(mapcat
                                 (fn [~smap-out]
                                   (if (and ~@(map
                                               (fn [var-name]
                                                 `(or (not (contains? ~smap-in ~var-name))
                                                      (= (get ~smap-out ~var-name)
                                                         (get ~smap-in ~var-name))))
                                               ret-env))
                                     (list (assoc ~smap-in ~@(mapcat (juxt identity symbol) ret-env)))))
                                 (list ~(compile-smap ret-env))) 
                               env))))))
                  pattern)
        smap (gensym "smap__")
        ret-env (derive-env pattern)]
    `(if (set? ~target)
       (mapcat
        (fn [~smap]
          (let [{:strs ~(mapv symbol ret-env)} ~smap]
            ~inner-form))
        (unify* ~pattern* ~target)))))


(defn compile-map-pattern
  {:private true}
  [pattern target inner-form env]
  (if (ground? (keys pattern))
    (reduce-kv
     (fn [inner-form* k v]
       (let [val-target (gensym "val__")
             ret-env (set/union env (derive-env v))]
         `(if-some [[~'_ ~val-target] (find ~target ~k)]
            ~(compile-pattern v
                              val-target
                              inner-form*
                              env))))
     inner-form
     pattern)
    (let [pattern* (fmap
                    (fn [[k v]]
                      (let [k* (cond
                                 (ground? k)
                                 k

                                 (variable? k)
                                 `(make-variable ~(name k)))
                            val-target (gensym "val__")
                            smap-in (gensym "smap_in__")
                            smap-out (gensym "smap_out__")
                            ret-env (set/union env (derive-env v))
                            v* `(reify
                                  protocols/IUnify*
                                  (protocols/-unify* [~'_ ~val-target ~smap-in]
                                    ~(compile-pattern
                                      v
                                      val-target
                                      `(do
                                         (mapcat
                                          (fn [~smap-out]
                                            (if (and ~@(map
                                                        (fn [var-name]
                                                          `(or (not (contains? ~smap-in ~var-name))
                                                               (= (get ~smap-out ~var-name)
                                                                  (get ~smap-in ~var-name))))
                                                        ret-env))
                                              (list (assoc ~smap-in ~@(mapcat (juxt identity symbol) ret-env)))))
                                          (list ~(compile-smap ret-env))))
                                      env)))]
                        [k* v*]))
                    pattern)
          smap (gensym "smap__")
          ret-env (derive-env pattern)]
      `(if (map? ~target)
         (mapcat
          (fn [~smap]
            (let [{:strs ~(mapv symbol ret-env)} ~smap]
              (list ~smap)))
          (unify* ~pattern* ~target))))))


(extend-type meander.core.Variable
  protocols/ICompilePattern
  (-compile-pattern [this target inner-form env]
    (if (contains? env (name this))
      `(let [target# ~target]
         (if (= target# ~(symbol (name this)))
           ~inner-form))
      `(let [~(symbol (name this)) ~target]
         ~inner-form))))


(extend-type clojure.lang.Symbol
  protocols/ICompilePattern
  (-compile-pattern [this target inner-form env]
    `(if (= ~target '~this)
       ~inner-form)))


(extend-type meander.core.SplicingVariable
  protocols/ICompilePattern
  (-compile-pattern [this target inner-form env]
    (if (contains? env (name this))
      `(let [target# ~target]
         (if (= target# ~(symbol (name this)))
           ~inner-form))
      `(let [~(symbol (name this)) ~target]
         ~inner-form))))


(extend-type clojure.lang.ISeq
  protocols/ICompilePattern
  (-compile-pattern [this target inner-form env]
    (if (ground? this)
      `(if (= ~target '~this)
         ~inner-form) 
      (compile-seq-pattern this target inner-form env))))


(extend-type clojure.lang.IPersistentVector
  protocols/ICompilePattern
  (-compile-pattern [this target inner-form env]
    (if (ground? this)
      `(if (= ~target ~this)
         ~inner-form)
      (compile-vector-pattern this target inner-form env))))


(extend-type clojure.lang.IPersistentSet
  protocols/ICompilePattern
  (-compile-pattern [this target inner-form env]
    (if (ground? this)
      `(if (= ~target ~this)
         ~inner-form)
      (compile-set-pattern this target inner-form env))))


(extend-type clojure.lang.IPersistentMap
  protocols/ICompilePattern
  (-compile-pattern [this target inner-form env]
    (if (ground? this)
      `(if (= ~target ~this)
         ~inner-form)
      (compile-map-pattern this target inner-form env))))


;; ---------------------------------------------------------------------
;; Substitution compilation


(defn compile-substitute
  "Given a pattern and symbol which represents a bound substitution
  `smap-sym`, return code which extracts and binds values from the
  substitution and constructs the data structure described by
  `pattern`."
  {:private true}
  [pattern smap-sym]
  (let [var-syms (map (comp symbol name) (variables pattern))]
    `(let [{:strs [~@var-syms]} ~smap-sym]
       ~(postwalk
         (fn [x]
           (cond
             (and (variable? x)
                  (not (splicing-variable? x)))
             (symbol (name x))

             (vector? x)
             (if (some splicing-variable? x)
               (reduce
                (fn [v y]
                  (if (splicing-variable? y)
                    `(into ~v ~(symbol (name y)))
                    `(conj ~v ~y)))
                []
                x)
               x)

             (seq? x)
             (if (some splicing-variable? x)
               `(concat
                 ~@(map
                    (fn [y]
                      (if (splicing-variable? y)
                        (symbol (name y))
                        `(list ~y)))
                    x))
               (cons `list x))

             (symbol? x)
             `'~x

             :else
             x))
         pattern))))


;; ---------------------------------------------------------------------
;; pattern macro


(spec/def ::pattern-args
  (spec/cat
   :pattern any?
   :when-clause (spec/? (spec/cat
                         :when #{:when}
                         :expr any?))))

(spec/fdef pattern
  :args ::pattern-args
  :ret any?)

(defmacro pattern
  {:arglists '([form & {:keys [when]}])
   :style/indent :defn}
  [form & {when-clause :when}]
  (let [target (gensym "target__")
        smap (gensym "smap__")
        form* (parse-form form)]
    `(reify
       protocols/ITermVariables
       (protocols/-term-variables [this#]
         #{~@(map
              (fn [v]
                `(make-variable ~(name v) ~(meta v)))
              (variables form*))})

       protocols/IUnify*
       (protocols/-unify* [this# ~target smap-outer#]
         (for [~smap ~(compile-pattern
                       form*
                       target
                       `(list ~(compile-smap (derive-env form*)))
                       #{})
               ~@(when when-clause
                   [:let `[{:strs [~@(map (comp symbol name) (variables form*))]} ~smap]
                    :when when-clause])
               :when (every?
                      (fn [[ik# iv#]]
                        (if-some [[_# ov#] (find smap-outer# ik#)]
                          (= iv# ov#)
                          true))
                      ~smap)]
           (merge smap-outer# ~smap)))

       protocols/ISubstitute
       (protocols/-substitute [this# ~smap]
         ~(compile-substitute form* smap))

       clojure.lang.IFn
       (~'invoke [this# ~target]
        this#))))


;; ---------------------------------------------------------------------
;; Strategy combinators
;;
;; A strategy is a unary function of a term and returns the term
;; rewriten.
;;
;; Notation
;;
;; t ∈ Term
;; p, q, r, s ∈ Strategy


(def
  ^{:arglists '([t])
    :dynamic true}
  *pass*
  "Strategy which returns t. Unifies with anything."
  (reify 
    clojure.lang.IFn
    (invoke [_ t]
      t)

    (applyTo [_ args]
      (first args))

    protocols/IUnify
    (protocols/-unify [_ _ smap]
      smap)

    protocols/IUnify*
    (protocols/-unify* [_ _ smap]
      (list smap))))


(defmethod print-method (class *pass*) [v ^java.io.Writer w]
  (.write w "#meander/pass[]"))


(def
  ^{:arglists '([t])
    :dynamic true}
  *fail*
  "Strategy which always fails. Unifies with nothing."
  (reify
    clojure.lang.IFn
    (invoke [this _]
      this)

    (applyTo [this _]
      this)

    protocols/IUnify
    (-unify [_ _ _]
      nil)

    protocols/IUnify*
    (-unify* [_ _ _]
      nil)

    protocols/IFmap
    (-fmap [this _]
      this)))


(defmethod print-method (class *fail*) [v ^java.io.Writer w]
  (.write w "#meander/fail[]"))


(defn fail?
  "true if `x` is `*fail*`, false otherwise."
  [x]
  (identical? x *fail*))


(defn build
  "Build a strategy which always returns `t`."
  [t]
  (fn [_] t))


(deftype Pipe [p q]
  clojure.lang.IFn
  (invoke [_ t]
    (let [t* (p t)]
      (if (fail? t*)
        *fail*
        (q t*))))

  (applyTo [this args]
    (clojure.lang.AFn/applyToHelper this args))

  protocols/IUnify
  (-unify [this v smap]
    (first (protocols/-unify* this v smap)))
  
  protocols/IUnify*
  (-unify* [_ v smap]
    ((lconj
      (fn [smap]
        (protocols/-unify* p v smap))
      (fn [smap]
        (protocols/-unify* q v smap)))
     smap)))


(defn pipe
  "Build a strategy which applies `p` to `t` and then `q` iff `p` rewrites
  `t`. If `p` and `q` are successful, return the result, otherwise
  return `*fail*`. This is the strategy equivalent of `and`."
  ([] *pass*)
  ([p] p)
  ([p q]
   (if (or (fail? p) (fail? q)) 
     *fail*
     (Pipe. p q)))
  ([p q & more]
   (apply pipe (pipe p q) more)))


(deftype Choice [p q]
  clojure.lang.IFn
  (invoke [_ t]
    (let [t* (p t)]
      (if (fail? t*)
        (q t)
        t*)))

  (applyTo [this args]
    (clojure.lang.AFn/applyToHelper this args))

  protocols/IUnify
  (protocols/-unify [_ v smap]
    (or (protocols/-unify p v smap)
        (protocols/-unify q v smap)))

  protocols/IUnify*
  (protocols/-unify* [_ v smap]
    (let [ldisj (fn f [smaps1 smap2]
                  (if (seq smaps1)
                    (cons (first smaps1)
                          (lazy-seq (f smap2 (next smaps1))))
                    smap2))]
      (ldisj (protocols/-unify* p v smap)
             (protocols/-unify* q v smap)))))


(defn choice
  "Build a strategy which applies `p` or `q` to `t`. If `p` rewrites,
  return the result, otherwise apply `q`. This is the strategy
  equivalent of `or`."
  ([] *fail*)
  ([p]
   (if (fail? p)
     *fail*
     (Choice. p *fail*)))
  ([p q]
   (case [(fail? p) (fail? q)]
     [true true]
     *fail*

     [true false]
     q

     [false true]
     p

     [false false]
     (Choice. p q)))
  ([p q & more]
   (apply choice (choice p q) more)))


(defn branch
  {:style/indent :defn}
  [p q r]
  (fn [t]
    (let [t* (p t)]
      (if (fail? t*)
        (r t)
        (q t*)))))


(defn attempt
  "Build a strategy which attempts apply `s` to a term. If `s`
  succeeds, it returns the result. If `s` fails return the original
  term."
  [s]
  (choice s *pass*))


(defn pred
  "Build a strategy which returns `t` iff `p` is true for `t` and
  fails otherwise."
  [p]
  (fn [t]
    (if (p t)
      t
      *fail*)))


(defn guard
  "Build a strategy which applies `s` to `t` iff `p` is true for `t`."
  {:style/indent :defn}
  [p s]
  (attempt (pipe (pred p) s)))


(defn repeat
  "Build a strategy which applies `s` to `t` repeatedly until failure.
  Note that, if used in conjunction with a strategy which never fails
  i.e. `attempt`, this will cause a stack overflow. To avoid this, use
  `while` or `until`.

  Example:

  ((repeat
    (pipe (pred vector?)
          (fn [v]
            (if (seq v)
              (if (= (peek v) 2)
                *fail*
                (pop v))
              *fail*))))
   [1 2 3 4])
  ;; =>
  [1 2]
  "
  [s]
  (fn rec [t]
    ((attempt (pipe s rec)) t)))


(defn while
  "Build a strategy which repeatedly applies `s` to `t` so long as `pred`
  is false for `t` and `t*`.

  ((while not=
     (t (let [~@bvs ~b ~v] ~@body)
       (let [~@bvs] ((fn [~b] ~@body) ~v))))
   '(let [a 1
          b 2
          c 3]
      (+ a b c)))
  =>
  (let [] ((fn [a] ((fn [b] ((fn [c] (+ a b c)) 3)) 2)) 1))"
  {:style/indent :defn}
  [pred s]
  (fn rec [t]
    ((pipe (attempt s)
           (fn [t*]
             (if (pred t t*)
               (rec t*)
               t*)))
     t)))


(defn until
  "Build a strategy which repeatedly applies `s` to `t` so long as `pred`
  is false for `t` and `t*`.

  ((until =
     (t (let [~@bvs ~b ~v] ~@body)
       (let [~@bvs] ((fn [~b] ~@body) ~v))))
   '(let [a 1
          b 2
          c 3]
      (+ a b c)))
  =>
  (let [] ((fn [a] ((fn [b] ((fn [c] (+ a b c)) 3)) 2)) 1))"
  {:style/indent :defn}
  [pred s]
  (fn rec [t]
    ((pipe (attempt s)
           (fn [t*]
             (if (pred t t*)
               t*
               (rec t*))))
     t)))



(defn iall? [x]
  (satisfies? protocols/IAll x))


(defn all [s]
  (fn [t]
    (if (iall? t)
      (protocols/-all t s)
      t)))


(defn all-td
  "Apply the all strategy with `s` to every subterm in `t` from the
  top down."
  [s]
  (fn rec [t]
    ((choice s (all rec)) t)))


(defn all-bu
  "Apply the all strategy with `s` to every subterm in `t` from the
  bottom up."
  [s]
  (fn rec [t]
    ((choice (all rec) s) t)))


(defn ione? [x]
  (satisfies? protocols/IOne x))


(defn one [s]
  (fn [t]
    (if (ione? t)
      (protocols/-one t s)
      t)))


(defn once-td
  "Apply the `one` strategy with `s` to every subterm in `t` from the
  top down."
  [s]
  (fn rec [t]
    ((choice s (one rec)) t)))


(defn once-bu
  "Apply the `one` strategy with `s` to every subterm in `t` from the
  bottom up."
  [s]
  (fn rec [t]
    ((choice (one rec) s) t)))


(defn imany? [x]
  (satisfies? protocols/IMany x))


(defn many
  "Build a strategy which applies `s` to as many direct subterms of
  `t` as possible. Succeeds if at least one application applies, fails
  otherwise."
  [s]
  (fn [t]
    (if (imany? t)
      (protocols/-many t s)
      t)))


(defn many-td
  [s]
  (fn rec [t]
    ((choice s (many rec)) t)))


(defn many-bu
  [s]
  (fn rec [t]
    ((choice (many rec) s) t)))


(defn spine-td
  [s]
  (fn rec [t]
    ((pipe s (attempt (one rec))))))


(defn spine-bu
  [s]
  (fn rec [t]
    ((pipe (attempt (one rec)) s))))


(defn breadth-first [s]
  (fn rec [t]
    ((pipe (all s) (all rec)) t)))


(defn bottom-up
  "Build a strategy which applies `s` to each subterm of `t` from
  bottom to top."
  [s]
  (fn rec [t]
    ((pipe (all rec) s) t)))


(defn top-down
  "Build a strategy which applies `s` to each subterm of `t` from
  top to bottom."
  [s]
  (fn rec [t]
    ((pipe s (all rec)) t)))


(defn top-down-while
  "Build a strategy which applies `s` to each subterm of `t` from
  top to bottom so long as `pred` is true for some subterm of `t`."
  [pred s]
  (fn rec [t]
    (if (pred t)
      ((pipe s (all rec)) t)
      t)))


(defn top-down-until
  "Build a strategy which applies `s` to each subterm of `t` from
  top to bottom untl as `pred` is false for some subterm of `t`."
  [pred s]
  (fn rec [t]
    (if (pred t)
      t
      ((pipe s (all rec)) t))))


(defn outermost
  "Build a strategy which repeatedly applies `s` to `t` starting from
  the outermost subterm in `t` until it fails."
  [s]
  (repeat (once-td s)))


(defn innermost
  "Build a strategy which repeatedly applies `s` to `t` starting from
  the innermost subterm in `t`."
  [s]
  (fn rec [t]
    ((bottom-up (repeat s)) t)))


(defn trace
  "Build a strategy which monitors the entry and exit values of `s`."
  ([s]
   (trace s prn))
  ([s f]
   (let [id (gensym "t_")]
     (fn [t]
       (f {:id id, :in t})
       (let [t* (s t)]
         (f {:id id, :out t*})
         t*)))))


(defn spread
  "Build a strategey which applies the first `n` values of `t` to `f`
  iff `t` is a coll. Behaves like apply if `n` is not supplied. Useful
  in conjunction with `juxt`.

  ((pipe
    (juxt (comp (many keyword) keys)
          vals)
    (spread zipmap 2))
   {\"foo\" \"bar\"
    \"baz\" \"quux\"})
  ;; => 
  {:foo \"bar\"
   :baz \"quux\"}"
  ([f]
   (fn spread-all [t]
     (if (coll? t)
       (apply f t)
       t)))
  ([f n]
   (fn spread-n [t]
     (if (coll? t)
       (let [args (take n t)]
         (if (= (count args)
                n)
           (apply f args)
           t))
       t))))


(defn tuple
  "Build a s juxt"
  ([]
   (build []))
  ([p]
   (pipe p vector))
  ([p q]
   (fn [t]
     (let [t*1 (p t)]
       (if (fail? t*1)
         *fail*
         (let [t*2 (q t)]
           (if (fail? t*2)
             *fail*
             [t*1 t*2]))))))
  ([p q & more]
   (pipe (apply tuple (tuple p q) more)
         (spread conj))))


(extend-type clojure.lang.IPersistentVector
  protocols/IAll
  (-all [this s]
    (reduce
     (fn [this* x]
       (let [x* (s x)]
         (if (fail? x*)
           (reduced *fail*)
           (conj this* x*))))
     []
     this))


  protocols/IMany
  (-many [this s]
    (let [[this* pass?]
          (reduce-kv
           (fn [[this* pass?] i x]
             (let [x* (s x)]
               (if (fail? x*)
                 [this* pass?]
                 [(assoc this* i x*) true])))
           [this false]
           this)]
      (if pass?
        this*
        *fail*)))

  
  protocols/IOne
  (-one [this s]
    (reduce-kv
     (fn [acc i x]
       (let [x* (s x)]
         (if (fail? x*)
           acc
           (reduced (assoc this i x*)))))
     *fail*
     this)))


(extend-type clojure.lang.ISeq
  protocols/IAll
  (-all [this s]
    (reduce
     (fn [this* x]
       (let [x* (s x)]
         (if (fail? x*)
           (reduced *fail*)
           (concat this* (list x*)))))
     ()
     this))


  protocols/IMany
  (-many [this s]
    (let [[this* pass?]
          (reduce
           (fn [[this* pass?] x]
             (let [x* (s x)]
               (if (fail? x*)
                 [(cons x this*) pass?]
                 [(cons x* this*) true])))
           [() false]
           (reverse this))]
      (if pass?
        this*
        *fail*)))

  
  protocols/IOne
  (-one [this s]
    (reduce
     (fn [_acc [i x]]
       (let [x* (s x)]
         (if (fail? x*)
           *fail*
           (reduced (concat (take i this)
                            (list x*)
                            (drop (inc i) this))))))
     *fail*
     (map-indexed vector this))))


(extend-type clojure.lang.IPersistentMap
  protocols/IAll
  (-all [this s]
    (reduce-kv
     (fn [this* k v]
       (let [k* (s k)]
         (if (fail? k*)
           *fail*
           (let [v* (s v)]
             (if (fail? v*)
               *fail*
               (assoc this* k* v*))))))
     {}
     this))


  protocols/IMany
  (-many [this s]
    (let [[this* pass?]
          (reduce-kv
           (fn [[this* pass?] k v]
             (let [k* (s k)
                   v* (s v)]
               (case [(fail? k*) (fail? v*)]
                 [true true]
                 [(assoc this* k v) pass?]

                 [true false]
                 [(assoc this* k v*) true]

                 [false true]
                 [(assoc this* k* v) true]

                 [false false]
                 [(assoc this* k* v*) true])))
           [{} false]
           this)]
      (if pass?
        this*
        *fail*)))

  
  protocols/IOne
  (-one [this s]
    (reduce-kv
     (fn [acc k v]
       (let [k* (s k)]
         (if (fail? k*)
           (let [v* (s v)]
             (if (fail? v*)
               acc
               (reduced (assoc this k v*))))
           (reduced (assoc this k* v)))))
     *fail*
     this)))


(extend-type clojure.lang.IPersistentSet
  protocols/IAll
  (-all [this s]
    (reduce
     (fn [this* x]
       (let [x* (s x)]
         (if (fail? x*)
           (reduced *fail*)
           (conj this* x*))))
     #{}
     this))


  protocols/IMany
  (-many [this s]
    (let [[this* pass?] 
          (reduce
           (fn [[this* pass?] x]
             (let [x* (s x)]
               (if (fail? x*)
                 [(conj this* x) pass?]
                 [(conj this* x*) true])))
           [#{} false]
           this)]
      (if pass?
        this*
        *fail*)))

  
  protocols/IOne
  (-one [this s]
    (reduce
     (fn [acc x]
       (let [x* (s x)]
         (if (fail? x*)
           *fail*
           (reduced (conj (disj this x) x*)))))
     *fail*
     this)))


;; ---------------------------------------------------------------------
;; Tools


(defn match?
  "true if u unifies with v."
  ([u]
   (fn [v]
     (match? u v)))
  ([u v]
   (some? (unify u v))))


(defn extract
  "Return a lazy sequence of all instances of `v` in `t` that unify
  with `u`. If `u` supports implements substitution it will return
  a sequence of `v*` instead where `v*` is the result of applying the
  substitution to `v`. Uses `tree-seq` to produce all subterms.

  Example:

    (extract (pattern [:foo ~@xs :baz])
             [[:foo :bar :baz]
              [:foo :baz :bar]
              [:foo [:foo :baz] :baz]
              [:foo]])
    ;; =>
    ([:foo :bar :baz]
     [:foo [:foo :baz] :baz]
     [:foo :baz])

    (extract
     (t {:student/id ~id
         :test/score ~score}
       :when (< 90 score)
       [~id ~score])
     [{:student/id 1, :test/score 85}
      {:student/id 2, :test/score 93}
      {:student/id 3, :test/score 61}
      {:student/id 4, :test/score 99}])
    ;; =>
    ([2 93] [4 99])
  "
  ([u]
   (fn [t]
     (extract u t)))
  ([u t]
   (for [v (tree-seq seqable? seq t)
         :let [smap (unify u v {})]
         :when smap
         :let [v* (substitute u smap)]]
     (if (= v* u)
       v
       v*))))


(defn zero-property-t
  "The zero property of the function that's symbol is
  `fsym`. Semantically equivalent to

  (t (fsym ~@xs ~zero ~@ys)
    ~zero)
  "
  ([fsym zero]
   {:pre [(symbol? fsym)]}
   (reify
     clojure.lang.IFn
     (invoke [this t]
       (if-unifies [smap* this t {}]
         (protocols/-substitute this smap*)
         *fail*))


     protocols/ISubstitute
     (-substitute [this smap]
       (if-some [seq (get smap this)]
         (if (some (partial = zero) seq)
           zero
           seq)
         (throw
          (ex-info "Zero property transform not be found in the substitution map."
                   {:fsym fsym
                    :zero zero}))))


     protocols/IUnify
     (-unify [this x smap]
       (if (and (seq? x)
                (= (first x) fsym))
         ;; Intentional use of `assoc` instead of `extend`, etc.
         (assoc smap this x)
         nil)))))


(defmacro zero-property
  "Examples:

  ((zero-property * 0)
   '(* x y 0))
  ;; => 0

  ((zero-property and false)
   '(and true x y false z))
  ;; => false
  "
  [fsym zero]
  {:pre [(symbol? fsym)]}
  `(zero-property-t '~fsym ~zero))


(defn associative-t
  ([fsym]
   {:pre [(symbol? fsym)]}
   (reify
     clojure.lang.IFn
     (invoke [this t]
       (if-unifies [smap* this t {}]
         (protocols/-substitute this smap*)
         *fail*))

     protocols/ISubstitute
     (-substitute [this smap]
       (if-some [seq (get smap this)]
         (mapcat
          (fn [x]
            (if (and (seq? x)
                     (= (first x) fsym))
              (rest x)
              (list x)))
          seq)
         (throw
          (ex-info "Associative transform not found in the substitution."
                   {:fsym fsym}))))


     protocols/IUnify
     (-unify [this x smap]
       (if (and (seq? x)
                (= (first x) fsym))
         ;; Intentional use of `assoc` instead of `extend`, etc.
         (assoc smap this x)
         nil)))))


(defmacro associative
  ([fsym]
   {:pre [(symbol? fsym)]}
   `(associative-t '~fsym)))


(defn monoid-t
  ([fsym id]
   {:pre [(symbol? fsym)]}
   (reify
     clojure.lang.IFn
     (invoke [this t]
       (if-some [smap (protocols/-unify this t {})]
         (protocols/-substitute this smap)
         *fail*))


     protocols/ISubstitute
     (-substitute [this smap]
       (if-some [seq (get smap this)]
         (let [seq* (mapcat
                     (fn [x]
                       (cond
                         (and (seq? x)
                              (= (first x) fsym))
                         (remove #{id} (rest x))

                         (= x id)
                         ()

                         :else
                         (list x)))
                     seq)]
           (if (= seq* (list fsym))
             id
             seq*))
         ;; Is the "right" thing to do?
         (throw
          (ex-info "Monoid transform not found in the substitution map."
                   {:fsym fsym
                    :id id}))))


     protocols/IUnify
     (-unify [this x smap]
       (when (and (seq? x)
                  (= (first x) fsym))
         ;; Intentional use of `assoc` instead of `extend`.
         (assoc smap this x))))))


(defmacro monoid
  ([fsym id]
   {:pre [(symbol? fsym)]}
   `(monoid-t '~fsym ~id)))


;; ---------------------------------------------------------------------
;; transform macro

(spec/def ::t
  (spec/cat
   :pattern any?
   :as-clause (spec/?
               (spec/cat :as #{:as}
                         :sym symbol?))
   :clauses (spec/*
             (spec/alt
              :when-clause (spec/cat
                            :when #{:when}
                            :expr any?)
              :let-clause (spec/cat
                           :let #{:let}
                           :bindings ::core.specs/bindings)))
   :ret any?))


(spec/fdef meander.core/t
  :args ::t
  :ret any?)


(defmacro t
  {:arglists '([u-pattern clauses* s-pattern])
   :style/indent :defn}
  [& args]
  (let [[u-pattern & rest-args] args
        as (if (= (first rest-args) :as)
             (second rest-args))
        rest-args (if as
                    (nnext rest-args)
                    rest-args)
        clauses* (butlast rest-args)
        s-pattern (last rest-args)
        u-var-syms (map (comp symbol name)
                        (variables (parse-form u-pattern)))
        s-var-syms (map (comp symbol name)
                        (variables (parse-form s-pattern)))
        meta-smap (into {}
                        (map (juxt name identity)
                             (set/intersection
                              (set s-var-syms)
                              (set (mapcat
                                    (fn [clause]
                                      (when (= (first clause) :let)
                                        (take-nth 2 (destructure (second clause)))))
                                    (partition 2 clauses*))))))
        meta-smap (if as
                    (assoc meta-smap (name as) as)
                    meta-smap)
        v `v#]
    `(let [u-pattern# (pattern ~u-pattern)
           s-pattern# (pattern ~s-pattern)]
       (reify
         clojure.lang.IFn
         (clojure.lang.IFn/invoke [this# x#]
           (if-some [smap# (protocols/-unify this# x# {})]
             (protocols/-substitute this# (merge smap# (meta smap#)))
             *fail*))

         (clojure.lang.IFn/applyTo [this# args#]
           (clojure.lang.AFn/applyToHelper this# args#))

         protocols/IUnify
         (protocols/-unify [this# v# smap#]
           (first (protocols/-unify* this# v# smap#)))

         protocols/IUnify*
         (protocols/-unify* [this# ~v smap#]
           (for [~'&smap (unify* u-pattern# ~v smap#)
                 :let [{:strs [~@u-var-syms]} ~'&smap
                       ~@(when as (list as v))]
                 ~@clauses*]
             (with-meta ~'&smap ~meta-smap)))

         protocols/ISubstitute
         (protocols/-substitute [this# smap#]
           (protocols/-substitute s-pattern# (merge smap# (meta smap#))))))))
