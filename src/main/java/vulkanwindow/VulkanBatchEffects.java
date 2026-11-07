package vulkanwindow;

import basewindow.ShaderGroup;
import basewindow.VulkanShaderUtil;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** CPU equivalents of the legacy shader effects used by the Vulkan shape batch. */
public final class VulkanBatchEffects
{
    private static final int PASS_THROUGH = 0;
    private static final int OBSTACLE = 1;
    private static final int BOOST_PANEL = 2;
    private static final int GROUND_COLLAPSE = 3;
    private static final int OUTSIDE = 4;
    private static final int GROUND_ICE = 5;
    private static final int INTRO = 6;
    private static final int BEAT_BLOCK = 7;
    private static final int SHRUBBERY = 8;
    private static final int HOLE = 9;
    private static final int SNOW = 10;
    private static final int FIREWORK = 11;
    private static final int FIREWORK_TRAIL = 12;
    private static final int TRACK = 13;
    private static final int MUD = 14;
    private static final int GROUND_COLOR = 15;

    private static final ConcurrentHashMap<String, Optional<Field>> REFLECTED_FIELDS = new ConcurrentHashMap<>();

    private VulkanBatchEffects()
    {
    }

    /** Snapshots effect uniforms and resolves shader attributes once for one batch draw. */
    public static Evaluator create(VulkanGameWindow window, ShaderGroup shader)
    {
        return new Evaluator(shader);
    }

    /** Reusable per-draw evaluator. Output arrays are caller-owned and are never retained. */
    public static final class Evaluator
    {
        private final int effect;
        private final ShaderGroup.Attribute vertexCoord;
        private final ShaderGroup.Attribute centerCoord;
        private final ShaderGroup.Attribute groundHeight;
        private final ShaderGroup.Attribute groundColor;
        private final ShaderGroup.Attribute addTime;
        private final ShaderGroup.Attribute maxAgeAttribute;
        private final ShaderGroup.Attribute offset;
        private final ShaderGroup.Attribute timeOffset;
        private final ShaderGroup.Attribute posOffset;
        private final float size;
        private final float outlineSize;
        private final float flash;
        private final float shrubHeight;
        private final float time;
        private final float maxAge;
        private final float glow;
        private final float[] gravity;
        private final float[] tint;
        private final boolean d3;
        private final boolean fancyTerrain;

        private Evaluator(ShaderGroup shader)
        {
            String name = shader == null ? "" : shader.name;
            effect = effectFor(name);
            size = needsObstacleSize(effect) ? uniform(shader, "obstacleSizeFrac", 0) : 0;
            outlineSize = effect == BEAT_BLOCK ? uniform(shader, "outlineSizeFrac", 0) : 0;
            flash = effect == BEAT_BLOCK ? uniform(shader, "flashFrac", 0) : 0;
            shrubHeight = effect == SHRUBBERY || effect == SNOW ? uniform(shader, "shrubHeight", 1) : 1;
            time = effect == FIREWORK || effect == FIREWORK_TRAIL || effect == TRACK || effect == BOOST_PANEL ?
                    uniform(shader, "time", 0) : 0;
            maxAge = effect == TRACK ? uniform(shader, "maxAge", 0) : 0;
            glow = effect == FIREWORK ? uniform(shader, "fireworkGlow", 0) : 0;
            gravity = effect == FIREWORK || effect == FIREWORK_TRAIL ? uniformVector(shader, "gravity", 3) : null;
            tint = effect == FIREWORK || effect == FIREWORK_TRAIL ? uniformVector(shader, "color", 4) : null;
            d3 = effect == INTRO && uniform(shader, "d3", 0) != 0;
            fancyTerrain = effect == BOOST_PANEL && uniform(shader, "enableFancyTerrain", 0) != 0;

            vertexCoord = resolveAttribute(shader, "vertexCoord");
            centerCoord = resolveAttribute(shader, "centerCoord");
            groundHeight = resolveAttribute(shader, "groundHeight");
            groundColor = resolveAttribute(shader, "groundColor");
            addTime = resolveAttribute(shader, "addTime");
            maxAgeAttribute = resolveAttribute(shader, "maxAge");
            offset = resolveAttribute(shader, "offset");
            timeOffset = resolveAttribute(shader, "timeOffset");
            posOffset = resolveAttribute(shader, "posOffset");
        }

