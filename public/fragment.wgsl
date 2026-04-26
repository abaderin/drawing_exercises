@fragment
fn fs_main(
  @location(0) color : vec4<f32>,
  @location(1) normal : vec3<f32>
) -> @location(0) vec4<f32> {
  let light_direction = normalize(vec3<f32>(0.4, 0.8, 0.6));
  let ambient = 0.25;
  let diffuse = max(dot(normalize(normal), light_direction), 0.0);
  let lit_color = color.rgb * (ambient + diffuse * 0.75);
  return vec4<f32>(lit_color, color.a);
}
