#version 330
#extension GL_ARB_separate_shader_objects : require

// Nexo Mod's shader menu backgrounds, drawn as one full-screen quad by NexoBackdrop:
// the launcher's nebula and liquid chrome (Client/crates/nexo-app/src/sky.rs), and
// from backdrop.rs Nebula Grid's floor and Nebula Voxels' cubes, plus the mod's own
// starfield. No uniforms of its own:
//   Sampler0     a 9x1 palette: ink, text, accent, magenta, gradient 0-3, and the
//                launcher's particle amount in percent (25-200) in the red of texel 8
//   vertexColor  A = 1 + mode. Modes 0-3: RGB = time in 1/100 s (wrapped daily).
//                Mode 4: R = cursor height (0-255), GB = time in 1/100 s (wrapped at 600 s)
//   texCoord0    u = u0 + x / height, v = y / height across the screen; u0 is
//                -(aspect - 1) / 2 for modes 0-3 and the cursor's x offset for mode 4
//              A >= 128: see-through (over the running game); mode is then (A & 7) - 1
//              and (A >> 3) & 15 how far it has faded in, 0-15;
//              and the alpha out is how far the colour is from ink, so the world
//              shows where the sky is empty (chrome, which fills it all, is capped lower).
// mode: 0 nebula (Nebula Lightning; the bolts are drawn on top by the CPU),
// 1 liquid chrome, 2 nebula grid, 3 nebula voxels, 4 starfield.

uniform sampler2D Sampler0;

// A copy of vanilla's globals.glsl, as its own shaders do: the GUI pass binds it,
// and ScreenSize is the framebuffer size in exact pixels.
layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    float GlintAlpha;
    vec3 CameraOffset;
    float GameTime;
    vec2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

const float TAU = 6.2831853;

float time;
float amount;

vec3 pal(int slot) {
    return texelFetch(Sampler0, ivec2(slot, 0), 0).rgb;
}

vec3 linear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
}

// backdrop.rs random(): a repeatable number in 0..1 for a particle and a purpose.
float random(int index, int salt) {
    uint x = uint(index) * 0x9E3779B1u ^ uint(salt) * 0x85EBCA77u;
    x ^= x >> 15u;
    x *= 0xC2B2AE3Du;
    x ^= x >> 13u;
    return float(x & 0xFFFFu) / 65536.0;
}

float phase(int index, float secondsPerLoop) {
    float spread = 0.75 + random(index, 7) * 0.5;
    return fract(time * spread / secondsPerLoop + random(index, 1));
}

float fadeIn(float at) {
    return clamp(min(at * 6.0, (1.0 - at) * 6.0), 0.0, 1.0);
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 s = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), s.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), s.x), s.y);
}

float fbm(vec2 start) {
    vec2 p = start;
    float total = 0.0;
    float weight = 0.5;
    for (int octave = 0; octave < 4; octave++) {
        total += weight * noise(p);
        p = mat2(1.6, 1.2, -1.2, 1.6) * p;
        weight *= 0.5;
    }
    return total;
}

// sky.rs nebula(): the colour and its cover, stars included.
vec4 nebula(vec2 uv, vec2 pixel) {
    float t = time;
    vec2 p = uv * 2.6;
    vec2 q = vec2(fbm(p + vec2(0.0, t * 0.012)), fbm(p + vec2(5.2, 1.3) - t * 0.009));
    float v = fbm(p + 3.2 * q + vec2(t * 0.01, 0.0));
    float cloud = smoothstep(0.32, 0.8, v);

    vec3 color = mix(linear(pal(4)), linear(pal(6)), clamp(q.x * 1.9 - 0.3, 0.0, 1.0));
    color = mix(color, linear(pal(7)), clamp(q.y * q.y * 2.0, 0.0, 1.0));
    color = mix(color, linear(pal(2)), smoothstep(0.62, 0.9, v) * 0.5);

    vec2 cell = floor(pixel / 3.0);
    float star = step(0.9975, hash(cell));
    float twinkle = 0.55 + 0.45 * sin(t * (0.6 + hash(cell + 3.0) * 1.4) + hash(cell + 7.0) * 6.28);
    float stars = star * twinkle * (1.0 - cloud) * 0.8;

    float density = clamp(0.5 + 0.25 * amount, 0.0, 1.0);
    float alpha = clamp(cloud * density + stars, 0.0, 1.0);
    vec3 lit = mix(color, linear(pal(1)), stars / max(alpha, 0.001));
    return vec4(lit, alpha);
}

