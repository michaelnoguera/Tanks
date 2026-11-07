package vulkanwindow;

import basewindow.*;
import basewindow.transformation.Rotation;
import basewindow.transformation.Scale;
import basewindow.transformation.Translation;
import tanks.rendering.ShaderFireworkExplosion;
import tanks.rendering.ShaderFireworkExplosionTrail;

import java.util.ArrayList;
import java.util.IdentityHashMap;

/** CPU-side shape batch which records cached geometry into the ordered Vulkan draw list. */
public final class VulkanShapeBatchRenderer extends BaseShapeBatchRenderer
{
    private static final int FLOATS_PER_VERTEX = 7;

    private static final class AttributeStream
    {
        final int components;
        float[] values;
        int size;

        AttributeStream(int components, int existingVertices)
        {
            this.components = components;
            this.values = new float[Math.max(16, existingVertices) * components];
            this.size = existingVertices * components;
        }

        void append(float[] value)
        {
            ensureCapacity(size + components);
            System.arraycopy(value, 0, values, size, components);
            size += components;
        }

        void ensureCapacity(int required)
        {
            if (required <= values.length)
                return;
            int capacity = Math.max(values.length, 16 * components);
            while (capacity < required)
                capacity *= 2;
            values = java.util.Arrays.copyOf(values, capacity);
        }
    }

    private static final class Geometry
    {
        float[] vertices = new float[16 * FLOATS_PER_VERTEX];
        int vertexCount;
        final IdentityHashMap<ShaderGroup.Attribute, AttributeStream> attributes = new IdentityHashMap<>();
        VulkanFireworkDraw.Mesh fireworkMesh;

        void addVertex(float x, float y, float z, float r, float g, float b, float a)
        {
            int offset = vertexCount * FLOATS_PER_VERTEX;
            vertices = ensureCapacity(vertices, offset + FLOATS_PER_VERTEX);
            vertices[offset] = x;
            vertices[offset + 1] = y;
            vertices[offset + 2] = z;
            vertices[offset + 3] = r;
            vertices[offset + 4] = g;
            vertices[offset + 5] = b;
            vertices[offset + 6] = a;
            vertexCount++;
        }

        private static float[] ensureCapacity(float[] values, int required)
        {
            if (required <= values.length)
                return values;
            int capacity = Math.max(values.length, 16);
            while (capacity < required)
                capacity *= 2;
            return java.util.Arrays.copyOf(values, capacity);
        }
    }

    public final VulkanGameWindow window;
    public final ShaderGroup shader;
    public final String texture;
    private final int vertexCapacity;
    private final IdentityHashMap<IBatchRenderableObject, Geometry> objects = new IdentityHashMap<>();
    private final ArrayList<IBatchRenderableObject> objectOrder = new ArrayList<>();
    private final IdentityHashMap<ShaderGroup.Attribute, float[]> currentAttributes = new IdentityHashMap<>();
    private final ArrayList<ShaderGroup.Attribute> attributes = new ArrayList<>();
    private final IdentityHashMap<ShaderGroup.Attribute, float[]> vertexAttributes = new IdentityHashMap<>();
    private AttributeStream[] streamScratch = new AttributeStream[0];
    private float[][] vertexAttributeScratch = new float[0][];
    private final float[] positionScratch = new float[3];
    private final float[] colorScratch = new float[4];
    private final IBatchRenderableObject implicitObject = new IBatchRenderableObject() {};
    private IBatchRenderableObject adding;
    private Geometry building;
    private float red = 255;
    private float green = 255;
    private float blue = 255;
    private float alpha = 255;
    private float glow;
    private boolean depth;
    private boolean additive;
    private boolean depthWrite;
    private boolean staged;

    public VulkanShapeBatchRenderer(VulkanGameWindow window, boolean dynamic)
    {
        this(window, dynamic, null, 6000);
    }

    public VulkanShapeBatchRenderer(VulkanGameWindow window, boolean dynamic, String texture, int vertexCapacity)
    {
        this(window, null, dynamic, texture, vertexCapacity);
    }

