(ns drawing-exercises.core
  (:require ["wgpu-matrix" :refer [mat4]]))

(defonce app-state
  (atom {:canvas nil
         :device nil
         :context nil
         :pipeline nil
         :shadow-pipeline nil
         :objects []
         :depth-texture nil
         :depth-size nil
         :shadow-map-texture nil
         :light-view-projection-buffer nil
         :light-view-projection-bind-group nil
         :animation-id nil
         :canvas-format nil
         :light-position [0.5 1.6 0.8]
         :light-intensity 0.75
         :render-error-shown? false}))

(def depth-format "depth24plus")
(def shadow-map-format "depth32float")
(def shadow-map-size 1024)
(def uniform-buffer-byte-size 224)
(def light-view-projection-buffer-byte-size 64)

(defn load-shader [path]
  (-> (js/fetch path)
      (.then #(.text %))))

(defn set-status! [text color]
  (when-let [el (js/document.getElementById "status")]
    (set! (.-textContent el) text)
    (set! (.. el -style -color) color)))

(defn parse-slider-value [id fallback]
  (if-let [el (js/document.getElementById id)]
    (js/parseFloat (.-value el))
    fallback))

(defn read-light-controls []
  {:position [(parse-slider-value "light-x" 0.5)
              (parse-slider-value "light-y" 1.6)
              (parse-slider-value "light-z" 0.8)]
   :intensity (parse-slider-value "light-intensity" 0.75)})

(defn sync-light-from-controls! []
  (let [{:keys [position intensity]} (read-light-controls)]
    (swap! app-state assoc
           :light-position position
           :light-intensity intensity)))

(defn setup-light-controls! []
  (doseq [id ["light-x" "light-y" "light-z" "light-intensity"]]
    (when-let [el (js/document.getElementById id)]
      (.addEventListener el "input" sync-light-from-controls!)))
  (sync-light-from-controls!))

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
        :buffers #js [#js {:arrayStride 40
                           :attributes #js [#js {:shaderLocation 0
                                                  :offset 0
                                                  :format "float32x3"}
                                             #js {:shaderLocation 1
                                                  :offset 12
                                                  :format "float32x3"}
                                             #js {:shaderLocation 2
                                                  :offset 24
                                                  :format "float32x4"}]}]}
       :fragment #js
       {:module shader-module-frag
        :entryPoint "fs_main"
        :targets #js [#js {:format format}]}
       :primitive #js
       {:topology "triangle-list"}
       :depthStencil #js
       {:format depth-format
        :depthWriteEnabled true
        :depthCompare "less"}})))

(defn create-shadow-pipeline [^js device shadow-code ^js object-bind-group-layout ^js light-bind-group-layout]
  (let [shader-module (.createShaderModule device #js {:code shadow-code})
        pipeline-layout (.createPipelineLayout device #js
                          {:bindGroupLayouts #js [object-bind-group-layout
                                                  light-bind-group-layout]})]
    (.createRenderPipeline device #js
      {:layout pipeline-layout
       :vertex #js
       {:module shader-module
        :entryPoint "vs_main"
        :buffers #js [#js {:arrayStride 40
                           :attributes #js [#js {:shaderLocation 0
                                                  :offset 0
                                                  :format "float32x3"}
                                             #js {:shaderLocation 1
                                                  :offset 12
                                                  :format "float32x3"}
                                             #js {:shaderLocation 2
                                                  :offset 24
                                                  :format "float32x4"}]}]}
       :primitive #js
       {:topology "triangle-list"}
       :depthStencil #js
       {:format shadow-map-format
        :depthWriteEnabled true
        :depthCompare "less"}})))

(defn create-mesh [^js device vertices indices]
  (let [vertex-data (js/Float32Array. vertices)
        index-data (js/Uint32Array. indices)
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
     :index-count (.-length index-data)}))

(defn create-floor-mesh [^js device]
  (create-mesh device
               #js [-1.5 0.0 -1.5 0.0 1.0 0.0 0.65 0.65 0.65 1.0
                    1.5 0.0 -1.5 0.0 1.0 0.0 0.65 0.65 0.65 1.0
                    1.5 0.0 1.5 0.0 1.0 0.0 0.65 0.65 0.65 1.0
                    -1.5 0.0 1.5 0.0 1.0 0.0 0.65 0.65 0.65 1.0]
               #js [0 1 2
                    0 2 3]))