float molten(vec2 start) {
    float t = time;
    vec2 p = start;
    p += 0.42 * vec2(sin(p.y * 1.3 + t * 0.21), cos(p.x * 1.1 - t * 0.17));
    p += 0.2 * vec2(sin(p.y * 2.9 - t * 0.13), cos(p.x * 2.3 + t * 0.19));
    vec2 centre = vec2(sin(t * 0.13) * 1.4, cos(t * 0.11) * 0.8);
    return sin(p.x * 1.35 + t * 0.2)
        + 0.62 * sin(p.y * 2.05 - t * 0.16 + p.x * 0.7)
        + 0.34 * sin((p.x + p.y) * 3.1 + t * 0.26)
        + 0.3 * sin(length(p - centre) * 4.1 - t * 0.34);
}

vec3 chrome(vec2 uv) {
    vec2 p = (uv - 0.5) * 3.2;
    float e = 0.012;
    float dx = molten(p + vec2(e, 0.0)) - molten(p - vec2(e, 0.0));
    float dy = molten(p + vec2(0.0, e)) - molten(p - vec2(0.0, e));
    vec3 normal = normalize(vec3(-dx, -dy, e * 5.0));
    vec3 reflected = reflect(vec3(0.0, 0.0, -1.0), normal);

    float y = reflected.y + reflected.x * 0.25;
    float sky = mix(1.0, 0.34, pow(clamp(y, 0.0, 1.0), 0.55));
    float ground = mix(0.03, 0.3, pow(clamp(-y, 0.0, 1.0), 0.8));
    float tone = mix(ground, sky, smoothstep(-0.018, 0.018, y));
    vec3 light = normalize(vec3(-0.4, 0.7, 0.6));
    tone += pow(max(dot(reflected, light), 0.0), 90.0) * 0.8;

    vec3 tint = mix(linear(pal(1)), linear(pal(2)), 0.3);
    float edge = length((uv - 0.5) * vec2(1.1, 1.3));
    float dim = mix(0.2, 0.48, smoothstep(0.1, 0.75, edge));
    return tint * tone * dim * (0.75 + 0.25 * amount);
}

// backdrop.rs grid(): a darkened floor, lines rolling toward the viewer,
// spokes from the horizon, and the horizon line. Canvas colours, not linear.
vec3 grid(vec3 color, float x, float y, float width, float height) {
    float horizon = height * 0.56;
    if (y < horizon - 1.5) {
        return color;
    }
    vec3 accent = pal(2);
    color = mix(color, pal(0), 0.72 * step(horizon, y));
    int lines = max(4, int(14.0 * amount));
    for (int i = 0; i < lines; i++) {
        float along = fract(float(i) / float(lines) + time * 0.07);
        float line = horizon + (height - horizon) * along * along * along;
        float cover = clamp(1.25 - abs(y - (line + 0.75)), 0.0, 1.0);
        color = mix(color, accent, cover * (0.08 + along * 0.4));
    }
    float spokes = float(max(3, int(12.0 * amount)));
    float f = clamp((y - horizon) / (height - horizon), 0.0, 1.0);
    float spacing = width * 1.6 / spokes;
    float scale = spacing * (0.08 + 0.92 * f);
    float k = clamp(floor((x - width * 0.5) / scale + 0.5), -spokes, spokes);
    float slope = k * spacing * 0.92 / (height - horizon);
    float gap = abs(x - (width * 0.5 + k * scale)) / sqrt(1.0 + slope * slope);
    color = mix(color, pal(3), clamp(1.1 - gap, 0.0, 1.0) * mix(0.06, 0.5, f));
    float rim = clamp(1.5 - abs(y - horizon), 0.0, 1.0);
    return mix(color, accent, rim * 0.5);
}