    public VulkanShapeBatchRenderer(VulkanGameWindow window, ShaderGroup shader, boolean dynamic,
                                    String texture, int vertexCapacity)
    {
        super(dynamic);
        if (window == null) throw new IllegalArgumentException("window must not be null");
        if (vertexCapacity < 0) throw new IllegalArgumentException("vertexCapacity must not be negative");
        this.window = window;
        this.shader = shader;
        this.texture = texture;
        this.vertexCapacity = vertexCapacity;
        this.depthWrite = dynamic;
        if (shader != null)
            for (ShaderGroup.Attribute attribute: shader.attributes)
                addAttribute(attribute);
    }

    public VulkanShapeBatchRenderer(VulkanGameWindow window, ShaderGroup shader)
    {
        this(window, shader, true, null, 6000);
    }

    @Override
    public void delete(IBatchRenderableObject object)
    {
        if (object == null) return;
        if (adding == object)
        {
            adding = null;
            building = null;
        }
        if (objects.remove(object) != null)
            for (int i = 0; i < objectOrder.size(); i++)
                if (objectOrder.get(i) == object)
                {
                    objectOrder.remove(i);
                    break;
                }
    }

    @Override
    public void beginAdd(IBatchRenderableObject object)
    {
        if (adding == object && building != null)
            return;

        finishCurrent();
        if (object == null) throw new IllegalArgumentException("object must not be null");
        adding = object;
        building = newGeometry();
    }

    private Geometry newGeometry()
    {
        Geometry geometry = new Geometry();
        for (ShaderGroup.Attribute attribute: attributes)
            geometry.attributes.put(attribute, new AttributeStream(attribute.count, 0));
        return geometry;
    }

    private void finishCurrent()
    {
        if (adding != null && building != null)
        {
            if (!objects.containsKey(adding))
                objectOrder.add(adding);
            objects.put(adding, building);
        }
        adding = null;
        building = null;
    }

    @Override
    public void addPoint(float x, float y, float z)
    {
        // Some static batches (notably firework particles) are built as one
        // stream and never associate their vertices with individual objects.
        if (adding == null || building == null)
        {
            adding = implicitObject;
            building = newGeometry();
        }
        building.addVertex(x, y, z, red, green, blue, alpha);
        for (ShaderGroup.Attribute attribute: attributes)
        {
            building.attributes.get(attribute).append(currentAttributes.get(attribute));
        }
    }

    @Override
    public void setColor(float r, float g, float b, float a)
    {
        red = channel(r);
        green = channel(g);
        blue = channel(b);
        alpha = channel(a);
    }

    private static float channel(float value)
    {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Color must be finite");
        // GL batches preserve signed/out-of-range vertex colors until the
        // shader has combined them with its own color and alpha effects.
        return value;
    }

    @Override
    public void setGlow(float value)
    {
        glow = value;
    }

    @Override
    public void addAttribute(ShaderGroup.Attribute attribute)
    {
        if (attribute == null || attributes.contains(attribute)) return;
        if (attribute.count < 1)
            throw new IllegalArgumentException("Shader attribute must contain at least one component");
        attributes.add(attribute);
        currentAttributes.put(attribute, new float[attribute.count]);
        streamScratch = java.util.Arrays.copyOf(streamScratch, attributes.size());
        vertexAttributeScratch = java.util.Arrays.copyOf(vertexAttributeScratch, attributes.size());
        vertexAttributeScratch[attributes.size() - 1] = new float[attribute.count];
        vertexAttributes.put(attribute, vertexAttributeScratch[attributes.size() - 1]);
        // Attributes can be registered after an object's geometry was recorded.
        // Keep the per-vertex streams aligned, as the GL batch does.
        for (Geometry geometry: objects.values())
        {
            AttributeStream values = new AttributeStream(attribute.count, geometry.vertexCount);
            geometry.attributes.put(attribute, values);
        }
        if (building != null)
        {
            AttributeStream values = new AttributeStream(attribute.count, building.vertexCount);
            building.attributes.put(attribute, values);
        }
    }