(defn create-back-wall-mesh [^js device]
  (create-mesh device
               #js [-1.5 0.0 -1.5 0.0 0.0 1.0 0.78 0.78 0.76 1.0
                    1.5 0.0 -1.5 0.0 0.0 1.0 0.78 0.78 0.76 1.0
                    1.5 2.0 -1.5 0.0 0.0 1.0 0.78 0.78 0.76 1.0
                    -1.5 2.0 -1.5 0.0 0.0 1.0 0.78 0.78 0.76 1.0]
               #js [0 1 2
                    0 2 3]))

(defn create-left-wall-mesh [^js device]
  (create-mesh device
               #js [-1.5 0.0 1.5 1.0 0.0 0.0 0.72 0.72 0.70 1.0
                    -1.5 0.0 -1.5 1.0 0.0 0.0 0.72 0.72 0.70 1.0
                    -1.5 2.0 -1.5 1.0 0.0 0.0 0.72 0.72 0.70 1.0
                    -1.5 2.0 1.5 1.0 0.0 0.0 0.72 0.72 0.70 1.0]
               #js [0 1 2
                    0 2 3]))

(defn create-cube-mesh [^js device]
  (create-mesh device
               #js [-0.25 -0.25 0.25 0.0 0.0 1.0 0.86 0.58 0.36 1.0
                    0.25 -0.25 0.25 0.0 0.0 1.0 0.86 0.58 0.36 1.0
                    0.25 0.25 0.25 0.0 0.0 1.0 0.86 0.58 0.36 1.0
                    -0.25 0.25 0.25 0.0 0.0 1.0 0.86 0.58 0.36 1.0

                    0.25 -0.25 -0.25 0.0 0.0 -1.0 0.74 0.48 0.30 1.0
                    -0.25 -0.25 -0.25 0.0 0.0 -1.0 0.74 0.48 0.30 1.0
                    -0.25 0.25 -0.25 0.0 0.0 -1.0 0.74 0.48 0.30 1.0
                    0.25 0.25 -0.25 0.0 0.0 -1.0 0.74 0.48 0.30 1.0

                    0.25 -0.25 0.25 1.0 0.0 0.0 0.80 0.52 0.32 1.0
                    0.25 -0.25 -0.25 1.0 0.0 0.0 0.80 0.52 0.32 1.0
                    0.25 0.25 -0.25 1.0 0.0 0.0 0.80 0.52 0.32 1.0
                    0.25 0.25 0.25 1.0 0.0 0.0 0.80 0.52 0.32 1.0

                    -0.25 -0.25 -0.25 -1.0 0.0 0.0 0.68 0.42 0.26 1.0
                    -0.25 -0.25 0.25 -1.0 0.0 0.0 0.68 0.42 0.26 1.0
                    -0.25 0.25 0.25 -1.0 0.0 0.0 0.68 0.42 0.26 1.0
                    -0.25 0.25 -0.25 -1.0 0.0 0.0 0.68 0.42 0.26 1.0

                    -0.25 0.25 0.25 0.0 1.0 0.0 0.92 0.66 0.42 1.0
                    0.25 0.25 0.25 0.0 1.0 0.0 0.92 0.66 0.42 1.0
                    0.25 0.25 -0.25 0.0 1.0 0.0 0.92 0.66 0.42 1.0
                    -0.25 0.25 -0.25 0.0 1.0 0.0 0.92 0.66 0.42 1.0

                    -0.25 -0.25 -0.25 0.0 -1.0 0.0 0.58 0.34 0.22 1.0
                    0.25 -0.25 -0.25 0.0 -1.0 0.0 0.58 0.34 0.22 1.0
                    0.25 -0.25 0.25 0.0 -1.0 0.0 0.58 0.34 0.22 1.0
                    -0.25 -0.25 0.25 0.0 -1.0 0.0 0.58 0.34 0.22 1.0]
               #js [0 1 2 0 2 3
                    4 5 6 4 6 7
                    8 9 10 8 10 11
                    12 13 14 12 14 15
                    16 17 18 16 18 19
                    20 21 22 20 22 23]))

