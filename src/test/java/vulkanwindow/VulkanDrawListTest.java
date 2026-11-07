package vulkanwindow;

import basewindow.BaseShapeRenderer;

public final class VulkanDrawListTest
{
    public static void main(String[] args)
    {
        testHomogeneousCoordinates();
        testFacesAndOrder();
        testBatchDepthMask();
        testBatchGlow();
        testImageCoordinates();
        testShadowGeometry();
        testInvalidVertices();
        System.out.println("Vulkan draw list checks passed");
    }

    private static void testHomogeneousCoordinates()
    {
        VulkanDrawList list = new VulkanDrawList();
        float[] matrix = VulkanDrawList.identity();
        matrix[12] = 0.5f;
        matrix[11] = 1;
        list.setTransform(matrix);
        matrix[12] = 100;
        list.fillRect(0, 0, 1, 1, 1, true);
        float[] vertices = list.vertices();
        require(vertices.length == 6 * VulkanDrawList.FLOATS_PER_VERTEX, "wrong vertex stride");
        require(vertices[0] == 0.5f && vertices[3] == 2, "matrix must retain homogeneous w and copy input");
        require(vertices[VulkanDrawList.FLOATS_PER_VERTEX] == 1.5f, "CPU must not divide clip coordinates by w");
        list.begin();
        list.fillRect(0, 0, 0, 1, 1, false);
        require(list.vertices()[3] == 1, "begin must restore the identity transform");
        matrix = VulkanDrawList.identity();
        matrix[15] = 0;
        list.setTransform(matrix);
        list.clear();
        list.fillRect(0, 0, 0, 1, 1, false);
        require(list.vertices()[3] == 0, "GPU clipping must receive zero w");
    }

    private static void testFacesAndOrder()
    {
        VulkanDrawList list = new VulkanDrawList();
        list.fillBox(0, 0, 0, 1, 1, 1, BaseShapeRenderer.hide_all, null);
        require(list.commands().isEmpty(), "hidden box must not emit a command");
        list.fillBox(0, 0, 0, 1, 1, 1, BaseShapeRenderer.hide_neg_z, "box");
        require(list.commands().get(0).count == 30, "one hidden face must remove six vertices");
        require(list.commands().get(0).depthWrite, "opaque geometry must write depth");
        list.setColor(255, 0, 0, 128);
        list.fillRect(0, 0, 0, 1, 1, true);
        list.fillOval(0, 0, 0, 1, 1, false);
        require(list.commands().size() == 3, "commands must preserve submission order");
        require(list.commands().get(1).first == 30 && list.commands().get(2).first == 36, "vertex ranges must remain ordered");
        require(list.commands().get(1).depth && !list.commands().get(1).depthWrite, "alpha geometry tests depth without writing it");
        require(!list.commands().get(2).depth && !list.commands().get(2).depthWrite, "UI must not test or write depth");
        float[] vertices = list.vertices();
        int firstVertex = 30 * VulkanDrawList.FLOATS_PER_VERTEX;
        require(vertices[firstVertex + 4] == 1 && vertices[firstVertex + 5] == 0, "vertex colors must use normalized channels");
        list.begin();
        list.fillRect(0, 0, 0, 1, 1, true);
        require(list.commands().get(0).first == 0 && list.vertices()[7] == 1, "begin must discard geometry and restore opaque color");
    }

    private static void testImageCoordinates()
    {
        VulkanDrawList list = new VulkanDrawList();
        list.drawImage(0, 0, 0, 2, 2, 0.2, 0.3, 0.8, 0.9, "image", Math.PI, false);
        float[] vertices = list.vertices();
        require(close(vertices[0], 2) && close(vertices[1], 2), "image rotation must use its center");
        require(close(vertices[8], 0.2) && close(vertices[9], 0.3), "image must retain supplied UV bounds");
        int secondVertex = VulkanDrawList.FLOATS_PER_VERTEX;
        require(close(vertices[secondVertex + 8], 0.8) && close(vertices[secondVertex + 9], 0.3), "image second corner has incorrect UVs");
        require("image".equals(list.commands().get(0).texture), "image resource must reach its command");
        list.drawImage(0, 0, 0, 1, 1, 0, 0, 1, 1, "image", 0, true);
        require(list.commands().get(1).depth && !list.commands().get(1).depthWrite,
                "images must test depth without writing, including opaque image colors");
    }