    @Override
    public void setAttribute(ShaderGroup.Attribute attribute, float... values)
    {
        if (attribute == null || values == null) throw new IllegalArgumentException("Attribute and values are required");
        addAttribute(attribute);
        if (values.length != attribute.count)
            throw new IllegalArgumentException("Expected " + attribute.count + " values for shader attribute");
        float[] currentValue = currentAttributes.get(attribute);
        if (currentValue == null)
        {
            currentValue = new float[attribute.count];
            currentAttributes.put(attribute, currentValue);
        }
        System.arraycopy(values, 0, currentValue, 0, attribute.count);
    }

    @Override
    public void settings(boolean depth)
    {
        settings(depth, false, depth);
    }

    @Override
    public void settings(boolean depth, boolean glow)
    {
        settings(depth, glow, !glow);
    }

    @Override public void settings(boolean depth, boolean glow, boolean depthMask)
    {
        this.depth = depth;
        this.additive = glow;
        this.depthWrite = depthMask;
    }

    @Override
    public void stage()
    {
        finishCurrent();
        staged = true;
    }

    @Override
    public void draw()
    {
        finishCurrent();
        if (hidden) return;
        if (!staged) stage();
        window.addMatrix();
        try
        {
            Translation.transform(window, posX / window.absoluteWidth, posY / window.absoluteHeight, posZ / window.absoluteDepth);
            Rotation.transform(window, -pitch, -roll, -yaw);
            Scale.transform(window, sX, sY, sZ);
            // The legacy VBO path routes the batch glow value through the window
            // so IGlowShader implementations see it before vertices are drawn.
            window.setColor(255, 255, 255, 255, glow);
            if (!dynamic && texture == null && !depth && (additive || window.drawing.hasUnitLight()) && drawFireworks())
                return;

            VulkanBatchEffects.Evaluator evaluator = shader == null ? null : VulkanBatchEffects.create(window, shader);
            int first = window.drawing.vertexCount();
            for (IBatchRenderableObject object: objectOrder)
                emit(objects.get(object), evaluator);
            window.drawing.finish(first, texture, depth, depthWrite, additive);
        }
        finally
        {
            window.removeMatrix();
        }
    }

    private boolean drawFireworks()
    {
        if (shader instanceof ShaderFireworkExplosion)
        {
            ShaderFireworkExplosion burst = (ShaderFireworkExplosion) shader;
            recordFireworks(burst.offset, null, burst.maxAge, VulkanShaderUtil.get(burst.gravity),
                    VulkanShaderUtil.get(burst.color), VulkanShaderUtil.get(burst.time), VulkanShaderUtil.get(burst.fireworkGlow));
            return true;
        }
        if (shader instanceof ShaderFireworkExplosionTrail)
        {
            ShaderFireworkExplosionTrail trail = (ShaderFireworkExplosionTrail) shader;
            recordFireworks(trail.timeOffset, trail.posOffset, trail.maxAge, VulkanShaderUtil.get(trail.gravity),
                    VulkanShaderUtil.get(trail.color), VulkanShaderUtil.get(trail.time), -1);
            return true;
        }
        return false;
    }

    private void recordFireworks(ShaderGroup.Attribute firstOffset, ShaderGroup.Attribute secondOffset,
                                 ShaderGroup.Attribute maxAge, float[] gravity, float[] tint, float time, float glow)
    {
        for (IBatchRenderableObject object: objectOrder)
        {
            Geometry geometry = objects.get(object);
            if (geometry.vertexCount == 0)
                continue;
            if (geometry.fireworkMesh == null)
                geometry.fireworkMesh = packFireworks(geometry, firstOffset, secondOffset, maxAge);
            window.drawing.firework(geometry.fireworkMesh, gravity, tint, time, glow, additive);
        }
    }

