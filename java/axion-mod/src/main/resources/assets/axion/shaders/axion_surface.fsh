#version 330 core
// Surfaces d'AXION (C-63, ADR-127 §5) : la BRDF du §19.5 — GGX, Smith à hauteurs corrélées,
// Fresnel de Schlick — sous le soleil ou la lune ; une ambiante dont la lightmap de Minecraft porte
// l'intensité et la teinte (R-1511), et un hémisphère ciel-sol la répartition, en attendant la sonde
// de C-81. Spéculaire analytique seulement, comme au niveau Q-1 de R-1512. Calcul linéaire, sortie
// sRGB, brouillard de Minecraft. La variante CUTOUT rejette les texels sous le seuil du matériau
// (R-762).

in vec3 v_position;
in vec3 v_normal;
in vec4 v_tangent;
in vec2 v_uv;
in vec2 v_uv_raw;
in vec4 v_color;
in vec2 v_lightmap;
in float v_sky;

uniform sampler2D u_albedo;
uniform sampler2D u_lightmap;
// Cartes de normales et ORM du natif (ADR-127 §5) : chacune sa tuile, et un drapeau qui dit qu'elle est là.
uniform sampler2D u_normal_map;
uniform sampler2D u_orm;
uniform int u_normal_mapped;
uniform float u_normal_scale;
uniform vec4 u_normal_region;
uniform int u_orm_mapped;
uniform float u_occlusion_strength;
uniform vec4 u_orm_region;
uniform vec4 u_albedo_factor;
uniform float u_metallic;
uniform float u_roughness;
uniform float u_alpha_cutoff;
uniform int u_fullbright;
uniform vec3 u_light_direction;
uniform vec3 u_light_color;
uniform vec3 u_sky_color;
uniform vec3 u_ground_color;
uniform vec4 u_fog_color;
uniform float u_fog_start;
uniform float u_fog_end;
uniform int u_fog_shape;

out vec4 o_color;

const float PI = 3.14159265;

vec3 toLinear(vec3 c) {
    return pow(max(c, vec3(0.0)), vec3(2.2));
}

vec3 toGamma(vec3 c) {
    return pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));
}

float distributionGgx(float nDotH, float alpha) {
    float a2 = alpha * alpha;
    float d = nDotH * nDotH * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d);
}

// Visibilité de Smith à hauteurs corrélées : G / (4 N.L N.V).
float visibilitySmith(float nDotV, float nDotL, float alpha) {
    float a2 = alpha * alpha;
    float v = nDotL * sqrt(nDotV * nDotV * (1.0 - a2) + a2);
    float l = nDotV * sqrt(nDotL * nDotL * (1.0 - a2) + a2);
    return 0.5 / max(v + l, 1e-5);
}

vec3 fresnelSchlick(float cosTheta, vec3 f0) {
    return f0 + (1.0 - f0) * pow(1.0 - cosTheta, 5.0);
}

// Réponse spéculaire intégrée sur l'environnement, approchée analytiquement (Karis, 2014) : sans
// la table brdfLUT ni la sonde de C-81, un métal garde le reflet de l'hémisphère, jamais noir.
vec3 environmentBrdf(vec3 f0, float roughness, float nDotV) {
    const vec4 c0 = vec4(-1.0, -0.0275, -0.572, 0.022);
    const vec4 c1 = vec4(1.0, 0.0425, 1.04, -0.04);
    vec4 r = roughness * c0 + c1;
    float a004 = min(r.x * r.x, exp2(-9.28 * nDotV)) * r.x + r.y;
    vec2 ab = vec2(-1.04, 1.04) * a004 + r.zw;
    return f0 * ab.x + ab.y;
}

vec3 hemisphere(vec3 direction) {
    return mix(u_ground_color, u_sky_color, clamp(direction.y * 0.5 + 0.5, 0.0, 1.0));
}

