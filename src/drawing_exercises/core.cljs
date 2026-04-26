(ns drawing-exercises.core)

(defonce app-state
  (atom {:canvas nil
         :device nil
         :context nil
         :pipeline nil
         :buffers nil
         :mesh nil
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
        :entryPoint "vs_main"
        :buffers #js [#js {:arrayStride 28
                           :attributes #js [#js {:shaderLocation 0
                                                  :offset 0
                                                  :format "float32x3"}
                                             #js {:shaderLocation 1
                                                  :offset 12
                                                  :format "float32x4"}]}]}
       :fragment #js
       {:module shader-module-frag
        :entryPoint "fs_main"
        :targets #js [#js {:format format}]}
       :primitive #js
       {:topology "triangle-list"}})))

(defn create-triangle-mesh [^js device]
  (let [vertex-data (js/Float32Array. #js [0.0 0.8 0.0 1.0 0.2 0.2 1.0
                                           -0.8 -0.8 0.0 0.2 1.0 0.2 1.0
                                           0.8 -0.8 0.0 0.2 0.2 1.0 1.0])
        index-data (js/Uint32Array. #js [0 1 2])
        vertex-buffer (.createBuffer device #js {:size (.-byteLength vertex-data)
                                                 :usage (bit-or (.-VERTEX js/GPUBufferUsage)
                                                                (.-COPY_DST js/GPUBufferUsage))})
        index-buffer (.createBuffer device #js {:size (.-byteLength index-data)
                                                :usage (bit-or (.-INDEX js/GPUBufferUsage)
                                                               (.-COPY_DST js/GPUBufferUsage))})
        queue (.-queue device)]
    (.writeBuffer queue vertex-buffer 0 vertex-data)
    (.writeBuffer queue index-buffer 0 index-data)
    {:vertex-buffer vertex-buffer
     :index-buffer index-buffer
     :index-count 3}))

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

(defn render [canvas ^js device ^js context pipeline bind-group mesh uniform-buffer canvas-format time]
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
    (.setVertexBuffer render-pass 0 (:vertex-buffer mesh))
    (.setIndexBuffer render-pass (:index-buffer mesh) "uint32")
    (.drawIndexed render-pass (:index-count mesh))
    (.end render-pass)
    (.submit queue #js [(.finish command-encoder)])))

(defn animate [time]
  (let [{:keys [canvas device context pipeline bind-group mesh buffers canvas-format render-error-shown?]} @app-state]
    (when device
      (try
        (render canvas device context pipeline bind-group mesh (:uniform buffers) canvas-format (/ time 1000))
        (catch :default err
          (when-not render-error-shown?
            (swap! app-state assoc :render-error-shown? true)
            (set-status! (str "Render failed: " err) "#ff6b6b"))))
      (swap! app-state assoc :animation-id (js/requestAnimationFrame animate)))))

(defn request-device []
  (-> js/navigator
      .-gpu
      (.requestAdapter nil)
      (.then (fn [^js adapter]
               (.requestDevice adapter nil)))))

(defn load-shaders []
  (let [cache-bust (.now js/Date)]
    (js/Promise.all #js [(load-shader (str "vertex.wgsl?v=" cache-bust))
                         (load-shader (str "fragment.wgsl?v=" cache-bust))])))

(defn setup-device-error-handler! [^js device]
  (set! (.-onuncapturederror device)
        (fn [event]
          (set-status! (str "WebGPU error: "
                            (.-message (.-error event)))
                       "#ff6b6b"))))

(defn start-animation! []
  (swap! app-state assoc :animation-id (js/requestAnimationFrame animate)))

(defn create-render-resources [^js canvas ^js device vertex-code fragment-code]
  (let [context (.getContext canvas "webgpu")
        format (.getPreferredCanvasFormat (.-gpu js/navigator))
        pipeline (create-pipeline device format vertex-code fragment-code)
        buffers (create-buffers device)
        mesh (create-triangle-mesh device)
        bind-group (create-bind-group device pipeline (:uniform buffers))]
    {:context context
     :canvas-format format
     :pipeline pipeline
     :buffers buffers
     :mesh mesh
     :bind-group bind-group}))

(defn initialize-rendering! [^js device vertex-code fragment-code]
  (swap! app-state assoc :device device)
  (setup-device-error-handler! device)
  (let [canvas (:canvas @app-state)
        {:keys [context canvas-format pipeline buffers mesh bind-group]}
        (create-render-resources canvas device vertex-code fragment-code)]
    (configure-canvas! context device canvas-format)
    (swap! app-state assoc
           :context context
           :canvas-format canvas-format
           :pipeline pipeline
           :buffers buffers
           :mesh mesh
           :bind-group bind-group
           :render-error-shown? false)
    (set-status! "Rendering with WebGPU" "#51cf66")
    (start-animation!)))

(defn initialize-rendering-for-device! [^js device]
  (-> (load-shaders)
      (.then (fn [[vertex-code fragment-code]]
               (initialize-rendering! device vertex-code fragment-code)))))

(defn ^:export init []
  (if (:animation-id @app-state)
    (js/Promise.resolve :already-running)
    (do
      (swap! app-state assoc :canvas (js/document.getElementById "canvas"))
      (-> (request-device)
          (.then initialize-rendering-for-device!)
          (.catch (fn [err]
                    (set-status! (str "WebGPU init failed: " err) "#ff6b6b")))))))

(defn ^:export stop []
  (when-let [animation-id (:animation-id @app-state)]
    (js/cancelAnimationFrame animation-id)
    (swap! app-state assoc :animation-id nil)))
