package vulkanwindow;

import basewindow.BaseShapeRenderer;

public final class VulkanDrawListTest
{
    public static void main(String[] args)
    {
        testHomogeneousCoordinates();
        testFacesAndOrder();
        testImageCoordinates();
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
        require(vertices.length == 6 * 10, "wrong vertex stride");
        require(vertices[0] == 0.5f && vertices[3] == 2, "matrix must retain homogeneous w and copy input");
        require(vertices[10] == 1.5f, "CPU must not divide clip coordinates by w");
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
        require(vertices[30 * 10 + 4] == 1 && vertices[30 * 10 + 5] == 0, "vertex colors must use normalized channels");
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
        require(close(vertices[18], 0.8) && close(vertices[19], 0.3), "image second corner has incorrect UVs");
        require("image".equals(list.commands().get(0).texture), "image resource must reach its command");
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
