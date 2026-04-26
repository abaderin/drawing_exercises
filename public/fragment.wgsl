struct Uniforms {
  view_projection : mat4x4<f32>,
  model : mat4x4<f32>,
  time : vec4<f32>,
  light_position : vec4<f32>,
  light_view_projection : mat4x4<f32>,
}

@group(0) @binding(0) var<uniform> uniforms : Uniforms;
@group(0) @binding(1) var shadow_map : texture_depth_2d;
@group(0) @binding(2) var shadow_sampler : sampler_comparison;

@fragment
fn fs_main(
  @location(0) color : vec4<f32>,
  @location(1) normal : vec3<f32>,
  @location(2) world_position : vec3<f32>
) -> @location(0) vec4<f32> {
  let light_direction = normalize(uniforms.light_position.xyz - world_position);
  let light_intensity = uniforms.light_position.w;
  let ambient = 0.25;
  let diffuse = max(dot(normalize(normal), light_direction), 0.0);
  let lit_color = color.rgb * (ambient + diffuse * light_intensity);
  return vec4<f32>(lit_color, color.a);
}