(defn create-light-marker-mesh [^js device]
  (create-mesh device
               #js [-0.025 -0.025 0.025 0.0 0.0 1.0 1.0 0.92 0.1 1.0
                    0.025 -0.025 0.025 0.0 0.0 1.0 1.0 0.92 0.1 1.0
                    0.025 0.025 0.025 0.0 0.0 1.0 1.0 0.92 0.1 1.0
                    -0.025 0.025 0.025 0.0 0.0 1.0 1.0 0.92 0.1 1.0

                    0.025 -0.025 -0.025 0.0 0.0 -1.0 1.0 0.92 0.1 1.0
                    -0.025 -0.025 -0.025 0.0 0.0 -1.0 1.0 0.92 0.1 1.0
                    -0.025 0.025 -0.025 0.0 0.0 -1.0 1.0 0.92 0.1 1.0
                    0.025 0.025 -0.025 0.0 0.0 -1.0 1.0 0.92 0.1 1.0

                    0.025 -0.025 0.025 1.0 0.0 0.0 1.0 0.92 0.1 1.0
                    0.025 -0.025 -0.025 1.0 0.0 0.0 1.0 0.92 0.1 1.0
                    0.025 0.025 -0.025 1.0 0.0 0.0 1.0 0.92 0.1 1.0
                    0.025 0.025 0.025 1.0 0.0 0.0 1.0 0.92 0.1 1.0

                    -0.025 -0.025 -0.025 -1.0 0.0 0.0 1.0 0.92 0.1 1.0
                    -0.025 -0.025 0.025 -1.0 0.0 0.0 1.0 0.92 0.1 1.0
                    -0.025 0.025 0.025 -1.0 0.0 0.0 1.0 0.92 0.1 1.0
                    -0.025 0.025 -0.025 -1.0 0.0 0.0 1.0 0.92 0.1 1.0

                    -0.025 0.025 0.025 0.0 1.0 0.0 1.0 0.92 0.1 1.0
                    0.025 0.025 0.025 0.0 1.0 0.0 1.0 0.92 0.1 1.0
                    0.025 0.025 -0.025 0.0 1.0 0.0 1.0 0.92 0.1 1.0
                    -0.025 0.025 -0.025 0.0 1.0 0.0 1.0 0.92 0.1 1.0

                    -0.025 -0.025 -0.025 0.0 -1.0 0.0 1.0 0.92 0.1 1.0
                    0.025 -0.025 -0.025 0.0 -1.0 0.0 1.0 0.92 0.1 1.0
                    0.025 -0.025 0.025 0.0 -1.0 0.0 1.0 0.92 0.1 1.0
                    -0.025 -0.025 0.025 0.0 -1.0 0.0 1.0 0.92 0.1 1.0]
               #js [0 1 2 0 2 3
                    4 5 6 4 6 7
                    8 9 10 8 10 11
                    12 13 14 12 14 15
                    16 17 18 16 18 19
                    20 21 22 20 22 23]))

(defn create-uniform-buffer [^js device]
  (.createBuffer device #js
    {:size uniform-buffer-byte-size
     :usage (bit-or (.-UNIFORM js/GPUBufferUsage)
                    (.-COPY_DST js/GPUBufferUsage))
     :mappedAtCreation false}))

(defn create-bind-group [^js device ^js pipeline uniform-buffer ^js shadow-map-view ^js shadow-sampler]
  (.createBindGroup device #js
    {:layout (.getBindGroupLayout pipeline 0)
     :entries #js [#js {:binding 0
                        :resource #js {:buffer uniform-buffer}}
                   #js {:binding 1
                        :resource shadow-map-view}
                   #js {:binding 2
                        :resource shadow-sampler}]}))

(defn create-depth-texture [^js device width height]
  (.createTexture device #js
    {:size #js [width height]
     :format depth-format
     :usage (.-RENDER_ATTACHMENT js/GPUTextureUsage)}))

