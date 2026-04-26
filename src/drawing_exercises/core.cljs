(ns drawing-exercises.core)

(defonce app-state
  (atom {:canvas nil
         :device nil
         :context nil
         :pipeline nil
         :buffers nil
         :bind-group nil
         :animation-id nil
         :canvas-format nil
         :render-error-shown? false}))

(defn load-shader [path]
  (-> (js/fetch path)
      (.then #(.text %))))

(defn set-status! [text color]
  (when-let [el (js/document.getElementById "status")]
    (set! (.-textContent el) text)
    (set! (.. el -style -color) color)))

(defn configure-canvas! [^js context device format]
  (.configure context #js
    {:device device
     :format format
     :usage (.-RENDER_ATTACHMENT js/GPUTextureUsage)
     :alphaMode "opaque"}))

(defn create-pipeline [^js device format vertex-code fragment-code]
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

(defn create-buffers [^js device]
  (let [uniform-buffer (.createBuffer device #js
                         {:size 16
                          :usage (bit-or (.-UNIFORM js/GPUBufferUsage)
                                         (.-COPY_DST js/GPUBufferUsage))
                          :mappedAtCreation false})]
    {:uniform uniform-buffer}))

(defn create-bind-group [^js device ^js pipeline uniform-buffer]
  (.createBindGroup device #js
    {:layout (.getBindGroupLayout pipeline 0)
     :entries #js [#js {:binding 0
                        :resource #js {:buffer uniform-buffer}}]}))

(defn resize-canvas [^js canvas device context canvas-format]
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

(defn render [canvas ^js device ^js context pipeline bind-group uniform-buffer canvas-format time]
  (let [_ (resize-canvas canvas device context canvas-format)
        time-data (js/Float32Array. #js [time 0 0 0])
        command-encoder (.createCommandEncoder device)
        texture (.getCurrentTexture context)
        texture-view (.createView texture)
        render-pass (.beginRenderPass command-encoder #js
                       {:colorAttachments #js [#js
                                               {:view texture-view
                                                :clearValue #js {:r 0.1 :g 0.1 :b 0.1 :a 1.0}
                                                :loadOp "clear"
                                                :storeOp "store"}]})
        queue (.-queue device)]
    (.writeBuffer queue uniform-buffer 0 time-data)
    (.setPipeline render-pass pipeline)
    (.setBindGroup render-pass 0 bind-group)
    (.draw render-pass 3)
    (.end render-pass)
    (.submit queue #js [(.finish command-encoder)])))

(defn animate [time]
  (let [{:keys [canvas device context pipeline bind-group buffers canvas-format render-error-shown?]} @app-state]
    (when device
      (try
        (render canvas device context pipeline bind-group (:uniform buffers) canvas-format (/ time 1000))
        (catch :default err
          (when-not render-error-shown?
            (swap! app-state assoc :render-error-shown? true)
            (set-status! (str "Render failed: " err) "#ff6b6b"))))
      (swap! app-state assoc :animation-id (js/requestAnimationFrame animate)))))

(defn ^:export init []
  (if (:animation-id @app-state)
    (js/Promise.resolve :already-running)
    (do
      (swap! app-state assoc :canvas (js/document.getElementById "canvas"))

      (-> js/navigator .-gpu (.requestAdapter nil)
          (.then (fn [^js adapter]
                   (.requestDevice adapter nil)))
          (.then (fn [^js dev]
                   (let [cache-bust (.now js/Date)]
                     (-> (js/Promise.all #js [(load-shader (str "vertex.wgsl?v=" cache-bust))
                                              (load-shader (str "fragment.wgsl?v=" cache-bust))])
                         (.then (fn [[vertex-code fragment-code]]
                                  (swap! app-state assoc :device dev)
                                  (set! (.-onuncapturederror dev)
                                        (fn [event]
                                          (set-status! (str "WebGPU error: "
                                                            (.-message (.-error event)))
                                                       "#ff6b6b")))
                                  (let [canvas (:canvas @app-state)
                                        context (.getContext canvas "webgpu")
                                        format (.getPreferredCanvasFormat (.-gpu js/navigator))
                                        pipeline (create-pipeline dev format vertex-code fragment-code)
                                        buffers (create-buffers dev)
                                        bind-group (create-bind-group dev pipeline (:uniform buffers))]
                                    (configure-canvas! context dev format)
                                    (swap! app-state assoc
                                           :context context
                                           :canvas-format format
                                           :pipeline pipeline
                                           :buffers buffers
                                           :bind-group bind-group
                                           :render-error-shown? false)
                                    (set-status! "Rendering with WebGPU" "#51cf66")
                                    (swap! app-state assoc :animation-id (js/requestAnimationFrame animate)))))))))
          (.catch (fn [err]
                    (set-status! (str "WebGPU init failed: " err) "#ff6b6b")))))))

(defn ^:export stop []
  (when-let [animation-id (:animation-id @app-state)]
    (js/cancelAnimationFrame animation-id)
    (swap! app-state assoc :animation-id nil)))