    private static VulkanFireworkDraw.Mesh packFireworks(Geometry geometry, ShaderGroup.Attribute firstOffset,
                                                        ShaderGroup.Attribute secondOffset, ShaderGroup.Attribute maxAge)
    {
        float[] first = geometry.attributes.get(firstOffset).values;
        float[] second = secondOffset == null ? first : geometry.attributes.get(secondOffset).values;
        int offsetStride = firstOffset.count;
        int secondComponent = secondOffset == null ? 1 : 0;
        float[] ages = geometry.attributes.get(maxAge).values;
        int stride = VulkanDrawList.FLOATS_PER_VERTEX;
        float[] packed = new float[geometry.vertexCount * stride];
        for (int i = 0; i < geometry.vertexCount; i++)
        {
            int source = i * FLOATS_PER_VERTEX;
            int target = i * stride;
            // Reuse the geometry pipeline's four vertex inputs for velocity, color, offset and age.
            System.arraycopy(geometry.vertices, source, packed, target, 3);
            for (int channel = 0; channel < 4; channel++)
                packed[target + 4 + channel] = geometry.vertices[source + 3 + channel] / 255f;
            packed[target + 8] = first[i * offsetStride];
            packed[target + 9] = second[i * offsetStride + secondComponent];
            packed[target + 10] = ages[i];
        }
        return new VulkanFireworkDraw.Mesh(packed);
    }

    private void emit(Geometry geometry, VulkanBatchEffects.Evaluator evaluator)
    {
        if (geometry.vertexCount == 0) return;
        VulkanDrawList drawing = window.drawing;
        for (int attributeIndex = 0; attributeIndex < attributes.size(); attributeIndex++)
            streamScratch[attributeIndex] = geometry.attributes.get(attributes.get(attributeIndex));
        for (int i = 0; i < geometry.vertexCount; i++)
        {
            for (int attributeIndex = 0; attributeIndex < attributes.size(); attributeIndex++)
            {
                ShaderGroup.Attribute attribute = attributes.get(attributeIndex);
                AttributeStream stream = streamScratch[attributeIndex];
                float[] value = vertexAttributeScratch[attributeIndex];
                System.arraycopy(stream.values, i * attribute.count, value, 0, attribute.count);
            }
            int offset = i * FLOATS_PER_VERTEX;
            float x = geometry.vertices[offset];
            float y = geometry.vertices[offset + 1];
            float z = geometry.vertices[offset + 2];
            if (evaluator != null)
            {
                evaluator.vertex(x, y, z, vertexAttributes, positionScratch);
                x = positionScratch[0];
                y = positionScratch[1];
                z = positionScratch[2];
            }
            float red = geometry.vertices[offset + 3] / 255f;
            float green = geometry.vertices[offset + 4] / 255f;
            float blue = geometry.vertices[offset + 5] / 255f;
            float alpha = geometry.vertices[offset + 6] / 255f;
            if (evaluator != null && evaluator.color(red, green, blue, alpha, vertexAttributes, colorScratch))
            {
                colorScratch[0] = normalizedUnit(colorScratch[0]);
                colorScratch[1] = normalizedUnit(colorScratch[1]);
                colorScratch[2] = normalizedUnit(colorScratch[2]);
                colorScratch[3] = normalizedUnit(colorScratch[3]);
            }
            else
            {
                colorScratch[0] = normalizedChannel(geometry.vertices[offset + 3]);
                colorScratch[1] = normalizedChannel(geometry.vertices[offset + 4]);
                colorScratch[2] = normalizedChannel(geometry.vertices[offset + 5]);
                colorScratch[3] = normalizedChannel(geometry.vertices[offset + 6]);
            }
            drawing.vertex(x, y, z, 0, 0, colorScratch);
        }
    }

    private static float normalizedChannel(float value)
    {
        return Math.max(0, Math.min(1, value / 255f));
    }

    private static float normalizedUnit(float value)
    {
        return Math.max(0, Math.min(1, value));
    }

    @Override
    public void endModification()
    {
        finishCurrent();
    }

    @Override
    public void free()
    {
        objects.clear();
        objectOrder.clear();
        adding = null;
        building = null;
        staged = false;
    }

    public int getVertexCapacity()
    {
        return vertexCapacity;
    }

    public float getGlow()
    {
        return glow;
    }
}
