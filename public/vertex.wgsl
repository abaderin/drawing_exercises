struct VertexOutput {
  @builtin(position) position : vec4<f32>,
  @location(0) color : vec4<f32>,
}

struct Uniforms {
  view_projection : mat4x4<f32>,
  time : vec4<f32>,
}

@group(0) @binding(0) var<uniform> uniforms : Uniforms;

@vertex
fn vs_main(
  @location(0) position : vec3<f32>,
  @location(1) color : vec4<f32>
) -> VertexOutput {
  let angle = uniforms.time.x;
  let c = cos(angle);
  let s = sin(angle);
  let rotated = vec2<f32>(position.x * c - position.y * s, position.x * s + position.y * c);

  var output : VertexOutput;
  let world_position = vec4<f32>(rotated, position.z, 1.0);
  output.position = uniforms.view_projection * world_position;
  output.color = color;
  return output;
}
