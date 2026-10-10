#version 330 core
// Émission des surfaces d'AXION (passe 5 du §19.10, ADR-127 §4) : additive, sans lumière du monde ;
// elle s'efface dans le brouillard comme l'émission des entités de Minecraft. Son propre programme :
// l'émission est une passe, non une variante de R-762.

in vec3 v_position;
in vec2 v_uv;

uniform sampler2D u_emission;
uniform vec3 u_emissive_factor;
uniform float u_fog_start;
uniform float u_fog_end;
uniform int u_fog_shape;

out vec4 o_color;

vec3 toLinear(vec3 c) {
    return pow(max(c, vec3(0.0)), vec3(2.2));
}

vec3 toGamma(vec3 c) {
    return pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));
}

float fogDistance(vec3 position) {
    if (u_fog_shape == 1) {
        return max(length(position.xz), abs(position.y));
    }
    return length(position);
}

void main() {
    vec3 emission = toLinear(texture(u_emission, v_uv).rgb) * u_emissive_factor;
    float distance = fogDistance(v_position);
    float fade = distance <= u_fog_start ? 1.0
            : (distance < u_fog_end ? 1.0 - smoothstep(u_fog_start, u_fog_end, distance) : 0.0);
    // De la lumière ajoutée, pas de la couverture : l'alpha de la cible reste le sien.
    o_color = vec4(toGamma(emission) * fade, 0.0);
}
