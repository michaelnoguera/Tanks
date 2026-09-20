package vulkanwindow;

/** Geometry and image submission exercised by the standalone Vulkan entry point. */
public final class VulkanScene
{
    private VulkanScene()
    {
    }

    public static VulkanDrawList create()
    {
        VulkanDrawList list = new VulkanDrawList();
        list.begin();
        list.setColor(50, 70, 90, 255);
        list.fillRect(-0.95, -0.9, 0.9, 1.9, 1.8, true);
        float[] transform = VulkanDrawList.identity();
        transform[8] = 0.3f;
        transform[9] = -0.3f;
        list.setTransform(transform);
        list.setColor(70, 180, 80, 255);
        list.fillBox(-0.8, -0.6, 0.25, 0.5, 0.55, 0.3, (byte) 0, null);
        list.setColor(80, 130, 230, 255);
        list.fillBox(-0.4, -0.25, 0.2, 0.5, 0.55, 0.3, (byte) 0, null);
        list.setTransform(VulkanDrawList.identity());
        list.setColor(255, 255, 255, 255);
        list.drawImage(0.15, -0.65, 0.3, 0.6, 0.6, 0, 0, 1, 1,
                "/images/mute.png", 0.15, false);
        list.drawImage(0.15, 0.05, 0.3, 0.5, 0.5, 0, 0, 1, 1,
                "/images/icons/pencil.png", -0.2, false);
        list.setColor(255, 200, 30, 180);
        list.fillOval(-0.75, 0.3, 0.1, 0.5, 0.4, false);
        list.setColor(230, 70, 100, 128);
        list.fillRect(-0.4, 0.45, 0.1, 1.1, 0.25, false);
        return list;
    }
}