    private static void testBatchDepthMask()
    {
        VulkanDrawList list = new VulkanDrawList();
        VulkanShapeRenderer renderer = new VulkanShapeRenderer(list);
        renderer.setBatchMode(true, true, true, false, false);
        renderer.fillBox(0, 0, 0, 1, 1, 1, (byte) 61, null);
        renderer.fillRect(0, 0, 0, 1, 1, true);
        renderer.setBatchMode(false, true, true, false, false);

        require(list.commands().size() == 1, "batched hole backing geometry must form one ordered draw");
        VulkanDrawList.Command backing = list.commands().get(0);
        require(backing.first == 0 && backing.count == 12, "batched box face and backing rect must stay contiguous");
        require(backing.depth && !backing.depthWrite, "global hole backing draw must test depth without writing it");

        renderer.fillRect(0, 0, 0.1, 1, 1, true);
        require(list.commands().size() == 2, "recessed depth-writing rect must keep independent draw state");
        VulkanDrawList.Command recessed = list.commands().get(1);
        require(recessed.first == 12 && recessed.count == 6 && recessed.depth && recessed.depthWrite,
                "recessed rect must independently test and write depth");
    }

    private static void testBatchGlow()
    {
        VulkanDrawList list = new VulkanDrawList();
        VulkanShapeRenderer renderer = new VulkanShapeRenderer(list);
        renderer.setBatchMode(true, false, false, true, false);
        renderer.addVertex(0, 0, 0, 0, 0);
        renderer.addVertex(1, 0, 0, 0, 0);
        renderer.addVertex(0, 1, 0, 0, 0);
        renderer.setBatchMode(false, false, false, true, false);
        renderer.fillRect(0, 0, 1, 1);

        require(list.commands().size() == 2, "rocket glow and ordinary shapes need separate blend modes");
        require(list.commands().get(0).additive && !list.commands().get(1).additive,
                "the glow flag must reach the batch command and stop at its end");
    }

    private static void testShadowGeometry()
    {
        VulkanDrawList list = new VulkanDrawList();
        list.setShadowTransform(VulkanDrawList.identity());
        list.fillRect(0, 0, 0, 1, 1, true);
        int shadowW = 13;
        require(list.vertices()[shadowW] == 1, "shadow transform must populate homogeneous light-space w");
        require(list.hasShadowGeometry(), "depth-writing geometry with nonzero shadow w must qualify for casting");

        float[] zeroW = VulkanDrawList.identity();
        zeroW[15] = 0;
        list.setShadowTransform(zeroW);
        list.clear();
        list.fillRect(0, 0, 0, 1, 1, true);
        require(list.vertices()[shadowW] == 0, "zero shadow w must be preserved as the no-shadow sentinel");
        require(!list.hasShadowGeometry(), "geometry with zero shadow w must not qualify for casting");

        list.clear();
        list.setShadowTransform(VulkanDrawList.identity());
        list.fillRect(0, 0, 0, 1, 1, false);
        require(!list.hasShadowGeometry(), "geometry without depth testing must not qualify for casting");

        list.clear();
        list.setColor(255, 255, 255, 128);
        list.fillRect(0, 0, 0, 1, 1, true);
        require(list.commands().get(0).depth && !list.commands().get(0).depthWrite,
                "alpha geometry must test depth without writing it");
        require(!list.hasShadowGeometry(), "depth-tested geometry without depth writes must not qualify for casting");
    }

    private static void testInvalidVertices()
    {
        VulkanDrawList list = new VulkanDrawList();
        expectInvalid(() -> list.fillRect(Double.NaN, 0, 0, 1, 1, false));
        expectInvalid(() -> list.fillRect(Double.MAX_VALUE, 0, 0, 1, 1, false));
        expectInvalid(() -> list.drawImage(0, 0, 0, 1, 1, Double.NaN, 0, 1, 1, "image", 0, false));
        require(list.vertices().length == 0, "invalid first vertex must not modify the draw list");
    }

    private static void expectInvalid(Runnable draw)
    {
        try
        {
            draw.run();
        }
        catch (IllegalArgumentException expected)
        {
            return;
        }
        throw new AssertionError("invalid vertex was accepted");
    }

    private static boolean close(float actual, double expected)
    {
        return Math.abs(actual - expected) < 0.00001;
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
            throw new AssertionError(message);
    }
}
