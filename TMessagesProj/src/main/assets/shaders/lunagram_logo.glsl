// Lunagram's current launcher mark. Brand colours live in object space, so the
// disc's curved facets rotate with the solid model instead of the screen.
precision highp float;
uniform mat4 world;
uniform float f_alpha;
uniform int modelIndex;
varying vec3 vNormal;
varying vec2 vUV;
varying vec3 modelViewVertex;

void main() {
    vec3 base = vec3(0.98, 0.985, 1.0);
    if (modelIndex == 1) {
        vec2 p = (vUV - .5) * (100.0 / 74.0) + .5;
        // Top-left cyan/blue to bottom-right violet; the two translucent circles
        // reproduce the intersecting curves of the main icon, not a star texture.
        float t = clamp((p.x + 1.0 - p.y) * .5, 0.0, 1.0);
        base = mix(vec3(.22, .67, .96), vec3(.46, .34, .99), t);
        float upperFacet = 1.0 - smoothstep(.916, .921, distance(p, vec2(.20, .94)));
        float lowerFacet = 1.0 - smoothstep(.694, .699, distance(p, vec2(1.03, -.15)));
        base = mix(base, vec3(1.0), .12 * upperFacet);
        base = mix(base, vec3(.70, .61, 1.0), .28 * lowerFacet);
    }
    vec3 n = normalize((world * vec4(vNormal, 0.0)).xyz);
    vec3 position = (world * vec4(modelViewVertex, 1.0)).xyz;
    vec3 light = normalize(vec3(-.45, .65, 1.4));
    vec3 view = normalize(vec3(0.0, 0.0, 100.0) - position);
    float lighting = .76 + .24 * max(dot(n, light), 0.0);
    float highlight = pow(max(dot(n, normalize(light + view)), 0.0), 56.0);
    // restrained enamel reflection keeps both the white border and plane white.
    vec3 color = base * lighting + vec3(.15 * highlight);
    gl_FragColor = vec4(clamp(color, 0.0, 1.0) * f_alpha, f_alpha);
}