        /** Writes the shader-space position into {@code output[0..2]}. */
        public void vertex(float x, float y, float z, Map<ShaderGroup.Attribute, float[]> attributes, float[] output)
        {
            output[0] = x;
            output[1] = y;
            output[2] = z;
            switch (effect)
            {
                case OBSTACLE:
                case BOOST_PANEL:
                {
                    int coord = (int) attribute(attributes, vertexCoord, 0);
                    float extent = 25 * (1 - size);
                    output[0] += signBit(coord, 0) * extent;
                    output[1] += signBit(coord, 1) * extent;
                    if (effect == OBSTACLE)
                        output[2] += signBit(coord, 2) * extent;
                    break;
                }
                case GROUND_COLLAPSE:
                case MUD:
                    output[2] = Math.min(z, z * (1 - size));
                    break;
                case OUTSIDE:
                    output[2] = z * (1 - size);
                    break;
                case GROUND_ICE:
                    output[2] = z - 15 * size;
                    break;
                case INTRO:
                    if (d3)
                        output[2] = z + Math.max(0, 2000 * (1 - size * (1 + attribute(attributes, groundHeight, 0) / 10)));
                    else
                    {
                        int coord = (int) attribute(attributes, vertexCoord, 0);
                        float extent = 25 * (1 - size);
                        output[0] += signBit(coord, 0) * extent;
                        output[1] += signBit(coord, 1) * extent;
                    }
                    break;
                case BEAT_BLOCK:
                {
                    float[] center = attributes == null ? null : attributes.get(centerCoord);
                    float cx = component(center, 0);
                    float cy = component(center, 1);
                    float cz = component(center, 2);
                    float currentSize = size;
                    if (cz < 0)
                    {
                        cz = -cz;
                        currentSize = outlineSize;
                    }
                    output[0] = x * currentSize + cx * (1 - currentSize);
                    output[1] = y * currentSize + cy * (1 - currentSize);
                    output[2] = z * currentSize + cz * (1 - currentSize);
                    break;
                }
                case SHRUBBERY:
                {
                    int coord = (int) attribute(attributes, vertexCoord, 0);
                    float inverse = 1 - size;
                    float half = 25 * inverse;
                    float h = (coord >> 3) * 50;
                    output[0] += signBit(coord, 0) * half;
                    output[1] += signBit(coord, 1) * half;
                    output[2] = h + ((z - h) * size + half) * shrubHeight;
                    break;
                }
                case HOLE:
                {
                    float baseX = (float) Math.floor(x / 50) * 50;
                    float baseY = (float) Math.floor(y / 50) * 50;
                    float remainderX = x - baseX;
                    float remainderY = y - baseY;
                    boolean edgeX = remainderX == 0;
                    boolean edgeY = remainderY == 0;
                    float rest = 25 * (1 - size);
                    output[0] = edgeX ? x : baseX + rest + size * remainderX;
                    output[1] = edgeY ? y : baseY + rest + size * remainderY;
                    if (!edgeX && !edgeY)
                        output[2] = size * (z - attribute(attributes, groundHeight, 0)) +
                                attribute(attributes, groundHeight, 0);
                    break;
                }
                case SNOW:
                    output[2] = z * size * shrubHeight + attribute(attributes, groundHeight, 0);
                    break;
                case FIREWORK:
                {
                    float maxParticleAge = attribute(attributes, maxAgeAttribute, 0);
                    float fraction = Math.max(0, maxParticleAge - time * (1 - glow));
                    float scale = (1 + glow * 7) * fraction / 80;
                    output[0] = x * time + .5f * gravity[0] * time * time +
                            attribute(attributes, offset, 0) * scale;
                    output[1] = y * time + .5f * gravity[1] * time * time +
                            attribute(attributes, offset, 1) * scale;
                    output[2] = z * time + .5f * gravity[2] * time * time;
                    break;
                }
                case FIREWORK_TRAIL:
                {
                    float start = attribute(attributes, timeOffset, 0);
                    float depthOffset = attribute(attributes, posOffset, 0);
                    float particleAge = attribute(attributes, maxAgeAttribute, 0);
                    float t = Math.max(time - start, 0);
                    float fraction = Math.max(0, particleAge - time) * Math.max((20 - start) / 20, 0);
                    float vx = x + gravity[0] * t;
                    float vy = y + gravity[1] * t;
                    float vz = z + gravity[2] * t;
                    float length = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
                    float crossScale = length == 0 ? 0 : depthOffset / length;
                    output[0] = x * t + .5f * gravity[0] * t * t + vy * crossScale * fraction / 80;
                    output[1] = y * t + .5f * gravity[1] * t * t - vx * crossScale * fraction / 80;
                    output[2] = z * t + .5f * gravity[2] * t * t;
                    break;
                }
                default:
                    break;
            }
        }

