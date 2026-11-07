package vulkanwindow;

import java.nio.ByteBuffer;

/** An ordered draw of a reusable particle mesh with the current burst parameters. */
final class VulkanFireworkDraw
{
    static final int PUSH_OFFSET = 16;
    static final int PUSH_SIZE = 24 * Float.BYTES;

    static final class Mesh
    {
        private final float[] vertices;

        Mesh(float[] vertices)
        {
            this.vertices = vertices;
        }

        float[] vertices()
        {
            return vertices;
        }

        int count()
        {
            return vertices.length / VulkanDrawList.FLOATS_PER_VERTEX;
        }
    }

    private final Mesh mesh;
    private final float[] transform;
    private final float[] gravity;
    private final float[] tint;
    private final float time;
    private final float glow;

    VulkanFireworkDraw(Mesh mesh, float[] transform, float[] gravity, float[] tint, float time, float glow)
    {
        this.mesh = mesh;
        this.transform = transform;
        this.gravity = gravity;
        this.tint = tint;
        this.time = time;
        this.glow = glow;
    }

    Mesh mesh()
    {
        return mesh;
    }

    void pushConstants(ByteBuffer output)
    {
        for (float element: transform)
            output.putFloat(element);
        output.putFloat(gravity[0]).putFloat(gravity[1]).putFloat(gravity[2]).putFloat(time);
        output.putFloat(tint[0]).putFloat(tint[1]).putFloat(tint[2]).putFloat(glow);
    }
}
