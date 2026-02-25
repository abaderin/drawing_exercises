(ns drawing-exercises.core)

(defn load-shader [path]
  (-> (js/fetch path)
      (.then #(.text %))))

(defn set-status! [text color]
  (when-let [el (js/document.getElementById "status")]
    (set! (.-textContent el) text)
    (set! (.. el -style -color) color)))

(defn configure-canvas! [context device format]
  (.configure context #js
    {:device device
     :format format
     :usage (.-RENDER_ATTACHMENT js/GPUTextureUsage)
     :alphaMode "opaque"}))

(defn create-pipeline [device format vertex-code fragment-code]
  (let [shader-module (.createShaderModule device #js {:code vertex-code})
        shader-module-frag (.createShaderModule device #js {:code fragment-code})]
    (.createRenderPipeline device #js
      {:layout "auto"
       :vertex #js
       {:module shader-module
        :entryPoint "vs_main"}
       :fragment #js
       {:module shader-module-frag
        :entryPoint "fs_main"
        :targets #js [#js {:format format}]}
       :primitive #js
       {:topology "triangle-list"}})))

(defn create-buffers [device]
  (let [uniform-buffer (.createBuffer device #js
                         {:size 16
                          :usage (bit-or (.-UNIFORM js/GPUBufferUsage)
                                         (.-COPY_DST js/GPUBufferUsage))
                          :mappedAtCreation false})]
    {:uniform uniform-buffer}))

(defn create-bind-group [device pipeline uniform-buffer]
  (.createBindGroup device #js
    {:layout (.getBindGroupLayout pipeline 0)
     :entries #js [#js {:binding 0
                        :resource #js {:buffer uniform-buffer}}]}))

(defn resize-canvas [canvas device context]
  (let [client-width (.-clientWidth canvas)
        client-height (.-clientHeight canvas)
        width (if (pos? client-width) client-width (.-width canvas))
        height (if (pos? client-height) client-height (.-height canvas))]
    (when (or (not= (.-width canvas) width)
              (not= (.-height canvas) height))
      (set! (.-width canvas) width)
      (set! (.-height canvas) height)
      (configure-canvas! context device canvas-format))
    {:width width
     :height height}))

(defn render [device context pipeline bind-group uniform-buffer time]
  (let [_ (resize-canvas canvas device context)
        time-data (js/Float32Array. #js [time 0 0 0])
        command-encoder (.createCommandEncoder device)
        texture (.getCurrentTexture context)
        texture-view (.createView texture)
        render-pass (.beginRenderPass command-encoder #js
                       {:colorAttachments #js [#js
                                               {:view texture-view
                                                :clearValue #js {:r 0.1 :g 0.1 :b 0.1 :a 1.0}
                                                :loadOp "clear"
                                                :storeOp "store"}]})]
    (-> device .-queue (.writeBuffer uniform-buffer 0 time-data))
    (.setPipeline render-pass pipeline)
    (.setBindGroup render-pass 0 bind-group)
    (.draw render-pass 3)
    (.end render-pass)
    (-> device .-queue (.submit #js [(.finish command-encoder)]))))

(def canvas nil)
(def device nil)
(def context nil)
(def pipeline nil)
(def buffers nil)
(def bind-group nil)
(def animation-id nil)
(def canvas-format nil)
(def render-error-shown? false)

(defn animate [time]
  (when device
    (try
      (render device context pipeline bind-group (:uniform buffers) (/ time 1000))
      (catch :default err
        (when-not render-error-shown?
          (set! render-error-shown? true)
          (set-status! (str "Render failed: " err) "#ff6b6b"))))
    (set! animation-id (js/requestAnimationFrame animate))))

(defn ^:export init []
  (set! canvas (js/document.getElementById "canvas"))

  (-> js/navigator .-gpu (.requestAdapter nil)
      (.then (fn [adapter]
               (.requestDevice adapter nil)))
      (.then (fn [dev]
               (let [cache-bust (.now js/Date)]
                 (-> (js/Promise.all #js [(load-shader (str "vertex.wgsl?v=" cache-bust))
                                          (load-shader (str "fragment.wgsl?v=" cache-bust))])
                     (.then (fn [[vertex-code fragment-code]]
                              (set! device dev)
                              (set! (.-onuncapturederror device)
                                    (fn [event]
                                      (set-status! (str "WebGPU error: "
                                                        (.-message (.-error event)))
                                                   "#ff6b6b")))
                              (set! context (.getContext canvas "webgpu"))
                              (let [format (.getPreferredCanvasFormat (.-gpu js/navigator))]
                                (set! canvas-format format)
                                (configure-canvas! context device canvas-format)
                                (set! pipeline (create-pipeline device canvas-format vertex-code fragment-code))
                                (set! buffers (create-buffers device))
                                (set! bind-group (create-bind-group device pipeline (:uniform buffers)))
                                (set! render-error-shown? false)
                                (set-status! "Rendering with WebGPU" "#51cf66")
                                (js/requestAnimationFrame animate))))))))
      (.catch (fn [err]
                (set-status! (str "WebGPU init failed: " err) "#ff6b6b")))))

(defn ^:export stop []
  (when animation-id
    (js/cancelAnimationFrame animation-id)))
