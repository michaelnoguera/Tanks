package vulkanwindow;

/** Desktop bootstrap for the Vulkan renderer while gameplay commands are being ported. */
public final class VulkanApplication
{
    private VulkanApplication() { }

    public static void main(String[] args)
    {
        run(args);
    }

    public static void run(String[] args)
    {
        int frames = 0;
        java.nio.file.Path capture = null;
        int resizeFrame = -1;
        boolean validation = false;
        boolean vsync = true;
        boolean exerciseWindow = false;
        for (String arg: args)
        {
            if (arg.startsWith("--frames=")) frames = Integer.parseInt(arg.substring(9));
            else if (arg.startsWith("--resize-frame=")) resizeFrame = Integer.parseInt(arg.substring(15));
            else if (arg.startsWith("--capture=")) capture = java.nio.file.Paths.get(arg.substring(10));
            else if (arg.equals("--validation")) validation = true;
            else if (arg.equals("--no-vsync")) vsync = false;
            else if (arg.equals("--exercise-window")) exerciseWindow = true;
            else throw new IllegalArgumentException("Unknown Vulkan argument: " + arg);
        }
        if (frames < 0 || resizeFrame < -1) throw new IllegalArgumentException("Invalid frame limit or resize frame");
        try (VulkanWindow window = new VulkanWindow(960, 640, "Tanks - Vulkan renderer"))
        {
            VulkanRenderer renderer = new VulkanRenderer(window, validation);
            int rendered = 0;
            try
            {
                renderer.setVsync(vsync);
                renderer.setDrawing(VulkanScene.create());
                if (capture != null) renderer.capture(capture);
                boolean resized = false;
                int windowStep = 0;
                while (!window.shouldClose() && (frames == 0 || rendered < frames))
                {
                    window.pollEvents();
                    if (!resized && rendered == resizeFrame)
                    {
                        window.resize(800, 600);
                        resized = true;
                    }
                    if (exerciseWindow && rendered >= (windowStep + 1) * 5 && windowStep < 5)
                    {
                        switch (windowStep++)
                        {
                            case 0:
                                window.resize(800, 600);
                                break;
                            case 1:
                                window.setFullscreen(true);
                                break;
                            case 2:
                                window.setFullscreen(false);
                                break;
                            case 3:
                                renderer.setVsync(!vsync);
                                break;
                            default:
                                window.iconify();
                                window.waitEvents(0.1);
                                window.restore();
                                break;
                        }
                    }
                    if (renderer.drawFrame()) rendered++;
                    else window.waitEvents(0.01);
                }
                renderer.waitIdle();
            }
            finally
            {
                renderer.close();
            }
            if (renderer.validationErrors() != 0)
                throw new IllegalStateException("Vulkan validation reported " + renderer.validationErrors() + " errors");
            System.out.println("Vulkan rendered " + rendered + " frames; swapchain generations=" + renderer.swapchainGenerations());
        }
    }
}
