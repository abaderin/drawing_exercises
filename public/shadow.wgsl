struct ObjectUniforms {
  view_projection : mat4x4<f32>,
  model : mat4x4<f32>,
  time : vec4<f32>,
  light_position : vec4<f32>,
}

struct ShadowUniforms {
  light_view_projection : mat4x4<f32>,
}

@group(0) @binding(0) var<uniform> object_uniforms : ObjectUniforms;
@group(1) @binding(0) var<uniform> shadow_uniforms : ShadowUniforms;

@vertex
fn vs_main(
  @location(0) position : vec3<f32>,
  @location(1) _normal : vec3<f32>,
  @location(2) _color : vec4<f32>
) -> @builtin(position) vec4<f32> {
  let world_position = object_uniforms.model * vec4<f32>(position, 1.0);
  return shadow_uniforms.light_view_projection * world_position;
}