// La distance de brouillard de Minecraft (fog.glsl) : sphère, ou cylindre.
float fogDistance(vec3 position) {
    if (u_fog_shape == 1) {
        return max(length(position.xz), abs(position.y));
    }
    return length(position);
}

void main() {
    vec4 texel = texture(u_albedo, v_uv);
    // Couleur des sommets si le matériau la demande, et teinte de l'instance.
    vec4 vertex = v_color;
    float alpha = texel.a * u_albedo_factor.a * vertex.a;
#ifdef CUTOUT
    if (alpha < u_alpha_cutoff) {
        discard;
    }
#endif
    vec3 albedo = toLinear(texel.rgb) * u_albedo_factor.rgb * vertex.rgb;
    vec3 color;
    if (u_fullbright == 1) {
        color = albedo;
    } else {
        vec3 n = normalize(v_normal);
        if (!gl_FrontFacing) {
            n = -n;
        }
        // Normal map en espace tangent (glTF : bitangente = normale × tangente, signée par w). Une
        // tangente nulle — un mesh sans normal map dans la source — garde la normale des sommets.
        if (u_normal_mapped == 1 && dot(v_tangent.xyz, v_tangent.xyz) > 1e-8) {
            vec3 t = normalize(v_tangent.xyz - n * dot(n, v_tangent.xyz));
            vec3 b = cross(n, t) * (v_tangent.w < 0.0 ? -1.0 : 1.0);
            vec3 m = texture(u_normal_map, u_normal_region.xy + v_uv_raw * u_normal_region.zw).xyz * 2.0 - 1.0;
            m.xy *= u_normal_scale;
            n = normalize(mat3(t, b, n) * m);
        }
        // ORM : occlusion (R), rugosité (G), métal (B), qui multiplient les facteurs du matériau ;
        // l'occlusion n'assombrit que l'ambiante.
        float metallic = u_metallic;
        float roughnessFactor = u_roughness;
        float occlusion = 1.0;
        if (u_orm_mapped == 1) {
            vec3 orm = texture(u_orm, u_orm_region.xy + v_uv_raw * u_orm_region.zw).rgb;
            occlusion = 1.0 + u_occlusion_strength * (orm.r - 1.0);
            roughnessFactor *= orm.g;
            metallic *= orm.b;
        }
        vec3 v = normalize(-v_position);
        vec3 l = normalize(u_light_direction);
        vec3 h = normalize(v + l);
        float nDotL = max(dot(n, l), 0.0);
        float nDotV = max(dot(n, v), 1e-4);
        float nDotH = max(dot(n, h), 0.0);
        float vDotH = max(dot(v, h), 0.0);
        float roughness = clamp(roughnessFactor, 0.045, 1.0);
        float alphaR = roughness * roughness;
        vec3 f0 = mix(vec3(0.04), albedo, metallic);
        vec3 fresnel = fresnelSchlick(vDotH, f0);
        vec3 specular = distributionGgx(nDotH, alphaR) * visibilitySmith(nDotV, nDotL, alphaR) * fresnel;
        vec3 diffuse = (1.0 - fresnel) * (1.0 - metallic) * albedo / PI;
        // Le soleil n'atteint que ce que le ciel voit ; la lightmap module tout (R-1511).
        vec3 lightmap = toLinear(texture(u_lightmap, v_lightmap).rgb);
        vec3 direct = (diffuse + specular) * u_light_color * nDotL * v_sky;
        vec3 ambient = ((1.0 - metallic) * albedo * hemisphere(n)
                + environmentBrdf(f0, roughness, nDotV) * hemisphere(reflect(-v, n))) * lightmap * occlusion;
        color = direct + ambient;
    }
    vec3 encoded = toGamma(color);
    float distance = fogDistance(v_position);
    float fog = distance <= u_fog_start ? 0.0
            : (distance < u_fog_end ? smoothstep(u_fog_start, u_fog_end, distance) : 1.0);
    o_color = vec4(mix(encoded, u_fog_color.rgb, fog * u_fog_color.a), alpha);
}