        /** Writes shader RGBA into {@code output[0..3]}; false means the shader has no color override. */
        public boolean color(float red, float green, float blue, float alpha,
                             Map<ShaderGroup.Attribute, float[]> attributes, float[] output)
        {
            output[0] = red;
            output[1] = green;
            output[2] = blue;
            output[3] = alpha;
            switch (effect)
            {
                case GROUND_COLOR:
                case SNOW:
                {
                    float[] ground = attributes == null ? null : attributes.get(groundColor);
                    output[0] = red * size + component(ground, 0) * (1 - size);
                    output[1] = green * size + component(ground, 1) * (1 - size);
                    output[2] = blue * size + component(ground, 2) * (1 - size);
                    output[3] = alpha * size + (1 - size);
                    return true;
                }
                case OUTSIDE:
                    output[0] = red * (1 - size) + .6823f * size;
                    output[1] = green * (1 - size) + .3608f * size;
                    output[2] = blue * (1 - size) + .0628f * size;
                    output[3] = alpha * (1 - size) + size;
                    return true;
                case MUD:
                    output[0] = red * (1 - size) + .275f * size;
                    output[1] = green * (1 - size) + .117f * size;
                    output[2] = blue * (1 - size);
                    output[3] = alpha * (1 - size) + size;
                    return true;
                case BEAT_BLOCK:
                {
                    float[] center = attributes == null ? null : attributes.get(centerCoord);
                    float color = component(center, 2) < 0 ? 0 : 1;
                    output[0] = red * (1 - flash) + color * flash;
                    output[1] = green * (1 - flash) + color * flash;
                    output[2] = blue * (1 - flash) + color * flash;
                    output[3] = alpha * (1 - flash) + flash;
                    return true;
                }
                case TRACK:
                {
                    float fade = maxAge == 0 ? 0 : 1 - clamp((time - attribute(attributes, addTime, 0)) / maxAge);
                    output[3] *= fade;
                    return true;
                }
                case BOOST_PANEL:
                {
                    float offsetValue = fancyTerrain ?
                            (float) ((Math.sin((alpha * 255 * 50 + time / 300 * Math.PI * 2) / 10) + 1) * 40 / 255) : 0;
                    output[0] = (1 - offsetValue / 2) * red;
                    output[1] = Math.min(1, .7f - offsetValue + green) * red;
                    output[2] = green * red;
                    output[3] = 1;
                    return true;
                }
                case FIREWORK:
                {
                    output[0] = (tint[0] + red) * (1 - glow * alpha);
                    output[1] = (tint[1] + green) * (1 - glow * alpha);
                    output[2] = (tint[2] + blue) * (1 - glow * alpha);
                    float particleAge = attribute(attributes, maxAgeAttribute, 0);
                    output[3] = particleAge == 0 ? 1 : 1 - time / particleAge;
                    return true;
                }
                case FIREWORK_TRAIL:
                {
                    float particleAge = attribute(attributes, maxAgeAttribute, 0);
                    float start = attribute(attributes, timeOffset, 0);
                    float factor = particleAge == 0 ? 0
                            : 1 - (time / particleAge) * Math.max((60 - start) / 60, 0);
                    output[0] = (tint[0] + red * alpha) * factor;
                    output[1] = (tint[1] + green * alpha) * factor;
                    output[2] = (tint[2] + blue * alpha) * factor;
                    output[3] = 1;
                    return true;
                }
                default:
                    return false;
            }
        }

