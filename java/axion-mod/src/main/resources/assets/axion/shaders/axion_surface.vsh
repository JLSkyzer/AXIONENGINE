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

#ifdef SKINNED
// Skinning (C-66, ADR-155) : quatre os par sommet, leurs poids en UNORM8 de somme 1 (R-140). La palette
// porte un mat4x3 par os, ses colonnes x, y, z puis sa translation, soit trois vec4 ; le premier vec4 de
// celle de l'instance est le premier mot de son bloc (palette_offset, R-780). Elle est dans un TBO
// (R-790), ou, en repli, dans la page d'un UBO que le dessin a liée (H-04).
layout(location = 6) in uvec4 a_bones;
layout(location = 7) in vec4 a_weights;
layout(location = 14) in uvec4 i_offsets;

uniform samplerBuffer u_palette;
uniform int u_palette_ubo;
layout(std140) uniform AxionPalette {
    vec4 u_palette_page[1024];
};

vec4 palette_texel(int index) {
    return u_palette_ubo == 1 ? u_palette_page[index] : texelFetch(u_palette, index);
}

mat4 bone(uint index) {
    int at = int(i_offsets.x) + int(index) * 3;
    vec4 a = palette_texel(at);
    vec4 b = palette_texel(at + 1);
    vec4 c = palette_texel(at + 2);
    return mat4(vec4(a.xyz, 0.0), vec4(a.w, b.xy, 0.0), vec4(b.zw, c.x, 0.0), vec4(c.yzw, 1.0));
}
#endif

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
    vec3 position = a_position;
    vec3 normal = a_normal.xyz;
    vec3 tangent = a_tangent.xyz;
#ifdef SKINNED
    // Le sommet d'abord porté par ses os, dans l'espace de l'asset ; puis l'instance le pose (R-792).
    mat4 skin = a_weights.x * bone(a_bones.x) + a_weights.y * bone(a_bones.y)
            + a_weights.z * bone(a_bones.z) + a_weights.w * bone(a_bones.w);
    position = (skin * vec4(a_position, 1.0)).xyz;
    normal = mat3(skin) * normal;
    tangent = mat3(skin) * tangent;
#endif
    vec4 relative = model * vec4(position, 1.0);
    v_position = relative.xyz;
    gl_Position = u_projection * u_view * relative;
    // Inverse-transposée : une normale juste sous une échelle non uniforme.
    v_normal = transpose(inverse(mat3(model))) * normal;
    v_tangent = vec4(mat3(model) * tangent, a_tangent.w);
    vec2 uv = u_uv_range.x + a_uv0 * u_uv_range.y;
    v_uv = u_uv_region.xy + uv * u_uv_region.zw;
    v_uv_raw = uv;
    v_color = (u_vertex_color == 1 ? a_color : vec4(1.0)) * i_tint;
    // Comme Minecraft : niveaux × 16, lus au centre des texels de la lightmap.
    v_lightmap = clamp(vec2(i_lightmap) / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    v_sky = clamp(float(i_lightmap.y) / 240.0, 0.0, 1.0);
}