// One corner of a cube turning by `yaw`, around its middle (backdrop.rs cube_faces).
vec2 corner(float halfSide, float yaw, float k, float up) {
    const float TILT = 0.6;
    float angle = yaw + TAU / 8.0 + k * TAU / 4.0;
    float r = halfSide * 1.4142136;
    return vec2(cos(angle) * r, sin(angle) * r * sin(TILT) - up * halfSide * cos(TILT));
}

float cross2(vec2 a, vec2 b) {
    return a.x * b.y - a.y * b.x;
}

float segmentDistance(vec2 p, vec2 a, vec2 b) {
    vec2 ab = b - a;
    float along = clamp(dot(p - a, ab) / max(dot(ab, ab), 0.0001), 0.0, 1.0);
    return length(p - a - ab * along);
}

// A face filled in its lit colour, then outlined, both at `opacity`, as the canvas draws it.
vec3 face(vec3 color, vec2 p, vec2 a, vec2 b, vec2 c, vec2 d, vec3 tint, float light, float opacity) {
    float s0 = cross2(b - a, p - a), s1 = cross2(c - b, p - b), s2 = cross2(d - c, p - c), s3 = cross2(a - d, p - d);
    bool inside = (s0 >= 0.0 && s1 >= 0.0 && s2 >= 0.0 && s3 >= 0.0) || (s0 <= 0.0 && s1 <= 0.0 && s2 <= 0.0 && s3 <= 0.0);
    if (inside) {
        color = mix(color, tint * light, opacity);
    }
    float edge = min(min(segmentDistance(p, a, b), segmentDistance(p, b, c)), min(segmentDistance(p, c, d), segmentDistance(p, d, a)));
    return mix(color, tint, clamp(1.0 - edge, 0.0, 1.0) * opacity * 0.9);
}

// backdrop.rs voxels(): small cubes turning as they rise through the nebula.
vec3 voxels(vec3 color, vec2 pixel, float width, float height) {
    int count = int(float(clamp(int(width * height / 22000.0), 12, 30)) * amount);
    for (int i = 0; i < count; i++) {
        float at = phase(i, 20.0);
        float depth = 0.35 + random(i, 2) * 0.65;
        float halfSide = (8.0 + 20.0 * depth) / 2.0;
        vec2 centre = vec2(random(i, 3) * width + sin(time * 0.3 + random(i, 4) * TAU) * 18.0, height * (1.1 - at * 1.2));
        vec2 p = pixel - centre;
        if (abs(p.x) > halfSide * 1.6 || abs(p.y) > halfSide * 1.8) {
            continue;
        }
        float yaw = time * (0.15 + random(i, 5) * 0.4) + random(i, 6) * TAU;
        vec3 tint = pal(4 + i % 4);
        float opacity = fadeIn(at) * (0.2 + 0.4 * depth);
        color = face(color, p, corner(halfSide, yaw, 0.0, 1.0), corner(halfSide, yaw, 1.0, 1.0),
                     corner(halfSide, yaw, 2.0, 1.0), corner(halfSide, yaw, 3.0, 1.0), tint, 1.0, opacity);
        for (int k = 0; k < 4; k++) {
            float facing = yaw + TAU / 4.0 + float(k) * TAU / 4.0;
            if (sin(facing) > 0.0) {
                float light = 0.4 + 0.3 * (cos(facing) + 1.0) / 2.0;
                float fk = float(k);
                color = face(color, p, corner(halfSide, yaw, fk, 1.0), corner(halfSide, yaw, fk + 1.0, 1.0),
                             corner(halfSide, yaw, fk + 1.0, -1.0), corner(halfSide, yaw, fk, -1.0), tint, light, opacity);
            }
        }
    }
    return color;
}