        private static float attribute(Map<ShaderGroup.Attribute, float[]> attributes,
                                       ShaderGroup.Attribute key, int component)
        {
            if (attributes == null || key == null)
                return 0;
            float[] values = attributes.get(key);
            return values == null || component >= values.length ? 0 : values[component];
        }
    }

    private static int effectFor(String name)
    {
        switch (name)
        {
            case "obstacles": return OBSTACLE;
            case "boost_panel": return BOOST_PANEL;
            case "ground_obstacles":
            case "ground_obstacle_beat":
                return GROUND_COLLAPSE;
            case "outside": return OUTSIDE;
            case "ground_ice": return GROUND_ICE;
            case "intro": return INTRO;
            case "beatblocks": return BEAT_BLOCK;
            case "shrubbery": return SHRUBBERY;
            case "holes": return HOLE;
            case "snow": return SNOW;
            case "firework": return FIREWORK;
            case "firework_trail": return FIREWORK_TRAIL;
            case "tracks": return TRACK;
            case "mud": return MUD;
            case "ground_color": return GROUND_COLOR;
            default: return PASS_THROUGH;
        }
    }

    private static boolean needsObstacleSize(int effect)
    {
        return effect == OBSTACLE || effect == BOOST_PANEL || effect == GROUND_COLLAPSE || effect == OUTSIDE ||
                effect == GROUND_ICE || effect == INTRO || effect == BEAT_BLOCK || effect == SHRUBBERY ||
                effect == HOLE || effect == SNOW || effect == MUD || effect == GROUND_COLOR;
    }

    private static float uniform(ShaderGroup shader, String name, float fallback)
    {
        Object value = shader == null ? null : VulkanShaderUtil.get(shader, name);
        if (value instanceof Number)
            return ((Number) value).floatValue();
        return value instanceof Boolean && (Boolean) value ? 1 : fallback;
    }

    private static float[] uniformVector(ShaderGroup shader, String name, int length)
    {
        Object value = shader == null ? null : VulkanShaderUtil.get(shader, name);
        float[] result = new float[length];
        if (value instanceof float[])
        {
            float[] source = (float[]) value;
            System.arraycopy(source, 0, result, 0, Math.min(source.length, length));
        }
        return result;
    }

    private static ShaderGroup.Attribute resolveAttribute(ShaderGroup shader, String name)
    {
        Field field = reflectedField(shader, name);
        if (field == null || shader == null)
            return null;
        try
        {
            Object value = field.get(shader);
            return value instanceof ShaderGroup.Attribute ? (ShaderGroup.Attribute) value : null;
        }
        catch (IllegalAccessException e)
        {
            return null;
        }
    }

    private static Field reflectedField(ShaderGroup shader, String name)
    {
        if (shader == null)
            return null;
        String key = shader.getClass().getName() + '#' + name;
        Optional<Field> result = REFLECTED_FIELDS.computeIfAbsent(key, unused ->
        {
            try
            {
                return Optional.of(shader.getClass().getField(name));
            }
            catch (NoSuchFieldException e)
            {
                return Optional.empty();
            }
        });
        return result.orElse(null);
    }

    private static float component(float[] values, int index)
    {
        return values == null || index >= values.length ? 0 : values[index];
    }

    private static float clamp(float value)
    {
        return Math.max(0, Math.min(1, value));
    }

    private static float signBit(int value, int bit)
    {
        return ((value >> bit) & 1) == 0 ? 1 : -1;
    }
}
