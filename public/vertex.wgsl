struct VertexOutput {
  @builtin(position) position : vec4<f32>,
  @location(0) color : vec4<f32>,
}

struct Uniforms {
  time : vec4<f32>,
}

@group(0) @binding(0) var<uniform> uniforms : Uniforms;

@vertex
fn vs_main(@builtin(vertex_index) vertex_index : u32) -> VertexOutput {
  let positions = array<vec2<f32>, 3>(
    vec2<f32>(0.0, 0.8),
    vec2<f32>(-0.8, -0.8),
    vec2<f32>(0.8, -0.8)
  );
  let colors = array<vec4<f32>, 3>(
    vec4<f32>(1.0, 0.2, 0.2, 1.0),
    vec4<f32>(0.2, 1.0, 0.2, 1.0),
    vec4<f32>(0.2, 0.2, 1.0, 1.0)
  );

  let angle = uniforms.time.x;
  let c = cos(angle);
  let s = sin(angle);
  let p = positions[vertex_index];
  let rotated = vec2<f32>(p.x * c - p.y * s, p.x * s + p.y * c);

  var output : VertexOutput;
  output.position = vec4<f32>(rotated, 0.0, 1.0);
  output.color = colors[vertex_index];
  return output;
}
