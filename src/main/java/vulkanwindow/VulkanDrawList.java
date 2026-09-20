package vulkanwindow;

import basewindow.BaseShapeRenderer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Ordered triangle geometry for the Vulkan renderer. Vertices contain xyz, rgba, uv.
 * Coordinates pass through a column-major transform into Vulkan NDC: x/y in [-1, 1],
 * y increasing downward, and z in [0, 1] with smaller z nearer the camera.
 * Rectangles and images use their upper-left corner; boxes use their minimum corner.
 * The transform must keep each primitive within one homogeneous half-space: this
 * adapter divides by w and does not implement clipping or perspective-correct UVs.
 */
public final class VulkanDrawList
{
    public static final int FLOATS_PER_VERTEX = 9;
    private float[] data = new float[1024];
    private int size;
    private final List<Command> draws = new ArrayList<>();
    private float[] transform = identity();
    private float red = 1, green = 1, blue = 1, alpha = 1;

    public static final class Command
    {
        public final int first;
        public final int count;
        /** Classpath resource path, or null for the renderer's white texture. */
        public final String texture;
        public final boolean depth;
        public final boolean depthWrite;

        private Command(int first, int count, String texture, boolean depth, boolean depthWrite)
        {
            this.first = first;
            this.count = count;
            this.texture = texture;
            this.depth = depth;
            this.depthWrite = depthWrite;
        }
    }

    /** Starts a frame with an identity transform and opaque white color. */
    public void begin()
    {
        clear();
        transform = identity();
        setColor(255, 255, 255, 255);
    }

    /** Discards geometry while retaining the current transform and color. */
    public void clear()
    {
        size = 0;
        draws.clear();
    }

    public void setColor(double r, double g, double b, double a)
    {
        red = channel(r);
        green = channel(g);
        blue = channel(b);
        alpha = channel(a);
    }

    private static float channel(double value)
    {
        if (!Double.isFinite(value) || value < 0 || value > 255)
            throw new IllegalArgumentException("Color channels must be finite and in [0, 255]");
        return (float) (value / 255);
    }

    public static float[] identity()
    {
        return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    public void setTransform(float[] matrix)
    {
        if (matrix == null || matrix.length != 16)
            throw new IllegalArgumentException("Transform must contain 16 floats");
        for (float value : matrix)
            if (!Float.isFinite(value))
                throw new IllegalArgumentException("Transform must be finite");
        transform = matrix.clone();
    }

    public float[] vertices()
    {
        return Arrays.copyOf(data, size);
    }

    public List<Command> commands()
    {
        return Collections.unmodifiableList(draws);
    }

    public void fillRect(double x, double y, double z, double width, double height, boolean depth)
    {
        int first = size / FLOATS_PER_VERTEX;
        quad(x, y, z, x + width, y, z, x + width, y + height, z, x, y + height, z);
        finish(first, null, depth);
    }

    public void fillOval(double x, double y, double z, double width, double height, boolean depth)
    {
        int first = size / FLOATS_PER_VERTEX;
        int segments = 48;
        for (int i = 0; i < segments; i++)
        {
            double a = i * Math.PI * 2 / segments;
            double b = (i + 1) * Math.PI * 2 / segments;
            vertex(x + width / 2, y + height / 2, z, 0.5, 0.5);
            vertex(x + width * (1 + Math.cos(a)) / 2, y + height * (1 + Math.sin(a)) / 2, z, 0, 0);
            vertex(x + width * (1 + Math.cos(b)) / 2, y + height * (1 + Math.sin(b)) / 2, z, 0, 0);
        }
        finish(first, null, depth);
    }

    /** Emits visible faces using BaseShapeRenderer's hide_* bits. */
    public void fillBox(double x, double y, double z, double width, double height, double depth, byte mask, String texture)
    {
        int first = size / FLOATS_PER_VERTEX;
        double X = x + width, Y = y + height, Z = z + depth;
        if ((mask & BaseShapeRenderer.hide_neg_z) == 0)
            quad(x, y, z, X, y, z, X, Y, z, x, Y, z);
        if ((mask & BaseShapeRenderer.hide_pos_z) == 0)
            quad(X, y, Z, x, y, Z, x, Y, Z, X, Y, Z);
        if ((mask & BaseShapeRenderer.hide_neg_y) == 0)
            quad(x, y, Z, X, y, Z, X, y, z, x, y, z);
        if ((mask & BaseShapeRenderer.hide_pos_y) == 0)
            quad(x, Y, z, X, Y, z, X, Y, Z, x, Y, Z);
        if ((mask & BaseShapeRenderer.hide_neg_x) == 0)
            quad(x, y, Z, x, y, z, x, Y, z, x, Y, Z);
        if ((mask & BaseShapeRenderer.hide_pos_x) == 0)
            quad(X, y, z, X, y, Z, X, Y, Z, X, Y, z);
        finish(first, texture, true);
    }

    /** UV bounds use the image's upper-left origin. Rotation is in radians about its center. */
    public void drawImage(double x, double y, double z, double width, double height,
                          double u1, double v1, double u2, double v2, String texture,
                          double rotation, boolean depth)
    {
        int first = size / FLOATS_PER_VERTEX;
        double cos = Math.cos(rotation), sin = Math.sin(rotation);
        int[] corners = {0, 1, 2, 0, 2, 3};
        for (int corner : corners)
        {
            boolean right = corner == 1 || corner == 2;
            boolean bottom = corner >= 2;
            double dx = (right ? 0.5 : -0.5) * width;
            double dy = (bottom ? 0.5 : -0.5) * height;
            vertex(x + width / 2 + dx * cos - dy * sin,
                    y + height / 2 + dx * sin + dy * cos, z,
                    right ? u2 : u1, bottom ? v2 : v1);
        }
        finish(first, texture, depth);
    }

    private void quad(double x1, double y1, double z1, double x2, double y2, double z2,
                      double x3, double y3, double z3, double x4, double y4, double z4)
    {
        vertex(x1, y1, z1, 0, 0);
        vertex(x2, y2, z2, 1, 0);
        vertex(x3, y3, z3, 1, 1);
        vertex(x1, y1, z1, 0, 0);
        vertex(x3, y3, z3, 1, 1);
        vertex(x4, y4, z4, 0, 1);
    }

    private void vertex(double x, double y, double z, double u, double v)
    {
        double w = transform[3] * x + transform[7] * y + transform[11] * z + transform[15];
        if (!Double.isFinite(w) || w == 0)
            throw new IllegalArgumentException("Vertex transform has invalid homogeneous w");
        if (size + FLOATS_PER_VERTEX > data.length)
            data = Arrays.copyOf(data, data.length * 2);
        data[size++] = (float) ((transform[0] * x + transform[4] * y + transform[8] * z + transform[12]) / w);
        data[size++] = (float) ((transform[1] * x + transform[5] * y + transform[9] * z + transform[13]) / w);
        data[size++] = (float) ((transform[2] * x + transform[6] * y + transform[10] * z + transform[14]) / w);
        data[size++] = red;
        data[size++] = green;
        data[size++] = blue;
        data[size++] = alpha;
        data[size++] = (float) u;
        data[size++] = (float) v;
    }

    private void finish(int first, String texture, boolean depth)
    {
        int count = size / FLOATS_PER_VERTEX - first;
        if (count > 0)
            draws.add(new Command(first, count, texture, depth, depth && alpha == 1));
    }
}