// The mod's starfield: three layers of stars drifting down at different speeds,
// twinkling, and shifting with the cursor by depth. Every loop divides 600 s,
// which is where the clock wraps.
vec3 starfield(vec2 pixel, vec2 cursor, float width, float height) {
    vec3 color = pal(0);
    float scale = height / 300.0;
    for (int layer = 0; layer < 3; layer++) {
        float speed = 0.4 + 0.4 * float(layer);
        float rows = max(1.0, floor(height / (40.0 * scale)));
        float cellHeight = height / rows;
        float cellWidth = cellHeight;
        vec2 shift = cursor * 10.0 * scale * speed + vec2(0.0, time * 0.025 * speed * height);
        vec2 p = pixel - shift;
        vec2 cell = floor(vec2(p.x / cellWidth, p.y / cellHeight));
        int column = int(cell.x);
        int row = int(mod(cell.y, rows));
        int index = (column * 131 + row) * 3 + layer;
        if (random(index, 20) > 0.65) {
            continue;
        }
        float size = (1.0 + random(index, 21) * 1.5) * scale;
        vec2 origin = cell * vec2(cellWidth, cellHeight)
            + vec2(random(index, 22), random(index, 23)) * (vec2(cellWidth, cellHeight) - size);
        vec2 inside = p - origin;
        if (inside.x >= 0.0 && inside.y >= 0.0 && inside.x < size && inside.y < size) {
            float twinkle = 0.4 + 0.6 * (0.5 + 0.5 * sin(time * TAU / 4.0 + random(index, 24) * TAU));
            color = mix(color, vec3(1.0), twinkle);
        }
    }
    return color;
}

void main() {
    ivec4 code = ivec4(round(vertexColor * 255.0));
    bool seeThrough = code.a >= 128;
    int mode = (seeThrough ? code.a & 7 : code.a) - 1;
    amount = round(texelFetch(Sampler0, ivec2(8, 0), 0).r * 255.0) / 100.0;

    float width = ScreenSize.x;
    float height = ScreenSize.y;
    float perPixel = dFdx(texCoord0.x);
    if (height < 1.0) {
        // Globals not bound: u moves 1/height per pixel.
        height = 1.0 / perPixel;
        width = height * (1.0 - 2.0 * (texCoord0.x - gl_FragCoord.x * perPixel));
    }
    float aspect = width / height;
    // Where u starts: the quad's left edge, by how far this pixel is from it.
    float u0 = texCoord0.x - gl_FragCoord.x / height;
    vec2 pixel = vec2(gl_FragCoord.x, texCoord0.y * height);

    vec3 color;
    if (mode == 4) {
        time = float(code.g * 256 + code.b) / 100.0;
        vec2 cursor = vec2(u0, float(code.r) / 255.0 - 0.5);
        color = starfield(pixel, cursor, width, height);
    } else {
        time = float(code.r * 65536 + code.g * 256 + code.b) / 100.0 + 60.0;
        vec2 uv = vec2(texCoord0.x - u0, texCoord0.y);
        if (mode == 1) {
            color = chrome(uv - vec2((aspect - 1.0) * 0.5, 0.0));
        } else {
            vec4 sky = nebula(uv, pixel);
            color = mix(pal(0), sky.rgb, sky.a);
            if (mode == 2) {
                color = grid(color, pixel.x, pixel.y, width, height);
            } else if (mode == 3) {
                color = voxels(color, pixel, width, height);
            }
        }
    }
    if (seeThrough) {
        vec3 d = abs(color - pal(0));
        float fade = float((code.a >> 3) & 15) / 15.0;
        fragColor = vec4(color, clamp(max(d.r, max(d.g, d.b)) * 4.0, 0.0, mode == 1 ? 0.45 : 0.8) * fade);
    } else {
        fragColor = vec4(color, 1.0);
    }
}
