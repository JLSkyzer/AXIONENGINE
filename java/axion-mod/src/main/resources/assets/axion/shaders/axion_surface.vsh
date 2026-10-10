#version 330 core
// Sommets des surfaces d'AXION (C-63, ADR-127 §4-5) : format GPU du §19.4 (48 octets), bloc
// d'instance de 88 octets, diviseur 1 (InstanceLayout). Positions relatives à la caméra.

layout(location = 0) in vec3 a_position;
layout(location = 1) in vec4 a_normal;
layout(location = 2) in vec4 a_tangent;
layout(location = 3) in vec2 a_uv0;
layout(location = 5) in vec4 a_color;

layout(location = 9) in vec4 i_row0;
layout(location = 10) in vec4 i_row1;
layout(location = 11) in vec4 i_row2;
layout(location = 12) in vec4 i_tint;
layout(location = 13) in ivec2 i_lightmap;

uniform mat4 u_projection;
uniform mat4 u_view;
// Plage des UV du mesh : début, étendue (ADR-122 §4) ; puis tuile de la texture : décalage u, v,
// échelle u, v (TextureRegion).
uniform vec2 u_uv_range;
uniform vec4 u_uv_region;
// 1 si le matériau porte VERTEX_COLOR : la couleur des sommets module l'albedo.
uniform int u_vertex_color;

// Les trois programmes partagent ces sommets : l'émission retombe exactement sur la profondeur que
// la surface a écrite.
invariant gl_Position;

out vec3 v_position;
out vec3 v_normal;
// Tangente du repère des cartes de normales, et son sens (w), en axes du monde.
out vec4 v_tangent;
out vec2 v_uv;
// Coordonnées dans la plage du mesh, avant toute tuile : chaque carte y applique la sienne.
out vec2 v_uv_raw;
out vec4 v_color;
out vec2 v_lightmap;
out float v_sky;

void main() {
    mat4 model = transpose(mat4(i_row0, i_row1, i_row2, vec4(0.0, 0.0, 0.0, 1.0)));
    vec4 relative = model * vec4(a_position, 1.0);
    v_position = relative.xyz;
    gl_Position = u_projection * u_view * relative;
    // Inverse-transposée : une normale juste sous une échelle non uniforme.
    v_normal = transpose(inverse(mat3(model))) * a_normal.xyz;
    v_tangent = vec4(mat3(model) * a_tangent.xyz, a_tangent.w);
    vec2 uv = u_uv_range.x + a_uv0 * u_uv_range.y;
    v_uv = u_uv_region.xy + uv * u_uv_region.zw;
    v_uv_raw = uv;
    v_color = (u_vertex_color == 1 ? a_color : vec4(1.0)) * i_tint;
    // Comme Minecraft : niveaux × 16, lus au centre des texels de la lightmap.
    v_lightmap = clamp(vec2(i_lightmap) / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    v_sky = clamp(float(i_lightmap.y) / 240.0, 0.0, 1.0);
}