(defn create-shadow-map-texture [^js device]
  (.createTexture device #js
    {:size #js [shadow-map-size shadow-map-size]
     :format shadow-map-format
     :usage (bit-or (.-TEXTURE_BINDING js/GPUTextureUsage)
                    (.-RENDER_ATTACHMENT js/GPUTextureUsage))}))

(defn ^js ensure-depth-texture! [device width height]
  (let [depth-size [width height]
        {:keys [depth-texture]} @app-state]
    (if (= depth-size (:depth-size @app-state))
      depth-texture
      (let [depth-texture (create-depth-texture device width height)]
        (swap! app-state assoc
               :depth-texture depth-texture
               :depth-size depth-size)
        depth-texture))))

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

(defn create-view-projection-matrix [width height]
  (let [aspect (/ width height)
        projection (.perspective mat4 (/ (.-PI js/Math) 4) aspect 0.1 100.0)
        view (.lookAt mat4 #js [0.0 2.0 4.0] #js [0.0 0.0 0.0] #js [0.0 1.0 0.0])]
    (.multiply mat4 projection view)))

(defn create-light-view-projection-matrix [light-position]
  (let [[x y z] light-position
        projection (.ortho mat4 -2.0 2.0 -2.0 2.0 0.1 10.0)
        view (.lookAt mat4 #js [x y z] #js [0.0 0.0 0.0] #js [0.0 1.0 0.0])]
    (.multiply mat4 projection view)))

(defn create-model-matrix [time]
  (.rotationZ mat4 time))

(defn create-translation-matrix [x y z]
  (.translation mat4 #js [x y z]))

(defn create-frame-uniforms [width height model time light-position light-intensity light-view-projection]
  (let [view-projection (create-view-projection-matrix width height)
        [light-x light-y light-z] light-position
        uniforms (js/Float32Array. 56)]
    (.set uniforms view-projection 0)
    (.set uniforms model 16)
    (aset uniforms 32 time)
    (aset uniforms 36 light-x)
    (aset uniforms 37 light-y)
    (aset uniforms 38 light-z)
    (aset uniforms 39 light-intensity)
    (.set uniforms light-view-projection 40)
    uniforms))

(defn marker-model-matrix [light-position]
  (let [[x y z] light-position]
    (create-translation-matrix x y z)))

(defn write-object-uniforms! [^js queue width height object time light-position light-intensity light-view-projection]
  (let [{:keys [model uniform-buffer follows-light?]} object
        effective-model (if follows-light?
                          (marker-model-matrix light-position)
                          model)
        frame-uniforms (create-frame-uniforms width height effective-model time light-position light-intensity light-view-projection)]
    (.writeBuffer queue uniform-buffer 0 frame-uniforms)))

(defn draw-object! [^js render-pass ^js queue width height object time light-position light-intensity light-view-projection]
  (let [{:keys [mesh bind-group]} object]
    (write-object-uniforms! queue width height object time light-position light-intensity light-view-projection)
    (.setBindGroup render-pass 0 bind-group)
    (.setVertexBuffer render-pass 0 (:vertex-buffer mesh))
    (.setIndexBuffer render-pass (:index-buffer mesh) "uint32")
    (.drawIndexed render-pass (:index-count mesh))))

(defn render [canvas
              ^js device
              ^js context
              pipeline
              shadow-pipeline
              objects
              canvas-format
              ^js shadow-map-texture
              ^js light-view-projection-buffer
              ^js light-view-projection-bind-group
              time
              light-position
              light-intensity]
  (let [{:keys [width height]} (resize-canvas canvas device context canvas-format)
        command-encoder (.createCommandEncoder device)
        texture (.getCurrentTexture context)
        texture-view (.createView texture)
        depth-texture (ensure-depth-texture! device width height)
        depth-view (.createView depth-texture)
        shadow-view (.createView shadow-map-texture)
        light-view-projection (create-light-view-projection-matrix light-position)
        queue (.-queue device)
        _ (.writeBuffer queue light-view-projection-buffer 0 light-view-projection)
        shadow-pass (.beginRenderPass command-encoder #js
                      {:colorAttachments #js []
                       :depthStencilAttachment #js
                       {:view shadow-view
                        :depthClearValue 1.0
                        :depthLoadOp "clear"
                        :depthStoreOp "store"}})]
    (.setPipeline shadow-pass shadow-pipeline)
    (.setBindGroup shadow-pass 1 light-view-projection-bind-group)
    (doseq [object objects]
      (write-object-uniforms! queue width height object time light-position light-intensity light-view-projection)
      (.setBindGroup shadow-pass 0 (:bind-group object))
      (.setVertexBuffer shadow-pass 0 (:vertex-buffer (:mesh object)))
      (.setIndexBuffer shadow-pass (:index-buffer (:mesh object)) "uint32")
      (.drawIndexed shadow-pass (:index-count (:mesh object))))
    (.end shadow-pass)
    (let [render-pass (.beginRenderPass command-encoder #js
                        {:colorAttachments #js [#js
                                                {:view texture-view
                                                 :clearValue #js {:r 0.1 :g 0.1 :b 0.1 :a 1.0}
                                                 :loadOp "clear"
                                                 :storeOp "store"}]
                         :depthStencilAttachment #js
                         {:view depth-view
                          :depthClearValue 1.0
                          :depthLoadOp "clear"
                          :depthStoreOp "store"}})]
      (.setPipeline render-pass pipeline)
      (doseq [object objects]
        (draw-object! render-pass queue width height object time light-position light-intensity light-view-projection))
      (.end render-pass))
    (.submit queue #js [(.finish command-encoder)])))

(defn animate [time]
  (let [{:keys [canvas
                device
                context
                pipeline
                shadow-pipeline
                objects
                canvas-format
                shadow-map-texture
                light-view-projection-buffer
                light-view-projection-bind-group
                light-position
                light-intensity
                render-error-shown?]} @app-state]
    (when device
      (try
        (render canvas
                device
                context
                pipeline
                shadow-pipeline
                objects
                canvas-format
                shadow-map-texture
                light-view-projection-buffer
                light-view-projection-bind-group
                (/ time 1000)
                light-position
                light-intensity)
        (catch :default err
          (when-not render-error-shown?
            (swap! app-state assoc :render-error-shown? true)
            (set-status! (str "Render failed: " err) "#ff6b6b"))))
      (swap! app-state assoc :animation-id (js/requestAnimationFrame animate)))))

(defn create-scene-object
  ([^js device ^js pipeline mesh model ^js shadow-map-view ^js shadow-sampler]
   (create-scene-object device pipeline mesh model shadow-map-view shadow-sampler nil))
  ([^js device ^js pipeline mesh model ^js shadow-map-view ^js shadow-sampler opts]
   (let [uniform-buffer (create-uniform-buffer device)
         bind-group (create-bind-group device pipeline uniform-buffer shadow-map-view shadow-sampler)]
     (merge {:mesh mesh
             :model model
             :uniform-buffer uniform-buffer
             :bind-group bind-group}
            opts))))

(defn request-device []
  (-> js/navigator
      .-gpu
      (.requestAdapter nil)
      (.then (fn [^js adapter]
               (.requestDevice adapter nil)))))

(defn load-shaders []
  (let [cache-bust (.now js/Date)]
    (js/Promise.all #js [(load-shader (str "vertex.wgsl?v=" cache-bust))
                         (load-shader (str "fragment.wgsl?v=" cache-bust))
                         (load-shader (str "shadow.wgsl?v=" cache-bust))])))

(defn setup-device-error-handler! [^js device]
  (set! (.-onuncapturederror device)
        (fn [event]
          (set-status! (str "WebGPU error: "
                            (.-message (.-error event)))
                       "#ff6b6b"))))

(defn start-animation! []
  (swap! app-state assoc :animation-id (js/requestAnimationFrame animate)))

(defn create-render-resources [^js canvas ^js device vertex-code fragment-code shadow-code]
  (let [context (.getContext canvas "webgpu")
        format (.getPreferredCanvasFormat (.-gpu js/navigator))
        pipeline (create-pipeline device format vertex-code fragment-code)
        object-bind-group-layout (.getBindGroupLayout pipeline 0)
        light-bind-group-layout (.createBindGroupLayout device #js
                                  {:entries #js [#js
                                                  {:binding 0
                                                   :visibility (.-VERTEX js/GPUShaderStage)
                                                   :buffer #js {:type "uniform"}}]})
        shadow-pipeline (create-shadow-pipeline device shadow-code object-bind-group-layout light-bind-group-layout)
        shadow-map-texture (create-shadow-map-texture device)
        shadow-map-view (.createView shadow-map-texture)
        shadow-sampler (.createSampler device #js
                        {:compare "less"
                         :magFilter "linear"
                         :minFilter "linear"})
        light-view-projection-buffer (.createBuffer device #js
                                       {:size light-view-projection-buffer-byte-size
                                        :usage (bit-or (.-UNIFORM js/GPUBufferUsage)
                                                       (.-COPY_DST js/GPUBufferUsage))
                                        :mappedAtCreation false})
        light-view-projection-bind-group (.createBindGroup device #js
                                           {:layout light-bind-group-layout
                                            :entries #js [#js
                                                          {:binding 0
                                                           :resource #js {:buffer light-view-projection-buffer}}]})
        floor-mesh (create-floor-mesh device)
        back-wall-mesh (create-back-wall-mesh device)
        left-wall-mesh (create-left-wall-mesh device)
        cube-mesh (create-cube-mesh device)
        light-marker-mesh (create-light-marker-mesh device)
        identity (.identity mat4)
        cube-model (create-translation-matrix -0.75 0.25 -0.75)
        light-marker-model (marker-model-matrix (:light-position @app-state))
        objects [(create-scene-object device pipeline floor-mesh identity shadow-map-view shadow-sampler)
                 (create-scene-object device pipeline back-wall-mesh identity shadow-map-view shadow-sampler)
                 (create-scene-object device pipeline left-wall-mesh identity shadow-map-view shadow-sampler)
                 (create-scene-object device pipeline cube-mesh cube-model shadow-map-view shadow-sampler)
                 (create-scene-object device pipeline light-marker-mesh light-marker-model shadow-map-view shadow-sampler {:follows-light? true})]]
    {:context context
     :canvas-format format
     :pipeline pipeline
     :shadow-pipeline shadow-pipeline
     :objects objects
     :shadow-map-texture shadow-map-texture
     :light-view-projection-buffer light-view-projection-buffer
     :light-view-projection-bind-group light-view-projection-bind-group}))

(defn initialize-rendering! [^js device vertex-code fragment-code shadow-code]
  (swap! app-state assoc :device device)
  (setup-device-error-handler! device)
  (let [canvas (:canvas @app-state)
        {:keys [context
                canvas-format
                pipeline
                shadow-pipeline
                objects
                shadow-map-texture
                light-view-projection-buffer
                light-view-projection-bind-group]}
        (create-render-resources canvas device vertex-code fragment-code shadow-code)]
    (configure-canvas! context device canvas-format)
    (swap! app-state assoc
           :context context
           :canvas-format canvas-format
           :pipeline pipeline
           :shadow-pipeline shadow-pipeline
           :objects objects
           :shadow-map-texture shadow-map-texture
           :light-view-projection-buffer light-view-projection-buffer
           :light-view-projection-bind-group light-view-projection-bind-group
           :render-error-shown? false)
    (set-status! "Rendering with WebGPU" "#51cf66")
    (start-animation!)))

(defn initialize-rendering-for-device! [^js device]
  (-> (load-shaders)
      (.then (fn [[vertex-code fragment-code shadow-code]]
               (initialize-rendering! device vertex-code fragment-code shadow-code)))))

(defn ^:export init []
  (if (:animation-id @app-state)
    (js/Promise.resolve :already-running)
    (do
      (swap! app-state assoc :canvas (js/document.getElementById "canvas"))
      (setup-light-controls!)
      (-> (request-device)
          (.then initialize-rendering-for-device!)
          (.catch (fn [err]
                    (set-status! (str "WebGPU init failed: " err) "#ff6b6b")))))))

(defn ^:export stop []
  (when-let [animation-id (:animation-id @app-state)]
    (js/cancelAnimationFrame animation-id)
    (swap! app-state assoc :animation-id nil)))
